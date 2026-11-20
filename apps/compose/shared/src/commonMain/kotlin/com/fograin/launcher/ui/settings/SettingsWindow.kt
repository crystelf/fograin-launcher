package com.fograin.launcher.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.SettingsSection
import com.fograin.launcher.platform.currentSystemLanguageTag
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.platform.resizeHorizontalPointerIcon
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.account_kind_microsoft
import com.fograin.launcher.resources.account_kind_offline
import com.fograin.launcher.resources.account_kind_yggdrasil
import com.fograin.launcher.resources.action_ok
import com.fograin.launcher.resources.settings_back
import com.fograin.launcher.resources.settings_close
import com.fograin.launcher.resources.settings_core_config_failed
import com.fograin.launcher.resources.settings_core_unavailable
import com.fograin.launcher.resources.settings_core_unavailable_body
import com.fograin.launcher.resources.settings_account_local
import com.fograin.launcher.resources.settings_adjust_sidebar
import com.fograin.launcher.resources.settings_group_about
import com.fograin.launcher.resources.settings_group_app
import com.fograin.launcher.resources.settings_group_data
import com.fograin.launcher.resources.settings_load_error
import com.fograin.launcher.resources.settings_save_error
import com.fograin.launcher.resources.settings_title
import com.fograin.launcher.resources.settings_window_title
import com.fograin.launcher.ui.components.fograinPageTransition
import com.fograin.launcher.ui.components.windowContentShape
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.theme.FograinTheme
import org.jetbrains.compose.resources.stringResource

const val SettingsWindowTag: String = "settings-window"
const val SettingsSidebarTag: String = "settings-sidebar"
const val SettingsSplitterTag: String = "settings-splitter"
const val SettingsContentTag: String = "settings-content"
const val SettingsWindowBackTag: String = "settings-window-back"
const val SettingsCompactListTag: String = "settings-compact-list"
const val SettingsCompactBackTag: String = "settings-compact-back"
const val SettingsCompactCloseTag: String = "settings-compact-close"
const val SettingsLoadingTag: String = "settings-loading"
fun settingsNavTag(section: SettingsSection): String = "settings-nav-${section.name.lowercase()}"

/**
 * Extra top space reserved for native window chrome (macOS traffic lights).
 * The desktop shell provides this while the in-window settings page is shown;
 * Android and tests keep the 0 dp default.
 */
val LocalSettingsTopInset = staticCompositionLocalOf { 0.dp }

private val DefaultSidebarWidth = 300.dp
private val MinSidebarWidth = 238.dp
private val MaxSidebarWidth = 420.dp
private val CompactBreakpoint = 820.dp

/**
 * The in-window settings surface.
 *
 * The shared [com.fograin.launcher.App] provides the theme, language and back
 * handling; this composable owns only the settings layout, its snackbar and
 * alerts. Keeping it separate from the wrapper below lets the App animate
 * between the shell and settings exactly like any other page.
 */
@Composable
fun SettingsPageHost(model: AppModel, state: AppUiState) {
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message)
        model.consumeNotice(notice.id)
    }
    LaunchedEffect(Unit) {
        if (!state.settingsWindow.loaded && !state.settingsWindow.isLoading) {
            model.retrySettings()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding()
                .testTag(SettingsWindowTag),
        ) {
            val compact = maxWidth < CompactBreakpoint
            LaunchedEffect(compact) { model.setSettingsCompact(compact) }
            if (compact) {
                CompactSettings(model = model, state = state)
            } else {
                WideSettings(model = model, state = state)
            }
            androidx.compose.material3.SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
            )
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
}

/**
 * Self-contained settings surface for previews and the off-screen render
 * tests. Product code reaches settings through [SettingsPageHost] inside the
 * shared App, so there is no second Window and the shell/settings transition
 * stays consistent.
 */
@Composable
fun SettingsWindowRoot(model: AppModel) {
    val state by model.state.collectAsState()
    FograinTheme(themeMode = state.theme, accent = state.accent.value) {
        AppLocale(state.language) {
            SettingsPageHost(model = model, state = state)
        }
    }
}

// ---------------------------------------------------------------------------
// Wide layout
// ---------------------------------------------------------------------------

