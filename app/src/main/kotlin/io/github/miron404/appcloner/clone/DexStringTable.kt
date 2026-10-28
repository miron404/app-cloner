package io.github.miron404.appcloner.clone

import java.io.EOFException

/**
 * Reads the string pool out of a dex without building a model of the rest of it.
 *
 * The indexing pass has two questions to answer — which classes does this app define, and which
 * dex files have a string a deep rename would move — and both are about strings in the pool.
 * Parsing a whole dex into objects to find that out costs hundreds of megabytes on a large app and
 * is what made the first version run out of heap. Walking `string_ids` by hand costs the size of
 * the file and nothing else.
 *
 * The layout being read is fixed by the dex format:
 *
 *     header:  ... string_ids_size @0x38, string_ids_off @0x3C   (little-endian u32)
 *     each string_id is a u32 offset to a string_data_item
 *     string_data_item: uleb128 utf16_size, then MUTF-8 bytes terminated by 0x00
 *
 * Strings are compared as raw bytes rather than decoded. A package name is ASCII, so a prefix
 * match needs no decoding at all, and only the strings that actually match are turned into Java
 * strings.
 */
object DexStringTable {

    private const val STRING_IDS_SIZE_OFFSET = 0x38
    private const val STRING_IDS_OFF_OFFSET = 0x3C

    /**
     * Calls [action] with the raw bytes of every string in the pool, reusing nothing: the range is
     * given as offsets into [dex] so no copy is made for strings the caller does not want.
     */
    fun forEachString(dex: ByteArray, action: (start: Int, endExclusive: Int) -> Unit) {
        val count = dex.readInt(STRING_IDS_SIZE_OFFSET)
        val table = dex.readInt(STRING_IDS_OFF_OFFSET)
        if (count <= 0 || table <= 0) return
        if (table.toLong() + count.toLong() * 4L > dex.size) {
            throw EOFException("dex string table runs past the end of the file")
        }
        for (i in 0 until count) {
            var at = dex.readInt(table + i * 4)
            if (at < 0 || at >= dex.size) throw EOFException("dex string offset out of range")
            at = skipUleb128(dex, at)
            var end = at
            while (end < dex.size && dex[end] != 0.toByte()) end++
            action(at, end)
        }
    }

    /** What one dex holds under a package, as far as a deep rename is concerned. */
    class Scan(
        /**
         * Dotted names of every class the dex defines under the package. These are the names that
         * must not be rewritten as strings: they are what `Class.forName` is given, and the classes
         * keep their original descriptors.
         */
        val classNames: Set<String>,
        /**
         * Every string that is the package itself or a dotted name under it — exactly the strings
         * [DexRewriter.rewrite] would consider moving, before class names are taken out.
         */
        val packageStrings: Set<String>,
    )

    /** Reads both halves of a [Scan] for [packageName] out of [dex] in one walk of its pool. */
    fun scan(dex: ByteArray, packageName: String): Scan {
        val descriptor = ("L" + packageName.replace('.', '/')).toByteArray(Charsets.US_ASCII)
        val dotted = packageName.toByteArray(Charsets.US_ASCII)
        val classNames = mutableSetOf<String>()
        val packageStrings = mutableSetOf<String>()
        forEachString(dex) { start, end ->
            val length = end - start
            if (isClassUnder(dex, start, end, descriptor)) {
                classNames += String(dex, start + 1, length - 2, Charsets.UTF_8).replace('/', '.')
            } else if (startsWith(dex, start, end, dotted) &&
                (length == dotted.size || dex[start + dotted.size] == '.'.code.toByte())
            ) {
                packageStrings += String(dex, start, length, Charsets.UTF_8)
            }
        }
        return Scan(classNames, packageStrings)
    }

    /** Dotted names of every class in [dex] that lives under [packageName]. */
    fun classNamesUnder(dex: ByteArray, packageName: String): Set<String> =
        scan(dex, packageName).classNames

    /** Whether the string at [start] is a descriptor `Lcom/foo/…;` for the package [prefix] names. */
    private fun isClassUnder(dex: ByteArray, start: Int, end: Int, prefix: ByteArray): Boolean {
        // 'Lcom/foo' plus at least a separator and the closing semicolon.
        if (end - start <= prefix.size) return false
        if (dex[end - 1] != ';'.code.toByte()) return false
        if (!startsWith(dex, start, end, prefix)) return false
        // Guard against 'Lcom/foobar;' matching the prefix of package 'com.foo'.
        val next = dex[start + prefix.size]
        return next == '/'.code.toByte() || next == ';'.code.toByte()
    }

    private fun startsWith(dex: ByteArray, start: Int, end: Int, prefix: ByteArray): Boolean {
        if (end - start < prefix.size) return false
        for (i in prefix.indices) {
            if (dex[start + i] != prefix[i]) return false
        }
        return true
    }

    private fun ByteArray.readInt(at: Int): Int {
        if (at < 0 || at + 4 > size) throw EOFException("dex header is truncated")
        return (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)
    }

    /** A uleb128 is at most five bytes; the value itself is the character count, not needed here. */
    private fun skipUleb128(dex: ByteArray, from: Int): Int {
        var at = from
        var read = 0
        while (at < dex.size && read < 5) {
            val byte = dex[at].toInt()
            at++
            read++
            if (byte and 0x80 == 0) return at
        }
        throw EOFException("malformed uleb128 in dex string table")
    }
}
