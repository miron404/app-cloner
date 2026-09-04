package io.github.miron404.appcloner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.miron404.appcloner.clone.BadgeCorner
import io.github.miron404.appcloner.clone.CloneRecord
import io.github.miron404.appcloner.clone.CloneReport
import io.github.miron404.appcloner.clone.CloneRequest
import io.github.miron404.appcloner.clone.IconMode
import io.github.miron404.appcloner.clone.PackageNames
import io.github.miron404.appcloner.clone.SourceApks
import io.github.miron404.appcloner.core.IdentityMeta

/** Configures a clone, runs the build, and shows what came out of it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureCloneScreen(model: CloneViewModel, onDone: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, state.error, snackbar, model::consumeMessage)

    val source = state.source
    val draft = state.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.built != null -> "Clone ready"
                            state.editing != null -> "Rebuild"
                            else -> "New clone"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            model.clearResult()
                            onDone()
                        },
                        enabled = !state.building,
                    ) {
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
            if (source == null || draft == null) {
                Text("Nothing selected.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            SourceHeader(source)

            val editing = state.editing
            if (editing != null && state.built == null) {
                RebuildNote(editing, draft)
            }

            val built = state.built
            val report = state.report
            when {
                state.building -> BuildingCard(model, state)
                built != null && report != null -> ResultCard(model, built, report)
                else -> Form(model, state, draft)
            }
        }
    }
}

@Composable
private fun SourceHeader(source: SourceApks) {
    SectionCard("Source") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(source.info.packageName)
            Column {
                Text(source.info.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    source.info.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    buildString {
                        append(source.info.versionName.ifBlank { "version ${source.info.versionCode}" })
                        if (source.info.hasSplits) {
                            append(" · base + ${source.splits.size} splits")
                        }
                        append(" · %.0f MB".format(source.totalBytes / 1_000_000.0))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What a rebuild will do to the clone already on the device. */
