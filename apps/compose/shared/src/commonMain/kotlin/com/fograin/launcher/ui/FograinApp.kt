package com.fograin.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.Notice
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.action_ok
import com.fograin.launcher.resources.global_search_open
import com.fograin.launcher.resources.modpack_drop_body
import com.fograin.launcher.resources.modpack_drop_title
import com.fograin.launcher.resources.update_open_failed
import com.fograin.launcher.ui.accounts.AccountSheet
import com.fograin.launcher.ui.components.AnimatedSheetHost
import com.fograin.launcher.ui.components.FograinSnackbarHost
import com.fograin.launcher.ui.components.LocalPageHeaderTopInset
import com.fograin.launcher.ui.components.PageHeaderMetrics
import com.fograin.launcher.ui.components.UpdatePrompt
import com.fograin.launcher.ui.components.contentPanelShape
import com.fograin.launcher.ui.components.fograinPageTransition
import com.fograin.launcher.ui.content.ContentProjectSheet
import com.fograin.launcher.ui.downloads.DownloadQueueScreen
import com.fograin.launcher.ui.downloads.DownloadsScreen
import com.fograin.launcher.ui.home.HomeScreen
import com.fograin.launcher.ui.library.LibraryScreen
import com.fograin.launcher.ui.search.GlobalSearchSheet
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import org.jetbrains.compose.resources.stringResource

/** Window width at which the shell swaps the bottom bar for a navigation rail. */
private val ExpandedWidth = 840.dp

/** Test tags so the adaptive breakpoint is assertable from Compose UI tests. */
const val NavBarTag: String = "nav-bar"
const val NavRailTag: String = "nav-rail"
const val GlobalSearchButtonTag: String = "global-search-button"

private data object GlobalSearchTarget