@Composable
private fun WideSettings(model: AppModel, state: AppUiState) {
    val preferences = remember { platformServices().preferences }
    var sidebarWidth by remember {
        mutableStateOf(
            preferences.settingsSidebarWidth
                .takeIf { it > 0f }
                ?.dp
                ?.coerceIn(MinSidebarWidth, MaxSidebarWidth)
                ?: DefaultSidebarWidth,
        )
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxForWindow = (maxWidth * 0.42f).coerceIn(MinSidebarWidth, MaxSidebarWidth)
        val clampedWidth = sidebarWidth.coerceIn(MinSidebarWidth, maxForWindow)
        Row(modifier = Modifier.fillMaxSize()) {
            SettingsSidebar(
                model = model,
                state = state,
                active = state.settingsWindow.section,
                modifier = Modifier
                    .width(clampedWidth)
                    .fillMaxHeight()
                    .testTag(SettingsSidebarTag),
            )
            SettingsSplitter(
                onDrag = { delta ->
                    sidebarWidth = (clampedWidth + delta).coerceIn(MinSidebarWidth, maxForWindow)
                },
                onCommit = {
                    preferences.settingsSidebarWidth = sidebarWidth.value
                },
            )
            SettingsContentPane(
                model = model,
                state = state,
                section = state.settingsWindow.section,
                showTitle = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SettingsSplitter(
    onDrag: (Dp) -> Unit,
    onCommit: () -> Unit,
) {
    val density = LocalDensity.current
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val description = stringResource(Res.string.settings_adjust_sidebar)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(12.dp)
            .testTag(SettingsSplitterTag)
            .semantics { contentDescription = description }
            .pointerHoverIcon(resizeHorizontalPointerIcon())
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { currentOnCommit() },
                    onDragCancel = { currentOnCommit() },
                ) { change, dragAmount ->
                    change.consume()
                    currentOnDrag(with(density) { dragAmount.toDp() })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // A small filled drag handle, not a full-height divider line. It keeps
        // its neutral colour while dragging; only the resize cursor changes.
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        )
    }
}

@Composable
private fun SettingsSidebar(
    model: AppModel,
    state: AppUiState,
    active: SettingsSection,
    modifier: Modifier = Modifier,
) {
    val topInset = LocalSettingsTopInset.current
    val account = state.account
    val language = state.language.resolve(currentSystemLanguageTag())

    Column(modifier = modifier) {
        // Header: close/back + title. The traffic-light strip on macOS sits in
        // `topInset`, so the row starts below the native buttons.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 18.dp, top = topInset + 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = model::settingsBack,
                modifier = Modifier.testTag(SettingsWindowBackTag),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.settings_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(Res.string.settings_window_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        SettingsProfileCard(
            name = account.displayName,
            subtitle = account.selected?.let { selected ->
                when (selected.kind) {
                    "offline" -> stringResource(Res.string.account_kind_offline)
                    "yggdrasil" -> selected.serverName
                        ?: stringResource(Res.string.account_kind_yggdrasil)

                    else -> stringResource(Res.string.account_kind_microsoft)
                }
            } ?: stringResource(Res.string.settings_account_local),
            initial = account.initial,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
        ) {
            SettingsNavGroup(
                label = stringResource(Res.string.settings_group_app),
                sections = listOf(
                    SettingsSection.APPEARANCE,
                    SettingsSection.GENERAL,
                    SettingsSection.JAVA,
                ),
                active = active,
                onSelect = model::selectSettingsSection,
            )
            SettingsNavGroup(
                label = stringResource(Res.string.settings_group_data),
                sections = listOf(
                    SettingsSection.DOWNLOAD,
                    SettingsSection.NETWORK,
                    SettingsSection.STORAGE,
                ),
                active = active,
                onSelect = model::selectSettingsSection,
            )
        }

        SettingsNavGroup(
            label = stringResource(Res.string.settings_group_about),
            sections = listOf(SettingsSection.ABOUT),
            active = active,
            onSelect = model::selectSettingsSection,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Text(
            text = "v${state.settings.appVersion} · ${language.nativeName()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 26.dp, top = 6.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun SettingsNavGroup(
    label: String?,
    sections: List<SettingsSection>,
    active: SettingsSection?,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 5.dp),
            )
        }
        sections.forEach { section ->
            SettingsNavItem(
                section = section,
                selected = section == active,
                onClick = { onSelect(section) },
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun SettingsNavItem(
    section: SettingsSection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth().testTag(settingsNavTag(section)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = section.title(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsProfileCard(
    name: String,
    subtitle: String,
    initial: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsContentPane(
    model: AppModel,
    state: AppUiState,
    section: SettingsSection,
    showTitle: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(SettingsContentTag),
        shape = windowContentShape(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        // On macOS the traffic lights sit in the sidebar strip; shift the pane
        // content down to line up with the sidebar header. Compact layouts
        // already account for the inset in their own top bar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = LocalSettingsTopInset.current),
        ) {
            val window = state.settingsWindow
            if (window.isLoading && !window.loaded) {
                Box(Modifier.fillMaxSize().testTag(SettingsLoadingTag), Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (window.isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val errors = buildList {
                        if (!model.isCoreAvailable || model.initializationError != null) {
                            add(
                                stringResource(
                                    if (model.isCoreAvailable) {
                                        Res.string.settings_core_config_failed
                                    } else {
                                        Res.string.settings_core_unavailable
                                    },
                                ) + " · " + (model.initializationError
                                        ?: stringResource(Res.string.settings_core_unavailable_body)),
                            )
                        }
                        window.error?.let { failure ->
                            add(settingsFailureText(failure))
                        }
                    }
                    if (errors.isNotEmpty()) {
                        SettingsErrorBanner(
                            message = errors.first(),
                            onRetry = model::retrySettings,
                        )
                    }
                    AnimatedContent(
                        targetState = section,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            fograinPageTransition(forward = state.settingsWindow.sectionForward)
                        },
                        label = "settings-section",
                    ) { target ->
                        SectionPage(
                            model = model,
                            state = state,
                            section = target,
                            showTitle = showTitle,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Compact layout (phone / narrow window)
// ---------------------------------------------------------------------------

@Composable
private fun CompactSettings(model: AppModel, state: AppUiState) {
    AnimatedContent(
        targetState = state.settingsWindow.detailOpen,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { fograinPageTransition(forward = targetState) },
        label = "settings-compact",
    ) { detail ->
        if (detail) {
            CompactSettingsDetail(model, state)
        } else {
            CompactSettingsList(model, state)
        }
    }
}

@Composable
private fun CompactSettingsList(model: AppModel, state: AppUiState) {
    val topInset = LocalSettingsTopInset.current
    val account = state.account
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SettingsCompactListTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 18.dp, top = topInset + 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(
                onClick = model::settingsBack,
                modifier = Modifier.testTag(SettingsCompactCloseTag),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.settings_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(Res.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        SettingsProfileCard(
            name = account.displayName,
            subtitle = account.selected?.let { selected ->
                when (selected.kind) {
                    "offline" -> stringResource(Res.string.account_kind_offline)
                    "yggdrasil" -> selected.serverName
                        ?: stringResource(Res.string.account_kind_yggdrasil)

                    else -> stringResource(Res.string.account_kind_microsoft)
                }
            } ?: stringResource(Res.string.settings_account_local),
            initial = account.initial,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            SettingsNavGroup(
                label = stringResource(Res.string.settings_group_app),
                sections = listOf(
                    SettingsSection.APPEARANCE,
                    SettingsSection.GENERAL,
                    SettingsSection.JAVA,
                ),
                active = null,
                onSelect = model::selectSettingsSection,
            )
            SettingsNavGroup(
                label = stringResource(Res.string.settings_group_data),
                sections = listOf(
                    SettingsSection.DOWNLOAD,
                    SettingsSection.NETWORK,
                    SettingsSection.STORAGE,
                ),
                active = null,
                onSelect = model::selectSettingsSection,
            )
            SettingsNavGroup(
                label = stringResource(Res.string.settings_group_about),
                sections = listOf(SettingsSection.ABOUT),
                active = null,
                onSelect = model::selectSettingsSection,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CompactSettingsDetail(model: AppModel, state: AppUiState) {
    val topInset = LocalSettingsTopInset.current
    val section = state.settingsWindow.section
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 18.dp, top = topInset + 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(
                onClick = model::settingsBack,
                modifier = Modifier.testTag(SettingsCompactBackTag),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.settings_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            AnimatedContent(
                targetState = section,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                },
                label = "settings-title",
            ) { target ->
                Text(
                    text = target.title(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        val window = state.settingsWindow
        if (window.isLoading && !window.loaded) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
            }
        } else {
            if (window.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            window.error?.let { failure ->
                SettingsErrorBanner(
                    message = settingsFailureText(failure),
                    onRetry = model::retrySettings,
                )
            }
            AnimatedContent(
                targetState = section,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    fograinPageTransition(forward = state.settingsWindow.sectionForward)
                },
                label = "settings-compact-section",
            ) { target ->
                SectionPage(
                    model = model,
                    state = state,
                    section = target,
                    showTitle = false,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section dispatch
// ---------------------------------------------------------------------------

@Composable
private fun SectionPage(
    model: AppModel,
    state: AppUiState,
    section: SettingsSection,
    showTitle: Boolean,
) {
    when (section) {
        SettingsSection.APPEARANCE -> AppearanceSettingsPage(model, state, showTitle)
        SettingsSection.GENERAL -> GeneralSettingsPage(model, state, showTitle)
        SettingsSection.JAVA -> JavaRuntimeScreen(
            model = model,
            state = state,
            embedded = true,
            showTitle = showTitle,
        )
        SettingsSection.DOWNLOAD -> DownloadSettingsPage(model, state, showTitle)
        SettingsSection.NETWORK -> NetworkSettingsPage(model, state, showTitle)
        SettingsSection.STORAGE -> StorageSettingsPage(model, state, showTitle)
        SettingsSection.ABOUT -> AboutSettingsPage(model, state, showTitle)
    }
}
