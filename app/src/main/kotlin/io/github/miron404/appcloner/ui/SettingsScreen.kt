package io.github.miron404.appcloner.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.miron404.appcloner.core.AuthPolicy
import io.github.miron404.appcloner.core.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(model: VaultViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, state.error, snackbar, model::consumeMessage)

    var pendingPolicy by remember { mutableStateOf<AuthPolicy?>(null) }
    var backupTarget by remember { mutableStateOf<Uri?>(null) }
    var restoreSource by remember { mutableStateOf<Uri?>(null) }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> backupTarget = uri }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> restoreSource = uri }

    val policy = state.masterKey.policy

    pendingPolicy?.let { candidate ->
        ConfirmDialog(
            title = "Re-key the vault?",
            body = "The authentication window is enforced by the secure element, so changing it " +
                "means creating a new hardware key and re-sealing every identity against it. " +
                "Nothing is lost, but you will be asked to authenticate.",
            confirmLabel = "Re-key",
            onConfirm = {
                pendingPolicy = null
                model.applyPolicy(candidate)
            },
            onDismiss = { pendingPolicy = null },
        )
    }

    backupTarget?.let { target ->
        PassphraseDialog(
            title = "Encrypt the backup",
            body = "The archive holds every private key. It is protected only by this passphrase: " +
                "Argon2id stretches it, then AES-256-GCM seals the contents. Choose something long.",
            confirmLabel = "Export",
            requireConfirmation = true,
            minimumLength = 16,
            onConfirm = { passphrase ->
                backupTarget = null
                model.exportBackup(target, passphrase)
            },
            onDismiss = { backupTarget = null },
        )
    }

    restoreSource?.let { source ->
        PassphraseDialog(
            title = "Open the backup",
            body = "Enter the passphrase used when the archive was created. Identities already " +
                "present are left untouched.",
            confirmLabel = "Import",
            requireConfirmation = false,
            onConfirm = { passphrase ->
                restoreSource = null
                model.importBackup(source, passphrase, overwrite = false)
            },
            onDismiss = { restoreSource = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.busy.orEmpty(), style = MaterialTheme.typography.labelMedium)
            }

            SectionCard("Authentication window") {
                Text(
                    "How long one authentication stays valid. Keymaster enforces this on the key " +
                        "itself, so it holds even if the app is tampered with.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthPolicy.TIMEOUT_CHOICES.forEach { seconds ->
                        FilterChip(
                            selected = policy.timeoutSeconds == seconds,
                            onClick = {
                                if (policy.timeoutSeconds != seconds) {
                                    pendingPolicy = policy.copy(timeoutSeconds = seconds)
                                }
                            },
                            label = { Text(formatDuration(seconds)) },
                        )
                    }
                }
                ToggleRow(
                    title = "Allow device PIN as fallback",
                    subtitle = "Off means biometric only, and re-enrolling a fingerprint " +
                        "permanently destroys the vault.",
                    checked = policy.allowDeviceCredential,
                ) { allow ->
                    pendingPolicy = policy.copy(allowDeviceCredential = allow)
                }
            }

            SectionCard("App lock") {
                ToggleRow(
                    title = "Lock on launch",
                    subtitle = "Authenticate before the identity list is shown.",
                    checked = state.lockOnLaunch,
                    onChange = model::setLockOnLaunch,
                )
            }

            SectionCard("Hardware") {
                KeyValueRow(
                    "Master key",
                    when {
                        !state.masterKey.exists -> "not created yet"
                        state.masterKey.strongBoxBacked -> "StrongBox secure element"
                        else -> "TEE (no StrongBox on this device)"
                    },
                )
                KeyValueRow("Re-authenticate", formatDuration(policy.timeoutSeconds))
                KeyValueRow("Identities", state.identities.size.toString())
            }

            SectionCard("Encrypted backup") {
                Text(
                    "Moves every identity to another device. The archive never involves this " +
                        "phone's secure element, so it can be opened anywhere with the passphrase.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { createBackup.launch(defaultBackupName()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.identities.isNotEmpty(),
                ) { Text("Export encrypted backup") }
                OutlinedButton(
                    // Same reasoning as the APK picker: a backup has no registered media type, so
                    // filtering on one hides it wherever the provider guesses differently.
                    onClick = { openBackup.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import backup") }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun defaultBackupName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "apk-signer-$stamp.aksbk"
}
