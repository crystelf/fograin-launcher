package com.fograin.launcher.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fograin.launcher.ui.components.WindowChromeMetrics
import java.awt.MouseInfo
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlin.math.abs

/**
 * Which kind of frame the shell should create.
 *
 * macOS keeps the native traffic lights inside a transparent title bar, which
 * is exactly the look the app wants and costs no custom hit-testing. Windows
 * and Linux have no equivalent native traffic lights, so they get an
 * undecorated window whose header is drawn by Compose instead.
 */
internal enum class WindowChromeKind {
    NATIVE,
    CUSTOM,
}

/**
 * [forceCustom] is a development aid: `-Dfograin.window.chrome=custom` renders
 * the Windows/Linux chrome on macOS so the layout can be inspected without a
 * Windows machine. It is never set by the released application.
 */
internal fun windowChromeKind(osName: String, forceCustom: Boolean = false): WindowChromeKind =
    if (osName.contains("mac", ignoreCase = true) && !forceCustom) {
        WindowChromeKind.NATIVE
    } else {
        WindowChromeKind.CUSTOM
    }

/**
 * Keyboard commands the native [androidx.compose.ui.window.MenuBar] used to
 * own. macOS still has the real menu bar, so this table is only consulted by
 * the borderless Windows/Linux shell.
 */
internal enum class DesktopCommand {
    NEW_INSTANCE,
    OPEN_LIBRARY,
    OPEN_SETTINGS,
    QUIT,
    GLOBAL_SEARCH,
    TOGGLE_THEME,
    HOME,
    LIBRARY,
    DOWNLOADS,
    SETTINGS,
}

internal fun desktopCommand(
    key: Key,
    ctrl: Boolean,
    meta: Boolean,
    alt: Boolean,
    shift: Boolean,
): DesktopCommand? {
    // Ctrl is the accelerator on Windows and Linux. Reject the Windows/Super
    // key so OS-level shortcuts such as Win+E keep working; Alt is reserved
    // for menu access and Alt+F4.
    if (!ctrl || meta || alt) return null
    return when {
        key == Key.N && !shift -> DesktopCommand.NEW_INSTANCE
        key == Key.I && !shift -> DesktopCommand.OPEN_LIBRARY
        key == Key.Comma && !shift -> DesktopCommand.OPEN_SETTINGS
        key == Key.Q && !shift -> DesktopCommand.QUIT
        key == Key.F && !shift -> DesktopCommand.GLOBAL_SEARCH
        key == Key.T && shift -> DesktopCommand.TOGGLE_THEME
        key == Key.One && !shift -> DesktopCommand.HOME
        key == Key.Two && !shift -> DesktopCommand.LIBRARY
        key == Key.Three && !shift -> DesktopCommand.DOWNLOADS
        key == Key.Four && !shift -> DesktopCommand.SETTINGS
        else -> null
    }
}

/** Localised accessibility labels for the app-drawn window buttons. */
internal data class WindowControlLabels(
    val minimize: String,
    val maximize: String,
    val restore: String,
    val close: String,
)

internal const val WindowMinimizeTag: String = "window-minimize"
internal const val WindowMaximizeTag: String = "window-maximize"
internal const val WindowCloseTag: String = "window-close"

/**
 * The three caption buttons, pinned to the top-right of the page header.
 *
 * The layout matches Windows 11: 46 x 48 dp hit targets, a subtle translucent
 * hover for minimise/maximise and the familiar red close state. The buttons do
 * not take keyboard focus; they are pointer affordances like a native caption.
 */
@Composable
internal fun WindowControls(
    isMaximized: Boolean,
    focused: Boolean,
    labels: WindowControlLabels,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(WindowChromeMetrics.HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowControlButton(
            icon = WindowControlIcon.MINIMIZE,
            label = labels.minimize,
            tag = WindowMinimizeTag,
            focused = focused,
            onClick = onMinimize,
        )
        WindowControlButton(
            icon = if (isMaximized) WindowControlIcon.RESTORE else WindowControlIcon.MAXIMIZE,
            label = if (isMaximized) labels.restore else labels.maximize,
            tag = WindowMaximizeTag,
            focused = focused,
            onClick = onToggleMaximize,
        )
        WindowControlButton(
            icon = WindowControlIcon.CLOSE,
            label = labels.close,
            tag = WindowCloseTag,
            focused = focused,
            danger = true,
            onClick = onClose,
        )
    }
}

private enum class WindowControlIcon {
    MINIMIZE,
    MAXIMIZE,
    RESTORE,
    CLOSE,
}

@Composable
private fun WindowControlButton(
    icon: WindowControlIcon,
    label: String,
    tag: String,
    focused: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val neutralHover = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (lightSurface) 0.07f else 0.10f,
    )
    val neutralPressed = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (lightSurface) 0.12f else 0.16f,
    )
    val closeHover = Color(0xFFC42B1C)
    val closePressed = Color(0xFFB0271A)

    val background by animateColorAsState(
        targetValue = when {
            danger && (hovered || pressed) -> if (pressed) closePressed else closeHover
            pressed -> neutralPressed
            hovered -> neutralHover
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 90),
        label = "window-control-background",
    )
    val iconColor = when {
        danger && (hovered || pressed) -> Color.White
        !focused -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .width(WindowChromeMetrics.ControlWidth)
            .fillMaxHeight()
            .background(background)
            .hoverable(interactionSource)
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        WindowControlGlyph(icon = icon, color = iconColor)
    }
}

