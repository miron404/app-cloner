package io.github.miron404.appcloner

import io.github.miron404.appcloner.core.BackupArchive
import io.github.miron404.appcloner.core.BackupDecryptionException
import io.github.miron404.appcloner.core.DistinguishedName
import io.github.miron404.appcloner.core.IdentityMeta
import io.github.miron404.appcloner.core.KeyAlgorithm
import io.github.miron404.appcloner.core.KeyMaterial
import io.github.miron404.appcloner.core.NewIdentityRequest
import io.github.miron404.appcloner.core.PortableIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.UUID

class BackupArchiveTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun installProvider() {
            io.github.miron404.appcloner.core.Bc.install()
        }

        private fun sampleIdentity(label: String): PortableIdentity {
            val material = KeyMaterial.generate(
                NewIdentityRequest(
                    label = label,
                    alias = label,
                    dn = DistinguishedName(commonName = label),
                    validityYears = 30,
                    algorithm = KeyAlgorithm.EC_P256,
                )
            )
            val password = KeyMaterial.randomKeystorePassword()
            val pkcs12 = KeyMaterial.writePkcs12(
                label,
                material.keyPair.private,
                material.certificate,
                password,
            )
            val meta = IdentityMeta(
                id = UUID.randomUUID().toString(),
                label = label,
                alias = label,
                dn = DistinguishedName(commonName = label).withDefaults(),
                algorithm = "EC",
                keySize = 256,
                signatureAlgorithm = KeyAlgorithm.EC_P256.signatureAlgorithm,
                serialNumberHex = material.certificate.serialNumber.toString(16),
                createdAt = 0,
                notBefore = material.certificate.notBefore.time,
                notAfter = material.certificate.notAfter.time,
                certificatePem = KeyMaterial.toPem(material.certificate),
                fingerprintSha256 = KeyMaterial.fingerprintSha256(material.certificate),
            )
            return PortableIdentity(meta, password, pkcs12)
        }
    }

    @Test
    fun `archive round trips every identity`() {
        val identities = listOf(sampleIdentity("alpha"), sampleIdentity("beta"))
        val passphrase = "correct horse battery staple".toCharArray()

        val archive = BackupArchive.create(identities, passphrase.copyOf())
        val restored = BackupArchive.open(archive, passphrase.copyOf())

        assertEquals(2, restored.size)
        assertEquals(identities.map { it.meta.label }, restored.map { it.meta.label })
        assertArrayEquals(identities[0].pkcs12, restored[0].pkcs12)
        assertArrayEquals(identities[0].keystorePassword, restored[0].keystorePassword)
    }

    @Test
    fun `restored keystore still opens with its original password`() {
        val identity = sampleIdentity("gamma")
        val passphrase = "a long enough passphrase".toCharArray()

        val archive = BackupArchive.create(listOf(identity), passphrase.copyOf())
        val restored = BackupArchive.open(archive, passphrase.copyOf()).single()

        val loaded = KeyMaterial.readPkcs12(
            restored.pkcs12,
            restored.keystorePassword,
            restored.meta.alias,
        )
        assertEquals("gamma", loaded.chain.first().subjectX500Principal.name.substringAfter("CN=").substringBefore(","))
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val archive = BackupArchive.create(
            listOf(sampleIdentity("delta")),
            "the right passphrase".toCharArray(),
        )

        assertThrows(BackupDecryptionException::class.java) {
            BackupArchive.open(archive, "the wrong passphrase".toCharArray())
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val passphrase = "a long enough passphrase".toCharArray()
        val archive = BackupArchive.create(listOf(sampleIdentity("epsilon")), passphrase.copyOf())
        archive[archive.size - 1] = (archive[archive.size - 1].toInt() xor 0x01).toByte()

        assertThrows(BackupDecryptionException::class.java) {
            BackupArchive.open(archive, passphrase.copyOf())
        }
    }

    @Test
    fun `downgraded kdf parameters are rejected`() {
        val passphrase = "a long enough passphrase".toCharArray()
        val archive = BackupArchive.create(listOf(sampleIdentity("zeta")), passphrase.copyOf())
        // Bytes 9..12 are the big-endian Argon2 memory cost. Nudging it to a value that is
        // still in range proves the header is bound by the GCM tag rather than merely sanity checked.
        archive[12] = 1

        assertThrows(BackupDecryptionException::class.java) {
            BackupArchive.open(archive, passphrase.copyOf())
        }
    }

    @Test
    fun `foreign files are rejected before any key derivation`() {
        assertThrows(BackupDecryptionException::class.java) {
            BackupArchive.open("not an archive at all".toByteArray(), "x".toCharArray())
        }
    }

    @Test
    fun `header is not the plaintext`() {
        val archive = BackupArchive.create(
            listOf(sampleIdentity("eta")),
            "a long enough passphrase".toCharArray(),
        )

        assertTrue(String(archive, Charsets.ISO_8859_1).indexOf("BEGIN CERTIFICATE") == -1)
        assertTrue(String(archive, Charsets.ISO_8859_1).indexOf("\"alias\"") == -1)
    }
}
