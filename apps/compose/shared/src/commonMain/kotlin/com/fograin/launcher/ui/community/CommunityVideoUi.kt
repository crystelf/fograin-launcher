package com.fograin.launcher.ui.community

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fograin.launcher.community.CommunityFile
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_close_video
import com.fograin.launcher.resources.community_mute_video
import com.fograin.launcher.resources.community_pause_video
import com.fograin.launcher.resources.community_play_video
import com.fograin.launcher.resources.community_unmute_video
import com.fograin.launcher.resources.community_video_error
import com.fograin.launcher.resources.community_video_exit_fullscreen
import com.fograin.launcher.resources.community_video_fullscreen
import com.fograin.launcher.resources.community_video_gesture_hint
import com.fograin.launcher.resources.community_video_preparing
import com.fograin.launcher.resources.community_video_volume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.math.roundToInt

const val CommunityInlineVideoTag: String = "community-inline-video"
const val CommunityVideoSurfaceTag: String = "community-video-surface"
const val CommunityVideoFullscreenOverlayTag: String = "community-video-fullscreen-overlay"
const val CommunityVideoPlayTag: String = "community-video-play"
const val CommunityVideoVolumeTag: String = "community-video-volume"
const val CommunityVideoVolumeSliderTag: String = "community-video-volume-slider"
const val CommunityVideoFullscreenTag: String = "community-video-fullscreen"
const val CommunityVideoSeekTag: String = "community-video-seek"
const val CommunityVideoCloseTag: String = "community-video-close"

/**
 * How long a tap waits for a second finger-down before it counts as a single
 * tap.
 *
 * The platform default (300 ms) makes entering and leaving fullscreen feel
 * delayed. Every video player with both gestures pays this debounce; 240 ms
 * keeps double-tap forgiving while the single tap answers promptly.
 */
private const val VideoDoubleTapWindowMillis: Long = 240L

/**
 * One video for the fullscreen host.
 *
 * Both surfaces drive the same controller, so entering fullscreen re-parents
 * the live player instead of loading the video again: playback, volume and
 * buffering state continue across the transition.
 */
internal data class CommunityVideoFullscreenRequest(
    val key: String,
    val controller: CommunityVideoController,
)

/**
 * Owns the window-level fullscreen player.
 *
 * The community feed hosts its video inside a `LazyColumn` item, so a
 * fullscreen dialog would either dim the feed to black or trap the player in
 * the content pane. Instead the shell provides one host above the navigation
 * chrome; the player slides over the whole window, and the outgoing animation
 * reveals the feed underneath.
 */
@Stable
class CommunityVideoFullscreenHost {
    internal var request by mutableStateOf<CommunityVideoFullscreenRequest?>(null)
        private set

    internal var closeRequest by mutableIntStateOf(0)
        private set

    val isOpen: Boolean get() = request != null

    internal fun show(request: CommunityVideoFullscreenRequest) {
        this.request = request
        closeRequest = 0
    }

    /** Asks the player to animate out; used by back, Escape and the window. */
    fun requestClose() {
        if (request != null) closeRequest++
    }

    internal fun finish() {
        request = null
    }
}

/** The shell-provided fullscreen host; `null` outside [com.fograin.launcher.ui.FograinApp]. */
val LocalCommunityVideoFullscreenHost: ProvidableCompositionLocal<CommunityVideoFullscreenHost?> =
    staticCompositionLocalOf { null }

/**
 * Draws the fullscreen player above the shell.
 *
 * Compose Media Player renders the native frames into a Compose surface on
 * every platform, so the same gesture layer and chrome slide over the feed.
 */
@Composable
fun CommunityVideoFullscreenOverlay(
    host: CommunityVideoFullscreenHost,
    modifier: Modifier = Modifier,
) {
    val request = host.request ?: return
    FullscreenVideoPlayer(request = request, host = host, modifier = modifier)
}

