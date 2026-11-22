package com.fograin.launcher.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
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
import com.fograin.launcher.resources.settings_account_local
import com.fograin.launcher.resources.settings_adjust_sidebar
import com.fograin.launcher.resources.settings_back
import com.fograin.launcher.resources.settings_close
import com.fograin.launcher.resources.settings_core_config_failed
import com.fograin.launcher.resources.settings_core_unavailable
import com.fograin.launcher.resources.settings_core_unavailable_body
import com.fograin.launcher.resources.settings_group_about
import com.fograin.launcher.resources.settings_group_app
import com.fograin.launcher.resources.settings_group_data
import com.fograin.launcher.resources.settings_title
import com.fograin.launcher.resources.settings_window_title
import com.fograin.launcher.ui.components.FograinSnackbarHost
import com.fograin.launcher.ui.components.SettingsRootTransitionMillis
import com.fograin.launcher.ui.components.contentPanelShape
import com.fograin.launcher.ui.components.fograinSettingsTransition
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.theme.FograinTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

const val SettingsWindowTag: String = "settings-window"
const val SettingsSidebarTag: String = "settings-sidebar"
const val SettingsSidebarPageTag: String = "settings-sidebar-page"
const val SettingsSplitterTag: String = "settings-splitter"
const val SettingsContentTag: String = "settings-content"
const val SettingsContentPageTag: String = "settings-content-page"
const val SettingsWindowBackTag: String = "settings-window-back"
const val SettingsCompactListTag: String = "settings-compact-list"
const val SettingsCompactBackTag: String = "settings-compact-back"
const val SettingsCompactCloseTag: String = "settings-compact-close"
fun settingsNavTag(section: SettingsSection): String = "settings-nav-${section.name.lowercase()}"

/**
 * Extra top space reserved for native window chrome (macOS traffic lights).
 * The desktop shell provides this while the in-window settings page is shown;
 * Android and tests keep the 0 dp default.
 */
val LocalSettingsTopInset = staticCompositionLocalOf { 0.dp }

/**
 * Proportion band inspired by Ani: each pane has a 32 % floor, while the
 * sidebar rests at a third of the window. Dragging may cross the floor all the
 * way to either edge, but releasing past it glides back to that floor.
 */
private const val PaneMinRatio = 0.32f
private const val DefaultSidebarRatio = 1f / 3f

private val SplitterWidth = 18.dp

/**
 * Part of the divider's overscroll the pages follow. Slightly below 1 so a
 * page visibly lags behind the divider: it slides out of the window while the
 * divider catches up and hides the rest.
 */
private const val PagePushFactor = 0.7f

/** Accelerate-then-decelerate glide back to the ratio floor. */
private const val ReturnDurationMillis = 300

/** Short settle after the tiny overshoot, so the divider comes to rest. */
private const val SettleDurationMillis = 110

/** "一丁点" bounce: fixed, so a long drag back does not overshoot wildly. */
private val ReturnBounce = 4.dp

/**
 * Fixed widths and pushes for the two pages of the split view.
 *
 * Both pages are flexible inside the shared pane band. A page is frozen and
 * pushed out only when its pane drops below the 32 % floor: the sidebar page
 * leaves through the left window edge, the content page through the right
 * edge. At the resting limits both pages fit their panes exactly, so returning
 * to a limit never leaves the sidebar clipped at the window edge.
 */
internal data class SplitPaneMetrics(
    val leftPageWidth: Dp,
    val leftPush: Dp,
    val rightPageWidth: Dp,
    val rightPush: Dp,
)

internal fun splitPaneMetrics(
    windowWidth: Dp,
    displayedWidth: Dp,
    minPaneWidth: Dp,
): SplitPaneMetrics {
    val rightVisibleWidth = (windowWidth - displayedWidth).coerceAtLeast(0.dp)
    // Like the content page, the sidebar page is only frozen once the pane
    // drops below the shared floor. At the resting 32 % limit it therefore
    // still fits exactly and keeps its normal left padding; the push-out
    // starts only while the divider is dragged past that limit.
    val leftPageWidth = maxOf(displayedWidth, minPaneWidth)
    val leftPush = ((displayedWidth - leftPageWidth) * PagePushFactor)
        .coerceAtMost(0.dp)
    val rightPageWidth = maxOf(rightVisibleWidth, minPaneWidth)
    // Mirror of the left push: in window coordinates the content page moves
    // right with only 70 % of the divider's travel. Relative to its pane that
    // is -30 % of the overscroll, so the sidebar covers its left edge while
    // the window clips its right edge.
    val rightOverscroll = (minPaneWidth - rightVisibleWidth).coerceAtLeast(0.dp)
    val rightPush = -(rightOverscroll * (1f - PagePushFactor))
    return SplitPaneMetrics(
        leftPageWidth = leftPageWidth,
        leftPush = leftPush,
        rightPageWidth = rightPageWidth,
        rightPush = rightPush,
    )
}

