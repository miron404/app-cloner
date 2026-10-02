package io.github.miron404.appcloner.clone

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process

/**
 * Rewrites dex files in the `:dex` process, for [RemoteDexWorker] on the other side.
 *
 * Deliberately thin. Everything that can be got wrong lives in [DexJobRunner] and
 * [DexWorkerProtocol], which are plain Kotlin and run in the unit tests; what is left here is
 * reading a string out of a parcel and writing one back, because a JVM test cannot stand in for a
 * binder. Two calls, on a bare [Binder] rather than AIDL, since that is all there is:
 *
 *  - [HELLO] answers with this process's id, so the caller can end it, and its heap limit, which
 *    is what the size check before a deep rename has to be made against.
 *  - [REWRITE] takes a [DexJob] and answers with a [DexOutcome]. It is synchronous and can take
 *    minutes, which is fine for a call the caller makes from a thread of its own.
 *
 * It holds no key material and touches nothing in the app's container, which is never created in
 * this process — see [io.github.miron404.appcloner.AppClonerApplication].
 */
class DexWorkerService : Service() {

    private val runner = DexJobRunner()

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            when (code) {
                HELLO -> {
                    reply?.writeInt(Process.myPid())
                    reply?.writeLong(Runtime.getRuntime().maxMemory())
                    true
                }

                REWRITE -> {
                    val outcome = runCatching { DexWorkerProtocol.decodeJob(data.readString().orEmpty()) }
                        .fold(
                            onSuccess = { job -> runner.run(job) },
                            onFailure = { DexOutcome(error = "The request could not be read: ${it.message}") },
                        )
                    reply?.writeString(DexWorkerProtocol.encode(outcome))
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val HELLO = IBinder.FIRST_CALL_TRANSACTION
        const val REWRITE = IBinder.FIRST_CALL_TRANSACTION + 1
    }
}
