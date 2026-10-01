package io.github.miron404.appcloner.clone

import com.reandroid.dex.id.StringId
import com.reandroid.dex.model.DexFile
import com.reandroid.dex.sections.SectionType
import java.io.File
import java.io.InputStream

/**
 * Rewrites package-derived string constants inside the app's own bytecode.
 *
 * Renaming a package in the manifest is invisible to code that was compiled against the old name.
 * The common casualty is `BuildConfig.APPLICATION_ID`, which the Java compiler inlines as a string
 * literal: an app that builds a `FileProvider` authority out of it asks for `com.old.provider`
 * while its manifest now declares `com.new.provider`, and crashes the first time it shares a file.
 *
 * This is a modification of the application's code and it is not always safe, which is why it is
 * off by default. Two rules keep it as narrow as it can usefully be:
 *
 *  - Only strings that are exactly the old package, or live under it as `old.something`, are
 *    touched. Type descriptors are written `Lcom/old/Thing;` with slashes, so the classes
 *    themselves are never renamed and the code keeps working.
 *  - A string that names a class the app actually contains is left alone. Those are
 *    `Class.forName` targets and reflection keys; rewriting them would point the app at a class
 *    that does not exist. This is why every dex is indexed before any of them is edited.
 *
 * Rebuilding a dex is the memory-hungry part of a clone, because re-sorting the string pool means
 * every offset in the file has to be laid out again. Only one dex is ever held as an object model,
 * it is read straight from the archive and written straight to a file, and the read-only indexing
 * pass avoids the model entirely.
 */
object DexRewriter {

    /** The dotted names of every class the app defines under the package being renamed. */
    class ClassIndex(private val names: Set<String>) {
        operator fun contains(dotted: String) = dotted in names
        val size: Int get() = names.size
    }

    /** Collects the class names in one dex by walking its string pool directly. */
    fun indexClasses(dexBytes: ByteArray, oldPackage: String): Set<String> =
        DexStringTable.classNamesUnder(dexBytes, oldPackage)

    /**
     * Rewrites one dex read from [input], writing the result to [output].
     *
     * [Rewritten.written] is false when no string matched, in which case [output] is untouched and
     * the caller should keep the original entry byte for byte.
     */
    fun rewrite(
        input: InputStream,
        oldPackage: String,
        newPackage: String,
        index: ClassIndex,
        output: File,
    ): Rewritten {
        var changed = 0
        var skipped = 0
        return DexFile.read(input).use { dex ->
            dex.getItems(SectionType.STRING_ID).forEach { id: StringId ->
                val text = id.string ?: return@forEach
                val moved = ManifestRewriter.swapPrefix(text, oldPackage, newPackage)
                    ?: return@forEach
                if (text in index) {
                    skipped++
                    return@forEach
                }
                id.string = moved
                changed++
            }
            if (changed == 0) {
                Rewritten(false, 0, skipped)
            } else {
                // String ids must stay sorted by their contents or the verifier rejects the dex.
                // refreshFull re-sorts the string sections and rebuilds every offset that moved.
                dex.refreshFull()
                dex.write(output)
                Rewritten(true, changed, skipped)
            }
        }
    }

    class Rewritten(val written: Boolean, val changed: Int, val skipped: Int)

    /** Names of the entries a dex rewrite has to consider, in the order Android loads them. */
    fun isDexEntry(name: String): Boolean =
        name.startsWith("classes") && name.endsWith(".dex") && !name.contains('/')

    // --- how much heap this needs, and whether there is that much ---------------------------

    /**
     * What the object model for a dex costs, as a multiple of the dex's own size.
     *
     * Editing a string means re-sorting the string pool, and ARSCLib re-sorts the type, proto,
     * field, method and class sections along with it, because items there reference strings by
     * object rather than by index. So the whole file has to exist as objects at once; there is no
     * cheaper path through the library, and a hand-written one would have to renumber every string
     * index in every instruction, annotation and debug record itself.
     *
     * The multiple is measured rather than guessed, from the one app whose dex sizes are known
     * here — this app's own debug build. Its largest dex is 42 MB, rebuilding it does not fit in
     * the 512 MB a JVM gets by default, and the test that does it is given 2 GB. That puts the
     * true cost above 12x and below roughly 48x. Twenty is deliberately near the cautious end:
     * being too high refuses a rewrite that might just have fitted, while being too low kills the
     * process and loses the whole build with it.
     */
    const val MODEL_HEAP_FACTOR = 20L

    /**
     * Heap that has to stay free for everything which is not this rewrite.
     *
     * Leaving the app mid-build and coming back is the normal case rather than the exception, and
     * a recreated activity rebuilds the entire Compose tree. With nothing spare, that allocation
     * is the one that throws — on the main thread, where it takes the process down instead of
     * failing the build.
     */
    const val HEAP_RESERVE = 96L * 1024 * 1024

    /** What building the object model for a dex of [dexBytes] is expected to cost. */
    fun estimateModelHeap(dexBytes: Long): Long = dexBytes * MODEL_HEAP_FACTOR

    /**
     * Whether a dex of [dexBytes] can be rebuilt with [availableHeap] bytes to hand.
     *
     * A size of zero means the archive did not say, in which case this cannot judge and says yes:
     * the caller still has the out-of-memory guard behind it.
     */
    fun fitsInHeap(dexBytes: Long, availableHeap: Long): Boolean =
        dexBytes <= 0 || estimateModelHeap(dexBytes) + HEAP_RESERVE <= availableHeap

    /** Heap still unspent: what the limit allows, less what is already held. */
    fun availableHeap(): Long = Runtime.getRuntime().let { runtime ->
        runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
    }

    /**
     * Why a dex cannot be rewritten here, phrased for someone who has to decide what to do about
     * it. Deep rename is the only thing that needs this, and turning it off is always available.
     */
    fun tooLargeMessage(dexName: String, apkName: String, dexBytes: Long, availableHeap: Long) =
        "$dexName in $apkName is ${megabytes(dexBytes)}, and rebuilding its string pool needs " +
            "about ${megabytes(estimateModelHeap(dexBytes))} of heap — there is " +
            "${megabytes(availableHeap)} free of the ${megabytes(Runtime.getRuntime().maxMemory())} " +
            "this device allows one app. Turn deep rename off to clone this app: the package is " +
            "still renamed everywhere the manifest declares it, which is all most apps need."

    private fun megabytes(bytes: Long) = "%.0f MB".format(bytes / 1048576.0)
}
