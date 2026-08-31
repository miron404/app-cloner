package io.github.miron404.appcloner.clone

import com.reandroid.dex.id.StringId
import com.reandroid.dex.model.DexFile
import com.reandroid.dex.sections.SectionType

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
 *    that does not exist. This is why every dex is scanned before any of them is edited.
 */
object DexRewriter {

    /** Result of the read-only first pass: the dotted names of every class the app defines. */
    class ClassIndex(private val names: Set<String>) {
        operator fun contains(dotted: String) = dotted in names
        val size: Int get() = names.size
    }

    /**
     * Collects the class names in one dex, as dotted names.
     *
     * Every type descriptor is a string in the pool, so scanning the string section finds them
     * without also materialising the class table.
     */
    fun indexClasses(dexBytes: ByteArray, oldPackage: String): Set<String> {
        val prefix = "L" + oldPackage.replace('.', '/')
        val names = mutableSetOf<String>()
        DexFile.read(dexBytes).use { dex ->
            dex.getItems(SectionType.STRING_ID).forEach { id: StringId ->
                val text = id.string ?: return@forEach
                if (!text.startsWith(prefix) || !text.endsWith(";")) return@forEach
                // Guard against 'Lcom/oldest/...' matching the prefix of 'com.old'.
                val after = text.getOrNull(prefix.length)
                if (after != '/' && after != ';') return@forEach
                names += text.substring(1, text.length - 1).replace('/', '.')
            }
        }
        return names
    }

    /**
     * Rewrites one dex against [index]. Returns the new bytes, or null when nothing changed and
     * the original can be kept byte for byte.
     */
    fun rewrite(
        dexBytes: ByteArray,
        oldPackage: String,
        newPackage: String,
        index: ClassIndex,
    ): Rewritten {
        var changed = 0
        var skipped = 0
        return DexFile.read(dexBytes).use { dex ->
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
                Rewritten(null, 0, skipped)
            } else {
                // String ids must stay sorted by their contents or the verifier rejects the dex.
                // refreshFull re-sorts the string sections and rebuilds every offset that moved.
                dex.refreshFull()
                Rewritten(dex.bytes, changed, skipped)
            }
        }
    }

    class Rewritten(val bytes: ByteArray?, val changed: Int, val skipped: Int)

    /** Names of the entries a dex rewrite has to consider, in the order Android loads them. */
    fun isDexEntry(name: String): Boolean =
        name.startsWith("classes") && name.endsWith(".dex") && !name.contains('/')
}
