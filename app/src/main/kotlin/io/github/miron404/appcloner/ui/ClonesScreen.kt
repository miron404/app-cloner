package io.github.miron404.appcloner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.miron404.appcloner.clone.CloneStatus

/**
 * The clones this app has built, and whether any of them has fallen behind its source.
 *
 * A clone has no link back to the app it came from — to the system they are unrelated packages —
 * so noticing an update is this app's job: it remembers the version each clone was built from and
 * compares it with what is installed now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClonesScreen(
    model: CloneViewModel,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onIdentities: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, state.error, snackbar, model::consumeMessage)

    val outdated = state.clones.count { it.updateAvailable }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clones") },
                actions = {
                    IconButton(onClick = model::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                    }
                    IconButton(onClick = onIdentities) {
                        Icon(Icons.Default.Key, contentDescription = "Signing identities")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New clone") },
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
            if (outdated > 0) {
                Text(
                    if (outdated == 1) {
                        "1 clone is behind its source app."
                    } else {
                        "$outdated clones are behind their source apps."
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.clones.isEmpty()) {
                EmptyClones()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.clones, key = { it.record.id }) { status ->
                        CloneCard(status) { onOpen(status.record.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyClones() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No clones yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "A clone is a copy of an app under a new package name and your own signing key, so " +
                "it installs alongside the original and keeps its own data.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloneCard(status: CloneStatus, onClick: () -> Unit) {
    val record = status.record
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The clone's own icon once it is installed; until then, the app it came from.
            AppIcon(
                packageName = if (status.cloneInstalled) {
                    record.clonePackage
                } else {
                    record.source.packageName
                },
            )
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(record.cloneLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    "from ${record.source.label} · ${record.builtFromVersionName.ifBlank { "?" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        status.updateAvailable -> Chip(
                            "Update to ${status.sourceVersionName.orEmpty().ifBlank { "newer" }}",
                            MaterialTheme.colorScheme.primary,
                        )

                        !status.cloneInstalled -> Chip(
                            "Built, not installed",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        status.rebuildPending -> Chip(
                            "Installed copy is older",
                            MaterialTheme.colorScheme.tertiary,
                        )

                        !status.sourcePresent -> Chip(
                            "Source removed",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> Chip("Up to date", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, colour: Color) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = colour,
        ),
    )
}
