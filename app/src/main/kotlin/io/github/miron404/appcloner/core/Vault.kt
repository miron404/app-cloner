package io.github.miron404.appcloner.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.UUID

/** Payload sealed inside an identity envelope: exactly the keystore and the password that opens it. */
@Serializable
private data class SealedIdentity(
    val keystorePassword: String,
    val pkcs12: String,
)

/** Everything needed to re-create an identity elsewhere. Used by backup and by restore. */
class PortableIdentity(
    val meta: IdentityMeta,
    val keystorePassword: CharArray,
    val pkcs12: ByteArray,
) : AutoCloseable {
    override fun close() {
        keystorePassword.wipe()
        pkcs12.wipe()
    }
}

class VaultLockedException(message: String) : Exception(message)

/**
 * The subset of [MasterKey] the vault depends on.
 *
 * Split out so the staging-and-commit logic below can be exercised on a plain JVM, where
 * AndroidKeyStore does not exist.
 */
interface MasterKeyStore {
    fun state(alias: String): MasterKeyState
    fun exists(alias: String): Boolean
    fun create(alias: String, policy: AuthPolicy): Boolean
    fun wrapper(alias: String, policy: AuthPolicy): KeyWrapper
    fun delete(alias: String)
    fun deleteOrphans(keep: Set<String>)
    fun newAlias(): String
}

/** The subset of [AppSettings] the vault depends on. */
interface VaultSettings {
    var masterKeyAlias: String?
    var pendingMasterKeyAlias: String?
    var desiredPolicy: AuthPolicy
}

/**
 * Stores signing identities on disk.
 *
 * Metadata is cleartext so the list screen works without authentication; it only repeats what is
 * public in every signed APK. The private key and its randomly generated keystore password live in
 * an [Envelope] sealed by the hardware master key, so reading them always costs an authentication.
 */
