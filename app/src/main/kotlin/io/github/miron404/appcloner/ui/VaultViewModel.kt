package io.github.miron404.appcloner.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.miron404.appcloner.container
import io.github.miron404.appcloner.core.AuthCancelledException
import io.github.miron404.appcloner.core.AuthPolicy
import io.github.miron404.appcloner.core.BackupArchive
import io.github.miron404.appcloner.core.IdentityMeta
import io.github.miron404.appcloner.core.KeyMaterial
import io.github.miron404.appcloner.core.MasterKeyState
import io.github.miron404.appcloner.core.NewIdentityRequest
import io.github.miron404.appcloner.core.PortableIdentity
import io.github.miron404.appcloner.core.SystemAuthenticator
import io.github.miron404.appcloner.core.wipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VaultUiState(
    val identities: List<IdentityMeta> = emptyList(),
    val masterKey: MasterKeyState = MasterKeyState(false, false, AuthPolicy.DEFAULT),
    val lockOnLaunch: Boolean = true,
    val unlocked: Boolean = false,
    /** Set when the lock screen should raise the system prompt without waiting for a tap. */
    val promptPending: Boolean = false,
    /** An APK handed to the app from outside, waiting to be picked up by the signing screen. */
    val incomingApk: Uri? = null,
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
)

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container
    private val vault = container.vault
    private val settings = container.settings

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    /**
     * Monotonic timestamp of the moment the app was last backgrounded, or null if it has not been
     * in the foreground yet. Wall-clock time would let a clock change extend the window.
     */
    private var backgroundedAt: Long? = null

    private var warmedUp = false

    init {
        refresh()
        // The vault owns the list; this screen only mirrors it, so creating or deleting an
        // identity anywhere shows up here without anyone having to call refresh().
        viewModelScope.launch {
            vault.identities.collect { identities ->
                _state.update { it.copy(identities = identities) }
            }
        }
    }

    /** Re-reads the state that is not a flow. The identity list arrives on its own. */
    fun refresh() {
        _state.update {
            it.copy(
                masterKey = vault.state(),
                lockOnLaunch = settings.lockOnLaunch,
            )
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null, error = null) }

    /**
     * Records an APK that another app opened or shared with us. The read permission granted with
     * the intent lasts only as long as this task, so the signing screen copies it out promptly.
     */
    fun onApkReceived(uri: Uri) = _state.update { it.copy(incomingApk = uri) }

    fun consumeIncomingApk() = _state.update { it.copy(incomingApk = null) }

    // --- app lock -----------------------------------------------------------------------------

    fun onBackgrounded() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Re-locks the UI when the app has been away for longer than the authentication window it
     * advertises. A cold start always locks. Under a per-operation policy the window is zero, so
     * any trip to the background re-locks — which is the point of that setting.
     */
    fun onForegrounded() {
        refresh()
        if (!settings.lockOnLaunch) {
            _state.update { it.copy(unlocked = true) }
            return
        }
        // A prompt already on screen owns this restart: the user is in the system credential
        // activity, not returning from elsewhere.
        if (container.authenticator.isPrompting) return

        val since = backgroundedAt
        val window = vault.state().policy.timeoutSeconds * 1000L
        val stillFresh = since != null && SystemClock.elapsedRealtime() - since <= window
        _state.update { it.copy(unlocked = stillFresh, promptPending = !stillFresh) }
    }

    fun unlockApp() = run("Unlocking") {
        _state.update { it.copy(promptPending = false) }
        val policy = vault.state().policy
        container.authenticator.authenticate(
            crypto = null,
            title = "Unlock App Cloner",
            subtitle = "Protects the signing identities your clones are signed with",
            allowDeviceCredential = policy.allowDeviceCredential,
        )
        _state.update { it.copy(unlocked = true) }
    }

    fun setLockOnLaunch(enabled: Boolean) {
        settings.lockOnLaunch = enabled
        _state.update {
            it.copy(lockOnLaunch = enabled, unlocked = it.unlocked || !enabled, promptPending = false)
        }
    }

    // --- identities ---------------------------------------------------------------------------

    /**
     * Pays BouncyCastle's one-off class-loading cost while the user is still filling in the form,
     * instead of adding it to the wait after they press Generate.
     */
    fun prepareForKeyGeneration() {
        if (warmedUp) return
        warmedUp = true
        viewModelScope.launch(Dispatchers.Default) { runCatching { KeyMaterial.warmUp() } }
    }

    fun createIdentity(request: NewIdentityRequest, onCreated: () -> Unit) =
        run("Generating ${request.algorithm.label} key") {
            if (!SystemAuthenticator.canAuthenticate(
                    getApplication(),
                    settings.desiredPolicy.allowDeviceCredential,
                )
            ) {
                error("Set up a screen lock or biometric before creating keys")
            }
            val started = SystemClock.elapsedRealtime()
            val strongBox = withContext(Dispatchers.IO) { vault.ensureMasterKey() }
            val meta = vault.create(request)
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            refresh()
            onCreated()
            _state.update {
                it.copy(
                    message = "Created ${meta.label} in %.1fs; ".format(seconds) + if (strongBox) {
                        "sealed by the secure element"
                    } else {
                        "no secure element, sealed by the TEE"
                    }
                )
            }
        }

    /**
     * Renaming only the display label is free; changing the keystore alias rewrites the sealed
     * keystore and will ask for authentication.
     */
    fun renameIdentity(meta: IdentityMeta, label: String, alias: String) = run("Renaming") {
        val updated = vault.rename(meta, label, alias)
        _state.update { it.copy(message = "Renamed to ${updated.label}") }
    }

    fun deleteIdentity(meta: IdentityMeta) = run("Deleting") {
        vault.delete(meta.id)
        _state.update { it.copy(message = "Deleted ${meta.label}") }
    }

    /** Writes a PKCS#12 protected by a passphrase the user chooses, for use with other tooling. */
    fun exportKeystore(meta: IdentityMeta, target: Uri, passphrase: CharArray) =
        run("Exporting keystore") {
            try {
                vault.open(meta).use { portable ->
                    val material = KeyMaterial.readPkcs12(
                        portable.pkcs12,
                        portable.keystorePassword,
                        portable.meta.alias,
                    )
                    val encoded = withContext(Dispatchers.Default) {
                        KeyMaterial.writePkcs12(
                            alias = meta.alias,
                            privateKey = material.privateKey,
                            certificate = material.chain.first(),
                            password = passphrase,
                            iterations = KeyMaterial.EXPORT_KDF_ITERATIONS,
                        )
                    }
                    writeToUri(target, encoded)
                    encoded.wipe()
                }
                _state.update { it.copy(message = "Exported ${meta.alias}.p12") }
            } finally {
                passphrase.wipe()
            }
        }

    // --- backup -------------------------------------------------------------------------------

    fun exportBackup(target: Uri, passphrase: CharArray) = run("Encrypting backup") {
        val metas = vault.list()
        if (metas.isEmpty()) error("There is nothing to back up")
        val portables = mutableListOf<PortableIdentity>()
        try {
            metas.forEach { portables += vault.open(it) }
            val archive = withContext(Dispatchers.Default) {
                BackupArchive.create(portables, passphrase)
            }
            writeToUri(target, archive)
            _state.update { it.copy(message = "Backed up ${portables.size} identities") }
        } finally {
            portables.forEach { it.close() }
            passphrase.wipe()
        }
    }

    fun importBackup(source: Uri, passphrase: CharArray, overwrite: Boolean) =
        run("Decrypting backup") {
            val bytes = readFromUri(source)
            val portables = withContext(Dispatchers.Default) {
                BackupArchive.open(bytes, passphrase)
            }
            try {
                withContext(Dispatchers.IO) { vault.ensureMasterKey() }
                var added = 0
                var skipped = 0
                portables.forEach { portable ->
                    if (vault.restore(portable, overwrite)) added++ else skipped++
                }
                // For the master key the import may have just created; identities arrive on their own.
                refresh()
                _state.update {
                    it.copy(
                        message = "Imported $added identities" +
                            if (skipped > 0) ", skipped $skipped already present" else ""
                    )
                }
            } finally {
                portables.forEach { it.close() }
                bytes.wipe()
                passphrase.wipe()
            }
        }

    // --- policy -------------------------------------------------------------------------------

    /**
     * Applies a new authentication policy. The window is baked into the hardware key, so this mints
     * a replacement and re-seals the vault against it.
     */
    fun applyPolicy(policy: AuthPolicy) = run("Re-keying vault") {
        settings.desiredPolicy = policy
        if (vault.state().exists) {
            vault.rekey(policy)
        } else {
            withContext(Dispatchers.IO) { vault.ensureMasterKey() }
        }
        refresh()
        _state.update { it.copy(message = "Authentication now required " + describe(policy)) }
    }

    private fun describe(policy: AuthPolicy): String {
        val window = if (policy.perOperation) {
            "for every operation"
        } else {
            "once per ${io.github.miron404.appcloner.core.formatDuration(policy.timeoutSeconds)}"
        }
        return window + if (policy.allowDeviceCredential) " (biometric or PIN)" else " (biometric only)"
    }

    // --- plumbing -----------------------------------------------------------------------------

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

    private suspend fun writeToUri(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
            ?.use { it.write(bytes) }
            ?: error("Could not open the destination for writing")
    }

    private suspend fun readFromUri(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        getApplication<Application>().contentResolver.openInputStream(uri)
            ?.use { stream ->
                // Read one byte past the limit rather than slurping the whole stream and checking
                // afterwards, so an oversized file cannot exhaust memory first.
                val bytes = stream.readNBytes(BackupArchive.MAX_ARCHIVE_BYTES + 1)
                if (bytes.size > BackupArchive.MAX_ARCHIVE_BYTES) error("File is too large")
                bytes
            }
            ?: error("Could not open the file")
    }
}
