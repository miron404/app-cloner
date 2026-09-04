package io.github.miron404.appcloner.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.miron404.appcloner.clone.ApkSources
import io.github.miron404.appcloner.clone.BadgeCorner
import io.github.miron404.appcloner.clone.CloneJobService
import io.github.miron404.appcloner.clone.CloneJobs
import io.github.miron404.appcloner.clone.CloneProgress
import io.github.miron404.appcloner.clone.CloneRecord
import io.github.miron404.appcloner.clone.CloneReport
import io.github.miron404.appcloner.clone.CloneRequest
import io.github.miron404.appcloner.clone.CloneStatus
import io.github.miron404.appcloner.clone.IconFactory
import io.github.miron404.appcloner.clone.IconLayers
import io.github.miron404.appcloner.clone.IconMode
import io.github.miron404.appcloner.clone.InstalledApp
import io.github.miron404.appcloner.clone.PackageNames
import io.github.miron404.appcloner.clone.SourceApks
import io.github.miron404.appcloner.container
import io.github.miron404.appcloner.core.AuthCancelledException
import io.github.miron404.appcloner.core.IdentityMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the configured icon will look like, both ways round.
 *
 * [themed] is the monochrome layer alone, which is all a launcher draws when Material You themed
 * icons are on — the case the badge is designed around, and the one worth seeing before building.
 */
data class IconPreview(val normal: ImageBitmap, val themed: ImageBitmap?)

/** Whether [CloneViewModel.reconfigure] could open the form straight away. */
enum class Reconfigure {
    /** The source was found and the form is filled in with the clone's existing settings. */
    READY,

    /** The source is not installed, so the user has to point at its APKs again. */
    NEEDS_SOURCE,
}

data class CloneUiState(
    val clones: List<CloneStatus> = emptyList(),
    val identities: List<IdentityMeta> = emptyList(),
    val installedApps: List<InstalledApp> = emptyList(),
    val showSystemApps: Boolean = false,
    val loadingApps: Boolean = false,
    /** The app a new clone is being configured from. */
    val source: SourceApks? = null,
    val draft: CloneRequest? = null,
    /** Set when the draft is changing an existing clone rather than describing a new one. */
    val editing: CloneRecord? = null,
    /** An edit waiting for the user to point at the source APKs again. */
    val pendingEdit: CloneRecord? = null,
    val preview: IconPreview? = null,
    val progress: CloneProgress? = null,
    val report: CloneReport? = null,
    /** Set once a build finishes, so the result screen can offer to install or export it. */
    val built: CloneRecord? = null,
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val building: Boolean get() = progress != null
}

class CloneViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container
    private val vault = container.vault
    private val registry = container.registry

    private val _state = MutableStateFlow(CloneUiState())
    val state: StateFlow<CloneUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** The source app's icon, rasterised once so moving the badge does not re-read the APK. */
    private var sourceLayers: IconLayers? = null
    private var previewJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        _state.update {
            it.copy(
                clones = registry.statuses(getApplication()),
                identities = vault.list(),
            )
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null, error = null) }

    fun clearResult() {
        previewJob?.cancel()
        sourceLayers?.recycle()
        sourceLayers = null
        _state.update {
            it.copy(
                source = null,
                draft = null,
                editing = null,
                pendingEdit = null,
                preview = null,
                report = null,
                built = null,
                progress = null,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        sourceLayers?.recycle()
        sourceLayers = null
    }

    // --- choosing what to clone ---------------------------------------------------------------

    fun loadInstalledApps() {
        if (_state.value.installedApps.isNotEmpty() || _state.value.loadingApps) return
        viewModelScope.launch {
            _state.update { it.copy(loadingApps = true) }
            val apps = withContext(Dispatchers.IO) { ApkSources.installed(getApplication()) }
            _state.update { it.copy(installedApps = apps, loadingApps = false) }
        }
    }

    fun setShowSystemApps(show: Boolean) = _state.update { it.copy(showSystemApps = show) }

    fun chooseInstalled(packageName: String) = run("Reading the app") {
        val app = ApkSources.find(getApplication(), packageName)
            ?: error("$packageName is no longer installed")
        adopt(ApkSources.fromInstalled(app), editing = _state.value.pendingEdit)
    }

    fun chooseFiles(uris: List<Uri>) = run("Copying the APK") {
        val workDir = File(container.workRoot, UUID.randomUUID().toString())
        adopt(ApkSources.stage(getApplication(), uris, workDir), editing = _state.value.pendingEdit)
    }

    /**
     * Sets the source and fills the form.
     *
     * With [editing] given the form starts from that clone's own settings, so a rebuild changes
     * only what the user touches; otherwise it starts from defaults derived from the source.
     */
    private fun adopt(source: SourceApks, editing: CloneRecord? = null) {
        if (editing != null && editing.source.packageName != source.info.packageName) {
            error(
                "That is ${source.info.packageName}, but this clone was built from " +
                    "${editing.source.packageName}"
            )
        }
        val index = PackageNames.nextIndex(source.info.packageName, registry.list())
        val identity = vault.list().firstOrNull()
        _state.update {
            it.copy(
                source = source,
                editing = editing,
                pendingEdit = null,
                report = null,
                built = null,
                draft = editing?.toRequest() ?: CloneRequest(
                    label = "${source.info.label} $index",
                    packageName = PackageNames.suggest(source.info.packageName, index),
                    identityId = identity?.id.orEmpty(),
                    iconMode = IconMode.BADGE,
                    badgeText = index.toString(),
                    badgeCorner = BadgeCorner.BOTTOM_RIGHT,
                    deepRename = false,
                    renameIntentActions = false,
                    cloneIndex = index,
                ),
            )
        }
        refreshPreview(reload = true)
    }

    fun editDraft(edit: (CloneRequest) -> CloneRequest) {
        val before = _state.value.draft
        _state.update { state -> state.copy(draft = state.draft?.let(edit)) }
        val after = _state.value.draft
        if (before != null && after != null && before.rendersDifferentlyTo(after)) {
            refreshPreview(reload = false)
        }
    }

    /** Whether two drafts would draw a different icon, which is the only reason to redraw one. */
    private fun CloneRequest.rendersDifferentlyTo(other: CloneRequest) =
        iconMode != other.iconMode ||
            badgeText != other.badgeText ||
            badgeCorner != other.badgeCorner

    /**
     * Redraws the icon preview.
     *
     * The source's own layers are rasterised once and kept, because the point of the preview is to
     * make moving the badge feel immediate; only [reload] goes back to the APK. Every write to
     * [sourceLayers] happens on the main dispatcher, which is what keeps two overlapping loads from
     * treading on each other.
     */
    private fun refreshPreview(reload: Boolean) {
        previewJob?.cancel()
        val source = _state.value.source ?: return
        previewJob = viewModelScope.launch {
            if (reload) {
                sourceLayers?.recycle()
                sourceLayers = null
                _state.update { it.copy(preview = null) }
                sourceLayers = withContext(Dispatchers.IO) {
                    val drawable =
                        IconFactory.loadIcon(getApplication(), source.base, source.splits)
                    drawable?.let { runCatching { IconFactory.toLayers(it) }.getOrNull() }
                }
            }
            val layers = sourceLayers ?: return@launch
            val draft = _state.value.draft ?: return@launch
            val preview = withContext(Dispatchers.Default) {
                val badged = if (draft.iconMode == IconMode.BADGE) {
                    IconFactory.badge(layers, draft.badgeText, draft.badgeCorner)
                } else {
                    null
                }
                val shown = badged ?: layers
                try {
                    IconPreview(
                        normal = IconFactory.flatten(shown).asImageBitmap(),
                        themed = IconFactory.flattenMonochrome(shown)?.asImageBitmap(),
                    )
                } finally {
                    badged?.recycle()
                }
            }
            _state.update { it.copy(preview = preview) }
        }
    }

    // --- building -----------------------------------------------------------------------------

    /**
     * Repacks, signs and verifies the clone.
     *
     * The work runs in the application scope rather than this view model's, so leaving the app
     * does not throw the build away; a foreground service keeps the process around meanwhile.
     */
    fun build() {
        val state = _state.value
        val source = state.source ?: return
        val draft = state.draft ?: return
        // Editing an existing clone but giving it a different package name makes a second app, not
        // a new version of the first, so it gets a record of its own and the original stays listed.
        val existing = state.editing?.takeIf { it.clonePackage == draft.packageName }
        startBuild(source, draft, existing = existing)
    }

    /**
     * Opens the form on an existing clone so its settings can be changed and it rebuilt.
     *
     * The source has to be read again either way — the built APKs are the output, not the input —
     * so a clone made from a file the app no longer holds needs that file picked again. Whichever
     * source is picked then has to be the same package: a rebuild of *this* clone is only a rebuild
     * if it comes from the app the clone was made from.
     */
    fun reconfigure(record: CloneRecord): Reconfigure {
        val app = ApkSources.find(getApplication(), record.source.packageName)
        if (app == null) {
            previewJob?.cancel()
            sourceLayers?.recycle()
            sourceLayers = null
            _state.update {
                it.copy(
                    source = null,
                    draft = null,
                    editing = null,
                    preview = null,
                    report = null,
                    built = null,
                    pendingEdit = record,
                    message = "${record.source.label} is not installed. Pick its APK to rebuild.",
                )
            }
            return Reconfigure.NEEDS_SOURCE
        }
        adopt(ApkSources.fromInstalled(app), editing = record)
        return Reconfigure.READY
    }

    /** Rebuilds an existing clone from whatever version of its source is installed now. */
    fun rebuild(record: CloneRecord) {
        val app = ApkSources.find(getApplication(), record.source.packageName)
        if (app == null) {
            _state.update { it.copy(error = "${record.source.label} is not installed any more") }
            return
        }
        startBuild(ApkSources.fromInstalled(app), record.toRequest(), existing = record)
    }

    private fun startBuild(source: SourceApks, request: CloneRequest, existing: CloneRecord?) {
        if (job?.isActive == true) {
            _state.update { it.copy(error = "A build is already running") }
            return
        }
        val identity = vault.list().firstOrNull { it.id == request.identityId }
        if (identity == null) {
            _state.update { it.copy(error = "Choose a signing identity first") }
            return
        }
        if (!PackageNames.isValid(request.packageName)) {
            _state.update { it.copy(error = "'${request.packageName}' is not a valid package name") }
            return
        }
        if (registry.list().any {
                it.clonePackage == request.packageName && it.id != existing?.id
            }
        ) {
            _state.update { it.copy(error = "Another clone already uses that package name") }
            return
        }

        val application = getApplication<Application>()
        CloneJobService.start(application)
        _state.update {
            it.copy(
                progress = CloneProgress("Starting"),
                report = null,
                built = null,
                error = null,
                message = null,
            )
        }

        job = container.jobScope.launch {
            val recordId = existing?.id ?: UUID.randomUUID().toString()
            val outDir = File(container.outputRoot, recordId)
            try {
                outDir.deleteRecursively()
                val report = vault.unlock(identity).use { unlocked ->
                    container.pipeline.build(
                        source = source,
                        request = request,
                        identity = unlocked,
                        schemes = container.settings.defaultSchemes,
                        outDir = outDir,
                    ) { progress ->
                        CloneJobs.publish(progress)
                        _state.update { it.copy(progress = progress) }
                    }
                }

                val record = CloneRecord(
                    id = recordId,
                    source = source.info,
                    clonePackage = request.packageName,
                    cloneLabel = request.label,
                    identityId = identity.id,
                    identityLabel = identity.label,
                    iconMode = request.iconMode,
                    badgeText = request.badgeText,
                    badgeCorner = request.badgeCorner,
                    deepRename = request.deepRename,
                    renameIntentActions = request.renameIntentActions,
                    cloneIndex = request.cloneIndex,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    builtAt = System.currentTimeMillis(),
                    builtFromVersionCode = source.info.versionCode,
                    builtFromVersionName = source.info.versionName,
                )
                registry.put(record)
                _state.update {
                    it.copy(
                        progress = null,
                        report = report,
                        built = record,
                        clones = registry.statuses(application),
                        message = "Built ${record.cloneLabel}",
                    )
                }
            } catch (_: AuthCancelledException) {
                outDir.deleteRecursively()
                _state.update { it.copy(progress = null, error = "Authentication cancelled") }
            } catch (throwable: Throwable) {
                outDir.deleteRecursively()
                _state.update {
                    it.copy(
                        progress = null,
                        error = throwable.message ?: throwable.javaClass.simpleName,
                    )
                }
            } finally {
                source.staging?.parentFile?.deleteRecursively()
                CloneJobService.stop(application)
            }
        }
    }

    fun cancelBuild() {
        job?.cancel()
        job = null
        CloneJobService.stop(getApplication())
        _state.update { it.copy(progress = null, message = "Build cancelled") }
    }

    // --- what to do with a finished clone ------------------------------------------------------

    fun outputsOf(record: CloneRecord): List<File> =
        File(container.outputRoot, record.id).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk") }
            ?.sortedBy { it.name }
            .orEmpty()

    fun install(record: CloneRecord) = run("Installing") {
        val apks = outputsOf(record)
        if (apks.isEmpty()) error("Nothing built for this clone yet — rebuild it first")
        container.installer.install(apks, record.clonePackage)
        _state.update {
            it.copy(
                clones = registry.statuses(getApplication()),
                message = "Installed ${record.cloneLabel}",
            )
        }
    }

    /**
     * Writes the clone out for the user to keep.
     *
     * A single APK is written as it is. A split app cannot be: the base and its splits only mean
     * anything together, so they go into one zip that keeps their names.
     */
    fun export(record: CloneRecord, target: Uri) = run("Exporting") {
        val apks = outputsOf(record)
        if (apks.isEmpty()) error("Nothing built for this clone yet — rebuild it first")
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(target, "wt")
                ?.use { output ->
                    if (apks.size == 1) {
                        apks.single().inputStream().use { it.copyTo(output) }
                    } else {
                        ZipOutputStream(output).use { zip ->
                            for (apk in apks) {
                                zip.putNextEntry(ZipEntry(apk.name))
                                apk.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
                ?: error("Could not open the destination for writing")
        }
        _state.update { it.copy(message = "Exported ${record.cloneLabel}") }
    }

    /** Suggested file name for [export]; a split app needs a container, not an APK. */
    fun exportName(record: CloneRecord): String =
        if (outputsOf(record).size > 1) {
            "${record.clonePackage}.apks"
        } else {
            "${record.clonePackage}.apk"
        }

    fun forget(record: CloneRecord) = run("Removing") {
        File(container.outputRoot, record.id).deleteRecursively()
        registry.remove(record.id)
        _state.update {
            it.copy(
                clones = registry.statuses(getApplication()),
                message = "Forgot ${record.cloneLabel}. The installed clone was left alone.",
            )
        }
    }

    private fun run(busy: String, block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = busy, error = null, message = null) }
        try {
            block()
        } catch (_: AuthCancelledException) {
            _state.update { it.copy(error = "Authentication cancelled") }
        } catch (throwable: Throwable) {
            _state.update { it.copy(error = throwable.message ?: throwable.javaClass.simpleName) }
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }
}
