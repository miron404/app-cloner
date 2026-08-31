package io.github.miron404.appcloner.clone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The single running build, shared between the view model and the foreground service. */
object CloneJobs {
    private val _progress = MutableStateFlow<CloneProgress?>(null)
    val progress = _progress.asStateFlow()

    fun publish(progress: CloneProgress?) {
        _progress.value = progress
    }
}

/**
 * Keeps the process alive while a clone is being built.
 *
 * Rewriting and signing a few hundred megabytes takes long enough that the user will put the phone
 * down, and a plain coroutine dies with the process. The service holds no key material and does no
 * work of its own: the pipeline runs in the application scope and this only publishes its progress
 * so the system has a reason to keep everything running.
 */
class CloneJobService : Service() {

    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(
            NOTIFICATION_ID,
            build(CloneJobs.progress.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (scope == null) {
            val created = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
            scope = created
            created.launch {
                CloneJobs.progress.collect { progress ->
                    val manager = getSystemService(NotificationManager::class.java)
                    // Posting can be refused when notifications are turned off; the service and
                    // the build carry on regardless.
                    runCatching { manager?.notify(NOTIFICATION_ID, build(progress)) }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun build(progress: CloneProgress?): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle(progress?.step ?: "Building a clone")
            .setContentText(progress?.detail.orEmpty())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                val fraction = progress?.fraction
                if (fraction != null) {
                    setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Clone builds", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while an app is being repacked and signed"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL = "clone-builds"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.miron404.appcloner.STOP_JOB"

        fun start(context: Context) {
            val intent = Intent(context, CloneJobService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CloneJobService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
            CloneJobs.publish(null)
        }
    }
}
