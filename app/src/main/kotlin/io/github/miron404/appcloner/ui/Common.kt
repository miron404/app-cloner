package io.github.miron404.appcloner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Collects a passphrase, requiring it twice when it is going to protect something being written.
 *
 * Compose only offers String-backed text fields, so the passphrase does exist as an immutable
 * String for the lifetime of this dialog. Everything downstream takes a CharArray that is wiped
 * after use; this dialog is the one unavoidable exception.
 */
@Composable
fun PassphraseDialog(
    title: String,
    body: String,
    confirmLabel: String,
    requireConfirmation: Boolean,
    minimumLength: Int = 12,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }

    val tooShort = requireConfirmation && passphrase.length < minimumLength
    val mismatch = requireConfirmation && repeated != passphrase
    val valid = passphrase.isNotEmpty() && !tooShort && !mismatch

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    isError = tooShort && passphrase.isNotEmpty(),
                    supportingText = if (requireConfirmation) {
                        { Text("At least $minimumLength characters") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (requireConfirmation) {
                    OutlinedTextField(
                        value = repeated,
                        onValueChange = { repeated = it },
                        label = { Text("Repeat passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        isError = mismatch && repeated.isNotEmpty(),
                        supportingText = if (mismatch && repeated.isNotEmpty()) {
                            { Text("Passphrases do not match") }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(passphrase.toCharArray()) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Edits the two names an identity carries: what this app calls it, and the alias inside the
 * keystore. Only the alias costs anything to change, which the dialog says out loud.
 */
@Composable
fun RenameDialog(
    currentLabel: String,
    currentAlias: String,
    onConfirm: (label: String, alias: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(currentLabel) }
    var alias by remember { mutableStateOf(currentAlias) }
    val aliasChanged = alias.trim() != currentAlias
    val valid = label.isNotBlank() && alias.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name in this app") },
                    singleLine = true,
                    isError = label.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Keystore alias") },
                    singleLine = true,
                    isError = alias.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (aliasChanged) {
                        "Changing the alias rewrites the keystore, so you will be asked to " +
                            "authenticate. The key and its certificate are unchanged, so APKs " +
                            "already signed with this identity stay valid."
                    } else {
                        "The alias is what appears inside the keystore and in v1 signature files."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(label.trim(), alias.trim()) }) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun KeyValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private val dateFormat: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

fun formatDate(epochMillis: Long): String = dateFormat.format(Date(epochMillis))

/** Leaves a screen that has nothing left to show, on the frame it notices. */
@Composable
fun LaunchedBack(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }
}
