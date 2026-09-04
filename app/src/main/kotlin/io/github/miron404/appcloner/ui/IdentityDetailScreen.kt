package io.github.miron404.appcloner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityDetailScreen(model: VaultViewModel, identityId: String, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, state.error, snackbar, model::consumeMessage)

    val identity = state.identities.firstOrNull { it.id == identityId }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<Uri?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pkcs12")
    ) { uri -> exportTarget = uri }

    if (identity == null) {
        LaunchedBack(onBack)
        return
    }

    exportTarget?.let { target ->
        PassphraseDialog(
            title = "Protect the exported keystore",
            body = "The .p12 file is encrypted with PBKDF2-HMAC-SHA256 and AES-256. Anyone with " +
                "this file and the passphrase can sign as ${identity.label}.",
            confirmLabel = "Export",
            requireConfirmation = true,
            onConfirm = { passphrase ->
                exportTarget = null
                model.exportKeystore(identity, target, passphrase)
            },
            onDismiss = { exportTarget = null },
        )
    }

    if (renaming) {
        RenameDialog(
            currentLabel = identity.label,
            currentAlias = identity.alias,
            onConfirm = { label, alias ->
                renaming = false
                model.renameIdentity(identity, label, alias)
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${identity.label}?",
            body = "The private key is destroyed. Any APK already signed with it stays valid, but " +
                "you will never be able to publish an update to it again.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                model.deleteIdentity(identity)
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(identity.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename")
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

            SectionCard("Certificate") {
                KeyValueRow("Subject", identity.dn.rfc2253())
                KeyValueRow("Alias", identity.alias)
                KeyValueRow("Key", "${identity.algorithm} ${identity.keySize}")
                KeyValueRow("Signature", identity.signatureAlgorithm)
                KeyValueRow("Serial", identity.serialNumberHex)
                KeyValueRow("Valid from", formatDate(identity.notBefore))
                KeyValueRow("Valid until", formatDate(identity.notAfter))
            }

            SectionCard("SHA-256 fingerprint") {
                LabeledValue("Certificate digest", identity.fingerprintSha256, monospace = true)
            }

            SectionCard("Export") {
                Text(
                    "A PKCS#12 export is portable but only as strong as the passphrase you choose. " +
                        "Prefer the encrypted vault backup in Settings for moving to a new device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { createDocument.launch("${identity.alias}.p12") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export as PKCS#12") }
            }

            Button(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Delete identity") }

            Text(
                identity.certificatePem,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}
