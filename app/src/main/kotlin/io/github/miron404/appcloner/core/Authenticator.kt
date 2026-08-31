package io.github.miron404.appcloner.core

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The user dismissed the system prompt, or it failed in a way that should abort the operation. */
class AuthCancelledException(message: String) : Exception(message)

/**
 * Drives the platform [BiometricPrompt]. Only the foreground activity can show system UI, so the
 * activity registers itself here for as long as it is resumed and unregisters afterwards.
 */
class SystemAuthenticator {

    private var host: WeakReference<Activity> = WeakReference(null)

    /**
     * True while a system prompt is on screen.
     *
     * Choosing the PIN fallback hands control to the system credential activity, which stops this
     * app. Without this flag the resulting restart would look like a return from the background and
     * would raise a second prompt on top of the one already waiting.
     */
    @Volatile
    var isPrompting: Boolean = false
        private set

    fun attach(activity: Activity) {
        host = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (host.get() === activity) host = WeakReference(null)
    }

    /**
     * Shows the system authentication sheet. When [crypto] is supplied the resulting authorisation
     * applies to that single crypto operation; otherwise it opens the key's time-bound window.
     */
    suspend fun authenticate(
        crypto: BiometricPrompt.CryptoObject?,
        title: String,
        subtitle: String,
        allowDeviceCredential: Boolean,
    ) {
        val activity = host.get() ?: throw AuthCancelledException("App is not in the foreground")
        isPrompting = true
        try {
            showPrompt(activity, crypto, title, subtitle, allowDeviceCredential)
        } finally {
            isPrompting = false
        }
    }

    private suspend fun showPrompt(
        activity: Activity,
        crypto: BiometricPrompt.CryptoObject?,
        title: String,
        subtitle: String,
        allowDeviceCredential: Boolean,
    ) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val executor = Executor { it.run() }
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }

                val builder = BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setConfirmationRequired(false)
                    .setAllowedAuthenticators(allowedAuthenticators(allowDeviceCredential))
                if (!allowDeviceCredential) {
                    builder.setNegativeButton("Cancel", executor) { _, _ -> cancellation.cancel() }
                }

                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence?) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                AuthCancelledException(message?.toString() ?: "Authentication error $code")
                            )
                        }
                    }
                    // onAuthenticationFailed is a retryable rejection; the prompt stays up.
                }

                if (crypto != null) {
                    builder.build().authenticate(crypto, cancellation, executor, callback)
                } else {
                    builder.build().authenticate(cancellation, executor, callback)
                }
            }
        }
    }

    companion object {
        fun allowedAuthenticators(allowDeviceCredential: Boolean): Int =
            if (allowDeviceCredential) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            }

        /** True when the device can actually satisfy the requested authenticators today. */
        fun canAuthenticate(context: Context, allowDeviceCredential: Boolean): Boolean {
            val manager = context.getSystemService(BiometricManager::class.java) ?: return false
            return manager.canAuthenticate(allowedAuthenticators(allowDeviceCredential)) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }
    }
}
