package io.github.miron404.appcloner.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.Base64

@Serializable
private data class BackupEntry(
    val meta: IdentityMeta,
    val keystorePassword: String,
    val pkcs12: String,
)

@Serializable
private data class BackupPayload(
    val version: Int,
    val createdAt: Long,
    val identities: List<BackupEntry>,
)

class BackupDecryptionException(message: String) : Exception(message)

/**
 * Passphrase-protected archive used to move identities between devices.
 *
 * The device master key is deliberately not involved: an archive has to be readable on hardware
 * that has never seen this phone's secure element. Its only protection is the passphrase, so the
 * key is stretched with Argon2id before AES-256-GCM, and the KDF parameters are stored in the
 * cleartext header and authenticated as additional data so they cannot be downgraded in transit.
 */
object BackupArchive {

    private val MAGIC = "AKSGBKP1".toByteArray(Charsets.US_ASCII)
    private const val KDF_ARGON2ID = 1
    private const val FORMAT_VERSION = 1

    private const val MEMORY_KIB = 64 * 1024
    private const val ITERATIONS = 4
    private const val PARALLELISM = 2
    private const val SALT_SIZE = 16

    /** Archives are small; refuse anything large enough to be a denial-of-service on import. */
    const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun create(identities: List<PortableIdentity>, passphrase: CharArray): ByteArray {
        val payload = BackupPayload(
            version = FORMAT_VERSION,
            createdAt = System.currentTimeMillis(),
            identities = identities.map { identity ->
                BackupEntry(
                    meta = identity.meta,
                    keystorePassword = String(identity.keystorePassword),
                    pkcs12 = Base64.getEncoder().encodeToString(identity.pkcs12),
                )
            },
        )
        val plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

        val salt = randomBytes(SALT_SIZE)
        val header = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { sink ->
                sink.write(MAGIC)
                sink.writeByte(KDF_ARGON2ID)
                sink.writeInt(MEMORY_KIB)
                sink.writeInt(ITERATIONS)
                sink.writeInt(PARALLELISM)
                sink.writeByte(salt.size)
                sink.write(salt)
            }
        }.toByteArray()

        val key = deriveKey(passphrase, salt, MEMORY_KIB, ITERATIONS, PARALLELISM)
        try {
            val (iv, ciphertext) = Aead.encrypt(key, header, plaintext)
            val out = ByteArrayOutputStream(ciphertext.size + header.size + 32)
            DataOutputStream(out).use { sink ->
                sink.write(header)
                sink.writeByte(iv.size)
                sink.write(iv)
                sink.writeInt(ciphertext.size)
                sink.write(ciphertext)
            }
            return out.toByteArray()
        } finally {
            key.wipe()
            plaintext.wipe()
        }
    }

    fun open(archive: ByteArray, passphrase: CharArray): List<PortableIdentity> {
        if (archive.size > MAX_ARCHIVE_BYTES) throw BackupDecryptionException("Archive is too large")
        val buffer = ByteBuffer.wrap(archive)
        val magic = buffer.take(MAGIC.size)
        if (!magic.contentEquals(MAGIC)) throw BackupDecryptionException("Not an APK Signer backup")

        val kdf = buffer.byteValue().toInt()
        if (kdf != KDF_ARGON2ID) throw BackupDecryptionException("Unsupported key derivation")
        val memoryKib = buffer.int
        val iterations = buffer.int
        val parallelism = buffer.int
        require(memoryKib in 8..(1024 * 1024) && iterations in 1..64 && parallelism in 1..16) {
            "Unreasonable Argon2 parameters"
        }
        val salt = buffer.take(buffer.byteValue().toInt() and 0xFF)

        val headerLength = buffer.position()
        val header = archive.copyOfRange(0, headerLength)

        val iv = buffer.take(buffer.byteValue().toInt() and 0xFF)
        val ciphertext = buffer.take(buffer.int)

        val key = deriveKey(passphrase, salt, memoryKib, iterations, parallelism)
        val plaintext = try {
            Aead.decrypt(key, header, iv, ciphertext)
        } catch (_: Exception) {
            throw BackupDecryptionException("Wrong passphrase, or the archive is corrupted")
        } finally {
            key.wipe()
        }

        try {
            val payload = json.decodeFromString<BackupPayload>(String(plaintext, Charsets.UTF_8))
            if (payload.version != FORMAT_VERSION) {
                throw BackupDecryptionException("Unsupported backup version ${payload.version}")
            }
            return payload.identities.map { entry ->
                PortableIdentity(
                    meta = entry.meta,
                    keystorePassword = entry.keystorePassword.toCharArray(),
                    pkcs12 = Base64.getDecoder().decode(entry.pkcs12),
                )
            }
        } finally {
            plaintext.wipe()
        }
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
    ): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        return ByteArray(Aead.KEY_SIZE_BYTES).also { generator.generateBytes(passphrase, it) }
    }

    private fun ByteBuffer.byteValue(): Byte =
        if (hasRemaining()) get() else throw BackupDecryptionException("Truncated archive")

    private fun ByteBuffer.take(length: Int): ByteArray {
        if (length < 0 || length > remaining()) throw BackupDecryptionException("Truncated archive")
        return ByteArray(length).also { get(it) }
    }
}
