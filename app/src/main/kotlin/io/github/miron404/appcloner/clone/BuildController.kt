package io.github.miron404.appcloner.clone

import android.content.Context
import io.github.miron404.appcloner.core.AppSettings
import io.github.miron404.appcloner.core.AuthCancelledException
import io.github.miron404.appcloner.core.IdentityMeta
import io.github.miron404.appcloner.core.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * The build that is running now, described well enough to draw a screen for it.
 *
 * It carries the source and the request rather than ids, because the screen that shows a build has
 * to be reachable from anywhere — including after the view model that started it has been thrown
 * away — and there is nowhere else left to read them from.
 */
class ActiveBuild(
    val recordId: String,
    val request: CloneRequest,
    val source: SourceApks,
    /** The clone being rebuilt, or null when this is a new one. */
    val existing: CloneRecord?,
)

/** A finished build, kept until someone has looked at it. */
class FinishedBuild(val record: CloneRecord, val report: CloneReport)

/**
 * Owns the running build.
 *
 * This lives in the application container, not in a view model, and that is the whole point: a
 * build outlives the screen that started it. Repacking and signing a large app takes long enough
 * that the user will leave, and when they come back the activity — and every view model with it —
 * may be gone while the work carries on in the application scope. Anything the build screen needs
 * to redraw itself therefore has to be here, where it survives.
 *
 * A foreground service runs alongside for as long as a build does, to give the system a reason to
 * keep the process; it reads [progress] and does no work of its own.
 */
class BuildController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val vault: Vault,
    private val registry: CloneRegistry,
    private val pipeline: ClonePipeline,
    private val settings: AppSettings,
    private val outputRoot: File,
) {
    private val _active = MutableStateFlow<ActiveBuild?>(null)
    val active: StateFlow<ActiveBuild?> = _active.asStateFlow()

    private val _progress = MutableStateFlow<CloneProgress?>(null)
    val progress: StateFlow<CloneProgress?> = _progress.asStateFlow()

    private val _finished = MutableStateFlow<FinishedBuild?>(null)
    val finished: StateFlow<FinishedBuild?> = _finished.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private var job: Job? = null

    val running: Boolean get() = _active.value != null

    /** True when the build started. False means one was already running and was left alone. */
    fun start(
        source: SourceApks,
        request: CloneRequest,
        identity: IdentityMeta,
        existing: CloneRecord?,
    ): Boolean {
        if (running) return false
        val build = ActiveBuild(
            recordId = existing?.id ?: UUID.randomUUID().toString(),
            request = request,
            source = source,
            existing = existing,
        )
        _active.value = build
        _progress.value = CloneProgress("Starting")
        _finished.value = null
        _failure.value = null
        CloneJobService.start(context)
        job = scope.launch { execute(build, identity) }
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Called once whoever needed to see the result has seen it. */
    fun consumeFinished() {
        _finished.value = null
    }

    fun consumeFailure() {
        _failure.value = null
    }

    private suspend fun execute(build: ActiveBuild, identity: IdentityMeta) {
        val outDir = File(outputRoot, build.recordId)
        try {
            outDir.deleteRecursively()
            val report = vault.unlock(identity).use { unlocked ->
                pipeline.build(
                    source = build.source,
                    request = build.request,
                    identity = unlocked,
                    schemes = settings.defaultSchemes,
                    outDir = outDir,
                ) { progress -> _progress.value = progress }
            }

            val record = CloneRecord(
                id = build.recordId,
                source = build.source.info,
                clonePackage = build.request.packageName,
                cloneLabel = build.request.label,
                identityId = identity.id,
                identityLabel = identity.label,
                iconMode = build.request.iconMode,
                badgeText = build.request.badgeText,
                badgeCorner = build.request.badgeCorner,
                deepRename = build.request.deepRename,
                renameIntentActions = build.request.renameIntentActions,
                cloneIndex = build.request.cloneIndex,
                createdAt = build.existing?.createdAt ?: System.currentTimeMillis(),
                builtAt = System.currentTimeMillis(),
                builtFromVersionCode = build.source.info.versionCode,
                builtFromVersionName = build.source.info.versionName,
            )
            registry.put(record)
            _finished.value = FinishedBuild(record, report)
        } catch (cancellation: CancellationException) {
            // Cancelling is not a failure and must not be reported as one, but a half-written set
            // of APKs is no use to anybody.
            outDir.deleteRecursively()
            throw cancellation
        } catch (_: AuthCancelledException) {
            outDir.deleteRecursively()
            _failure.value = "Authentication cancelled"
        } catch (throwable: Throwable) {
            outDir.deleteRecursively()
            _failure.value = throwable.message ?: throwable.javaClass.simpleName
        } finally {
            build.source.staging?.parentFile?.deleteRecursively()
            _active.value = null
            _progress.value = null
            job = null
            CloneJobService.stop(context)
        }
    }
}
