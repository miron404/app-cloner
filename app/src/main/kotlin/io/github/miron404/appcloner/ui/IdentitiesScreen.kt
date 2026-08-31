package io.github.miron404.appcloner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.miron404.appcloner.core.IdentityMeta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitiesScreen(
    model: VaultViewModel,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, state.error, snackbar, model::consumeMessage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Signing identities") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New identity") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    state.busy.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            StorageBanner(state)
            if (state.identities.isEmpty()) {
                EmptyIdentities()
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.identities, key = { it.id }) { identity ->
                        IdentityCard(identity) { onOpen(identity.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageBanner(state: VaultUiState) {
    if (!state.masterKey.exists) return
    val text = if (state.masterKey.strongBoxBacked) {
        "Master key held in the secure element (StrongBox / Titan M2)."
    } else {
        "No StrongBox on this device: the master key is protected by the TEE instead."
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (state.masterKey.strongBoxBacked) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyIdentities() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.CenterVertically),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text("No identities yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Every clone is signed with one of these. Create one to generate a key pair and a " +
                "self-signed certificate; its keystore password is random and sealed by the " +
                "secure element.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityCard(identity: IdentityMeta, onClick: () -> Unit) {
    val expired = identity.notAfter < System.currentTimeMillis()
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(identity.label, style = MaterialTheme.typography.titleMedium)
            Text(
                identity.dn.rfc2253(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${identity.algorithm} ${identity.keySize} · " +
                    (if (expired) "expired " else "valid until ") + formatDate(identity.notAfter),
                style = MaterialTheme.typography.labelMedium,
                color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                identity.fingerprintSha256.take(29),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MessageEffect(
    message: String?,
    error: String?,
    snackbar: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(message, error) {
        val text = error ?: message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        onConsumed()
    }
}