@Composable
private fun FullscreenVideoPlayer(
    request: CommunityVideoFullscreenRequest,
    host: CommunityVideoFullscreenHost,
    modifier: Modifier,
) {
    CommunityVideoImmersiveMode(enabled = true)

    val controller = request.controller
    val video = controller.state
    var lastVolume by remember(request.key) {
        mutableFloatStateOf(if (video.volume > 0.01f) video.volume else 1f)
    }
    LaunchedEffect(video.volume) {
        if (video.volume > 0.01f) lastVolume = video.volume
    }

    // Floating chrome: show on any pointer move, hide five seconds after the
    // pointer leaves it. Touch platforms keep it visible so the tap gestures
    // stay discoverable.
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeInteraction by remember { mutableIntStateOf(0) }
    var chromeHovered by remember { mutableStateOf(false) }
    val chromeSource = remember { MutableInteractionSource() }
    val hovering by chromeSource.collectIsHoveredAsState()
    LaunchedEffect(hovering) { chromeHovered = hovering }
    LaunchedEffect(chromeVisible, chromeInteraction, chromeHovered) {
        if (!communityVideoAutoHideChrome) return@LaunchedEffect
        if (chromeVisible && !chromeHovered) {
            delay(5_000)
            chromeVisible = false
        }
    }
    fun showChrome() {
        chromeVisible = true
        chromeInteraction++
    }

    // The native surface is re-parented from the inline player; wait one frame
    // so Android's view hierarchy is free before attaching it.
    var surfaceReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        surfaceReady = true
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(CommunityVideoFullscreenOverlayTag),
    ) {
        val density = LocalDensity.current
        val screenHeight = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val dismissThreshold = min(
            screenHeight * 0.18f,
            with(density) { 220.dp.toPx() },
        ).coerceAtLeast(with(density) { 96.dp.toPx() })

        val offset = remember { Animatable(screenHeight) }
        var dragging by remember { mutableStateOf(false) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var dismissing by remember { mutableStateOf(false) }
        var showHint by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()
        val displayedOffset = if (dragging) dragOffset else offset.value
        val backdropAlpha = (1f - displayedOffset / screenHeight).coerceIn(0f, 1f)

        fun dismiss() {
            if (dismissing) return
            dismissing = true
            scope.launch {
                offset.animateTo(
                    targetValue = screenHeight,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                )
                host.finish()
            }
        }

        LaunchedEffect(Unit) {
            offset.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            )
        }
        LaunchedEffect(host.closeRequest) {
            if (host.closeRequest > 0) dismiss()
        }
        LaunchedEffect(Unit) {
            delay(2_600)
            showHint = false
        }

        // The backdrop also swallows touches while the player slides down.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backdropAlpha))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, displayedOffset.roundToInt()) },
        ) {
            if (video.error != null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Text(
                        text = stringResource(Res.string.community_video_error),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(request.key) {
                                detectTapGestures(onTap = { dismiss() })
                            },
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    if (surfaceReady) {
                        CommunityVideoPlayer(
                            controller = controller,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (!video.rendered) {
                        VideoPlaceholder(Modifier.fillMaxSize())
                    } else if (video.buffering) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(30.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                    }
                }
                CompositionLocalProvider(
                    LocalViewConfiguration provides rememberShortDoubleTapConfiguration(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // Pointer moves wake the chrome without consuming
                            // the tap/drag detectors below.
                            .pointerInput(request.key) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Move) showChrome()
                                    }
                                }
                            }
                            .pointerInput(request.key) {
                                if (communityVideoTouchNavigation) {
                                    detectTapGestures(
                                        onTap = { dismiss() },
                                        onDoubleTap = { controller.togglePlayPause() },
                                    )
                                } else {
                                    detectTapGestures(
                                        onTap = {
                                            controller.togglePlayPause()
                                            showChrome()
                                        },
                                    )
                                }
                            }
                            .pointerInput(request.key) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        dragging = true
                                        dragOffset = offset.value
                                        scope.launch { offset.stop() }
                                    },
                                    onVerticalDrag = { _, amount ->
                                        dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                                    },
                                    onDragEnd = {
                                        val travelled = dragOffset
                                        scope.launch {
                                            offset.snapTo(travelled)
                                            dragging = false
                                            if (travelled >= dismissThreshold) {
                                                if (!dismissing) {
                                                    dismissing = true
                                                    offset.animateTo(
                                                        targetValue = screenHeight,
                                                        animationSpec = tween(
                                                            durationMillis = 220,
                                                            easing = FastOutSlowInEasing,
                                                        ),
                                                    )
                                                    host.finish()
                                                }
                                            } else if (!dismissing) {
                                                offset.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = spring(
                                                        dampingRatio = 0.86f,
                                                        stiffness = Spring.StiffnessMediumLow,
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        val travelled = dragOffset
                                        scope.launch {
                                            offset.snapTo(travelled)
                                            dragging = false
                                            if (!dismissing) {
                                                offset.animateTo(0f, spring(dampingRatio = 0.86f))
                                            }
                                        }
                                    },
                                )
                            },
                    )
                }
                AnimatedVisibility(
                    visible = showHint && communityVideoTouchNavigation,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(220)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        )
                        .padding(top = 22.dp),
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.46f),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.community_video_gesture_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !communityVideoAutoHideChrome || chromeVisible,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    CommunityVideoControls(
                        state = video,
                        fullscreen = true,
                        showTime = true,
                        onTogglePlay = controller::togglePlayPause,
                        onSeek = { fraction ->
                            controller.seekTo((fraction * video.durationMillis).toLong())
                        },
                        onVolumeChange = controller::setVolume,
                        onToggleMute = {
                            controller.setVolume(if (video.volume <= 0.01f) lastVolume else 0f)
                        },
                        onFullscreen = { dismiss() },
                        modifier = Modifier.hoverable(chromeSource),
                    )
                }
            }
        }
    }
}

