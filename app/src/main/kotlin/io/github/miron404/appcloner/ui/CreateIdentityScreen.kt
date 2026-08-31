package io.github.miron404.appcloner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.miron404.appcloner.core.DEFAULT_VALIDITY_YEARS
import io.github.miron404.appcloner.core.DistinguishedName
import io.github.miron404.appcloner.core.KeyAlgorithm
import io.github.miron404.appcloner.core.NewIdentityRequest
import io.github.miron404.appcloner.core.UNKNOWN

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateIdentityScreen(model: VaultViewModel, onDone: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, state.error, snackbar, model::consumeMessage)

    var label by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var commonName by remember { mutableStateOf("") }
    var organizationalUnit by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var locality by remember { mutableStateOf("") }
    var stateOrProvince by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var validity by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf(KeyAlgorithm.DEFAULT) }

    LaunchedEffect(Unit) { model.prepareForKeyGeneration() }

    val busy = state.busy != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New identity") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
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
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.busy.orEmpty(), style = MaterialTheme.typography.labelMedium)
            }

            SectionCard("Identity") {
                Field("Name in this app", label, { label = it }, "Shown in the list")
                Field("Keystore alias", alias, { alias = it }, "Defaults to $UNKNOWN")
            }

            SectionCard("Certificate subject") {
                Text(
                    "Every field left blank is certified as \"$UNKNOWN\", the same default keytool uses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field("Common name (CN)", commonName, { commonName = it }, "Person or app name")
                Field("Organisational unit (OU)", organizationalUnit, { organizationalUnit = it }, "Team")
                Field("Organisation (O)", organization, { organization = it }, "Company")
                Field("Locality (L)", locality, { locality = it }, "City")
                Field("State or province (ST)", stateOrProvince, { stateOrProvince = it }, "Region")
                Field(
                    label = "Country code (C)",
                    value = country,
                    onChange = { country = it.uppercase().take(2) },
                    hint = "Two letters, e.g. NL",
                )
            }

            SectionCard("Key and validity") {
                AlgorithmPicker(algorithm) { algorithm = it }
                OutlinedTextField(
                    value = validity,
                    onValueChange = { input -> validity = input.filter { it.isDigit() }.take(3) },
                    label = { Text("Validity in years") },
                    placeholder = { Text(DEFAULT_VALIDITY_YEARS.toString()) },
                    supportingText = { Text("Blank means $DEFAULT_VALIDITY_YEARS years") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val request = NewIdentityRequest(
                        label = label.trim(),
                        alias = alias.trim(),
                        dn = DistinguishedName(
                            commonName = commonName,
                            organizationalUnit = organizationalUnit,
                            organization = organization,
                            locality = locality,
                            state = stateOrProvince,
                            country = country,
                        ),
                        validityYears = validity.toIntOrNull()?.coerceIn(1, 100)
                            ?: DEFAULT_VALIDITY_YEARS,
                        algorithm = algorithm,
                    )
                    model.createIdentity(request, onDone)
                },
            ) { Text("Generate key pair") }

            Text(
                "The keystore password is generated from the system CSPRNG and never shown: it is " +
                    "sealed with the hardware master key and only unsealed while signing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlgorithmPicker(selected: KeyAlgorithm, onSelect: (KeyAlgorithm) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Key algorithm") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            KeyAlgorithm.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
