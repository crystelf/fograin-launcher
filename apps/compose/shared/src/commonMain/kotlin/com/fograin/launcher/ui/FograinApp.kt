package com.fograin.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.action_ok
import com.fograin.launcher.ui.components.AnimatedSheetHost
import com.fograin.launcher.ui.downloads.DownloadsScreen
import com.fograin.launcher.ui.home.HomeScreen
import com.fograin.launcher.ui.instances.InstanceSettingsSheet
import com.fograin.launcher.ui.library.InstancePreviewSheet
import com.fograin.launcher.ui.library.LibraryScreen
import com.fograin.launcher.ui.settings.SettingsScreen
import org.jetbrains.compose.resources.stringResource

/** Window width at which the shell swaps the bottom bar for a navigation rail. */
private val ExpandedWidth = 840.dp

/** Test tags so the adaptive breakpoint is assertable from Compose UI tests. */
const val NavBarTag: String = "nav-bar"
const val NavRailTag: String = "nav-rail"

/**
 * Root shell: one screen per destination plus the model's notices, alerts and
 * sheets. Android and desktop render exactly this tree; only the surrounding
 * window differs.
 *
 * Below [ExpandedWidth] the shell keeps the phone layout (bottom navigation);
 * from [ExpandedWidth] up it uses a `NavigationRail` so a 1280-px desktop
 * window does not show a stretched phone bar. Destinations and screens are
 * identical in both modes.
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

    // One handler for Android's back gesture and the desktop Escape key. It
    // peels sheets and dialogs before it leaves the current destination.
    backDispatcher.handleBack(enabled = model.canGoBack()) { model.back() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= ExpandedWidth

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
                if (!expanded) {
                    NavigationBar(
                        modifier = Modifier.testTag(NavBarTag),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        AppDestination.entries.forEach { item ->
                            NavigationBarItem(
                                modifier = Modifier.testTag(navItemTag(item)),
                                selected = item == state.destination,
                                onClick = { model.navigate(item) },
                                icon = {
                                    DestinationIcon(
                                        destination = item,
                                        selected = item == state.destination,
                                        activeDownloads = state.activeDownloadCount,
                                    )
                                },
                                label = { Text(stringResource(item.label)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (expanded) {
                    NavigationRail(
                        modifier = Modifier.testTag(NavRailTag),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Spacer(Modifier.height(8.dp))
                        AppDestination.entries.forEach { item ->
                            NavigationRailItem(
                                modifier = Modifier.testTag(navItemTag(item)),
                                selected = item == state.destination,
                                onClick = { model.navigate(item) },
                                icon = {
                                    DestinationIcon(
                                        destination = item,
                                        selected = item == state.destination,
                                        activeDownloads = state.activeDownloadCount,
                                    )
                                },
                                label = { Text(stringResource(item.label)) },
                            )
                        }
                    }
                    VerticalDivider()
                }
                AnimatedContent(
                    targetState = state.destination,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val enter = fadeIn(tween(220)) +
                            slideInHorizontally(tween(260)) { if (forward) it / 18 else -it / 18 }
                        val exit = fadeOut(tween(140)) +
                            slideOutHorizontally(tween(200)) { if (forward) -it / 18 else it / 18 }
                        enter togetherWith exit
                    },
                    label = "page-transition",
                ) { destination ->
                    when (destination) {
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

    // Each host keeps its payload composed through the slide-out animation.
    AnimatedSheetHost(
        value = state.previewInstance,
        onDismissRequest = model::closePreview,
    ) { instance ->
        InstancePreviewSheet(model = model, state = state, instance = instance)
    }

    AnimatedSheetHost(
        value = state.instanceSettings,
        onDismissRequest = model::closeInstanceSettings,
    ) { instance ->
        InstanceSettingsSheet(model = model, instance = instance)
    }
}

/** Stable tag per destination, so UI tests do not depend on translated labels. */
fun navItemTag(destination: AppDestination): String = "nav-item-${destination.name.lowercase()}"

/** Destination icon with the running-download badge shared by both navigations. */
@Composable
private fun DestinationIcon(destination: AppDestination, selected: Boolean, activeDownloads: Int) {
    val image = if (selected) destination.selectedIcon else destination.icon
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = tween(180),
        label = "nav-icon-scale",
    )
    val iconModifier = Modifier.graphicsLayer {
        scaleX = iconScale
        scaleY = iconScale
    }
    if (destination == AppDestination.DOWNLOADS) {
        BadgedBox(
            badge = {
                AnimatedVisibility(
                    visible = activeDownloads > 0,
                    enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.6f),
                    exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.6f),
                ) {
                    Badge { Text(activeDownloads.toString()) }
                }
            },
        ) {
            Icon(imageVector = image, contentDescription = null, modifier = iconModifier)
        }
    } else {
        Icon(imageVector = image, contentDescription = null, modifier = iconModifier)
    }
}
