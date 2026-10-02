package io.github.miron404.appcloner

import android.app.Application
import android.content.Context
import io.github.miron404.appcloner.clone.AndroidIconRenderer
import io.github.miron404.appcloner.clone.BuildController
import io.github.miron404.appcloner.clone.CloneRegistry
import io.github.miron404.appcloner.clone.ClonePipeline
import io.github.miron404.appcloner.clone.Installer
import io.github.miron404.appcloner.clone.RemoteDexWorker
import io.github.miron404.appcloner.core.AppSettings
import io.github.miron404.appcloner.core.Bc
import io.github.miron404.appcloner.core.MasterKey
import io.github.miron404.appcloner.core.SystemAuthenticator
import io.github.miron404.appcloner.core.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Hand-rolled service locator; the graph is small enough that a DI framework would be noise. */
class AppContainer(context: Context) {
    val settings = AppSettings(context)
    val authenticator = SystemAuthenticator()
    val masterKey = MasterKey(authenticator)
    val vault = Vault(File(context.filesDir, "vault"), masterKey, settings)
    val registry = CloneRegistry(File(context.filesDir, "clones.json"))
    val pipeline = ClonePipeline(
        icons = AndroidIconRenderer(context),
        openDexWorker = { RemoteDexWorker.open(context) },
    )
    val installer = Installer(context)

    /** Where finished clones are kept so they can still be installed or exported later. */
    val outputRoot = File(context.filesDir, "clones")

    /** Scratch space for staged sources and half-written APKs. Safe to wipe at any time. */
    val workRoot = File(context.cacheDir, "work")

    /**
     * Builds run here rather than in a view model scope, so swiping the app away mid-build does
     * not throw away twenty minutes of repacking and signing. The foreground service keeps the
     * process around for as long as this scope has work in it.
     */
    val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The running build, kept here rather than in a view model so that leaving the screen — or the
     * app — does not lose the way back to it.
     */
    val builds = BuildController(
        context = context.applicationContext,
        scope = jobScope,
        vault = vault,
        registry = registry,
        pipeline = pipeline,
        settings = settings,
        outputRoot = outputRoot,
    )
}

class AppClonerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Android creates this class in every process the app has, and the dex worker runs in one
        // of its own. Nothing below belongs there, and two of the steps would do real damage: the
        // work directory holds the staged APKs that process is about to read, and a vault repair
        // racing the main process's could finish a rekey twice. The container is never created
        // there, and nothing in that process asks for it.
        if (Application.getProcessName() != packageName) return
        // Android's built-in "BC" provider is a stripped subset; swap in the full build before any
        // crypto runs so PKCS#12 writing and certificate building resolve to it.
        Bc.install()
        container = AppContainer(this)
        // Finish any rekey a previous run was interrupted mid-way through.
        runCatching { container.vault.repair() }
        // Nothing in the work directory outlives the run that created it.
        runCatching { container.workRoot.deleteRecursively() }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as AppClonerApplication).container
