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

/**
 * The badge's geometry, as fractions of the icon canvas.
 *
 * Kept here, away from anything that needs a `Canvas`, so the arithmetic below can be checked by a
 * plain unit test rather than only by looking at a phone.
 */
object BadgeGeometry {
    /** How far the badge's centre sits from the icon's, along each axis. */
    const val OFFSET = 0.155f
    const val RADIUS = 0.100f

    /** A transparent ring around the badge, so it separates from whatever it lands on. */
    const val GAP = 0.024f

    /**
     * What a circular adaptive mask keeps: an icon is authored at 108dp and masked to its central
     * 72dp, so the mask is a disc of radius 36/108 around the centre.
     */
    const val MASK_RADIUS = 1f / 3f
}

/**
 * Which corner of the icon the badge is drawn in.
 *
 * The offsets put the badge's centre [BadgeGeometry.OFFSET]·√2 = 0.219 of the canvas away from the
 * icon's, which leaves its 0.100 radius inside the 0.333 mask with 0.014 to spare — about 6px of
 * the 432px layer. That margin is what makes all four corners equally safe: a launcher with a
 * circular mask, which is the strictest one in use, still shows the badge whole.
 */
@Serializable
enum class BadgeCorner(val label: String, val x: Float, val y: Float) {
    TOP_LEFT("Top left", 0.5f - BadgeGeometry.OFFSET, 0.5f - BadgeGeometry.OFFSET),
    TOP_RIGHT("Top right", 0.5f + BadgeGeometry.OFFSET, 0.5f - BadgeGeometry.OFFSET),
    BOTTOM_LEFT("Bottom left", 0.5f - BadgeGeometry.OFFSET, 0.5f + BadgeGeometry.OFFSET),
    BOTTOM_RIGHT("Bottom right", 0.5f + BadgeGeometry.OFFSET, 0.5f + BadgeGeometry.OFFSET),
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

/** The images a generated launcher icon is made of, already encoded as PNG. */
class IconImages(
    val foreground: ByteArray,
    val background: ByteArray,
    /** Absent when the source icon had no themed layer to badge. */
    val monochrome: ByteArray?,
    /** Flattened fallback for anything that asks for a raster rather than an adaptive icon. */
    val flattened: ByteArray,
)

/**
 * Renders the clone's launcher icon.
 *
 * The pipeline is expressed in terms of this rather than of bitmaps, so it does not need a
 * `Context` and can be run end to end on a plain JVM. The real implementation is
 * [io.github.miron404.appcloner.clone.AndroidIconRenderer].
 */
fun interface IconRenderer {
    /** Null when the source app's icon could not be rendered, which is not fatal to a build. */
    fun render(source: SourceApks, badge: String, corner: BadgeCorner): IconImages?
}

/** Everything the user chose before the build starts. */
data class CloneRequest(
    val label: String,
    val packageName: String,
    val identityId: String,
    val iconMode: IconMode,
    val badgeText: String,
    val badgeCorner: BadgeCorner,
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
    /** Defaulted, because clones recorded before the corner could be chosen have no field for it. */
    val badgeCorner: BadgeCorner = BadgeCorner.BOTTOM_RIGHT,
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
        badgeCorner = badgeCorner,
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
