package com.fograin.launcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Keeps a modal sheet composed while it slides out.
 *
 * The app state drops the sheet's payload the moment the user closes it
 * (`instanceSettingsId = null`, …), so a naive `if (payload != null)` removes
 * the `ModalBottomSheet` before Material can run its exit animation. This host
 * retains the last payload, plays [SheetState.hide], and only then lets the
 * sheet leave the composition. Opening, closing and Esc/back all animate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : Any> AnimatedSheetHost(
    value: T?,
    onDismissRequest: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    var retained by remember { mutableStateOf(value) }
    var composed by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (value != null && retained !== value) retained = value
    val rendered = retained

    LaunchedEffect(value) {
        if (value != null) {
            if (composed) sheetState.show()
            composed = true
        } else if (composed) {
            sheetState.hide()
            composed = false
            retained = null
        } else {
            retained = null
        }
    }

    val sheetContent = rendered
    if (composed && sheetContent != null) {
        FograinBottomSheet(
            sheetState = sheetState,
            onDismissRequest = onDismissRequest,
        ) {
            content(sheetContent)
        }
    }
}

/**
 * Shared sheet chrome: surface tokens, rounded top corners, drag handle and a
 * short content fade/rise so the sheet never looks like a hard cut.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FograinBottomSheet(
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 260,
                delayMillis = 60,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f),
        sheetMaxWidth = 720.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            CircleShape,
                        ),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 22.dp.toPx()
            },
        ) {
            content()
        }
    }
}