/**
 * The inline feed player.
 *
 * The video fills the card and the floating chrome sits over its bottom edge.
 * On touch platforms a single tap enters fullscreen and a double tap toggles
 * playback; desktop uses a single click for play/pause and the fullscreen
 * button for the rest.
 */
@Composable
internal fun InlineVideoPlayer(
    playbackKey: String,
    file: CommunityFile,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(playbackKey) {
        InlineVideoPlayback.claim(playbackKey)
        onDispose { InlineVideoPlayback.release(playbackKey) }
    }
    LaunchedEffect(InlineVideoPlayback.activeKey) {
        if (InlineVideoPlayback.activeKey != null &&
            InlineVideoPlayback.activeKey != playbackKey
        ) {
            onClose()
        }
    }

    val controller = rememberCommunityVideoController(file.url)
    val video = controller.state
    val host = LocalCommunityVideoFullscreenHost.current
    val fullscreen = host?.request?.key == playbackKey

    // The fullscreen overlay owns the native surface while open; wait one
    // frame after closing so the overlay's view detaches first.
    var surfaceReady by remember(playbackKey) { mutableStateOf(!fullscreen) }
    LaunchedEffect(fullscreen) {
        if (fullscreen) {
            surfaceReady = false
        } else {
            withFrameNanos { }
            surfaceReady = true
        }
    }

    var lastVolume by remember(file.id) {
        mutableFloatStateOf(if (video.volume > 0.01f) video.volume else 1f)
    }
    LaunchedEffect(video.volume) {
        if (video.volume > 0.01f) lastVolume = video.volume
    }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(file.id) {
        runCatching { bringIntoView.bringIntoView() }
    }

    fun enterFullscreen() {
        val target = host ?: return
        target.show(
            CommunityVideoFullscreenRequest(
                key = playbackKey,
                controller = controller,
            ),
        )
    }

    // Desktop-only chrome auto-hide. Touch keeps the controls visible so the
    // tap-to-fullscreen gesture stays discoverable.
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeInteraction by remember { mutableIntStateOf(0) }
    var chromeHovered by remember { mutableStateOf(false) }
    val chromeSource = remember { MutableInteractionSource() }
    val hovering by chromeSource.collectIsHoveredAsState()
    LaunchedEffect(hovering) { chromeHovered = hovering }
    LaunchedEffect(chromeVisible, chromeInteraction, chromeHovered) {
        if (!communityVideoAutoHideChrome) return@LaunchedEffect
        if (chromeVisible && !chromeHovered) {
            delay(5_000)
            chromeVisible = false
        }
    }
    fun showChrome() {
        chromeVisible = true
        chromeInteraction++
    }

    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier
            .bringIntoViewRequester(bringIntoView)
            .testTag(CommunityInlineVideoTag),
        shape = shape,
        color = Color.Transparent,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().clip(shape)) {
            if (fullscreen) {
                // The window-level overlay owns the player surface.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            } else if (video.error != null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Text(
                        text = stringResource(Res.string.community_video_error),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                    VideoIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.community_close_video),
                        onClick = onClose,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    if (surfaceReady) {
                        CommunityVideoPlayer(
                            controller = controller,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(CommunityVideoSurfaceTag),
                        )
                    }
                    if (!video.rendered) {
                        VideoPlaceholder(Modifier.fillMaxSize())
                    } else if (video.buffering) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(26.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                    }
                }
                CompositionLocalProvider(
                    LocalViewConfiguration provides rememberShortDoubleTapConfiguration(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(playbackKey) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Move) showChrome()
                                    }
                                }
                            }
                            .pointerInput(playbackKey) {
                                if (communityVideoTouchNavigation) {
                                    detectTapGestures(
                                        onTap = {
                                            showChrome()
                                            enterFullscreen()
                                        },
                                        onDoubleTap = { controller.togglePlayPause() },
                                    )
                                } else {
                                    detectTapGestures(
                                        onTap = {
                                            controller.togglePlayPause()
                                            showChrome()
                                        },
                                    )
                                }
                            },
                    )
                }
                AnimatedVisibility(
                    visible = !communityVideoAutoHideChrome || chromeVisible,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    CommunityVideoControls(
                        state = video,
                        fullscreen = false,
                        showTime = false,
                        onTogglePlay = controller::togglePlayPause,
                        onSeek = { fraction ->
                            controller.seekTo((fraction * video.durationMillis).toLong())
                        },
                        onVolumeChange = controller::setVolume,
                        onToggleMute = {
                            controller.setVolume(if (video.volume <= 0.01f) lastVolume else 0f)
                        },
                        onFullscreen = { enterFullscreen() },
                        modifier = Modifier.hoverable(chromeSource),
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

/**
 * Floating video chrome: time, seek bar, play/pause, volume, fullscreen and
 * close over one short bottom gradient.
 *
 * The long bar is playback progress. Volume is a button at the bottom-right:
 * desktop expands its slider on wide players, touch expands it on tap.
 */
@Composable
fun CommunityVideoControls(
    state: CommunityVideoState,
    fullscreen: Boolean,
    showTime: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val muted = state.volume <= 0.01f
    var touchVolumeExpanded by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.32f to Color.Black.copy(alpha = 0.38f),
                    1f to Color.Black.copy(alpha = 0.82f),
                ),
            )
            .padding(top = 24.dp, start = 8.dp, end = 8.dp),
    ) {
        val desktop = communityVideoAutoHideChrome
        val showInlineVolume = desktop && maxWidth > 360.dp
        val showExpandedVolume = !desktop && touchVolumeExpanded
        Column(Modifier.fillMaxWidth()) {
            if (showTime) {
                Text(
                    text = videoTimeLabel(state),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                )
            }
            VideoSeekSlider(
                positionMillis = state.positionMillis,
                durationMillis = state.durationMillis,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(horizontal = 8.dp)
                    .testTag(CommunityVideoSeekTag),
            )
            if (showExpandedVolume) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    VideoVolumeSlider(
                        volume = state.volume,
                        label = stringResource(Res.string.community_video_volume),
                        onVolumeChange = onVolumeChange,
                        modifier = Modifier
                            .width(160.dp)
                            .testTag(CommunityVideoVolumeSliderTag),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VideoIconButton(
                    icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(
                        if (state.isPlaying) {
                            Res.string.community_pause_video
                        } else {
                            Res.string.community_play_video
                        },
                    ),
                    onClick = onTogglePlay,
                    modifier = Modifier.testTag(CommunityVideoPlayTag),
                )
                Spacer(Modifier.weight(1f))
                if (showInlineVolume) {
                    VideoVolumeSlider(
                        volume = state.volume,
                        label = stringResource(Res.string.community_video_volume),
                        onVolumeChange = onVolumeChange,
                        modifier = Modifier
                            .width(140.dp)
                            .testTag(CommunityVideoVolumeSliderTag),
                    )
                }
                VideoIconButton(
                    icon = if (muted) {
                        Icons.AutoMirrored.Rounded.VolumeOff
                    } else {
                        Icons.AutoMirrored.Rounded.VolumeDown
                    },
                    contentDescription = stringResource(
                        when {
                            desktop && muted -> Res.string.community_unmute_video
                            desktop -> Res.string.community_mute_video
                            else -> Res.string.community_video_volume
                        },
                    ),
                    onClick = {
                        if (desktop) {
                            onToggleMute()
                        } else {
                            touchVolumeExpanded = !touchVolumeExpanded
                        }
                    },
                    modifier = Modifier.testTag(CommunityVideoVolumeTag),
                )
                VideoIconButton(
                    icon = if (fullscreen) {
                        Icons.Rounded.FullscreenExit
                    } else {
                        Icons.Rounded.Fullscreen
                    },
                    contentDescription = stringResource(
                        if (fullscreen) {
                            Res.string.community_video_exit_fullscreen
                        } else {
                            Res.string.community_video_fullscreen
                        },
                    ),
                    onClick = onFullscreen,
                    modifier = Modifier.testTag(CommunityVideoFullscreenTag),
                )
                if (onClose != null) {
                    VideoIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.community_close_video),
                        onClick = onClose,
                        modifier = Modifier.testTag(CommunityVideoCloseTag),
                    )
                }
            }
        }
    }
}

