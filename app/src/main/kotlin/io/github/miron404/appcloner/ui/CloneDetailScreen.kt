package io.github.miron404.appcloner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TextButton
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
import io.github.miron404.appcloner.clone.CloneStatus

/** One clone: where it came from, how it was made, and what can still be done with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneDetailScreen(model: CloneViewModel, cloneId: String, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, state.error, snackbar, model::consumeMessage)

    val status = state.clones.firstOrNull { it.record.id == cloneId }
    var confirmForget by remember { mutableStateOf(false) }

    // Forgetting the clone removes the thing this screen is about.
    if (status == null) {
        LaunchedBack(onBack)
        return
    }
    val record = status.record
    val outputs = model.outputsOf(record)

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) model.export(record, uri) }

    if (confirmForget) {
        ConfirmDialog(
            title = "Forget this clone?",
            body = "The built APKs are deleted and this app stops tracking updates for it. " +
                "A clone already installed on the device is left where it is; uninstall it from " +
                "the system settings if you want it gone.",
            confirmLabel = "Forget",
            onConfirm = {
                confirmForget = false
                model.forget(record)
            },
            onDismiss = { confirmForget = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(record.cloneLabel) },
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
            if (state.busy != null || state.building) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    state.progress?.step ?: state.busy.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            UpdateBanner(status)

            SectionCard("Source") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(record.source.packageName)
                    Column {
                        Text(record.source.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            record.source.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LabeledValue(
                    "Built from",
                    "${record.builtFromVersionName.ifBlank { "?" }} (${record.builtFromVersionCode})",
                )
                LabeledValue(
                    "Installed now",
                    status.sourceVersionCode?.let {
                        "${status.sourceVersionName.orEmpty().ifBlank { "?" }} ($it)"
                    } ?: "not installed",
                )
            }

            SectionCard("Clone") {
                LabeledValue("Package", record.clonePackage, monospace = true)
                LabeledValue("Signed by", record.identityLabel)
                LabeledValue("Icon", record.iconMode.label)
                LabeledValue(
                    "Deep rename",
                    if (record.deepRename) "on" else "off",
                )
                LabeledValue("Built", formatDate(record.builtAt))
                LabeledValue(
                    "On device",
                    if (status.cloneInstalled) "installed" else "not installed",
                )
                LabeledValue(
                    "Files kept",
                    if (outputs.isEmpty()) {
                        "none — rebuild before installing"
                    } else {
                        "${outputs.size} APK" + if (outputs.size == 1) "" else "s"
                    },
                )
            }

            Button(
                onClick = { model.rebuild(record) },
                enabled = !state.building && status.sourcePresent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (status.updateAvailable) "Update from the new version" else "Rebuild")
            }
            OutlinedButton(
                onClick = { model.install(record) },
                enabled = outputs.isNotEmpty() && !state.building,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Install")
            }
            OutlinedButton(
                onClick = { exporter.launch(model.exportName(record)) },
                enabled = outputs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (outputs.size > 1) "Export as .apks" else "Export APK")
            }
            TextButton(
                onClick = { confirmForget = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Forget this clone", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun UpdateBanner(status: CloneStatus) {
    val text = when {
        status.updateAvailable ->
            "${status.record.source.label} has been updated to " +
                "${status.sourceVersionName.orEmpty().ifBlank { "a newer version" }}. Rebuilding " +
                "produces a clone of that version; installing it over the existing clone keeps " +
                "its data, because the signing key is the same."

        !status.sourcePresent ->
            "The source app is no longer installed, so this clone cannot be rebuilt from it. " +
                "Reinstall it, or build a new clone from an APK file."

        status.rebuildPending ->
            "A newer build exists than the copy installed on the device. Install it to catch up."

        else -> return
    }
    SectionCard(if (status.updateAvailable) "Update available" else "Note") {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LaunchedBack(onBack: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
}
