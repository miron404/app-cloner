package io.github.miron404.appcloner.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer

/**
 * Envelope encryption for at-rest secrets.
 *
 * A fresh 256-bit data-encryption key (DEK) protects the payload with AES-256-GCM in software; the
 * DEK itself is wrapped by the hardware-backed master key. Only 32 bytes ever pass through
 * StrongBox, which is important because the secure element is slow for bulk data.
 *
 * Layout (big-endian):
 *
 *     magic "AKS1" (4) | flags (1) | wrapIv (1+n) | wrappedDek (2+n) | bodyIv (1+n) | body (4+n)
 *
 * Everything up to and including `wrappedDek` is authenticated as additional data on the body, and
 * the header prefix plus the caller's context is authenticated on the wrap. A stored blob is
 * therefore bound both to this app's master key and to the identity it belongs to, so blobs cannot
 * be swapped between entries.
 */
object Envelope {
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    private const val FLAG_HARDWARE_BACKED = 0x01

    suspend fun seal(wrapper: KeyWrapper, context: ByteArray, plaintext: ByteArray): ByteArray {
        val flags = if (wrapper.isHardwareBacked) FLAG_HARDWARE_BACKED else 0
        val header = MAGIC + byteArrayOf(flags.toByte())
        val dek = Aead.newKey()
        try {
            val wrapped = wrapper.wrap(header + context, dek)
            val (bodyIv, body) = Aead.encrypt(dek, header + context, plaintext)
            val out = ByteArrayOutputStream(plaintext.size + 128)
            DataOutputStream(out).use { sink ->
                sink.write(header)
                sink.writeByte(wrapped.iv.size)
                sink.write(wrapped.iv)
                sink.writeShort(wrapped.ciphertext.size)
                sink.write(wrapped.ciphertext)
                sink.writeByte(bodyIv.size)
                sink.write(bodyIv)
                sink.writeInt(body.size)
                sink.write(body)
            }
            return out.toByteArray()
        } finally {
            dek.wipe()
        }
    }

    suspend fun open(wrapper: KeyWrapper, context: ByteArray, blob: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(blob)
        val magic = buffer.take(MAGIC.size)
        require(magic.contentEquals(MAGIC)) { "Not an APK Signer envelope" }
        val flags = buffer.byte().toInt()
        val header = MAGIC + byteArrayOf(flags.toByte())

        val wrapIv = buffer.take(buffer.byte().toInt() and 0xFF)
        val wrappedDek = buffer.take(buffer.short.toInt() and 0xFFFF)
        val bodyIv = buffer.take(buffer.byte().toInt() and 0xFF)
        val body = buffer.take(buffer.int)

        val dek = wrapper.unwrap(header + context, wrapIv, wrappedDek)
        return try {
            Aead.decrypt(dek, header + context, bodyIv, body)
        } finally {
            dek.wipe()
        }
    }

    private fun ByteBuffer.byte(): Byte = if (hasRemaining()) get() else throw EOFException("Truncated envelope")

    private fun ByteBuffer.take(length: Int): ByteArray {
        if (length < 0 || length > remaining()) throw EOFException("Truncated envelope")
        return ByteArray(length).also { get(it) }
    }
}