class Vault(
    root: File,
    private val masterKey: MasterKeyStore,
    private val settings: VaultSettings,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val root = root.apply { mkdirs() }

    /** Resumes an interrupted rekey and clears stray state. Safe to call repeatedly. */
    fun repair() {
        val pending = settings.pendingMasterKeyAlias
        if (pending != null) {
            root.listFiles { file -> file.name.endsWith(STAGED_SUFFIX) }?.forEach { staged ->
                val target = File(root, staged.name.removeSuffix(STAGED_SUFFIX))
                if (!staged.renameTo(target)) {
                    staged.copyTo(target, overwrite = true)
                    staged.delete()
                }
            }
            val previous = settings.masterKeyAlias
            settings.masterKeyAlias = pending
            settings.pendingMasterKeyAlias = null
            if (previous != null && previous != pending) masterKey.delete(previous)
        } else {
            // A rekey that never reached its commit point: discard the half-written copies.
            root.listFiles { file -> file.name.endsWith(STAGED_SUFFIX) }?.forEach { it.delete() }
        }
        masterKey.deleteOrphans(setOfNotNull(settings.masterKeyAlias))
    }

    fun state(): MasterKeyState {
        val alias = settings.masterKeyAlias
            ?: return MasterKeyState(false, false, settings.desiredPolicy)
        return masterKey.state(alias)
    }

    /** Creates the master key on first use. Returns whether it landed in the secure element. */
    fun ensureMasterKey(): Boolean {
        settings.masterKeyAlias?.let { alias ->
            if (masterKey.exists(alias)) return masterKey.state(alias).strongBoxBacked
        }
        val alias = masterKey.newAlias()
        val strongBox = masterKey.create(alias, settings.desiredPolicy)
        settings.masterKeyAlias = alias
        masterKey.deleteOrphans(setOf(alias))
        return strongBox
    }

    fun list(): List<IdentityMeta> =
        root.listFiles { file -> file.name.endsWith(META_SUFFIX) }
            .orEmpty()
            .mapNotNull { file -> runCatching { json.decodeFromString<IdentityMeta>(file.readText()) }.getOrNull() }
            .sortedBy { it.label.lowercase() }

    suspend fun create(request: NewIdentityRequest): IdentityMeta = withContext(Dispatchers.Default) {
        val material = KeyMaterial.generate(request)
        val password = KeyMaterial.randomKeystorePassword()
        try {
            val alias = request.alias.ifBlank { UNKNOWN }
            val pkcs12 = KeyMaterial.writePkcs12(
                alias = alias,
                privateKey = material.keyPair.private,
                certificate = material.certificate,
                password = password,
            )
            val meta = IdentityMeta(
                id = UUID.randomUUID().toString(),
                label = request.label.ifBlank { request.dn.withDefaults().commonName },
                alias = alias,
                dn = request.dn.withDefaults(),
                algorithm = request.algorithm.jcaName,
                keySize = request.algorithm.keySize,
                signatureAlgorithm = request.algorithm.signatureAlgorithm,
                serialNumberHex = material.certificate.serialNumber.toString(16).uppercase(),
                createdAt = System.currentTimeMillis(),
                notBefore = material.certificate.notBefore.time,
                notAfter = material.certificate.notAfter.time,
                certificatePem = KeyMaterial.toPem(material.certificate),
                fingerprintSha256 = KeyMaterial.fingerprintSha256(material.certificate),
            )
            store(meta, password, pkcs12)
            meta
        } finally {
            password.wipe()
        }
    }

    /** Adds an identity that came from a backup archive, keeping its original key and certificate. */
    suspend fun restore(portable: PortableIdentity, overwrite: Boolean): Boolean {
        if (!overwrite && File(root, portable.meta.id + META_SUFFIX).exists()) return false
        store(portable.meta, portable.keystorePassword, portable.pkcs12)
        return true
    }

    /**
     * Renames an identity.
     *
     * Changing only the display label rewrites cleartext metadata and costs nothing. Changing the
     * keystore alias has to rewrite the keystore itself, which means unsealing it and therefore
     * authenticating; the key and its certificate are carried over untouched, so the identity a
     * signature proves does not change. Blank input keeps the current value.
     */
    suspend fun rename(meta: IdentityMeta, label: String, alias: String): IdentityMeta {
        val newLabel = label.trim().ifEmpty { meta.label }
        val newAlias = alias.trim().ifEmpty { meta.alias }
        if (newLabel == meta.label && newAlias == meta.alias) return meta

        if (newAlias == meta.alias) {
            val updated = meta.copy(label = newLabel)
            withContext(Dispatchers.IO) { writeMeta(updated) }
            return updated
        }

        return open(meta).use { portable ->
            val material = KeyMaterial.readPkcs12(
                portable.pkcs12,
                portable.keystorePassword,
                meta.alias,
            )
            val updated = meta.copy(label = newLabel, alias = newAlias)
            val pkcs12 = withContext(Dispatchers.Default) {
                KeyMaterial.writePkcs12(
                    alias = newAlias,
                    privateKey = material.privateKey,
                    certificate = material.chain.first(),
                    password = portable.keystorePassword,
                )
            }
            try {
                store(updated, portable.keystorePassword, pkcs12)
            } finally {
                pkcs12.wipe()
            }
            updated
        }
    }

    private fun writeMeta(meta: IdentityMeta) {
        File(root, meta.id + META_SUFFIX).writeText(json.encodeToString(meta))
    }

    private suspend fun store(meta: IdentityMeta, password: CharArray, pkcs12: ByteArray) {
        val wrapper = requireWrapper()
        val payload = json.encodeToString(
            SealedIdentity(
                keystorePassword = String(password),
                pkcs12 = Base64.getEncoder().encodeToString(pkcs12),
            )
        ).toByteArray(Charsets.UTF_8)
        try {
            val sealed = Envelope.seal(wrapper, meta.id.toByteArray(Charsets.UTF_8), payload)
            withContext(Dispatchers.IO) {
                File(root, meta.id + KEY_SUFFIX).writeBytesAtomically(sealed)
                writeMeta(meta)
            }
        } finally {
            payload.wipe()
        }
    }

    suspend fun unlock(meta: IdentityMeta): UnlockedIdentity {
        val portable = open(meta)
        return UnlockedIdentity(portable.meta, portable.keystorePassword, portable.pkcs12)
    }

    suspend fun open(meta: IdentityMeta): PortableIdentity {
        val wrapper = requireWrapper()
        val blob = withContext(Dispatchers.IO) {
            File(root, meta.id + KEY_SUFFIX).takeIf { it.isFile }?.readBytes()
        } ?: throw VaultLockedException("Key material for ${meta.label} is missing")
        val plaintext = Envelope.open(wrapper, meta.id.toByteArray(Charsets.UTF_8), blob)
        try {
            val sealed = json.decodeFromString<SealedIdentity>(String(plaintext, Charsets.UTF_8))
            return PortableIdentity(
                meta = meta,
                keystorePassword = sealed.keystorePassword.toCharArray(),
                pkcs12 = Base64.getDecoder().decode(sealed.pkcs12),
            )
        } finally {
            plaintext.wipe()
        }
    }

    fun delete(id: String) {
        File(root, id + KEY_SUFFIX).delete()
        File(root, id + META_SUFFIX).delete()
    }

    /**
     * Applies a new [AuthPolicy] by minting a fresh master key and re-sealing every identity under
     * it. Re-sealed blobs are staged next to the originals and only swapped in after the new alias
     * is committed, so an interruption at any point leaves a state [repair] can finish.
     */
    suspend fun rekey(policy: AuthPolicy) {
        val previousAlias = settings.masterKeyAlias
        val oldWrapper = previousAlias?.let { masterKey.wrapper(it, masterKey.state(it).policy) }
        val newAlias = masterKey.newAlias()
        masterKey.create(newAlias, policy)
        val newWrapper = masterKey.wrapper(newAlias, policy)

        try {
            for (meta in list()) {
                val context = meta.id.toByteArray(Charsets.UTF_8)
                val keyFile = File(root, meta.id + KEY_SUFFIX)
                if (!keyFile.isFile) continue
                val plaintext = if (oldWrapper == null) {
                    throw VaultLockedException("No master key to read the vault with")
                } else {
                    Envelope.open(oldWrapper, context, withContext(Dispatchers.IO) { keyFile.readBytes() })
                }
                try {
                    val resealed = Envelope.seal(newWrapper, context, plaintext)
                    withContext(Dispatchers.IO) {
                        File(root, meta.id + KEY_SUFFIX + STAGED_SUFFIX).writeBytesAtomically(resealed)
                    }
                } finally {
                    plaintext.wipe()
                }
            }
        } catch (error: Throwable) {
            root.listFiles { file -> file.name.endsWith(STAGED_SUFFIX) }?.forEach { it.delete() }
            masterKey.delete(newAlias)
            throw error
        }

        // Commit point: from here on, repair() can always finish the swap.
        settings.pendingMasterKeyAlias = newAlias
        settings.desiredPolicy = policy
        repair()
    }

    private fun requireWrapper(): KeyWrapper {
        val alias = settings.masterKeyAlias ?: throw VaultLockedException("Vault is not initialised")
        if (!masterKey.exists(alias)) throw VaultLockedException("Master key is missing")
        return masterKey.wrapper(alias, masterKey.state(alias).policy)
    }

    private companion object {
        const val META_SUFFIX = ".meta.json"
        const val KEY_SUFFIX = ".key"
        const val STAGED_SUFFIX = ".staged"

        fun File.writeBytesAtomically(bytes: ByteArray) {
            val temporary = File(parentFile, "$name.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(this)) {
                temporary.copyTo(this, overwrite = true)
                temporary.delete()
            }
        }
    }
}