@Composable
private fun WindowControlGlyph(icon: WindowControlIcon, color: Color) {
    Canvas(Modifier.size(10.dp)) {
        val width = size.width
        val height = size.height
        val stroke = 1.dp.toPx()
        when (icon) {
            WindowControlIcon.MINIMIZE -> drawLine(
                color = color,
                start = Offset(0f, height / 2f),
                end = Offset(width, height / 2f),
                strokeWidth = stroke,
                cap = StrokeCap.Square,
            )

            WindowControlIcon.MAXIMIZE -> drawRect(
                color = color,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(width - stroke, height - stroke),
                style = Stroke(width = stroke),
            )

            WindowControlIcon.RESTORE -> {
                // Back sheet: top/right edges only, so the front square keeps
                // a clean outline at caption sizes.
                val back = Path().apply {
                    moveTo(width * 0.30f, height * 0.05f)
                    lineTo(width * 0.95f, height * 0.05f)
                    lineTo(width * 0.95f, height * 0.70f)
                    lineTo(width * 0.70f, height * 0.70f)
                }
                drawPath(back, color = color, style = Stroke(width = stroke))
                val front = Path().apply {
                    moveTo(width * 0.05f, height * 0.30f)
                    lineTo(width * 0.70f, height * 0.30f)
                    lineTo(width * 0.70f, height * 0.95f)
                    lineTo(width * 0.05f, height * 0.95f)
                    close()
                }
                drawPath(front, color = color, style = Stroke(width = stroke))
            }

            WindowControlIcon.CLOSE -> {
                drawLine(
                    color = color,
                    start = Offset(0.5f, 0.5f),
                    end = Offset(width - 0.5f, height - 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = color,
                    start = Offset(width - 0.5f, 0.5f),
                    end = Offset(0.5f, height - 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            }
        }
    }
}

/**
 * Tracks native window focus so an inactive window dims its caption buttons,
 * matching the behaviour of a real title bar.
 */
@Composable
internal fun rememberWindowFocused(window: Window): Boolean {
    var focused by remember { mutableStateOf(window.isFocused) }
    DisposableEffect(window) {
        val listener = object : WindowFocusListener {
            override fun windowGainedFocus(event: WindowEvent) {
                focused = true
            }

            override fun windowLostFocus(event: WindowEvent) {
                focused = false
            }
        }
        window.addWindowFocusListener(listener)
        focused = window.isFocused
        onDispose { window.removeWindowFocusListener(listener) }
    }
    return focused
}

/**
 * Drag gesture installed on the page-title slot and the settings titles.
 *
 * The slot contains only a Text, so it can use the normal pointer pass without
 * competing with the search field, chips or buttons next to it. Putting the
 * gesture on the whole header would run before those children consume their
 * presses and turn a double click in the search field into a window maximize.
 * `WindowDraggableArea` was not used here because its AWT mouse listeners do
 * not see drags that start over the Compose panel on a standard (non-JBR) JDK.
 */
internal fun windowDragModifier(
    window: Window,
    dragEnabled: Boolean,
    onDoubleClick: (() -> Unit)?,
): Modifier = Modifier.pointerInput(dragEnabled, onDoubleClick != null) {
    detectWindowTitleGestures(
        window = window,
        dragEnabled = dragEnabled,
        onDoubleClick = { onDoubleClick?.invoke() },
    )
}

/**
 * Window drag plus double-click-to-maximize.
 *
 * Global [MouseInfo] coordinates stay correct even after the window has moved
 * under the cursor, which is what makes a direct `setLocation` drag stable.
 */
private suspend fun PointerInputScope.detectWindowTitleGestures(
    window: Window,
    dragEnabled: Boolean,
    onDoubleClick: () -> Unit,
) {
    var lastReleaseTime = 0L
    var lastReleasePosition = Offset.Zero
    awaitEachGesture {
        // The modifier sits on the page-title slot, not the whole header, so
        // the search field and action buttons are not in this hit path.
        val down = awaitFirstDown(requireUnconsumed = true)
        // Only the left button drags or double-clicks; a right-drag should
        // keep its platform meaning (and not move the window).
        if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        val startPointer = MouseInfo.getPointerInfo()?.location
        val startWindow = window.location
        var dragged = false
        var releasePosition: Offset? = null

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                releasePosition = change.position
                break
            }
            // A child claimed the gesture after the down (for example a text
            // field starting a selection); leave it alone.
            if (change.isConsumed) {
                lastReleaseTime = 0L
                return@awaitEachGesture
            }
            if (!dragEnabled || startPointer == null) continue

            val pointer = MouseInfo.getPointerInfo()?.location ?: continue
            val dx = pointer.x - startPointer.x
            val dy = pointer.y - startPointer.y
            if (!dragged && abs(dx) + abs(dy) > 3) {
                dragged = true
            }
            if (dragged) {
                window.setLocation(startWindow.x + dx, startWindow.y + dy)
            }
        }

        val release = releasePosition ?: return@awaitEachGesture
        if (dragged) {
            lastReleaseTime = 0L
            return@awaitEachGesture
        }

        val now = System.currentTimeMillis()
        val withinTimeout =
            now - lastReleaseTime in 1..viewConfiguration.doubleTapTimeoutMillis
        val closeEnough = (release - lastReleasePosition).getDistance() <= 12f
        if (withinTimeout && closeEnough) {
            lastReleaseTime = 0L
            onDoubleClick()
        } else {
            lastReleaseTime = now
            lastReleasePosition = release
        }
    }
}
