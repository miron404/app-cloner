package io.github.miron404.appcloner.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * Plain preferences. Nothing stored here is secret: the master key alias only names an
 * AndroidKeyStore entry, and the entry itself is what actually protects the vault.
 */
class AppSettings(context: Context) : VaultSettings {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * Which AndroidKeyStore entry the vault is sealed with.
     *
     * This and [pendingMasterKeyAlias] are written with `commit`, not `apply`. The vault deletes a
     * master key straight after naming its replacement here, and that deletion reaches the
     * keystore at once; `apply` would leave the new name in memory for a while first, and a crash
     * in that window boots into settings that name a key which no longer exists.
     */
    override var masterKeyAlias: String?
        get() = prefs.getString(KEY_MASTER_ALIAS, null)
        set(value) = prefs.edit().putString(KEY_MASTER_ALIAS, value).commitOrThrow()

    /** Set between re-sealing the vault and swapping to the new master key, so a crash can resume. */
    override var pendingMasterKeyAlias: String?
        get() = prefs.getString(KEY_PENDING_ALIAS, null)
        set(value) = prefs.edit().putString(KEY_PENDING_ALIAS, value).commitOrThrow()

    /** Requested policy for a master key that does not exist yet. */
    override var desiredPolicy: AuthPolicy
        get() = AuthPolicy(
            timeoutSeconds = prefs.getInt(KEY_TIMEOUT, AuthPolicy.DEFAULT.timeoutSeconds),
            allowDeviceCredential = prefs.getBoolean(
                KEY_ALLOW_CREDENTIAL,
                AuthPolicy.DEFAULT.allowDeviceCredential,
            ),
        )
        set(value) = prefs.edit()
            .putInt(KEY_TIMEOUT, value.timeoutSeconds)
            .putBoolean(KEY_ALLOW_CREDENTIAL, value.allowDeviceCredential)
            .apply()

    /** Ask for authentication when the app comes to the foreground, before showing anything. */
    var lockOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ON_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ON_LAUNCH, value).apply()

    var defaultSchemes: SignatureSchemes
        get() = SignatureSchemes(
            v1 = prefs.getBoolean(KEY_V1, true),
            v2 = prefs.getBoolean(KEY_V2, true),
            v3 = prefs.getBoolean(KEY_V3, true),
            v4 = prefs.getBoolean(KEY_V4, false),
        )
        set(value) = prefs.edit()
            .putBoolean(KEY_V1, value.v1)
            .putBoolean(KEY_V2, value.v2)
            .putBoolean(KEY_V3, value.v3)
            .putBoolean(KEY_V4, value.v4)
            .apply()

    /**
     * For a write the vault's next step depends on: if it did not reach the disk, that step must
     * not run.
     */
    @SuppressLint("ApplySharedPref")
    private fun SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "Could not save the vault's settings" }
    }

    private companion object {
        const val KEY_MASTER_ALIAS = "master_alias"
        const val KEY_PENDING_ALIAS = "pending_master_alias"
        const val KEY_TIMEOUT = "auth_timeout_seconds"
        const val KEY_ALLOW_CREDENTIAL = "auth_allow_device_credential"
        const val KEY_LOCK_ON_LAUNCH = "lock_on_launch"
        const val KEY_V1 = "scheme_v1"
        const val KEY_V2 = "scheme_v2"
        const val KEY_V3 = "scheme_v3"
        const val KEY_V4 = "scheme_v4"
    }
}

data class SignatureSchemes(
    val v1: Boolean = true,
    val v2: Boolean = true,
    val v3: Boolean = true,
    val v4: Boolean = false,
) {
    val any: Boolean get() = v1 || v2 || v3 || v4
}
