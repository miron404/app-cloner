package io.github.miron404.appcloner

import io.github.miron404.appcloner.clone.DexRewriter
import io.github.miron404.appcloner.clone.DexStringTable
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * What the indexing pass reads out of a string pool, on a pool built by hand.
 *
 * The scan decides which dex files are sent to be rewritten at all, so a string it misses is a
 * string the clone keeps unchanged. The fixture APK checks the scan against a real rewrite; this
 * checks the edges of what counts as being under a package, which the fixture may not have.
 */
class DexStringTableTest {

    private val pool = dex(
        "Lcom/foo/Bar;",
        "Lcom/foo/inner/Baz;",
        "Lcom/foobar/Other;",
        "Lcom/foo;",
        "com.foo",
        "com.foo.Bar",
        "com.foo.provider",
        "com.foobar.provider",
        "com.fo",
        "Lcom/foo/Unterminated",
        "prefix com.foo.provider",
    )

    @Test
    fun `class names come from descriptors under the package only`() {
        assertEquals(
            setOf("com.foo.Bar", "com.foo.inner.Baz", "com.foo"),
            DexStringTable.scan(pool, "com.foo").classNames,
        )
    }

    @Test
    fun `package strings are the package itself and dotted names under it`() {
        assertEquals(
            setOf("com.foo", "com.foo.Bar", "com.foo.provider"),
            DexStringTable.scan(pool, "com.foo").packageStrings,
        )
    }

    @Test
    fun `a dex whose only package strings are class names has nothing to move`() {
        val scan = DexStringTable.scan(dex("Lcom/foo/Bar;", "com.foo.Bar"), "com.foo")
        val index = DexRewriter.ClassIndex(scan.classNames)

        assertEquals(0, DexRewriter.willMove(scan.packageStrings, index))
        // The same index against the larger pool: 'com.foo' and 'com.foo.provider' still move.
        val larger = DexStringTable.scan(pool, "com.foo").packageStrings
        assertEquals(2, DexRewriter.willMove(larger, index))
    }

    @Test
    fun `a dex that never mentions the package has nothing to scan`() {
        val scan = DexStringTable.scan(dex("Landroidx/core/Thing;", "androidx.core"), "com.foo")

        assertEquals(emptySet<String>(), scan.classNames)
        assertEquals(emptySet<String>(), scan.packageStrings)
    }

    /**
     * The smallest file [DexStringTable] will read: a header that is all zeroes but for where the
     * string ids are, the ids, and the string data they point at. Nothing else in a dex is looked
     * at, so nothing else is written.
     */
    private fun dex(vararg strings: String): ByteArray {
        val header = 0x70
        val ids = strings.size * 4
        val data = ByteArrayOutputStream()
        val offsets = strings.map { text ->
            val offset = header + ids + data.size()
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(text.length < 0x80) { "one-byte uleb128 lengths only" }
            data.write(text.length)
            data.write(bytes)
            data.write(0)
            offset
        }
        val out = ByteArray(header + ids + data.size())
        out.putInt(0x38, strings.size)
        out.putInt(0x3C, header)
        offsets.forEachIndexed { i, offset -> out.putInt(header + i * 4, offset) }
        data.toByteArray().copyInto(out, header + ids)
        return out
    }

    private fun ByteArray.putInt(at: Int, value: Int) {
        for (i in 0 until 4) this[at + i] = (value ushr (8 * i)).toByte()
    }
}
