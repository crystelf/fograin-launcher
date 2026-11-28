package com.fograin.launcher.ui.community

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_image
import com.fograin.launcher.resources.community_login_close
import com.fograin.launcher.resources.community_save_media
import com.fograin.launcher.ui.components.FograinMotion
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

const val CommunityImageDialogTag: String = "community-image-dialog"
const val CommunityZoomableImageTag: String = "community-zoomable-image"

/**
 * Full-screen image with direct-manipulation zoom and pan.
 *
 * Pinch (or the mouse wheel) zooms, dragging pans a zoomed image, a single
 * tap closes the viewer, a double tap toggles between fitted and magnified,
 * and a downward swipe closes it — the same gestures with a mouse or a
 * finger. The transform is applied with `graphicsLayer`, so the decoded
 * bitmap is reused and offset bounds are recomputed from the laid-out size.
 */
@Composable
fun CommunityZoomableImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    resetKey: Any? = url,
    onDismiss: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var dragY by remember(resetKey) { mutableFloatStateOf(0f) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var zoomJob by remember(resetKey) { mutableStateOf<Job?>(null) }
    var wheelJob by remember(resetKey) { mutableStateOf<Job?>(null) }
    var dismissJob by remember(resetKey) { mutableStateOf<Job?>(null) }
    var revealed by remember(resetKey) { mutableStateOf(false) }
    val latestScale by rememberUpdatedState(scale)
    val latestOffset by rememberUpdatedState(offset)
    val latestBounds by rememberUpdatedState(bounds)
    val dismissThreshold = with(LocalDensity.current) { 120.dp.toPx() }
    val openProgress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.94f,
        animationSpec = tween(durationMillis = 220),
        label = "viewer-open",
    )
    LaunchedEffect(resetKey) {
        revealed = true
        zoomJob?.cancel()
        wheelJob?.cancel()
        dismissJob?.cancel()
    }

    fun clampOffset(candidate: Offset, currentScale: Float): Offset {
        val maxX = (bounds.width * (currentScale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (bounds.height * (currentScale - 1f) / 2f).coerceAtLeast(0f)
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY),
        )
    }

    fun animateZoom(targetScale: Float, reset: Boolean = false) {
        zoomJob?.cancel()
        wheelJob?.cancel()
        val startScale = scale
        val startOffset = offset
        val target = targetScale.coerceIn(1f, 5f)
        val targetOffset = if (reset || target == 1f) {
            Offset.Zero
        } else {
            clampOffset(startOffset * (target / startScale), target)
        }
        if (abs(startScale - target) < 0.001f && startOffset == targetOffset) return
        zoomJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = FograinMotion.Layout,
            ) { fraction, _ ->
                val progress = fraction.coerceIn(0f, 1f)
                scale = startScale + (target - startScale) * progress
                offset = Offset(
                    x = startOffset.x + (targetOffset.x - startOffset.x) * progress,
                    y = startOffset.y + (targetOffset.y - startOffset.y) * progress,
                )
            }
            scale = target
            offset = targetOffset
        }
    }

    fun toggleZoom() {
        if (scale > 1.01f) animateZoom(1f, reset = true) else animateZoom(2.5f)
    }

    fun applyWheelZoom(delta: Float, focus: Offset) {
        wheelJob?.cancel()
        zoomJob?.cancel()
        dismissJob?.cancel()
        val startScale = latestScale
        val targetScale = (startScale * (1f - delta * 0.22f)).coerceIn(1f, 5f)
        if (abs(targetScale - startScale) < 0.001f) return
        if (latestBounds.width <= 0 || latestBounds.height <= 0) return
        val center = Offset(latestBounds.width / 2f, latestBounds.height / 2f)
        // Keep the pixel under the cursor anchored while the scale changes,
        // then ease between the two transforms instead of snapping.
        val point = (focus - center - latestOffset) / startScale
        val startOffset = latestOffset
        val maxX = (latestBounds.width * (targetScale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (latestBounds.height * (targetScale - 1f) / 2f).coerceAtLeast(0f)
        val targetOffset = Offset(
            x = (startOffset.x + point.x * (startScale - targetScale)).coerceIn(-maxX, maxX),
            y = (startOffset.y + point.y * (startScale - targetScale)).coerceIn(-maxY, maxY),
        )
        wheelJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = 140),
            ) { fraction, _ ->
                val progress = fraction.coerceIn(0f, 1f)
                scale = startScale + (targetScale - startScale) * progress
                offset = Offset(
                    x = startOffset.x + (targetOffset.x - startOffset.x) * progress,
                    y = startOffset.y + (targetOffset.y - startOffset.y) * progress,
                )
            }
            scale = targetScale
            offset = targetOffset
        }
    }

    fun settleSwipe() {
        dismissJob?.cancel()
        if (dragY > dismissThreshold) {
            dismissJob = scope.launch {
                val start = dragY
                val target = latestBounds.height.toFloat().coerceAtLeast(start + 1f)
                animate(
                    initialValue = start,
                    targetValue = target,
                    animationSpec = tween(durationMillis = 200),
                ) { value, _ ->
                    dragY = value
                }
                onDismiss()
            }
        } else if (dragY != 0f) {
            dismissJob = scope.launch {
                animate(
                    initialValue = dragY,
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.84f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) { value, _ ->
                    dragY = value
                }
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { bounds = it }
            .pointerInput(resetKey) {
                detectViewerGestures(
                    isZoomed = { latestScale > 1.01f },
                    onZoom = { zoomChange, panChange, _ ->
                        zoomJob?.cancel()
                        wheelJob?.cancel()
                        dismissJob?.cancel()
                        val next = (latestScale * zoomChange).coerceIn(1f, 5f)
                        scale = next
                        offset = clampOffset(
                            Offset(
                                x = latestOffset.x + panChange.x,
                                y = latestOffset.y + panChange.y,
                            ),
                            next,
                        )
                    },
                    onSwipe = { dy ->
                        wheelJob?.cancel()
                        // Follow the pointer both ways; upward travel keeps a
                        // little resistance so it reads as a rubber band.
                        dragY = if (dragY + dy >= 0f) {
                            dragY + dy
                        } else {
                            dragY + dy * 0.35f
                        }
                    },
                    onSwipeEnd = ::settleSwipe,
                )
            }
            .pointerInput(resetKey) {
                // Mouse wheel and trackpad scroll zoom around the pointer.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val delta = change.scrollDelta.y
                        if (delta != 0f) {
                            applyWheelZoom(delta, change.position)
                            change.consume()
                        }
                    }
                }
            }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onTap = { onDismiss() },
                    onDoubleTap = { toggleZoom() },
                )
            }
            .testTag(CommunityZoomableImageTag),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale * openProgress
                    scaleY = scale * openProgress
                    alpha = ((openProgress - 0.94f) / 0.06f).coerceIn(0f, 1f)
                    translationX = offset.x
                    translationY = offset.y + dragY
                },
        )
    }
}

