package io.github.miron404.appcloner.clone

import kotlinx.serialization.Serializable
import java.io.File

/** How the clone's launcher icon is derived from the source app's. */
@Serializable
enum class IconMode(val label: String, val description: String) {
    KEEP(
        "Keep the original",
        "The clone looks exactly like the app it came from. Nothing can go wrong with the icon; " +
            "the two are told apart by name alone.",
    ),
    BADGE(
        "Badge the icon",
        "Draws a numbered badge over the icon and cuts the same badge out of the themed " +
            "(monochrome) layer, so it stays visible with Material You themed icons on.",
    ),
}

@Serializable
enum class SourceKind { INSTALLED, FILE }

/** What the source app says about itself, before anything is rewritten. */
@Serializable
data class SourceInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val kind: SourceKind,
    val splitNames: List<String> = emptyList(),
) {
    val hasSplits: Boolean get() = splitNames.isNotEmpty()
}

/**
 * A source app resolved down to the APK files it is made of.
 *
 * An installed app is read straight out of `/data/app`, so nothing is copied; files picked through
 * the document picker are staged into [staging], which the caller deletes afterwards.
 */
class SourceApks(
    val info: SourceInfo,
    val base: File,
    val splits: List<File>,
    val staging: File?,
) {
    val all: List<File> get() = listOf(base) + splits

    val totalBytes: Long get() = all.sumOf { it.length() }
}

/** Everything the user chose before the build starts. */
data class CloneRequest(
    val label: String,
    val packageName: String,
    val identityId: String,
    val iconMode: IconMode,
    val badgeText: String,
    val deepRename: Boolean,
    val renameIntentActions: Boolean,
    val cloneIndex: Int,
)

/**
 * A clone this app has built, remembered so its source can be watched for updates and so the same
 * choices can be replayed against a newer version of that source.
 */
@Serializable
data class CloneRecord(
    val id: String,
    val source: SourceInfo,
    val clonePackage: String,
    val cloneLabel: String,
    val identityId: String,
    val identityLabel: String,
    val iconMode: IconMode,
    val badgeText: String,
    val deepRename: Boolean,
    val renameIntentActions: Boolean,
    val cloneIndex: Int,
    val createdAt: Long,
    val builtAt: Long,
    /** Version of the source this clone was last built from. The basis for "update available". */
    val builtFromVersionCode: Long,
    val builtFromVersionName: String,
) {
    fun toRequest() = CloneRequest(
        label = cloneLabel,
        packageName = clonePackage,
        identityId = identityId,
        iconMode = iconMode,
        badgeText = badgeText,
        deepRename = deepRename,
        renameIntentActions = renameIntentActions,
        cloneIndex = cloneIndex,
    )
}

/** A [CloneRecord] joined with what the package manager says about it right now. */
data class CloneStatus(
    val record: CloneRecord,
    /** Version of the source currently installed, or null when the source is gone. */
    val sourceVersionCode: Long?,
    val sourceVersionName: String?,
    /** Version of the clone currently installed, or null when it is not installed. */
    val installedVersionCode: Long?,
) {
    val sourcePresent: Boolean get() = sourceVersionCode != null
    val cloneInstalled: Boolean get() = installedVersionCode != null

    /** The source has moved on since this clone was last built from it. */
    val updateAvailable: Boolean
        get() = sourceVersionCode != null && sourceVersionCode > record.builtFromVersionCode

    /** The clone on the device is older than the last build we produced. */
    val rebuildPending: Boolean
        get() = installedVersionCode != null && installedVersionCode < record.builtFromVersionCode
}

data class ManifestRewriteReport(
    val expandedClassNames: Int = 0,
    val renamedAuthorities: Int = 0,
    val renamedPermissions: Int = 0,
    val renamedProcesses: Int = 0,
    val renamedActions: Int = 0,
    val strippedSharedUserId: String? = null,
    val launcherActivities: Int = 0,
) {
    operator fun plus(other: ManifestRewriteReport) = ManifestRewriteReport(
        expandedClassNames = expandedClassNames + other.expandedClassNames,
        renamedAuthorities = renamedAuthorities + other.renamedAuthorities,
        renamedPermissions = renamedPermissions + other.renamedPermissions,
        renamedProcesses = renamedProcesses + other.renamedProcesses,
        renamedActions = renamedActions + other.renamedActions,
        strippedSharedUserId = strippedSharedUserId ?: other.strippedSharedUserId,
        launcherActivities = launcherActivities + other.launcherActivities,
    )
}

data class DexRewriteReport(
    val dexFiles: Int,
    val rewrittenStrings: Int,
    val skippedClassNames: Int,
)

/** What a finished build produced, and what it had to compromise on along the way. */
class CloneReport(
    val outputs: List<File>,
    val clonePackage: String,
    val manifest: ManifestRewriteReport,
    val dex: DexRewriteReport?,
    val icon: String,
    val signerFingerprint: String,
    val warnings: List<String>,
)

/** Progress of a running build, surfaced to the UI and to the foreground notification. */
data class CloneProgress(
    val step: String,
    val detail: String = "",
    /** 0..1, or null while the step's length is unknown. */
    val fraction: Float? = null,
)