/**
 * Placeholder shown until the decoder produces its first frame.
 *
 * Its colour follows the app theme (near-black in dark mode, white in light
 * mode) so the loading state never flashes a colour from the opposite scheme.
 */
@Composable
private fun VideoPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            Text(
                text = stringResource(Res.string.community_video_preparing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = Color.White.copy(alpha = 0.22f)),
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.45f),
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun VideoVolumeSlider(
    volume: Float,
    label: String,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = volume.coerceIn(0f, 1f)
    val accent = MaterialTheme.colorScheme.primary
    val currentOnVolumeChange by rememberUpdatedState(onVolumeChange)
    Canvas(
        modifier = modifier
            .height(56.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    onVolumeChange(target)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (size.width > 0) {
                        currentOnVolumeChange((down.position.x / size.width).coerceIn(0f, 1f))
                    }
                    horizontalDrag(down.id) { change ->
                        change.consume()
                        if (size.width > 0) {
                            currentOnVolumeChange((change.position.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                }
            },
    ) {
        val centerY = size.height / 2f
        val trackHeight = 4.dp.toPx()
        val trackRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.30f),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = trackRadius,
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.95f),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width * fraction, trackHeight),
            cornerRadius = trackRadius,
        )
        val thumbRadius = 7.dp.toPx()
        drawCircle(
            color = accent,
            radius = thumbRadius,
            center = Offset(
                x = (size.width * fraction).coerceIn(
                    thumbRadius,
                    (size.width - thumbRadius).coerceAtLeast(thumbRadius),
                ),
                y = centerY,
            ),
        )
    }
}

