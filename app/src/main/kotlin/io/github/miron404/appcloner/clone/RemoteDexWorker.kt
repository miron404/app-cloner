package io.github.miron404.appcloner.clone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A [DexWorker] in the `:dex` process, reached through [DexWorkerService].
 *
 * One is opened per build and closed as soon as the build has no more dex files to rewrite.
 * Closing ends the process outright rather than leaving it cached: its heap is the reason it
 * exists, and one that has just rebuilt a large dex is holding hundreds of megabytes the phone
 * would otherwise get back only when the system next went looking.
 */
class RemoteDexWorker private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val binder: IBinder,
    private val pid: Int,
    override val heapLimit: Long,
) : DexWorker {

    private val shutDown = AtomicBoolean(false)

    /**
     * Sends [job] to the worker and waits for the answer.
     *
     * A binder call cannot be interrupted, so it is not made on a dispatcher thread that
     * cancellation would then sit waiting for. Cancelling ends the worker process instead, which
     * makes the call return at once — with a [RemoteException] nobody is listening for any more.
     * That is also what makes Cancel work in the middle of a dex, which it never did before: the
     * rewrite ran on the build's own thread and could only be abandoned once it had finished.
     */
    override suspend fun rewrite(job: DexJob): DexOutcome =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { shutDown() }
            thread(name = "dex-worker-call", isDaemon = true) {
                continuation.resumeWith(runCatching { call(job) })
            }
        }

    private fun call(job: DexJob): DexOutcome {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(DexWorkerProtocol.encode(job))
            binder.transact(DexWorkerService.REWRITE, data, reply, 0)
            val answer = reply.readString() ?: return DexOutcome(error = "The dex worker gave no answer")
            DexWorkerProtocol.decodeOutcome(answer)
        } catch (_: RemoteException) {
            // The process is gone. Out of memory is by far the likeliest reason, and the message
            // built from this says so without claiming to be sure.
            DexOutcome(workerDied = true)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun close() = shutDown()

    private fun shutDown() {
        if (!shutDown.compareAndSet(false, true)) return
        // Unbound first, so the system does not restart the service for a client still holding it.
        runCatching { context.unbindService(connection) }
        // Guarded because this would be the app itself if the service ever lost android:process.
        if (pid != Process.myPid()) Process.killProcess(pid)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val NOT_STARTED = "The process that rewrites dex files could not be started"

        /** Starts the worker process, or reuses one that is already up, and connects to it. */
        suspend fun open(context: Context): RemoteDexWorker {
            val app = context.applicationContext
            val connected = CompletableDeferred<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connected.complete(service)
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit

                override fun onBindingDied(name: ComponentName) {
                    connected.completeExceptionally(IllegalStateException(NOT_STARTED))
                }

                override fun onNullBinding(name: ComponentName) {
                    connected.completeExceptionally(IllegalStateException(NOT_STARTED))
                }
            }

            return try {
                val intent = Intent(app, DexWorkerService::class.java)
                check(app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { NOT_STARTED }
                // OrNull, and not withTimeout: its TimeoutCancellationException is a
                // CancellationException, and the build would take a worker that never came up for
                // the user pressing Cancel — and report nothing at all.
                val binder = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
                    ?: error(NOT_STARTED)
                val (pid, heapLimit) = withContext(Dispatchers.IO) { hello(binder) }
                RemoteDexWorker(app, connection, binder, pid, heapLimit)
            } catch (error: Throwable) {
                runCatching { app.unbindService(connection) }
                throw error
            }
        }

        private fun hello(binder: IBinder): Pair<Int, Long> {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                binder.transact(DexWorkerService.HELLO, data, reply, 0)
                reply.readInt() to reply.readLong()
            } catch (error: RemoteException) {
                throw IllegalStateException(NOT_STARTED, error)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}
