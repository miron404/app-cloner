package io.github.miron404.appcloner

import io.github.miron404.appcloner.clone.DexRewriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that decides whether a deep rename is attempted at all.
 *
 * It exists because the alternative way of finding out is an `OutOfMemoryError`, and that does not
 * land politely inside the build: on a heap with nothing left, the thread that throws is whichever
 * one allocates next, which is often the one drawing the screen. The user then loses the process
 * and the whole build rather than being told no.
 */
class DexHeapBudgetTest {

    private val megabyte = 1024L * 1024

    /** The real case from the device: a dex this size against the heap a phone actually allows. */
    @Test
    fun `this app's own largest dex does not fit in a phone's heap`() {
        val dex = 42 * megabyte
        assertFalse(
            "42 MB of dex cannot be rebuilt in 512 MB",
            DexRewriter.fitsInHeap(dex, 512 * megabyte),
        )
    }

    /**
     * The one that has to keep working: a dex small enough for a phone is not refused. Ten
     * megabytes is the size of the smaller dexes in this app's own debug build, and refusing those
     * would have made deep rename useless rather than merely limited.
     */
    @Test
    fun `a dex a phone can manage is allowed`() {
        assertTrue(DexRewriter.fitsInHeap(10 * megabyte, 512 * megabyte))
    }

    /** And the CI case, so the pipeline test that does a real deep rename still runs. */
    @Test
    fun `the same dex fits in the heap the tests are given`() {
        assertTrue(DexRewriter.fitsInHeap(42 * megabyte, 2048 * megabyte))
    }

    @Test
    fun `room is left over for everything that is not the rewrite`() {
        val dex = 10 * megabyte
        val model = DexRewriter.estimateModelHeap(dex)

        assertFalse(
            "a heap that only just holds the model leaves nothing for the screen",
            DexRewriter.fitsInHeap(dex, model),
        )
        assertTrue(DexRewriter.fitsInHeap(dex, model + DexRewriter.HEAP_RESERVE))
    }

    /**
     * A zip that does not declare an entry's size cannot be judged in advance, so the rewrite is
     * allowed to try and the out-of-memory guard behind it is what catches the miss.
     */
    @Test
    fun `an undeclared size is not treated as zero cost`() {
        assertTrue(DexRewriter.fitsInHeap(0L, 1L))
    }

    @Test
    fun `the refusal says which dex, how big, and what to do instead`() {
        val message = DexRewriter.tooLargeMessage(
            dexName = "classes.dex",
            apkName = "base.apk",
            dexBytes = 42 * megabyte,
            heapLimit = 512 * megabyte,
        )

        assertTrue(message, message.contains("classes.dex"))
        assertTrue(message, message.contains("base.apk"))
        assertTrue(message, message.contains("42 MB"))
        assertTrue(message, message.contains("deep rename"))
        assertTrue(message, message.contains("512 MB"))
    }
}
