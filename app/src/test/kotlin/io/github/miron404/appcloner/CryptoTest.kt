package io.github.miron404.appcloner

import io.github.miron404.appcloner.core.Aead
import io.github.miron404.appcloner.core.Envelope
import io.github.miron404.appcloner.core.KeyWrapper
import io.github.miron404.appcloner.core.WrappedKey
import io.github.miron404.appcloner.core.randomBytes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/** Stands in for the StrongBox key: same contract, no hardware and no authentication. */
private class SoftwareWrapper(private val key: ByteArray = Aead.newKey()) : KeyWrapper {
    override val isHardwareBacked = false

    override suspend fun wrap(aad: ByteArray, plaintext: ByteArray): WrappedKey {
        val (iv, ciphertext) = Aead.encrypt(key, aad, plaintext)
        return WrappedKey(iv, ciphertext)
    }

    override suspend fun unwrap(aad: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray =
        Aead.decrypt(key, aad, iv, ciphertext)
}

class EnvelopeTest {

    private val context = "identity-1".toByteArray()

    @Test
    fun `round trips the payload`() = runTest {
        val wrapper = SoftwareWrapper()
        val secret = "keystore password and a PKCS#12 blob".toByteArray()

        val sealed = Envelope.seal(wrapper, context, secret)

        assertTrue(sealed.size > secret.size)
        assertArrayEquals(secret, Envelope.open(wrapper, context, sealed))
    }

    @Test
    fun `produces a different envelope every time`() = runTest {
        val wrapper = SoftwareWrapper()
        val secret = randomBytes(64)

        val first = Envelope.seal(wrapper, context, secret)
        val second = Envelope.seal(wrapper, context, secret)

        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `rejects an envelope opened under a different identity`() = runTest {
        val wrapper = SoftwareWrapper()
        val sealed = Envelope.seal(wrapper, context, randomBytes(32))

        assertThrows(AEADBadTagException::class.java) {
            kotlinx.coroutines.runBlocking {
                Envelope.open(wrapper, "identity-2".toByteArray(), sealed)
            }
        }
    }

    @Test
    fun `rejects an envelope opened with a different master key`() = runTest {
        val sealed = Envelope.seal(SoftwareWrapper(), context, randomBytes(32))

        assertThrows(AEADBadTagException::class.java) {
            kotlinx.coroutines.runBlocking { Envelope.open(SoftwareWrapper(), context, sealed) }
        }
    }

    @Test
    fun `detects a flipped bit in the body`() = runTest {
        val wrapper = SoftwareWrapper()
        val sealed = Envelope.seal(wrapper, context, randomBytes(128))
        sealed[sealed.size - 20] = (sealed[sealed.size - 20].toInt() xor 0x01).toByte()

        assertThrows(AEADBadTagException::class.java) {
            kotlinx.coroutines.runBlocking { Envelope.open(wrapper, context, sealed) }
        }
    }

    @Test
    fun `rejects a foreign header`() = runTest {
        val wrapper = SoftwareWrapper()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { Envelope.open(wrapper, context, ByteArray(64)) }
        }
    }
}

class AeadTest {

    @Test
    fun `authenticates the associated data`() {
        val key = Aead.newKey()
        val (iv, ciphertext) = Aead.encrypt(key, "context-a".toByteArray(), "secret".toByteArray())

        assertThrows(AEADBadTagException::class.java) {
            Aead.decrypt(key, "context-b".toByteArray(), iv, ciphertext)
        }
    }

    @Test
    fun `uses a fresh nonce per message`() {
        val key = Aead.newKey()
        val first = Aead.encrypt(key, ByteArray(0), "same".toByteArray()).first
        val second = Aead.encrypt(key, ByteArray(0), "same".toByteArray()).first

        assertEquals(Aead.IV_SIZE_BYTES, first.size)
        assertNotEquals(first.toList(), second.toList())
    }
}
