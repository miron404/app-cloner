package io.github.miron404.appcloner.clone

import com.reandroid.apk.ApkModule
import com.reandroid.archive.FileInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import io.github.miron404.appcloner.core.ApkSigningService
import io.github.miron404.appcloner.core.SignOptions
import io.github.miron404.appcloner.core.SignatureSchemes
import io.github.miron404.appcloner.core.UnlockedIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Turns a source app into an installable clone: rewrite, re-icon, sign, verify.
 *
 * Everything happens on files. The source is never modified — an installed app's APKs are opened
 * read-only where they lie — and each stage writes a new file, so a failure part way through
 * leaves nothing but rubbish in the work directory.
 */
class ClonePipeline(
    private val icons: IconRenderer,
    /**
     * Where deep rename's dex files are rewritten: a process of its own on a device, so the largest
     * allocation a clone makes cannot take the app down with it. Opened only when deep rename has
     * a dex with something in it to move, and closed as soon as there are none left.
     */
    private val openDexWorker: suspend () -> DexWorker = { InProcessDexWorker() },
) {

    suspend fun build(
        source: SourceApks,
        request: CloneRequest,
        identity: UnlockedIdentity,
        schemes: SignatureSchemes,
        outDir: File,
        onProgress: (CloneProgress) -> Unit,
    ): CloneReport = withContext(Dispatchers.Default) {
        val oldPackage = source.info.packageName
        val newPackage = request.packageName
        require(newPackage != oldPackage) { "The clone needs a package name of its own" }
        require(PackageNames.isValid(newPackage)) { "'$newPackage' is not a valid package name" }

        val unsignedDir = File(outDir, "unsigned").apply { mkdirs() }
        // Rewritten dex files are written here rather than held as byte arrays: a multidex app
        // would otherwise keep every one of them in memory until the APK is written out.
        val dexScratch = File(outDir, "dex").apply { mkdirs() }
        outDir.mkdirs()

        val warnings = mutableListOf<String>()
        var manifestReport = ManifestRewriteReport()
        var iconSummary = IconMode.KEEP.label
        var dexChanged = 0
        var dexSkipped = 0

        val minSdk = runCatching { ApkSigningService.inspect(source.base).minSdkVersion }
            .getOrDefault(DEFAULT_MIN_SDK)

        var dexWorker: DexWorker? = null
        val outputs = mutableListOf<File>()
        try {
            // Every dex in the app is indexed before any of them is edited: a string naming a class
            // that lives in a different dex still has to be left alone. The same pass says which
            // dex files have anything to move, and only those are ever sent to the worker.
            val dexPlan = if (request.deepRename) {
                onProgress(CloneProgress("Indexing classes", "so reflection keeps working"))
                planDexRewrite(source.all, oldPackage)
            } else {
                null
            }

            // The index goes to a file, which is how it reaches the worker, and is not kept here
            // once written.
            var classIndex: File? = null
            if (dexPlan != null && dexPlan.pending.isNotEmpty()) {
                onProgress(
                    CloneProgress("Starting the dex worker", "a process with a heap to itself")
                )
                val worker = openDexWorker().also { dexWorker = it }
                // The heap that matters is that process's, not this one's, and only the dex files
                // that are going to be rebuilt are held against it.
                requireRoomForDeepRename(dexPlan.pending, worker.heapLimit)
                classIndex = File(dexScratch, CLASS_INDEX).also { dexPlan.classIndex.write(it) }
            }

            // Rendered from the source APKs before anything is rewritten, because that is where
            // the icon's own resources still are.
            val images = if (request.iconMode == IconMode.BADGE) {
                onProgress(CloneProgress("Rendering icon"))
                icons.render(source, request.badgeText, request.badgeCorner).also {
                    if (it == null) {
                        warnings += "The source app's icon could not be rendered, so it was left " +
                            "as it is."
                    }
                }
            } else {
                null
            }

            source.all.forEachIndexed { index, apk ->
                coroutineContext.ensureActive()
                val isBase = index == 0
                onProgress(
                    CloneProgress(
                        step = if (isBase) "Rewriting the base APK" else "Rewriting a split",
                        detail = apk.name,
                        fraction = index.toFloat() / source.all.size,
                    )
                )

                ApkModule.loadApkFile(apk).use { module ->
                    // Nothing here resolves a platform resource by name, so pulling in a bundled
                    // copy of the android framework table would be time spent for no purpose.
                    module.setLoadDefaultFramework(false)
                    val manifest = module.androidManifest
                        ?: throw IllegalArgumentException("${apk.name} has no AndroidManifest.xml")
                    val splitName = manifest.split

                    if (isBase) warnings += inspect(manifest, request)

                    manifestReport += ManifestRewriter.rewrite(
                        module = module,
                        oldPackage = oldPackage,
                        newPackage = newPackage,
                        // Only the base carries the application label; a split declaring one would
                        // make the manifests disagree and the install would be rejected.
                        label = request.label.takeIf { isBase },
                        renameIntentActions = request.renameIntentActions,
                    )

                    val worker = dexWorker
                    val pending = dexPlan?.pending?.get(apk)
                    if (worker != null && classIndex != null && pending != null) {
                        val result = rewriteDex(
                            module = module,
                            apk = apk,
                            pending = pending,
                            oldPackage = oldPackage,
                            newPackage = newPackage,
                            worker = worker,
                            classIndex = classIndex,
                            scratch = dexScratch,
                            // The module name is the same for a base and its splits, so the
                            // position in the set is what keeps the scratch files apart.
                            prefix = index.toString(),
                        )
                        dexChanged += result.first
                        dexSkipped += result.second
                    }

                    if (isBase && images != null) {
                        val applied = runCatching {
                            val resourceId = IconInjector.inject(module, images)
                            ManifestRewriter.setIcon(manifest, resourceId)
                        }
                        if (applied.isSuccess) {
                            iconSummary = "badged '${request.badgeText}', " +
                                request.badgeCorner.label.lowercase() +
                                if (images.monochrome == null) {
                                    ", no themed layer in the original"
                                } else {
                                    ", themed layer badged too"
                                }
                        } else {
                            warnings += "The icon could not be replaced (" +
                                (applied.exceptionOrNull()?.message ?: "unknown reason") +
                                "), so the original was kept."
                        }
                    }

                    module.refreshManifest()
                    module.refreshTable()

                    val name = if (splitName.isNullOrBlank()) {
                        "$newPackage.apk"
                    } else {
                        "$newPackage-$splitName.apk"
                    }
                    module.writeApk(File(unsignedDir, name))
                    outputs += File(outDir, name)
                }
            }
            // Nothing is left for it to rewrite, and signing can take a while: there is no reason
            // for a process holding the remains of the last dex model to wait through it.
            dexWorker?.close()

            // One signer, one minSdk and one set of schemes across the base and every split: a
            // session install rejects a set whose members do not agree.
            val signed = mutableListOf<File>()
            outputs.forEachIndexed { index, target ->
                coroutineContext.ensureActive()
                onProgress(
                    CloneProgress(
                        step = "Signing",
                        detail = target.name,
                        fraction = index.toFloat() / outputs.size,
                    )
                )
                val input = File(unsignedDir, target.name)
                ApkSigningService.sign(
                    identity = identity,
                    input = input,
                    output = target,
                    v4Output = null,
                    options = SignOptions(schemes = schemes, realign = true, minSdkOverride = minSdk),
                )
                input.delete()
                signed += target
            }

            onProgress(CloneProgress("Verifying"))
            for (apk in signed) {
                val report = ApkSigningService.verify(apk, null, minSdk)
                if (!report.verified) {
                    throw IllegalStateException(
                        "${apk.name} failed verification: " +
                            report.errors.take(3).joinToString("; ").ifEmpty { "no detail given" }
                    )
                }
            }

            CloneReport(
                outputs = signed,
                clonePackage = newPackage,
                manifest = manifestReport,
                dex = dexPlan?.let {
                    DexRewriteReport(it.dexFiles, dexChanged, dexSkipped + it.sparedUnsent)
                },
                icon = iconSummary,
                signerFingerprint = identity.meta.fingerprintSha256,
                warnings = warnings,
            )
        } finally {
            dexWorker?.close()
            unsignedDir.deleteRecursively()
            dexScratch.deleteRecursively()
        }
    }

    /**
     * Rewrites the string pools of the [pending] dex files in one APK, by way of [worker].
     *
     * Returns how many strings moved, and how many were left alone because they name a class the
     * app actually contains. Every other dex keeps its original entry, byte for byte.
     */
    private suspend fun rewriteDex(
        module: ApkModule,
        apk: File,
        pending: Map<String, Long>,
        oldPackage: String,
        newPackage: String,
        worker: DexWorker,
        classIndex: File,
        scratch: File,
        prefix: String,
    ): Pair<Int, Int> {
        var changed = 0
        var skipped = 0
        for (name in dexEntryNames(module)) {
            val size = pending[name] ?: continue
            val source = module.zipEntryMap.getInputSource(name) ?: continue
            val method = source.method
            val output = File(scratch, "$prefix-$name")
            // The worker reads the entry from the APK file itself rather than from this module:
            // nothing has touched the dex entries yet, so the two are the same bytes, and a path
            // is all that has to cross into the other process.
            val job = DexJob(
                apk = apk.absolutePath,
                entry = name,
                oldPackage = oldPackage,
                newPackage = newPackage,
                classIndex = classIndex.absolutePath,
                output = output.absolutePath,
            )
            val result = worker.rewrite(job).requireRewritten(job, size, worker.heapLimit)
            changed += result.changed
            skipped += result.skipped
            if (!result.written) continue
            module.zipEntryMap.remove(name)
            module.zipEntryMap.add(
                FileInputSource(output, name).apply { this.method = method }
            )
        }
        return changed to skipped
    }

    /** Multidex names its files `classes.dex`, `classes2.dex`, … with no gaps. */
    private fun dexEntryNames(module: ApkModule): List<String> =
        generateSequence(1) { it + 1 }
            .map { if (it == 1) "classes.dex" else "classes$it.dex" }
            .takeWhile { module.zipEntryMap.contains(it) }
            .toList()

    /**
     * Refuses a deep rename whose largest dex to rebuild could not be rebuilt in the worker's heap
     * however empty it were. A dex with nothing to move does not count, however large.
     *
     * The limit is real and not generous: a phone gives a process a few hundred megabytes even
     * when the app asks for a large heap, and the object model for a dex is many times the size of
     * the file. Since the worker has a process of its own, running out no longer costs the app —
     * but it still costs the user the minutes spent before it happened, where this costs a second.
     */
    private fun requireRoomForDeepRename(pending: Map<File, Map<String, Long>>, limit: Long) {
        for ((apk, dexes) in pending) {
            val largest = dexes.maxByOrNull { it.value } ?: continue
            if (!DexRewriter.fitsInHeap(largest.value, limit)) {
                throw IllegalStateException(
                    DexRewriter.tooLargeMessage(largest.key, apk.name, largest.value, limit)
                )
            }
        }
    }

    /**
     * What the indexing pass learned about the dex files of a build.
     *
     * A dex that is not in [pending] holds nothing a deep rename would change, and keeps its entry
     * without ever reaching the worker. Building its model would cost as much as building one that
     * does change — for a large library dex, most of what a phone gives a process — and come to
     * nothing; worse, the size check would refuse the whole rename on its account.
     */
    private class DexPlan(
        val classIndex: DexRewriter.ClassIndex,
        /** Every dex in the app, sent or not. */
        val dexFiles: Int,
        /** Per APK, the dex entries with a string to move, and the uncompressed size of each. */
        val pending: Map<File, Map<String, Long>>,
        /** Class names among the strings of dex files that were not sent, spared all the same. */
        val sparedUnsent: Int,
    )

    private class ScannedDex(val size: Long, val packageStrings: Set<String>)

    private fun planDexRewrite(apks: List<File>, oldPackage: String): DexPlan {
        val classNames = mutableSetOf<String>()
        // Only the strings under the old package are kept, never a dex itself: they come to a few
        // hundred at most, where the dex is megabytes. Whether one moves cannot be told until
        // every dex is indexed, since the class it names may be defined in any of them.
        val scanned = apks.associateWith { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && DexRewriter.isDexEntry(it.name) }
                    .associate { entry ->
                        // One dex at a time, and only its bytes: the string pool is read directly
                        // rather than parsing the file into objects.
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val scan = DexRewriter.scan(bytes, oldPackage)
                        classNames += scan.classNames
                        entry.name to ScannedDex(entry.size.coerceAtLeast(0L), scan.packageStrings)
                    }
            }
        }
        val index = DexRewriter.ClassIndex(classNames)
        var spared = 0
        val pending = scanned.mapValues { (_, dexes) ->
            dexes.filter { (_, dex) ->
                val moves = DexRewriter.willMove(dex.packageStrings, index) > 0
                if (!moves) spared += dex.packageStrings.size
                moves
            }.mapValues { (_, dex) -> dex.size }
        }.filterValues { it.isNotEmpty() }
        return DexPlan(index, scanned.values.sumOf { it.size }, pending, spared)
    }

    /**
     * Looks for the things that make a clone misbehave in ways this app cannot fix, so the user
     * hears about them before installing rather than after the clone crashes.
     */
    private fun inspect(manifest: AndroidManifestBlock, request: CloneRequest): List<String> {
        val warnings = mutableListOf<String>()
        val elements = manifest.recursive(ResXmlElement::class.java).asSequence().toList()

        fun nameOf(element: ResXmlElement): String? =
            element.searchAttributeByResourceId(android.R.attr.name)
                ?.takeIf { it.valueType == ValueType.STRING }
                ?.valueAsString

        fun named(tag: String, name: String) =
            elements.any { it.name == tag && nameOf(it) == name }

        if (named("meta-data", "com.google.android.gms.version")) {
            warnings += "This app uses Google Play services. Maps, Sign-In, Firebase messaging " +
                "and Play Integrity are all keyed to the original package name and signing key, " +
                "so those features will not work in the clone."
        }

        val hasFileProvider = elements.any { element ->
            element.name == "provider" && nameOf(element).orEmpty().endsWith("FileProvider")
        }
        if (hasFileProvider && !request.deepRename) {
            warnings += "The app publishes a FileProvider. Its authority has been renamed, but " +
                "code compiled against the old one has not. Turn on deep rename if sharing or " +
                "opening files from the clone fails."
        }

        if (manifest.documentElement
                ?.searchAttributeByResourceId(android.R.attr.isolatedSplits) != null
        ) {
            warnings += "The app uses isolated splits, which this tool does not rewrite. If the " +
                "clone starts but cannot load a feature, that is why."
        }

        return warnings
    }

    private companion object {
        const val DEFAULT_MIN_SDK = 24
        const val CLASS_INDEX = "class-index.txt"
    }
}

/** Rules for the package name a clone is given. */
object PackageNames {

    private val SEGMENT = Regex("[a-zA-Z][a-zA-Z0-9_]*")

    /**
     * The package manager's own rule: at least two dot-separated segments, each starting with a
     * letter. Underscores are allowed; hyphens are not.
     */
    fun isValid(name: String): Boolean {
        val parts = name.split('.')
        return parts.size >= 2 && parts.all { SEGMENT.matches(it) }
    }

    /**
     * Suggests a name for clone number [index] of [source].
     *
     * The suffix goes on the end rather than the front so the clone sorts next to its original in
     * anything that lists packages alphabetically.
     */
    fun suggest(source: String, index: Int): String {
        val base = source.removeSuffix(".clone$index")
        return "$base.clone$index"
    }

    /** The first index not already taken by an existing clone of the same source. */
    fun nextIndex(source: String, taken: Collection<CloneRecord>): Int {
        val used = taken.filter { it.source.packageName == source }.map { it.cloneIndex }.toSet()
        return generateSequence(2) { it + 1 }.first { it !in used }
    }
}
