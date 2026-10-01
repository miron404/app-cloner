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
class ClonePipeline(private val icons: IconRenderer) {

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
        var dexFiles = 0

        val minSdk = runCatching { ApkSigningService.inspect(source.base).minSdkVersion }
            .getOrDefault(DEFAULT_MIN_SDK)

        // Every dex in the app is indexed before any of them is edited: a string naming a class
        // that lives in a different dex still has to be left alone.
        val classIndex = if (request.deepRename) {
            // Asked before the indexing pass, which reads every dex in the app: there is no point
            // spending that on a rewrite the heap cannot hold. The same question is asked again at
            // the moment of each rewrite, when the answer is exact.
            requireRoomForDeepRename(source.all)
            onProgress(CloneProgress("Indexing classes", "so reflection keeps working"))
            buildClassIndex(source.all, oldPackage)
        } else {
            null
        }

        // Rendered from the source APKs before anything is rewritten, because that is where the
        // icon's own resources still are.
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

        val outputs = mutableListOf<File>()
        try {
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

                    if (classIndex != null) {
                        val result = rewriteDex(
                            module = module,
                            apk = apk,
                            oldPackage = oldPackage,
                            newPackage = newPackage,
                            index = classIndex,
                            scratch = dexScratch,
                            // The module name is the same for a base and its splits, so the
                            // position in the set is what keeps the scratch files apart.
                            prefix = index.toString(),
                        )
                        dexFiles += result.first
                        dexChanged += result.second
                        dexSkipped += result.third
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
                dex = classIndex?.let { DexRewriteReport(dexFiles, dexChanged, dexSkipped) },
                icon = iconSummary,
                signerFingerprint = identity.meta.fingerprintSha256,
                warnings = warnings,
            )
        } finally {
            unsignedDir.deleteRecursively()
            dexScratch.deleteRecursively()
        }
    }

    /**
     * Rewrites the string pools of every dex in one APK.
     *
     * Returns how many dex files were seen, how many strings moved, and how many were left alone
     * because they name a class the app actually contains.
     */
    private fun rewriteDex(
        module: ApkModule,
        apk: File,
        oldPackage: String,
        newPackage: String,
        index: DexRewriter.ClassIndex,
        scratch: File,
        prefix: String,
    ): Triple<Int, Int, Int> {
        var files = 0
        var changed = 0
        var skipped = 0
        val sizes = dexSizes(apk)
        for (name in dexEntryNames(module)) {
            val source = module.zipEntryMap.getInputSource(name) ?: continue
            files++
            val method = source.method
            val output = File(scratch, "$prefix-$name")
            // Now is when the answer is exact: the resource table of this APK and the class index
            // are already held, so what is free here is what the rewrite really has to work in.
            val size = sizes[name] ?: 0L
            val available = DexRewriter.availableHeap()
            if (!DexRewriter.fitsInHeap(size, available)) {
                throw IllegalStateException(
                    DexRewriter.tooLargeMessage(name, apk.name, size, available)
                )
            }
            val result = try {
                source.openStream().use { stream ->
                    DexRewriter.rewrite(stream, oldPackage, newPackage, index, output)
                }
            } catch (error: OutOfMemoryError) {
                // The estimate above was optimistic. Say so and stop, rather than leaving the
                // process to die on whichever thread allocates next — which may well be the one
                // drawing the screen, and then the whole build is lost with it.
                throw IllegalStateException(
                    DexRewriter.tooLargeMessage(name, apk.name, size, available),
                    error,
                )
            }
            changed += result.changed
            skipped += result.skipped
            if (!result.written) continue
            module.zipEntryMap.remove(name)
            module.zipEntryMap.add(
                FileInputSource(output, name).apply { this.method = method }
            )
        }
        return Triple(files, changed, skipped)
    }

    /** Multidex names its files `classes.dex`, `classes2.dex`, … with no gaps. */
    private fun dexEntryNames(module: ApkModule): List<String> =
        generateSequence(1) { it + 1 }
            .map { if (it == 1) "classes.dex" else "classes$it.dex" }
            .takeWhile { module.zipEntryMap.contains(it) }
            .toList()

    /** Uncompressed sizes of the dex entries in [apk], read from the archive's central directory. */
    private fun dexSizes(apk: File): Map<String, Long> =
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && DexRewriter.isDexEntry(it.name) }
                .associate { it.name to it.size.coerceAtLeast(0L) }
        }

    /**
     * Refuses a deep rename whose largest dex cannot be rebuilt in the heap this device allows.
     *
     * The limit is real and not generous: a phone gives one app a few hundred megabytes even when
     * it asks for a large heap, and the object model for a dex is many times the size of the file.
     * Finding that out by running out of memory costs the user the whole build and, as often as
     * not, the process; finding it out here costs a second.
     */
    private fun requireRoomForDeepRename(apks: List<File>) {
        val available = DexRewriter.availableHeap()
        for (apk in apks) {
            val largest = dexSizes(apk).maxByOrNull { it.value } ?: continue
            if (!DexRewriter.fitsInHeap(largest.value, available)) {
                throw IllegalStateException(
                    DexRewriter.tooLargeMessage(largest.key, apk.name, largest.value, available)
                )
            }
        }
    }

    private fun buildClassIndex(apks: List<File>, oldPackage: String): DexRewriter.ClassIndex {
        val names = mutableSetOf<String>()
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && DexRewriter.isDexEntry(it.name) }
                    .forEach { entry ->
                        // One dex at a time, and only its bytes: indexing reads the string pool
                        // directly rather than parsing the file into objects.
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        names += DexRewriter.indexClasses(bytes, oldPackage)
                    }
            }
        }
        return DexRewriter.ClassIndex(names)
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
