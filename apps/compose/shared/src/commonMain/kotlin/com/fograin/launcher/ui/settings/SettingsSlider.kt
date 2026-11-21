package com.fograin.launcher.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/**
 * Settings slider with a larger thumb and a track that thickens while the user
 * presses or drags it.
 *
 * Material's default slider is 20 dp tall with a 4 dp track, which is hard to
 * grab with a mouse. This one keeps the same interaction (click anywhere on the
 * track, drag the thumb, live value, commit on release) and animates to an
 * 8 dp track / 22 dp thumb while active.
 */
@Composable
internal fun SettingsSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
    modifier: Modifier = Modifier,
) {
    var active by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (!active) current = value
    }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val trackThickness by animateDpAsState(
        targetValue = if (active) 11.dp else 7.dp,
        animationSpec = tween(140),
        label = "slider-track",
    )
    val thumbRadius by animateDpAsState(
        targetValue = if (active) 13.dp else 11.dp,
        animationSpec = tween(140),
        label = "slider-thumb",
    )
    val density = LocalDensity.current
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val activeColor = MaterialTheme.colorScheme.primary
    val thumbColor = MaterialTheme.colorScheme.primary
    val thumbDotColor = MaterialTheme.colorScheme.surfaceContainerLow

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(current, valueRange)
                if (enabled) {
                    setProgress { target ->
                        val next = target.coerceIn(valueRange.start, valueRange.endInclusive)
                        current = next
                        currentOnValueChange(next)
                        currentOnFinished(next)
                        true
                    }
                }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        // Fixed inset: the animated thumb must not move the mapping under the
        // pointer (and must not restart the gesture coroutine).
        val insetPx = with(density) { 15.dp.toPx() }
        val usablePx = (widthPx - insetPx * 2f).coerceAtLeast(1f)
        val fraction = if (valueRange.endInclusive > valueRange.start) {
            ((current - valueRange.start) / (valueRange.endInclusive - valueRange.start))
                .coerceIn(0f, 1f)
        } else {
            0f
        }
        val thicknessPx = with(density) { trackThickness.toPx() }
        val thumbPx = with(density) { thumbRadius.toPx() }

        Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
            val centerY = size.height / 2f
            drawCircle(
                color = activeColor.copy(alpha = 0.18f),
                radius = thumbPx + 5.dp.toPx(),
                center = Offset(insetPx + usablePx * fraction, centerY),
            )
            drawLine(
                color = trackColor,
                start = Offset(insetPx, centerY),
                end = Offset(insetPx + usablePx, centerY),
                strokeWidth = thicknessPx,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = activeColor,
                start = Offset(insetPx, centerY),
                end = Offset(insetPx + usablePx * fraction, centerY),
                strokeWidth = thicknessPx,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = thumbColor,
                radius = thumbPx,
                center = Offset(insetPx + usablePx * fraction, centerY),
            )
            drawCircle(
                color = thumbDotColor,
                radius = thumbPx * 0.34f,
                center = Offset(insetPx + usablePx * fraction, centerY),
            )
        }

        if (enabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(valueRange) {
                        val hitInset = 15.dp.toPx()
                        fun update(x: Float) {
                            val usable = (size.width - hitInset * 2f).coerceAtLeast(1f)
                            val raw = ((x - hitInset) / usable).coerceIn(0f, 1f)
                            val next = valueRange.start +
                                raw * (valueRange.endInclusive - valueRange.start)
                            current = next
                            currentOnValueChange(next)
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            active = true
                            update(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes
                                    .firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                update(change.position.x)
                            }
                            active = false
                            currentOnFinished(current)
                        }
                    },
            )
        }
    }
}