/**
 * Draggable playback progress bar.
 *
 * The local fraction follows the pointer while dragging; the seek is committed
 * on release so the native player is not flooded with seeks.
 */
@Composable
private fun VideoSeekSlider(
    positionMillis: Long,
    durationMillis: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (durationMillis > 0) {
        (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
    } else {
        0f
    }
    Canvas(
        modifier = modifier
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    onSeek(target)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (size.width > 0) {
                        dragFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                    }
                    horizontalDrag(down.id) { change ->
                        change.consume()
                        if (size.width > 0) {
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                    dragFraction?.let(onSeek)
                    dragFraction = null
                }
            },
    ) {
        val centerY = size.height / 2f
        val trackHeight = 4.dp.toPx()
        val trackRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.30f),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = trackRadius,
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.95f),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width * fraction, trackHeight),
            cornerRadius = trackRadius,
        )
        val thumbRadius = 7.dp.toPx()
        drawCircle(
            color = accent,
            radius = thumbRadius,
            center = Offset(
                x = (size.width * fraction).coerceIn(
                    thumbRadius,
                    (size.width - thumbRadius).coerceAtLeast(thumbRadius),
                ),
                y = centerY,
            ),
        )
    }
}

internal fun videoTimeLabel(state: CommunityVideoState): String {
    val position = formatVideoTime(state.positionMillis)
    return if (state.durationMillis > 0) {
        "$position / ${formatVideoTime(state.durationMillis)}"
    } else {
        position
    }
}

/**
 * `m:ss` below an hour and `h:mm:ss` above it.
 *
 * The player never assumes a duration is known: live or still-loading media
 * shows only the elapsed time.
 */
internal fun formatVideoTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    fun Long.twoDigits(): String = if (this < 10) "0$this" else toString()
    return if (hours > 0) {
        "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
    } else {
        "$minutes:${seconds.twoDigits()}"
    }
}

/**
 * Shortens the platform double-tap window for the player's tap layer only.
 *
 * Delegating keeps touch slop, long-press timing and fling limits exactly as
 * the host defines them.
 */
private class ShortDoubleTapViewConfiguration(
    base: ViewConfiguration,
) : ViewConfiguration by base {
    override val doubleTapTimeoutMillis: Long = VideoDoubleTapWindowMillis
    override val doubleTapMinTimeMillis: Long = 40L
}

@Composable
private fun rememberShortDoubleTapConfiguration(): ViewConfiguration {
    // Read the platform value before the provider installs the short window.
    val base = LocalViewConfiguration.current
    return remember(base) { ShortDoubleTapViewConfiguration(base) }
}

private object InlineVideoPlayback {
    var activeKey by mutableStateOf<String?>(null)
        private set

    fun claim(key: String) {
        activeKey = key
    }

    fun release(key: String) {
        if (activeKey == key) activeKey = null
    }
}