private data object AccountTarget

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
) {
    // Network artwork (version posters and content icons) shares one loader so
    // requests, memory cache and crossfades are process-wide. The factory is a
    // no-op after the first composition.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .build()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val services = remember { platformServices() }
    val backDispatcher = remember { services.backDispatcher }
    // The page header starts at the window edge on every platform. On macOS
    // that puts the title row on the same line as the native traffic lights;
    // the header itself uses a shorter 48 dp row for that title-bar feel.
    val headerHeight = PageHeaderMetrics.heightFor(services.platformName)
    val updateOpenFailed = stringResource(
        Res.string.update_open_failed,
        state.updates.available?.url.orEmpty(),
    )

    // One handler for Android's back gesture and the desktop Escape key. It
    // peels sheets and dialogs before it leaves the current destination and is
    // disabled while the settings destination owns the window.
    backDispatcher.handleBack(enabled = !state.settingsWindow.open && model.canGoBack()) {
        model.back()
    }

    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = notice.message)
        model.consumeNotice(notice.id)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= ExpandedWidth
        // A narrow macOS window has no rail for the traffic lights to sit in,
        // so move the whole header below them. The body then behaves like a
        // phone layout instead of being squeezed to the right.
        val pageHeaderTopInset =
            if (services.platformName == "macos" && !expanded) {
                40.dp
            } else {
                0.dp
            }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { FograinSnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!expanded) {
                    NavigationBar(
                        modifier = Modifier.testTag(NavBarTag),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        AppDestination.entries.forEach { item ->
                            val selected = !state.downloadQueueOpen && item == state.destination
                            NavigationBarItem(
                                modifier = Modifier.testTag(navItemTag(item)),
                                selected = if (item == AppDestination.SETTINGS) {
                                    state.settingsWindow.open
                                } else {
                                    selected
                                },
                                onClick = { model.navigate(item) },
                                icon = {
                                    DestinationIcon(
                                        destination = item,
                                        selected = selected,
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                CompositionLocalProvider(
                    LocalPageHeaderTopInset provides pageHeaderTopInset,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            // Painted before the content pane so the pane's
                            // rounded left corners reveal the rail colour, not
                            // a third background shade.
                            .then(
                                if (expanded) {
                                    Modifier.background(MaterialTheme.colorScheme.surface)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        if (expanded) {
                            NavigationRail(
                                modifier = Modifier.testTag(NavRailTag),
                                containerColor = MaterialTheme.colorScheme.surface,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxHeight(),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        // Align the search button with the first
                                        // content card (the home hero image).
                                        Spacer(
                                            Modifier.height(
                                                headerHeight + PageHeaderMetrics.ContentGap,
                                            ),
                                        )
                                        RailSearchButton(onClick = model::openGlobalSearch)
                                        Spacer(Modifier.height(24.dp))
                                        AppDestination.entries
                                            .filter { it != AppDestination.SETTINGS }
                                            .forEachIndexed { index, item ->
                                                if (index > 0) Spacer(Modifier.height(18.dp))
                                                val selected = !state.downloadQueueOpen &&
                                                    item == state.destination
                                                NavigationRailItem(
                                                    modifier = Modifier.testTag(navItemTag(item)),
                                                    selected = selected,
                                                    onClick = { model.navigate(item) },
                                                    icon = {
                                                        DestinationIcon(
                                                            destination = item,
                                                            selected = selected,
                                                            activeDownloads = state.activeDownloadCount,
                                                        )
                                                    },
                                                    label = { Text(stringResource(item.label)) },
                                                )
                                            }
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        HorizontalDivider(
                                            modifier = Modifier.width(36.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        val settings = AppDestination.SETTINGS
                                        val selected = !state.downloadQueueOpen &&
                                            settings == state.destination
                                        NavigationRailItem(
                                            modifier = Modifier.testTag(navItemTag(settings)),
                                            selected = state.settingsWindow.open,
                                            onClick = { model.navigate(settings) },
                                            icon = {
                                                DestinationIcon(
                                                    destination = settings,
                                                    selected = selected,
                                                    activeDownloads = state.activeDownloadCount,
                                                )
                                            },
                                            label = { Text(stringResource(settings.label)) },
                                        )
                                        Spacer(Modifier.height(12.dp))
                                    }
                                }
                            }
                        }
                        // The right half is flush with the window's top,
                        // right and bottom edges. Only its left corners are
                        // rounded, and only when the host window is rounded
                        // (macOS, Windows 11, GNOME); otherwise it stays
                        // rectangular so it merges with the system chrome.
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = contentPanelShape(inset = expanded),
                            // Wide: a lighter content card cut out of the
                            // darker rail. Compact: the page is the window
                            // itself, so it keeps the window surface colour.
                            color = if (expanded) {
                                MaterialTheme.colorScheme.surfaceContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            AnimatedContent(
                                targetState = state.downloadQueueOpen,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = {
                                    fograinPageTransition(forward = targetState)
                                },
                                label = "queue-page",
                            ) { queueOpen ->
                                if (queueOpen) {
                                    DownloadQueueScreen(model = model, state = state)
                                } else {
                                    AnimatedContent(
                                        targetState = state.destination,
                                        modifier = Modifier.fillMaxSize(),
                                        transitionSpec = {
                                            fograinPageTransition(
                                                forward = targetState.ordinal > initialState.ordinal,
                                            )
                                        },
                                        label = "page-transition",
                                    ) { destination ->
                                        when (destination) {
                                            AppDestination.HOME ->
                                                HomeScreen(model = model, state = state)

                                            AppDestination.LIBRARY ->
                                                LibraryScreen(model = model, state = state)

                                            AppDestination.DOWNLOADS ->
                                                DownloadsScreen(model = model, state = state)

                                            // Settings is an in-window
                                            // destination rendered by App;
                                            // this branch only keeps the enum
                                            // exhaustive without a second page.
                                            AppDestination.SETTINGS ->
                                                HomeScreen(model = model, state = state)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                UpdatePrompt(
                    update = state.updates.visible,
                    currentVersion = state.settings.appVersion,
                    onOpen = {
                        if (!model.openUpdate()) {
                            model.showNotice(updateOpenFailed, Notice.Tone.WARNING)
                        }
                    },
                    onDismiss = model::dismissUpdate,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = state.modpack.dragActive,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 36.dp, vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Inventory2,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(Res.string.modpack_drop_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(Res.string.modpack_drop_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
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
    // Instance management and modpack import/export live on in-window pages
    // now, so only the project inspector, global search and account panel
    // remain sheets.
    AnimatedSheetHost(
        value = state.contentBrowser.selectedProject,
        onDismissRequest = model.contentBrowser::closeProject,
    ) { project ->
        ContentProjectSheet(model = model, state = state, project = project)
    }

    AnimatedSheetHost(
        value = if (state.globalSearchOpen) GlobalSearchTarget else null,
        onDismissRequest = model::closeGlobalSearch,
    ) {
        GlobalSearchSheet(model = model, state = state)
    }

    AnimatedSheetHost(
        value = if (state.account.open) AccountTarget else null,
        onDismissRequest = model.account::close,
    ) {
        AccountSheet(model = model, state = state)
    }
}

@Composable
private fun RailSearchButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .testTag(GlobalSearchButtonTag),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(Res.string.global_search_open),
                modifier = Modifier.size(24.dp),
            )
        }
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
