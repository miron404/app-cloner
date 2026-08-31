package io.github.miron404.appcloner.core

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Single shared CSPRNG. On Android this is seeded from the kernel. */
val secureRandom: SecureRandom by lazy { SecureRandom() }

fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

object Bc {
    /**
     * Android ships a stripped-down "BC" provider that lacks the PKCS#12 and certificate-builder
     * pieces this app needs. Replace it with the full BouncyCastle build, once, at startup.
     */
    @Synchronized
    fun install(): Provider {
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing is BouncyCastleProvider) return existing
        if (existing != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        val provider = BouncyCastleProvider()
        Security.addProvider(provider)
        return provider
    }

    val provider: Provider get() = install()
}

/** AES-256-GCM over data that is already held in memory. */
object Aead {
    const val KEY_SIZE_BYTES = 32
    const val IV_SIZE_BYTES = 12
    const val TAG_SIZE_BITS = 128

    fun newKey(): ByteArray = randomBytes(KEY_SIZE_BYTES)

    fun encrypt(key: ByteArray, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = randomBytes(IV_SIZE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, iv))
        cipher.updateAAD(aad)
        return iv to cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, aad: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}

/**
 * Wraps and unwraps 32-byte data-encryption keys. The production implementation is backed by a
 * StrongBox key that the user must authenticate to use; tests substitute a software one.
 */
interface KeyWrapper {
    val isHardwareBacked: Boolean

    suspend fun wrap(aad: ByteArray, plaintext: ByteArray): WrappedKey

    suspend fun unwrap(aad: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray
}

class WrappedKey(val iv: ByteArray, val ciphertext: ByteArray)

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

fun ByteArray.toHex(separator: String = ""): String =
    joinToString(separator) { "%02X".format(it) }

/** Overwrites the array in place. Best effort: the JVM may still have copied it elsewhere. */
fun ByteArray.wipe() = fill(0)

fun CharArray.wipe() = fill(' ')
