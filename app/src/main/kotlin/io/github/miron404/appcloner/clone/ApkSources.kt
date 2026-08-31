package io.github.miron404.appcloner.clone

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.reandroid.apk.ApkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** An app on the device, and the APK files it is actually made of. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val baseApk: String,
    val splitApks: List<String>,
) {
    val hasSplits: Boolean get() = splitApks.isNotEmpty()
}

/**
 * Finds the APKs a clone will be built from.
 *
 * An installed app is read where it already lies. Its APKs are world readable, so copying several
 * hundred megabytes into our own cache just to read them again would cost time and space for
 * nothing; only files arriving through the document picker have to be staged, because the read
 * permission on a content URI does not outlive the task.
 */
object ApkSources {

    /** Everything installed that could plausibly be cloned, our own package excluded. */
    fun installed(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val self = context.packageName
        return pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            .asSequence()
            .filter { it.packageName != self }
            .mapNotNull { info ->
                val app = info.applicationInfo ?: return@mapNotNull null
                val base = app.sourceDir ?: return@mapNotNull null
                InstalledApp(
                    packageName = info.packageName,
                    label = app.loadLabel(pm).toString(),
                    versionName = info.versionName.orEmpty(),
                    versionCode = info.longVersionCode,
                    isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    baseApk = base,
                    // A base APK is listed in splitSourceDirs on some releases; drop it so the
                    // caller never sees the same file twice.
                    splitApks = app.splitSourceDirs.orEmpty().filter { it != base },
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun find(context: Context, packageName: String): InstalledApp? =
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            val app = info.applicationInfo ?: return null
            InstalledApp(
                packageName = info.packageName,
                label = app.loadLabel(pm).toString(),
                versionName = info.versionName.orEmpty(),
                versionCode = info.longVersionCode,
                isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                baseApk = app.sourceDir,
                splitApks = app.splitSourceDirs.orEmpty().filter { it != app.sourceDir },
            )
        }.getOrNull()

    /** The version of an installed package, or null when it is not there. */
    fun versionOf(context: Context, packageName: String): Pair<Long, String>? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        info.longVersionCode to info.versionName.orEmpty()
    }.getOrNull()

    fun fromInstalled(app: InstalledApp): SourceApks = SourceApks(
        info = SourceInfo(
            packageName = app.packageName,
            label = app.label,
            versionName = app.versionName,
            versionCode = app.versionCode,
            kind = SourceKind.INSTALLED,
            splitNames = app.splitApks.map { File(it).name },
        ),
        base = File(app.baseApk),
        splits = app.splitApks.map(::File),
        staging = null,
    )

    /**
     * Copies APKs handed over through the document picker into [workDir].
     *
     * Three shapes arrive here: one plain APK, several APKs that together are a split app, and a
     * single container (`.apks`, `.xapk`, `.apkm`) that holds those splits in a zip. All three end
     * up as the same thing — one base and zero or more splits on our own disk.
     */
    suspend fun stage(context: Context, uris: List<Uri>, workDir: File): SourceApks =
        withContext(Dispatchers.IO) {
            require(uris.isNotEmpty()) { "No file selected" }
            val staging = File(workDir, "source").apply { mkdirs() }

            val copied = uris.mapIndexed { index, uri ->
                val target = File(staging, "input-$index.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException("Could not read the selected file")
                target
            }

            val apks = if (copied.size == 1 && isContainer(copied.single())) {
                unpackContainer(copied.single(), staging).also { copied.single().delete() }
            } else {
                copied
            }
            require(apks.isNotEmpty()) { "No APK found in the selected file" }

            val base = apks.firstOrNull { !isSplit(it) }
                ?: throw IllegalArgumentException(
                    "Every selected file is a split. Add the base APK as well."
                )
            val splits = apks.filter { it != base }
            SourceApks(describe(context, base, splits), base, splits, staging)
        }

    /** Reads an APK's identity without installing or fully parsing it. */
    fun describe(context: Context, base: File, splits: List<File>): SourceInfo {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(base.absolutePath, 0)
            ?: throw IllegalArgumentException("That file is not an APK")
        val label = info.applicationInfo?.let { app ->
            app.sourceDir = base.absolutePath
            app.publicSourceDir = base.absolutePath
            runCatching { app.loadLabel(pm).toString() }.getOrNull()
        }
        return SourceInfo(
            packageName = info.packageName,
            label = label?.takeIf { it.isNotBlank() && it != info.packageName } ?: info.packageName,
            versionName = info.versionName.orEmpty(),
            versionCode = info.longVersionCode,
            kind = SourceKind.FILE,
            splitNames = splits.map { it.name },
        )
    }

    /** True when the APK declares a `split` name, i.e. it is a piece rather than the whole app. */
    fun isSplit(apk: File): Boolean = runCatching {
        ApkModule.loadApkFile(apk).use { module -> module.androidManifest?.isSplit == true }
    }.getOrDefault(false)

    /** A zip of APKs rather than an APK: no manifest of its own, but `.apk` entries inside. */
    private fun isContainer(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("AndroidManifest.xml") == null &&
                zip.entries().asSequence().any { it.name.endsWith(".apk") }
        }
    }.getOrDefault(false)

    private fun unpackContainer(container: File, target: File): List<File> =
        ZipFile(container).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk") }
                .map { entry ->
                    // Flatten the name: a container may nest its splits in directories, and an
                    // entry name is attacker-controlled enough to be worth not trusting as a path.
                    val out = File(target, entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out
                }
                .toList()
        }
}
