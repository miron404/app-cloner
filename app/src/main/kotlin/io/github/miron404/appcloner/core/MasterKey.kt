package io.github.miron404.appcloner.core

import android.hardware.biometrics.BiometricPrompt
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** How the master key gates access, as configured by the user. */
data class AuthPolicy(
    /** Seconds the authorisation stays valid. Zero means every single operation re-authenticates. */
    val timeoutSeconds: Int,
    /** Whether the device PIN/pattern/password may be used instead of a biometric. */
    val allowDeviceCredential: Boolean,
) {
    val perOperation: Boolean get() = timeoutSeconds <= 0

    companion object {
        val DEFAULT = AuthPolicy(timeoutSeconds = 300, allowDeviceCredential = true)

        /** Values offered in Settings, in seconds. Zero is "every operation". */
        val TIMEOUT_CHOICES = listOf(0, 30, 60, 300, 900, 3600)
    }
}

data class MasterKeyState(
    val exists: Boolean,
    val strongBoxBacked: Boolean,
    val policy: AuthPolicy,
)

/**
 * The vault's key-encryption key.
 *
 * It lives in the Titan M2 secure element when the device has one and never leaves it. Access is
 * gated by Keymaster itself: [AuthPolicy.timeoutSeconds] is compiled into the key at creation, so
 * the session window is enforced by hardware rather than by a flag this app could be patched to
 * ignore.
 *
 * AndroidKeyStore entries are neither exportable nor renameable, so a policy change means minting a
 * key under a fresh alias and re-sealing the vault against it. [Vault] owns that transition; this
 * class only creates, describes and destroys aliases.
 */
class MasterKey(private val authenticator: SystemAuthenticator) : MasterKeyStore {

    override fun state(alias: String): MasterKeyState {
        val key = loadKey(alias) ?: return MasterKeyState(false, false, AuthPolicy.DEFAULT)
        val info = keyInfo(key)
        return MasterKeyState(
            exists = true,
            strongBoxBacked = info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX,
            policy = AuthPolicy(
                timeoutSeconds = info.userAuthenticationValidityDurationSeconds.coerceAtLeast(0),
                allowDeviceCredential =
                    info.userAuthenticationType and KeyProperties.AUTH_DEVICE_CREDENTIAL != 0,
            ),
        )
    }

    override fun exists(alias: String): Boolean = androidKeyStore().containsAlias(alias)

    /** Creates the key under [alias]. Returns whether the secure element accepted it. */
    override fun create(alias: String, policy: AuthPolicy): Boolean {
        val authType = if (policy.allowDeviceCredential) {
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        } else {
            KeyProperties.AUTH_BIOMETRIC_STRONG
        }

        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(policy.timeoutSeconds.coerceAtLeast(0), authType)
            .setUnlockedDeviceRequired(true)
            // Re-enrolling a fingerprint must not destroy the vault while the device credential is
            // an accepted authenticator: invalidation would cost data without adding protection,
            // since an attacker holding the PIN could enrol anyway. Opting out of the credential
            // fallback restores strict invalidation.
            .setInvalidatedByBiometricEnrollment(!policy.allowDeviceCredential)
            .setIsStrongBoxBacked(strongBox)
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            generator.init(spec(true))
            generator.generateKey()
            true
        } catch (_: StrongBoxUnavailableException) {
            generator.init(spec(false))
            generator.generateKey()
            false
        }
    }

    override fun wrapper(alias: String, policy: AuthPolicy): KeyWrapper = HardwareWrapper(alias, policy)

    override fun delete(alias: String) {
        val store = androidKeyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    /** Removes master-key aliases this app owns other than the ones still referenced. */
    override fun deleteOrphans(keep: Set<String>) {
        val store = androidKeyStore()
        store.aliases().toList()
            .filter { it.startsWith(ALIAS_PREFIX) && it !in keep }
            .forEach { store.deleteEntry(it) }
    }

    private inner class HardwareWrapper(
        private val alias: String,
        private val policy: AuthPolicy,
    ) : KeyWrapper {

        override val isHardwareBacked: Boolean
            get() = runCatching {
                keyInfo(requireKey(alias)).securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            }.getOrDefault(false)

        override suspend fun wrap(aad: ByteArray, plaintext: ByteArray): WrappedKey {
            var iv: ByteArray? = null
            val ciphertext = authorized(
                init = {
                    Cipher.getInstance(TRANSFORMATION).apply {
                        init(Cipher.ENCRYPT_MODE, requireKey(alias))
                        iv = this.iv
                    }
                },
                block = { cipher ->
                    cipher.updateAAD(aad)
                    cipher.doFinal(plaintext)
                },
            )
            return WrappedKey(requireNotNull(iv), ciphertext)
        }

        override suspend fun unwrap(aad: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray =
            authorized(
                init = {
                    Cipher.getInstance(TRANSFORMATION).apply {
                        init(
                            Cipher.DECRYPT_MODE,
                            requireKey(alias),
                            GCMParameterSpec(Aead.TAG_SIZE_BITS, iv),
                        )
                    }
                },
                block = { cipher ->
                    cipher.updateAAD(aad)
                    cipher.doFinal(ciphertext)
                },
            )

        /**
         * Runs [block] against a freshly initialised cipher, prompting when Keymaster reports the
         * authorisation window has lapsed. Under a per-operation policy the cipher is bound into the
         * prompt so the authorisation covers exactly this one use and nothing else.
         */
        private suspend fun authorized(init: () -> Cipher, block: (Cipher) -> ByteArray): ByteArray {
            var attempts = 0
            while (true) {
                attempts++
                val cipher = try {
                    withContext(Dispatchers.IO) { init() }
                } catch (e: UserNotAuthenticatedException) {
                    if (attempts > MAX_AUTH_ATTEMPTS) throw e
                    prompt(null)
                    continue
                }
                if (policy.perOperation) prompt(BiometricPrompt.CryptoObject(cipher))
                try {
                    return withContext(Dispatchers.IO) { block(cipher) }
                } catch (e: UserNotAuthenticatedException) {
                    if (attempts > MAX_AUTH_ATTEMPTS) throw e
                    prompt(null)
                }
            }
        }

        private suspend fun prompt(crypto: BiometricPrompt.CryptoObject?) =
            authenticator.authenticate(
                crypto = crypto,
                title = "Unlock signing vault",
                subtitle = if (policy.perOperation) {
                    "Required for this operation"
                } else {
                    "Unlocks for " + formatDuration(policy.timeoutSeconds)
                },
                allowDeviceCredential = policy.allowDeviceCredential,
            )
    }

    override fun newAlias(): String = ALIAS_PREFIX + randomBytes(8).toHex().lowercase()

    companion object {
        const val ALIAS_PREFIX = "io.github.miron404.appcloner.master."
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAX_AUTH_ATTEMPTS = 2

        private fun androidKeyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        private fun loadKey(alias: String): SecretKey? =
            androidKeyStore().getKey(alias, null) as? SecretKey

        private fun requireKey(alias: String): SecretKey =
            loadKey(alias) ?: throw IllegalStateException("Master key is missing")

        private fun keyInfo(key: SecretKey): KeyInfo =
            SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }
}

fun formatDuration(seconds: Int): String = when {
    seconds <= 0 -> "every operation"
    seconds < 60 -> "$seconds seconds"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h"
}