@Composable
private fun RebuildNote(editing: CloneRecord, draft: CloneRequest) {
    val movedPackage = draft.packageName != editing.clonePackage
    SectionCard(if (movedPackage) "This will be a second clone" else "Rebuilding an existing clone") {
        Text(
            if (movedPackage) {
                "The package name has been changed from ${editing.clonePackage}, so what gets " +
                    "built installs as a new app beside the existing clone and is tracked as a " +
                    "clone of its own. Change it back to update the existing one in place."
            } else {
                "Settings start from the ones this clone was built with. Installing the result " +
                    "over the clone already on the device keeps its data, as long as the signing " +
                    "identity below is the one it was signed with (${editing.identityLabel})."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Form(model: CloneViewModel, state: CloneUiState, draft: CloneRequest) {
    val packageValid = PackageNames.isValid(draft.packageName)

    SectionCard("Identity in the launcher") {
        OutlinedTextField(
            value = draft.label,
            onValueChange = { value -> model.editDraft { it.copy(label = value) } },
            label = { Text("App name") },
            singleLine = true,
            isError = draft.label.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.packageName,
            onValueChange = { value -> model.editDraft { it.copy(packageName = value) } },
            label = { Text("Package name") },
            singleLine = true,
            isError = !packageValid,
            supportingText = {
                Text(
                    if (packageValid) {
                        "The clone installs alongside the original under this name."
                    } else {
                        "Needs at least two dot-separated parts, each starting with a letter."
                    }
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SectionCard("Icon") {
        for (mode in IconMode.entries) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = draft.iconMode == mode,
                        onClick = { model.editDraft { it.copy(iconMode = mode) } },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = draft.iconMode == mode,
                    onClick = { model.editDraft { it.copy(iconMode = mode) } },
                )
                Column(Modifier.padding(start = 4.dp, top = 10.dp)) {
                    Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (draft.iconMode == IconMode.BADGE) {
            OutlinedTextField(
                value = draft.badgeText,
                onValueChange = { value ->
                    model.editDraft { it.copy(badgeText = value.take(2)) }
                },
                label = { Text("Badge") },
                singleLine = true,
                supportingText = { Text("One or two characters. Usually the clone's number.") },
                modifier = Modifier.fillMaxWidth(),
            )
            CornerPicker(
                selected = draft.badgeCorner,
                onSelect = { corner -> model.editDraft { it.copy(badgeCorner = corner) } },
            )
        }
        IconPreviewRow(state.preview, draft.iconMode)
    }

    SectionCard("Signing identity") {
        IdentityPicker(
            identities = state.identities,
            selectedId = draft.identityId,
            onSelect = { id -> model.editDraft { it.copy(identityId = id) } },
        )
        Text(
            "Reusing one identity across every clone lets them update each other; a clone signed " +
                "by a different key has to be uninstalled before it can be replaced.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard("Deep rename") {
        ToggleLine(
            title = "Rewrite package names in the code",
            body = "Also swaps the old package name where it appears as a string constant in the " +
                "app's bytecode. Fixes FileProvider and anything else built from " +
                "BuildConfig.APPLICATION_ID, at the cost of modifying the app. Experimental.",
            checked = draft.deepRename,
            onChange = { on ->
                model.editDraft {
                    it.copy(deepRename = on, renameIntentActions = it.renameIntentActions && on)
                }
            },
        )
        ToggleLine(
            title = "Rename the app's own intent actions",
            body = "Stops the clone and the original answering each other's broadcasts. Only " +
                "coherent with the rewrite above, because the code that sends them changes too.",
            checked = draft.renameIntentActions,
            enabled = draft.deepRename,
            onChange = { on -> model.editDraft { it.copy(renameIntentActions = on) } },
        )
    }

    Button(
        onClick = model::build,
        enabled = draft.label.isNotBlank() && packageValid && draft.identityId.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.editing != null) "Rebuild clone" else "Build clone")
    }
}

/**
 * Which corner the badge goes in, drawn rather than described.
 *
 * The cells are the icon in miniature, so the choice reads at a glance; the preview above shows
 * what it actually does to this app's icon.
 */
@Composable
private fun CornerPicker(selected: BadgeCorner, onSelect: (BadgeCorner) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Corner · ${selected.label.lowercase()}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (corner in BadgeCorner.entries) {
                val active = corner == selected
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = RoundedCornerShape(14.dp),
                        )
                        .selectable(selected = active, onClick = { onSelect(corner) })
                        .padding(7.dp)
                ) {
                    Box(
                        Modifier
                            .align(corner.alignment())
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                    )
                }
            }
        }
    }
}

private fun BadgeCorner.alignment(): Alignment = when (this) {
    BadgeCorner.TOP_LEFT -> Alignment.TopStart
    BadgeCorner.TOP_RIGHT -> Alignment.TopEnd
    BadgeCorner.BOTTOM_LEFT -> Alignment.BottomStart
    BadgeCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
}

/**
 * The icon as it will be, both ways round.
 *
 * The themed tile is the monochrome layer tinted, which is all a launcher draws with Material You
 * themed icons on. It is the case the cut-out badge exists for, so it is worth seeing before the
 * build rather than after installing.
 */
@Composable
private fun IconPreviewRow(preview: IconPreview?, mode: IconMode) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PreviewTile("Launcher") {
            if (preview != null) {
                Image(
                    bitmap = preview.normal,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        PreviewTile("Themed", MaterialTheme.colorScheme.primaryContainer) {
            val themed = preview?.themed
            if (themed != null) {
                Image(
                    bitmap = themed,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                )
            }
        }
        Text(
            when {
                preview == null -> "Rendering the icon…"
                preview.themed == null && mode == IconMode.BADGE ->
                    "This icon has no themed layer, so one is derived from its outline. Themed " +
                        "launchers will show that instead."

                mode == IconMode.KEEP -> "The clone keeps the icon it came from."
                else -> "The badge is cut out of the themed layer, so it survives the tint."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@Composable
private fun PreviewTile(
    label: String,
    fill: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        // Held to the tile's own width so a label cannot widen the row past the screen.
        modifier = Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(fill),
            content = content,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BuildingCard(model: CloneViewModel, state: CloneUiState) {
    val progress = state.progress
    SectionCard(progress?.step ?: "Working") {
        val fraction = progress?.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val detail = progress?.detail
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "You can leave the app; the build keeps going and reports in the notification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = model::cancelBuild) { Text("Cancel") }
    }
}

@Composable
private fun ResultCard(model: CloneViewModel, record: CloneRecord, report: CloneReport) {
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) model.export(record, uri) }

    SectionCard("What changed") {
        LabeledValue("Package", report.clonePackage, monospace = true)
        LabeledValue(
            "Manifest",
            buildString {
                append("${report.manifest.expandedClassNames} class names expanded")
                append(", ${report.manifest.renamedAuthorities} provider authorities")
                append(", ${report.manifest.renamedPermissions} permissions")
                if (report.manifest.renamedProcesses > 0) {
                    append(", ${report.manifest.renamedProcesses} processes")
                }
                if (report.manifest.renamedActions > 0) {
                    append(", ${report.manifest.renamedActions} intent actions")
                }
            },
        )
        report.dex?.let { dex ->
            LabeledValue(
                "Bytecode",
                "${dex.rewrittenStrings} strings rewritten across ${dex.dexFiles} dex files, " +
                    "${dex.skippedClassNames} left alone as class names",
            )
        }
        LabeledValue("Icon", report.icon)
        LabeledValue("Signed by", record.identityLabel)
        LabeledValue("Fingerprint", report.signerFingerprint.take(29), monospace = true)
        LabeledValue(
            "Output",
            if (report.outputs.size == 1) {
                "1 APK"
            } else {
                "${report.outputs.size} APKs (base and splits)"
            },
        )
    }

    if (report.manifest.strippedSharedUserId != null) {
        SectionCard("Shared user id removed") {
            Text(
                "The app declared sharedUserId=\"${report.manifest.strippedSharedUserId}\". A " +
                    "shared user id cannot survive a change of signing key, so it was dropped. " +
                    "If the app relied on sharing data with its siblings, the clone will not.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (report.warnings.isNotEmpty()) {
        SectionCard("Worth knowing") {
            for (warning in report.warnings) {
                Text("• $warning", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Button(onClick = { model.install(record) }, modifier = Modifier.fillMaxWidth()) {
        Text("Install")
    }
    OutlinedButton(
        onClick = { exporter.launch(model.exportName(record)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (report.outputs.size == 1) "Export APK" else "Export as .apks")
    }
}

@Composable
private fun IdentityPicker(
    identities: List<IdentityMeta>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = identities.firstOrNull { it.id == selectedId }

    if (identities.isEmpty()) {
        Text(
            "No signing identities yet. Create one from the key icon on the clones screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.label ?: "Choose an identity", Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (identity in identities) {
                DropdownMenuItem(
                    text = { Text("${identity.label} · ${identity.algorithm} ${identity.keySize}") },
                    onClick = {
                        onSelect(identity.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ToggleLine(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
