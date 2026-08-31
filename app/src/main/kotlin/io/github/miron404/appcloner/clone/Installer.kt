package io.github.miron404.appcloner.clone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class InstallFailure(message: String) : Exception(message)

/**
 * Installs a finished clone through a package installer session.
 *
 * A session is used rather than an `ACTION_VIEW` intent because a split app has to arrive as one
 * atomic set: the base and every config split are written into the same session and committed
 * together, which is the only way the package manager will accept them. Installing over an
 * existing clone with the same signing key is an update, so its data survives.
 */
class Installer(private val context: Context) {

    suspend fun install(apks: List<File>, packageName: String): Unit = withContext(Dispatchers.IO) {
        require(apks.isNotEmpty()) { "Nothing to install" }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setSize(apks.sumOf { it.length() })
        }

        val sessionId = installer.createSession(params)
        val result = CompletableDeferred<Unit>()
        pending[sessionId] = result

        try {
            installer.openSession(sessionId).use { session ->
                for (apk in apks) {
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        apk.inputStream().use { it.copyTo(output) }
                        session.fsync(output)
                    }
                }
                session.commit(statusReceiver(sessionId).intentSender)
            }
            result.await()
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        } finally {
            pending.remove(sessionId)
        }
    }

    private fun statusReceiver(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java)
            .setAction(ACTION_RESULT)
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            // The installer fills the status extras in, so the intent has to stay mutable.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_RESULT = "io.github.miron404.appcloner.INSTALL_RESULT"

        private val pending = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

        fun complete(sessionId: Int, status: Int, message: String?) {
            val waiter = pending.remove(sessionId) ?: return
            if (status == PackageInstaller.STATUS_SUCCESS) {
                waiter.complete(Unit)
            } else {
                waiter.completeExceptionally(InstallFailure(describe(status, message)))
            }
        }

        private fun describe(status: Int, message: String?): String {
            val reason = when (status) {
                PackageInstaller.STATUS_FAILURE_ABORTED -> "Cancelled"
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked by the system"
                PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicts with an installed app"
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Not compatible with this device"
                PackageInstaller.STATUS_FAILURE_INVALID -> "The package was rejected as invalid"
                PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
                else -> "Install failed"
            }
            return if (message.isNullOrBlank()) reason else "$reason: $message"
        }
    }
}

/**
 * Receives the session's verdict.
 *
 * The first thing the installer sends back is usually a request to confirm with the user, carried
 * as an intent for us to launch. Launching it works because the app is in the foreground while a
 * clone is being installed; if it has been sent to the background the system blocks the launch and
 * the session sits waiting until the user comes back.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            return
        }
        Installer.complete(
            sessionId,
            status,
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        )
    }
}