/**
 * One gesture stream for the viewer: pinch/pan with two fingers, pan a zoomed
 * image with one finger, and swipe down to dismiss when fitted.
 *
 * Scroll (wheel) and tap/double-tap live in their own pointer nodes; this one
 * only consumes movement, so a plain tap still reaches [detectTapGestures].
 */
private suspend fun PointerInputScope.detectViewerGestures(
    isZoomed: () -> Boolean,
    onZoom: (zoom: Float, pan: Offset, focus: Offset) -> Unit,
    onSwipe: (dy: Float) -> Unit,
    onSwipeEnd: () -> Unit,
) {
    awaitEachGesture {
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        var zoom = 1f
        var pan = Offset.Zero
        var swiping = false
        do {
            val event = awaitPointerEvent()
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastTouchSlop) {
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                zoom *= zoomChange
                pan += panChange
                val zoomMotion = abs(1f - zoom) * centroidSize
                val panMotion = pan.getDistance()
                if (zoomMotion > touchSlop || panMotion > touchSlop) {
                    pastTouchSlop = true
                    // A fitted image only has one meaningful single-finger
                    // gesture left: swipe down to close.
                    swiping = !isZoomed() && event.changes.count { it.pressed } == 1
                }
            }
            if (pastTouchSlop) {
                if (swiping) {
                    val dy = event.changes.firstOrNull()
                        ?.let { it.position.y - it.previousPosition.y }
                        ?: 0f
                    if (dy != 0f) onSwipe(dy)
                } else if (zoomChange != 1f || panChange != Offset.Zero) {
                    onZoom(zoomChange, panChange, event.calculateCentroid())
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (swiping) onSwipeEnd()
    }
}

/** Single remote image dialog used for avatars and banners. */
@Composable
fun CommunityImageDialog(
    url: String,
    name: String,
    isSaving: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    title: String = name,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag(CommunityImageDialogTag),
        ) {
            CommunityZoomableImage(
                url = url,
                contentDescription = stringResource(Res.string.community_image),
                modifier = Modifier.fillMaxSize(),
                resetKey = url,
                onDismiss = onDismiss,
            )
            MediaViewerTopBar(
                title = title,
                isSaving = isSaving,
                onSave = onSave,
                onClose = onDismiss,
            )
        }
    }
}

@Composable
internal fun MediaViewerTopBar(
    title: String,
    isSaving: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val platformName = remember { platformServices().platformName }
    val statusBarInset = with(density) {
        WindowInsets.statusBars.getTop(density).toDp()
    }
    // The viewer is a dialog window: on macOS its content starts at the top
    // edge under the traffic lights, so reserve the title-bar strip here.
    val titleBarInset = if (platformName == "macos") 40.dp else 0.dp
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.42f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (statusBarInset > 0.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(statusBarInset)
                        .background(
                            if (lightSurface) {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                            } else {
                                Color.Transparent
                            },
                        ),
                )
            }
            if (titleBarInset > 0.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(titleBarInset),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.community_login_close),
                        tint = Color.White,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 56.dp),
                )
                IconButton(
                    onClick = onSave,
                    enabled = !isSaving,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .testTag(CommunityMediaSaveTag),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = stringResource(Res.string.community_save_media),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