private val CompactBreakpoint = 820.dp

/**
 * The in-window settings surface.
 *
 * The window background is the sidebar colour, so the content panel's rounded
 * left corners reveal the sidebar instead of a stripe of another shade. Above
 * [CompactBreakpoint] the layout is a fixed two-pane split view. The splitter
 * resizes the sidebar inside a proportional band; dragging past either limit
 * stretches the pane with resistance and springs back to the limit on release.
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
            model.settings.load()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SettingsWindowTag),
    ) {
        val compact = maxWidth < CompactBreakpoint
        LaunchedEffect(compact) { model.settings.setCompact(compact) }

        // 0 = fully outside, 1 = at rest. The desktop split view slides its
        // two panes in from opposite edges; the compact page slides as one
        // sheet from the right. The same Animatable drives the exit in
        // reverse, which keeps the layout from jumping between frames.
        val transition = remember {
            Animatable(if (state.settingsWindow.open) 0f else 1f)
        }
        val firstComposition = remember { mutableStateOf(true) }
        LaunchedEffect(state.settingsWindow.open) {
            val open = state.settingsWindow.open
            if (firstComposition.value) {
                firstComposition.value = false
                if (!open) {
                    // Standalone/preview use: show the settings surface as-is.
                    transition.snapTo(1f)
                    return@LaunchedEffect
                }
            }
            transition.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = tween(
                    durationMillis = SettingsRootTransitionMillis,
                    easing = EaseInOut,
                ),
            )
        }

        if (compact) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = maxWidth * (1f - transition.value)),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .imePadding(),
                ) {
                    CompactSettings(model = model, state = state)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .imePadding(),
            ) {
                WideSettings(
                    model = model,
                    state = state,
                    transitionProgress = transition.value,
                )
            }
        }
        FograinSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(18.dp),
        )
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
private fun WideSettings(
    model: AppModel,
    state: AppUiState,
    transitionProgress: Float,
) {
    val preferences = remember { platformServices().preferences }
    val savedWidth = remember {
        preferences.settingsSidebarWidth.takeIf { it > 0f }?.dp
    }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var bounceJob by remember { mutableStateOf<Job?>(null) }
    val bounceAnim = remember { Animatable(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowWidth = maxWidth
        // The handle floats over the divider, so the pane may travel all the
        // way to the window edge; the sidebar can cover the whole page
        // without leaving a splitter-wide sliver behind.
        val maxPaneWidth = windowWidth
        val minPaneWidth = (windowWidth * PaneMinRatio).coerceAtMost(maxPaneWidth)
        val maxSidebarWidth = (windowWidth - SplitterWidth - minPaneWidth)
            .coerceAtLeast(minPaneWidth)
        val defaultLeftWidth = windowWidth * DefaultSidebarRatio
        val defaultRightWidth = (windowWidth - defaultLeftWidth).coerceAtLeast(0.dp)

        var paneWidth by remember {
            mutableStateOf(
                (savedWidth ?: defaultLeftWidth).coerceIn(minPaneWidth, maxSidebarWidth),
            )
        }

        // Window changes clamp the resting width directly; the splitter only
        // animates as the result of an explicit drag past a ratio floor.
        LaunchedEffect(windowWidth) {
            bounceJob?.cancel()
            if (!dragging) {
                paneWidth = paneWidth.coerceIn(minPaneWidth, maxSidebarWidth)
            }
        }

        val displayedWidth = paneWidth.coerceIn(0.dp, maxPaneWidth)
        val rightVisibleWidth = (windowWidth - displayedWidth).coerceAtLeast(0.dp)
        val enterSlide = 1f - transitionProgress

        // The content page follows the divider down to the shared pane
        // floor, so it only freezes and slides under the sidebar once the
        // divider has crossed that limit; before the limit it simply
        // resizes with its pane.
        val pages = splitPaneMetrics(
            windowWidth = windowWidth,
            displayedWidth = displayedWidth,
            minPaneWidth = minPaneWidth,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                ClippedPage(
                    pageWidth = pages.leftPageWidth,
                    push = pages.leftPush,
                    modifier = Modifier
                        .width(displayedWidth)
                        .fillMaxHeight()
                        .offset(x = -displayedWidth * enterSlide)
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag(SettingsSidebarTag),
                ) {
                    SettingsSidebar(
                        model = model,
                        state = state,
                        active = state.settingsWindow.section,
                        modifier = Modifier.fillMaxSize().testTag(SettingsSidebarPageTag),
                    )
                }
                SettingsContentPane(
                    model = model,
                    state = state,
                    section = state.settingsWindow.section,
                    pageWidth = pages.rightPageWidth,
                    pagePush = pages.rightPush,
                    inset = displayedWidth > 0.5.dp,
                    showTitle = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .offset(x = rightVisibleWidth * enterSlide),
                )
            }
            // The handle floats over the page boundary instead of taking
            // layout space, so when the sidebar is pushed fully away the
            // content card's corner can coincide with the window corner.
            SettingsSplitter(
                modifier = Modifier
                    .offset(x = displayedWidth)
                    .alpha(transitionProgress),
                dragging = dragging,
                onDragStart = {
                    // Grabbing during the return glide takes over from the
                    // current visible width.
                    bounceJob?.cancel()
                    dragging = true
                    paneWidth = displayedWidth
                },
                onDrag = { delta ->
                    paneWidth = (paneWidth + delta).coerceIn(0.dp, maxPaneWidth)
                },
                onDragEnd = {
                    dragging = false
                    val released = paneWidth.coerceIn(0.dp, maxPaneWidth)
                    val target = released.coerceIn(minPaneWidth, maxSidebarWidth)
                    preferences.settingsSidebarWidth = target.value
                    if (target != released) {
                        // Past a side's 32 % floor: ease back with a tiny
                        // overshoot. The main leg uses EaseInOut (accelerate,
                        // then decelerate); the fixed 4 dp overshoot is a
                        // small bounce that does not grow with the drag
                        // distance.
                        bounceJob?.cancel()
                        bounceJob = scope.launch {
                            val distance = if (target > released) {
                                target - released
                            } else {
                                released - target
                            }
                            val bounce = minOf(ReturnBounce, distance)
                            val direction = if (target > released) 1f else -1f
                            val overshoot = (target + bounce * direction)
                                .coerceIn(0.dp, maxPaneWidth)
                            bounceAnim.snapTo(released.value)
                            bounceAnim.animateTo(
                                targetValue = overshoot.value,
                                animationSpec = tween(
                                    durationMillis = ReturnDurationMillis,
                                    easing = EaseInOut,
                                ),
                            ) { paneWidth = value.dp }
                            bounceAnim.animateTo(
                                targetValue = target.value,
                                animationSpec = tween(
                                    durationMillis = SettleDurationMillis,
                                    easing = EaseInOut,
                                ),
                            ) { paneWidth = value.dp }
                        }
                    }
                    // Released inside the band: stay exactly at the pointer's
                    // position; no snap and no extra animation.
                },
            )
        }
    }
}

@Composable
private fun SettingsSplitter(
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Dp) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val description = stringResource(Res.string.settings_adjust_sidebar)
    val handleWidth by animateDpAsState(
        targetValue = if (dragging) 9.dp else 6.dp,
        animationSpec = tween(140),
        label = "splitter-width",
    )
    val handleHeight by animateDpAsState(
        targetValue = if (dragging) 84.dp else 64.dp,
        animationSpec = tween(160),
        label = "splitter-height",
    )
    val handleColor by animateColorAsState(
        targetValue = if (dragging) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
        },
        animationSpec = tween(140),
        label = "splitter-color",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(18.dp)
            .testTag(SettingsSplitterTag)
            .semantics { contentDescription = description }
            .pointerHoverIcon(resizeHorizontalPointerIcon())
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    currentOnDragStart()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        // `positionChange` is measured in the gesture's own
                        // coordinate space, so it stays correct while the
                        // splitter itself moves with the sidebar.
                        val delta = change.positionChange().x
                        if (delta != 0f) {
                            change.consume()
                            currentOnDrag(with(density) { delta.toDp() })
                        }
                    }
                    currentOnDragEnd()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(handleWidth)
                .height(handleHeight)
                .clip(RoundedCornerShape(50))
                .background(handleColor),
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

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: close/back + title. The traffic-light strip on macOS sits in
            // `topInset`, so the row starts below the native buttons.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 18.dp, top = topInset + 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = model.settings::back,
                    modifier = Modifier.size(42.dp).testTag(SettingsWindowBackTag),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(Res.string.settings_back),
                        modifier = Modifier.size(23.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = stringResource(Res.string.settings_window_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            SettingsProfileCard(
                name = account.displayName,
                subtitle = accountSubtitle(account.selected?.kind, account.selected?.serverName),
                initial = account.initial,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
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
                    active = active,
                    onSelect = model.settings::select,
                )
                SettingsNavGroup(
                    label = stringResource(Res.string.settings_group_data),
                    sections = listOf(
                        SettingsSection.DOWNLOAD,
                        SettingsSection.NETWORK,
                        SettingsSection.STORAGE,
                    ),
                    active = active,
                    onSelect = model.settings::select,
                )
            }

            SettingsNavGroup(
                label = stringResource(Res.string.settings_group_about),
                sections = listOf(SettingsSection.ABOUT),
                active = active,
                onSelect = model.settings::select,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Text(
                text = "v${state.settings.appVersion} · ${language.nativeName()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun accountSubtitle(kind: String?, serverName: String?): String = when (kind) {
    "offline" -> stringResource(Res.string.account_kind_offline)
    "yggdrasil" -> serverName ?: stringResource(Res.string.account_kind_yggdrasil)
    null -> stringResource(Res.string.settings_account_local)
    else -> stringResource(Res.string.account_kind_microsoft)
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
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 14.dp, top = 20.dp, bottom = 6.dp),
            )
        }
        sections.forEach { section ->
            SettingsNavItem(
                section = section,
                selected = section == active,
                onClick = { onSelect(section) },
            )
            Spacer(Modifier.height(4.dp))
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
        shape = RoundedCornerShape(24.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = section.title(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Account header without a card background, as in the reference layout. */
