package io.github.miron404.appcloner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val CLONES = "clones"
    const val PICK = "pick"
    const val CONFIGURE = "configure"
    const val DETAIL = "detail"
    const val IDENTITIES = "identities"
    const val CREATE = "create"
    const val IDENTITY = "identity"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavHost(vaultModel: VaultViewModel, cloneModel: CloneViewModel) {
    val navController = rememberNavController()
    val vaultState by vaultModel.state.collectAsStateWithLifecycle()

    // An APK opened with, or shared to, this app becomes the source for a new clone.
    LaunchedEffect(vaultState.incomingApk) {
        val uri = vaultState.incomingApk ?: return@LaunchedEffect
        vaultModel.consumeIncomingApk()
        cloneModel.chooseFiles(listOf(uri))
        if (navController.currentBackStackEntry?.destination?.route != Routes.CONFIGURE) {
            navController.navigate(Routes.CONFIGURE)
        }
    }

    NavHost(navController = navController, startDestination = Routes.CLONES) {
        composable(Routes.CLONES) {
            ClonesScreen(
                model = cloneModel,
                onNew = { navController.navigate(Routes.PICK) },
                onOpen = { id -> navController.navigate("${Routes.DETAIL}/$id") },
                onIdentities = { navController.navigate(Routes.IDENTITIES) },
            )
        }
        composable(Routes.PICK) {
            SourcePickerScreen(
                model = cloneModel,
                // The picker is not worth returning to once a source is chosen.
                onPicked = {
                    navController.navigate(Routes.CONFIGURE) {
                        popUpTo(Routes.PICK) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CONFIGURE) {
            ConfigureCloneScreen(
                model = cloneModel,
                onDone = {
                    navController.popBackStack(Routes.CLONES, inclusive = false)
                },
            )
        }
        composable("${Routes.DETAIL}/{id}") { entry ->
            CloneDetailScreen(
                model = cloneModel,
                cloneId = entry.arguments?.getString("id").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.IDENTITIES) {
            IdentitiesScreen(
                model = vaultModel,
                onCreate = { navController.navigate(Routes.CREATE) },
                onOpen = { id -> navController.navigate("${Routes.IDENTITY}/$id") },
                onBack = {
                    // The clone form reads the identity list straight from the vault, so it has
                    // to be told that creating or deleting one changed it.
                    cloneModel.refresh()
                    navController.popBackStack()
                },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.CREATE) {
            CreateIdentityScreen(model = vaultModel, onDone = { navController.popBackStack() })
        }
        composable("${Routes.IDENTITY}/{id}") { entry ->
            IdentityDetailScreen(
                model = vaultModel,
                identityId = entry.arguments?.getString("id").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(model = vaultModel, onBack = { navController.popBackStack() })
        }
    }
}

@Composable
fun LockScreen(state: VaultUiState, onUnlock: () -> Unit) {
    // Ask straight away rather than making the user tap first. Declining clears the flag, so the
    // button below becomes the way to retry instead of the prompt reappearing immediately.
    LaunchedEffect(state.promptPending) {
        if (state.promptPending) onUnlock()
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("App Cloner", style = MaterialTheme.typography.headlineSmall)
            Text(
                "The keys your clones are signed with are held in the secure element and stay " +
                    "locked until you authenticate.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.busy != null || state.promptPending) {
                CircularProgressIndicator()
            } else {
                Button(onClick = onUnlock) { Text("Unlock") }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}
