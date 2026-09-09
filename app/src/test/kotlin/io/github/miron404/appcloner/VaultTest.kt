package io.github.miron404.appcloner

import io.github.miron404.appcloner.core.Aead
import io.github.miron404.appcloner.core.AuthPolicy
import io.github.miron404.appcloner.core.Bc
import io.github.miron404.appcloner.core.DistinguishedName
import io.github.miron404.appcloner.core.KeyAlgorithm
import io.github.miron404.appcloner.core.KeyMaterial
import io.github.miron404.appcloner.core.KeyWrapper
import io.github.miron404.appcloner.core.MasterKeyState
import io.github.miron404.appcloner.core.MasterKeyStore
import io.github.miron404.appcloner.core.NewIdentityRequest
import io.github.miron404.appcloner.core.UNKNOWN
import io.github.miron404.appcloner.core.Vault
import io.github.miron404.appcloner.core.VaultSettings
import io.github.miron404.appcloner.core.WrappedKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** In-memory stand-in for AndroidKeyStore. Each alias is just a random AES key. */
private class FakeKeyStore : MasterKeyStore {
    val keys = mutableMapOf<String, ByteArray>()
    private val policies = mutableMapOf<String, AuthPolicy>()
    private var counter = 0

    /** Set to make the next create() behave like a device without a secure element. */
    var strongBox = true

    override fun state(alias: String) = MasterKeyState(
        exists = keys.containsKey(alias),
        strongBoxBacked = strongBox,
        policy = policies[alias] ?: AuthPolicy.DEFAULT,
    )

    override fun exists(alias: String) = keys.containsKey(alias)

    override fun create(alias: String, policy: AuthPolicy): Boolean {
        keys[alias] = Aead.newKey()
        policies[alias] = policy
        return strongBox
    }

    override fun wrapper(alias: String, policy: AuthPolicy): KeyWrapper = object : KeyWrapper {
        override val isHardwareBacked = strongBox

        private fun key() = keys[alias] ?: error("Master key $alias is missing")

        override suspend fun wrap(aad: ByteArray, plaintext: ByteArray): WrappedKey {
            val (iv, ciphertext) = Aead.encrypt(key(), aad, plaintext)
            return WrappedKey(iv, ciphertext)
        }

        override suspend fun unwrap(aad: ByteArray, iv: ByteArray, ciphertext: ByteArray) =
            Aead.decrypt(key(), aad, iv, ciphertext)
    }

    override fun delete(alias: String) {
        keys.remove(alias)
        policies.remove(alias)
    }

    override fun deleteOrphans(keep: Set<String>) {
        keys.keys.filterNot { it in keep }.forEach(::delete)
    }

    override fun newAlias(): String = "master.${counter++}"
}

private class FakeSettings : VaultSettings {
    override var masterKeyAlias: String? = null
    override var pendingMasterKeyAlias: String? = null
    override var desiredPolicy: AuthPolicy = AuthPolicy.DEFAULT
}

class VaultTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var keyStore: FakeKeyStore
    private lateinit var settings: FakeSettings
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("vault")
        keyStore = FakeKeyStore()
        settings = FakeSettings()
        vault = Vault(root, keyStore, settings)
    }

    private fun request(label: String) = NewIdentityRequest(
        label = label,
        alias = label,
        dn = DistinguishedName(commonName = label, country = "NL"),
        validityYears = 30,
        algorithm = KeyAlgorithm.EC_P256,
    )

    @Test
    fun `stores metadata in the clear and key material sealed`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(request("alpha"))

        val metaFile = File(root, meta.id + ".meta.json")
        val keyFile = File(root, meta.id + ".key")
        assertTrue(metaFile.isFile)
        assertTrue(keyFile.isFile)

        val onDisk = String(keyFile.readBytes(), Charsets.ISO_8859_1)
        val password = vault.open(meta).use { String(it.keystorePassword) }
        assertFalse("keystore password leaked to disk", onDisk.contains(password))
        assertFalse("certificate subject leaked into the sealed blob", onDisk.contains("alpha"))
        assertTrue("metadata should stay readable without authenticating", metaFile.readText().contains("alpha"))
    }

    @Test
    fun `create then open returns usable key material`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(request("beta"))

        vault.open(meta).use { portable ->
            val loaded = KeyMaterial.readPkcs12(portable.pkcs12, portable.keystorePassword, "beta")
            assertEquals(meta.fingerprintSha256, KeyMaterial.fingerprintSha256(loaded.chain.first()))
        }
    }

    @Test
    fun `blank subject fields are certified as Unknown`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(
            NewIdentityRequest(
                label = "",
                alias = "",
                dn = DistinguishedName(),
                validityYears = 30,
                algorithm = KeyAlgorithm.EC_P256,
            )
        )

        assertEquals(UNKNOWN, meta.dn.commonName)
        assertEquals(UNKNOWN, meta.alias)
        assertEquals(UNKNOWN, meta.label)
    }

    @Test
    fun `sealed blobs cannot be swapped between identities`() = runTest {
        vault.ensureMasterKey()
        val first = vault.create(request("one"))
        val second = vault.create(request("two"))

        File(root, first.id + ".key").copyTo(File(root, second.id + ".key"), overwrite = true)

        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            kotlinx.coroutines.runBlocking { vault.open(second) }
        }
    }

    @Test
    fun `deleting removes both files`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(request("gone"))

        vault.delete(meta.id)

        assertTrue(vault.list().isEmpty())
        assertFalse(File(root, meta.id + ".key").exists())
        assertFalse(File(root, meta.id + ".meta.json").exists())
    }

    /**
     * The clone form and the vault screen are separate view models reading one vault, so the list
     * has to arrive rather than be fetched. When it was fetched, an identity created on one screen
     * stayed invisible to the other — and so unusable — until the app was restarted.
     */
    @Test
    fun `every change to the vault is published to the identity flow`() = runTest {
        vault.ensureMasterKey()
        assertTrue("a fresh vault has nothing in it", vault.identities.value.isEmpty())

        val alpha = vault.create(request("alpha"))
        assertEquals(listOf(alpha.id), vault.identities.value.map { it.id })

        val beta = vault.create(request("beta"))
        assertEquals(listOf(alpha.id, beta.id), vault.identities.value.map { it.id })

        // Renaming only the label skips the re-sealing path, so it is published from somewhere else.
        vault.rename(beta, "aardvark", "")
        assertEquals(
            listOf("aardvark", "alpha"),
            vault.identities.value.map { it.label },
        )

        vault.delete(alpha.id)
        assertEquals(listOf(beta.id), vault.identities.value.map { it.id })
    }

    @Test
    fun `rekey re-seals every identity under a new master key`() = runTest {
        vault.ensureMasterKey()
        val first = vault.create(request("one"))
        val second = vault.create(request("two"))
        val originalAlias = settings.masterKeyAlias
        val originalBlob = File(root, first.id + ".key").readBytes()
        val originalSecret = vault.open(first).use { it.pkcs12.copyOf() }

        vault.rekey(AuthPolicy(timeoutSeconds = 0, allowDeviceCredential = false))

        assertNotEquals(originalAlias, settings.masterKeyAlias)
        assertNull(settings.pendingMasterKeyAlias)
        assertFalse("old master key should be gone", keyStore.exists(originalAlias!!))
        assertNotEquals(
            originalBlob.toList(),
            File(root, first.id + ".key").readBytes().toList(),
        )
        assertArrayEquals(originalSecret, vault.open(first).use { it.pkcs12.copyOf() })
        vault.open(second).use { assertEquals("two", it.meta.label) }
        assertEquals(0, vault.state().policy.timeoutSeconds)
    }

    @Test
    fun `rekey leaves no staged files behind`() = runTest {
        vault.ensureMasterKey()
        vault.create(request("one"))

        vault.rekey(AuthPolicy(timeoutSeconds = 60, allowDeviceCredential = true))

        assertTrue(root.listFiles()!!.none { it.name.endsWith(".staged") })
    }

    @Test
    fun `a crash before the commit point leaves the vault on the old key`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(request("one"))
        val originalAlias = settings.masterKeyAlias
        val originalSecret = vault.open(meta).use { it.pkcs12.copyOf() }

        // Simulate dying midway: re-sealed copies exist, but nothing was committed.
        File(root, meta.id + ".key.staged").writeBytes(ByteArray(64) { 0x7F })

        Vault(root, keyStore, settings).repair()

        assertEquals(originalAlias, settings.masterKeyAlias)
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".staged") })
        assertArrayEquals(originalSecret, vault.open(meta).use { it.pkcs12.copyOf() })
    }

    @Test
    fun `a crash after the commit point is finished on the next start`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(request("one"))
        val oldAlias = settings.masterKeyAlias!!

        // Re-seal by hand against a new key, then stop right after recording the pending alias.
        val newAlias = keyStore.newAlias()
        keyStore.create(newAlias, AuthPolicy(timeoutSeconds = 0, allowDeviceCredential = true))
        val plaintext = io.github.miron404.appcloner.core.Envelope.open(
            keyStore.wrapper(oldAlias, AuthPolicy.DEFAULT),
            meta.id.toByteArray(),
            File(root, meta.id + ".key").readBytes(),
        )
        File(root, meta.id + ".key.staged").writeBytes(
            io.github.miron404.appcloner.core.Envelope.seal(
                keyStore.wrapper(newAlias, AuthPolicy.DEFAULT),
                meta.id.toByteArray(),
                plaintext,
            )
        )
        settings.pendingMasterKeyAlias = newAlias

        val restarted = Vault(root, keyStore, settings)
        restarted.repair()

        assertEquals(newAlias, settings.masterKeyAlias)
        assertNull(settings.pendingMasterKeyAlias)
        assertFalse(keyStore.exists(oldAlias))
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".staged") })
        restarted.open(meta).use { assertEquals("one", it.meta.label) }
    }

    @Test
    fun `renaming the label leaves the sealed keystore untouched`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(request("before"))
        val sealed = File(root, meta.id + ".key").readBytes()

        val renamed = vault.rename(meta, label = "after", alias = "")

        assertEquals("after", renamed.label)
        assertEquals(meta.alias, renamed.alias)
        assertEquals("after", vault.list().single().label)
        assertArrayEquals(sealed, File(root, meta.id + ".key").readBytes())
    }

    @Test
    fun `renaming the alias rewrites the keystore but keeps the key`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(request("one"))
        val originalFingerprint = meta.fingerprintSha256

        val renamed = vault.rename(meta, label = "renamed", alias = "newalias")

        assertEquals("renamed", renamed.label)
        assertEquals("newalias", renamed.alias)
        assertEquals(originalFingerprint, renamed.fingerprintSha256)

        vault.open(renamed).use { portable ->
            val loaded = KeyMaterial.readPkcs12(portable.pkcs12, portable.keystorePassword, "newalias")
            assertEquals(originalFingerprint, KeyMaterial.fingerprintSha256(loaded.chain.first()))
        }
    }

    @Test
    fun `renaming keeps values the user left blank`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(request("keep"))

        val renamed = vault.rename(meta, label = "  ", alias = "  ")

        assertEquals(meta.label, renamed.label)
        assertEquals(meta.alias, renamed.alias)
    }

    @Test
    fun `renaming to the same names is a no-op`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(request("same"))
        val sealed = File(root, meta.id + ".key").readBytes()

        val renamed = vault.rename(meta, label = meta.label, alias = meta.alias)

        assertEquals(meta, renamed)
        assertArrayEquals(sealed, File(root, meta.id + ".key").readBytes())
    }

    @Test
    fun `restore skips identities that are already present`() = runTest {
        vault.ensureMasterKey()
        val meta = vault.create(request("one"))
        val portable = vault.open(meta)

        assertFalse(vault.restore(portable, overwrite = false))
        assertEquals(1, vault.list().size)
    }

    @Test
    fun `opening without a master key is refused rather than silently failing`() = runTest {
        assertThrows(io.github.miron404.appcloner.core.VaultLockedException::class.java) {
            kotlinx.coroutines.runBlocking { vault.create(request("nope")) }
        }
    }

    @Test
    fun `reports when the device has no secure element`() = runTest {
        keyStore.strongBox = false

        assertFalse(vault.ensureMasterKey())
        assertFalse(vault.state().strongBoxBacked)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun installProvider() {
            Bc.install()
        }
    }
}