@Composable
private fun SettingsProfileCard(
    name: String,
    subtitle: String,
    initial: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
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

/**
 * Hosts a page at a fixed [pageWidth] inside a pane, with [push] shifting it
 * horizontally. Anything outside the pane is clipped.
 *
 * This is deliberately a `Layout` instead of a `Box` + `requiredWidth`: a Box
 * centres an oversized child inside the smaller pane, while the split view
 * needs exact control over which edge of the page stays anchored to the
 * window. The page is laid out once at its resting width, so dragging the
 * divider never re-flows its text into a narrower column; it only reveals or
 * hides parts of the page.
 */
@Composable
private fun ClippedPage(
    pageWidth: Dp,
    push: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.width(pageWidth).fillMaxHeight()) { content() }
        },
        modifier = modifier.clipToBounds(),
    ) { measurables, constraints ->
        val width = pageWidth.roundToPx().coerceAtLeast(0)
        val page = measurables.first().measure(
            Constraints(
                minWidth = width,
                maxWidth = width,
                minHeight = constraints.minHeight,
                maxHeight = constraints.maxHeight,
            ),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            page.place(push.roundToPx(), 0)
        }
    }
}

@Composable
private fun SettingsContentPane(
    model: AppModel,
    state: AppUiState,
    section: SettingsSection,
    pageWidth: Dp,
    pagePush: Dp,
    inset: Boolean,
    showTitle: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .testTag(SettingsContentTag)
            // The panel's rounded left corner reveals this sidebar-coloured
            // backing. Keeping it on the panel (instead of the full window)
            // means it slides in together with the content and never covers
            // the home page during the entrance animation.
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = contentPanelShape(inset),
            // Slightly raised grey panel over the sidebar shade, matching the
            // cards on the home/settings surfaces.
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            ClippedPage(
                pageWidth = pageWidth,
                push = pagePush,
                modifier = Modifier.fillMaxSize(),
            ) {
                val window = state.settingsWindow
                Column(
                    modifier = Modifier
                        .padding(top = LocalSettingsTopInset.current)
                        .fillMaxSize()
                        .testTag(SettingsContentPageTag),
                ) {
                    // Never blank the page while the first settings.get is in
                    // flight (it used to pop a spinner in and out during the
                    // entrance animation). The content renders with its current
                    // values; failures surface through the banner below.
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
                        window.error?.let { failure -> add(settingsFailureText(failure)) }
                    }
                    if (errors.isNotEmpty()) {
                        SettingsErrorBanner(
                            message = errors.first(),
                            onRetry = model.settings::load,
                        )
                    }
                    AnimatedContent(
                        targetState = section,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = { fograinSettingsTransition() },
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
        transitionSpec = { fograinSettingsTransition() },
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
                .padding(start = 10.dp, end = 18.dp, top = topInset + 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = model.settings::back,
                modifier = Modifier.testTag(SettingsCompactCloseTag),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.settings_close),
                    modifier = Modifier.size(23.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(Res.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        SettingsProfileCard(
            name = account.displayName,
            subtitle = accountSubtitle(account.selected?.kind, account.selected?.serverName),
            initial = account.initial,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
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
                onSelect = model.settings::select,
            )
            SettingsNavGroup(
                label = stringResource(Res.string.settings_group_data),
                sections = listOf(
                    SettingsSection.DOWNLOAD,
                    SettingsSection.NETWORK,
                    SettingsSection.STORAGE,
                ),
                active = null,
                onSelect = model.settings::select,
            )
            SettingsNavGroup(
                label = stringResource(Res.string.settings_group_about),
                sections = listOf(SettingsSection.ABOUT),
                active = null,
                onSelect = model.settings::select,
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
                onClick = model.settings::back,
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
        window.error?.let { failure ->
            SettingsErrorBanner(
                message = settingsFailureText(failure),
                onRetry = model.settings::load,
            )
        }
        AnimatedContent(
            targetState = section,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { fograinSettingsTransition() },
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
