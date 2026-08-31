package io.github.miron404.appcloner

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.miron404.appcloner.ui.AppClonerTheme
import io.github.miron404.appcloner.ui.AppNavHost
import io.github.miron404.appcloner.ui.CloneViewModel
import io.github.miron404.appcloner.ui.LockScreen
import io.github.miron404.appcloner.ui.VaultViewModel

class MainActivity : ComponentActivity() {

    private val vaultModel: VaultViewModel by viewModels()
    private val cloneModel: CloneViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        acceptIncomingApk(intent)
        requestNotificationPermission()

        setContent {
            AppClonerTheme {
                val state by vaultModel.state.collectAsStateWithLifecycle()

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> {
                                vaultModel.onForegrounded()
                                // An app updated or a clone installed while we were away.
                                cloneModel.refresh()
                            }

                            Lifecycle.Event.ON_STOP -> vaultModel.onBackgrounded()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                // The lock is an opaque overlay rather than a replacement, so a trip through the
                // system file picker does not tear down the screen the user was working on.
                Box(Modifier.fillMaxSize()) {
                    AppNavHost(vaultModel = vaultModel, cloneModel = cloneModel)
                    if (state.lockOnLaunch && !state.unlocked) {
                        LockScreen(state = state, onUnlock = vaultModel::unlockApp)
                    }
                }
            }
        }
    }

    /** The activity is `singleTask`, so a second APK arrives here rather than through onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIncomingApk(intent)
    }

    override fun onStart() {
        super.onStart()
        // Registered here rather than in onResume: the lock is decided on ON_START and may raise a
        // prompt immediately, which needs a host activity already in place.
        container.authenticator.attach(this)
    }

    override fun onStop() {
        container.authenticator.detach(this)
        super.onStop()
    }

    /**
     * Asked for once, and not insisted on. It only decides whether the build's progress
     * notification is visible; the foreground service, and the build, run either way.
     */
    private fun requestNotificationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun acceptIncomingApk(intent: Intent?) {
        incomingApkUri(intent)?.let(vaultModel::onApkReceived)
    }

    private fun incomingApkUri(intent: Intent?): Uri? {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return null
        // Only a content:// URI carries a permission grant from the sender. Anything else is
        // either unreadable to us anyway or an attempt to point this app at its own storage.
        return uri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
    }
}
