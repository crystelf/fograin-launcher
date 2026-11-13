package com.fograin.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.action_ok
import com.fograin.launcher.ui.downloads.DownloadsScreen
import com.fograin.launcher.ui.downloads.GameInstallSheet
import com.fograin.launcher.ui.home.HomeScreen
import com.fograin.launcher.ui.instances.InstanceSettingsSheet
import com.fograin.launcher.ui.library.InstancePreviewSheet
import com.fograin.launcher.ui.library.LibraryScreen
import com.fograin.launcher.ui.settings.SettingsScreen
import org.jetbrains.compose.resources.stringResource

/**
 * Root shell: Material 3 `NavigationBar`, one screen per destination and the
 * process-wide model's notices, alerts and sheets. Android and desktop render
 * exactly this tree; only the surrounding window differs.
 */
@Composable
fun FograinApp(
    model: AppModel,
    state: AppUiState,
    dynamicColorEnabled: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val backDispatcher = remember { platformServices().backDispatcher }

    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = notice.message,
            duration = SnackbarDuration.Short,
            withDismissAction = true,
        )
        model.consumeNotice(notice.id)
    }

    backDispatcher.handleBack(enabled = state.destination != AppDestination.HOME) {
        model.navigate(AppDestination.HOME)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                AppDestination.entries.forEach { item ->
                    val selected = item == state.destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { model.navigate(item) },
                        icon = {
                            if (item == AppDestination.DOWNLOADS && state.activeDownloadCount > 0) {
                                BadgedBox(
                                    badge = { Badge { Text(state.activeDownloadCount.toString()) } },
                                ) {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.icon,
                                        contentDescription = null,
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.icon,
                                    contentDescription = null,
                                )
                            }
                        },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (state.destination) {
                AppDestination.HOME -> HomeScreen(model = model, state = state)
                AppDestination.LIBRARY -> LibraryScreen(model = model, state = state)
                AppDestination.DOWNLOADS -> DownloadsScreen(model = model, state = state)
                AppDestination.SETTINGS -> SettingsScreen(
                    model = model,
                    state = state,
                    dynamicColorEnabled = dynamicColorEnabled,
                    onDynamicColorChange = onDynamicColorChange,
                )
            }
        }
    }

    state.alert?.let { alert ->
        AlertDialog(
            onDismissRequest = model::dismissAlert,
            title = { Text(alert.title) },
            text = { Text(alert.combinedMessage) },
            confirmButton = {
                TextButton(onClick = model::dismissAlert) {
                    Text(stringResource(Res.string.action_ok))
                }
            },
        )
    }

    state.previewInstance?.let { instance ->
        InstancePreviewSheet(model = model, state = state, instance = instance)
    }

    state.instanceSettings?.let { instance ->
        InstanceSettingsSheet(model = model, instance = instance)
    }

    if (state.download.installSheetVersion != null) {
        GameInstallSheet(
            model = model,
            state = state,
            initialVersionId = state.download.installSheetVersion.orEmpty(),
        )
    }
}
