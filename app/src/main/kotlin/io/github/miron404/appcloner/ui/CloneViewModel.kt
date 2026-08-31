package io.github.miron404.appcloner.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.miron404.appcloner.clone.ApkSources
import io.github.miron404.appcloner.clone.CloneJobService
import io.github.miron404.appcloner.clone.CloneJobs
import io.github.miron404.appcloner.clone.CloneProgress
import io.github.miron404.appcloner.clone.CloneRecord
import io.github.miron404.appcloner.clone.CloneReport
import io.github.miron404.appcloner.clone.CloneRequest
import io.github.miron404.appcloner.clone.CloneStatus
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

data class CloneUiState(
    val clones: List<CloneStatus> = emptyList(),
    val identities: List<IdentityMeta> = emptyList(),
    val installedApps: List<InstalledApp> = emptyList(),
    val showSystemApps: Boolean = false,
    val loadingApps: Boolean = false,
    /** The app a new clone is being configured from. */
    val source: SourceApks? = null,
    val draft: CloneRequest? = null,
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

    fun clearResult() = _state.update {
        it.copy(source = null, draft = null, report = null, built = null, progress = null)
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
        adopt(ApkSources.fromInstalled(app))
    }

    fun chooseFiles(uris: List<Uri>) = run("Copying the APK") {
        val workDir = File(container.workRoot, UUID.randomUUID().toString())
        adopt(ApkSources.stage(getApplication(), uris, workDir))
    }

    /** Sets the source and fills the form with defaults derived from it. */
    private fun adopt(source: SourceApks) {
        val index = PackageNames.nextIndex(source.info.packageName, registry.list())
        val identity = vault.list().firstOrNull()
        _state.update {
            it.copy(
                source = source,
                report = null,
                built = null,
                draft = CloneRequest(
                    label = "${source.info.label} $index",
                    packageName = PackageNames.suggest(source.info.packageName, index),
                    identityId = identity?.id.orEmpty(),
                    iconMode = IconMode.BADGE,
                    badgeText = index.toString(),
                    deepRename = false,
                    renameIntentActions = false,
                    cloneIndex = index,
                ),
            )
        }
    }

    fun editDraft(edit: (CloneRequest) -> CloneRequest) = _state.update { state ->
        state.copy(draft = state.draft?.let(edit))
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
        startBuild(source, draft, existing = null)
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
