package com.fograin.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.downloads.DownloadsScreen
import com.fograin.launcher.ui.home.HomeScreen
import com.fograin.launcher.ui.library.LibraryScreen
import com.fograin.launcher.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Root native shell: `Scaffold` + Material 3 `NavigationBar`, one top-level
 * screen per destination, saved per-tab state and a snackbar for actions whose
 * backend lands in a later milestone.
 */
@Composable
fun FograinApp(
    themeMode: ThemeMode,
    dynamicColorEnabled: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
    val destination = AppDestination.entries.firstOrNull { it.name == destinationName }
        ?: AppDestination.HOME
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val saveableStateHolder = rememberSaveableStateHolder()

    val showMessage: (String) -> Unit = { message ->
        snackbarScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
        }
    }

    BackHandler(enabled = destination != AppDestination.HOME) {
        destinationName = AppDestination.HOME.name
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                AppDestination.entries.forEach { item ->
                    val selected = item == destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { destinationName = item.name },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.icon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(item.labelRes)) },
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
            saveableStateHolder.SaveableStateProvider(destination.name) {
                when (destination) {
                    AppDestination.HOME -> HomeScreen(
                        onOpenLibrary = { destinationName = AppDestination.LIBRARY.name },
                        onShowMessage = showMessage,
                    )

                    AppDestination.LIBRARY -> LibraryScreen(onShowMessage = showMessage)

                    AppDestination.DOWNLOADS -> DownloadsScreen()

                    AppDestination.SETTINGS -> SettingsScreen(
                        themeMode = themeMode,
                        dynamicColorEnabled = dynamicColorEnabled,
                        onThemeModeChange = onThemeModeChange,
                        onDynamicColorChange = onDynamicColorChange,
                    )
                }
            }
        }
    }
}
