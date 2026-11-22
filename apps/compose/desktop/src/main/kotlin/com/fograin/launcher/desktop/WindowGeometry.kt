package com.fograin.launcher.desktop

import androidx.compose.ui.window.WindowState
import com.fograin.launcher.platform.WindowFrame
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Geometry helpers for the borderless desktop windows.
 *
 * Windows and Linux get an app-drawn title bar, which means the OS no longer
 * provides a caption. The shell therefore has to know where the maximized
 * rectangle of the current monitor is (excluding the taskbar / dock), keep the
 * floating rectangle around for restore, and put a window that was last closed
 * on a disconnected monitor back on screen.
 */

/** Smallest window we allow; below this the compact shell stops being usable. */
internal val MinimumWindowSize: Dimension = Dimension(480, 560)

/** Floating size used when a saved frame cannot describe a usable rectangle. */
internal val DefaultFloatingSize: Dimension = Dimension(1120, 780)

private const val MinimumVisibleWidth = 96
private const val MinimumVisibleHeight = 48

internal fun WindowFrame.toRectangle(): Rectangle =
    Rectangle(x, y, width, height)

internal fun Rectangle.toWindowFrame(maximized: Boolean = false): WindowFrame =
    WindowFrame(x = x, y = y, width = width, height = height, maximized = maximized)

/**
 * The usable rectangle of every monitor, i.e. its bounds minus the taskbar or
 * dock margins reported by AWT. Values are in AWT user-space units, the same
 * coordinate system as [java.awt.Window.getBounds].
 */
internal fun screenWorkAreas(): List<Rectangle> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .mapNotNull { device ->
            runCatching { device.defaultConfiguration.workArea() }.getOrNull()
        }
}.getOrNull().orEmpty().ifEmpty {
    listOf(Rectangle(0, 0, DefaultFloatingSize.width, DefaultFloatingSize.height))
}

private fun GraphicsConfiguration.workArea(): Rectangle {
    val screen = bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(this)
    return Rectangle(
        screen.x + insets.left,
        screen.y + insets.top,
        (screen.width - insets.left - insets.right).coerceAtLeast(1),
        (screen.height - insets.top - insets.bottom).coerceAtLeast(1),
    )
}

/**
 * Picks the monitor the window mostly lives on. A window touching no monitor
 * (for example after a display was unplugged) falls back to the nearest one,
 * so maximizing never sends it into empty space.
 */
internal fun workAreaFor(windowBounds: Rectangle, areas: List<Rectangle>): Rectangle {
    if (areas.isEmpty()) return windowBounds

    var best: Rectangle? = null
    var bestVisible = 0L
    for (area in areas) {
        val overlap = area.intersection(windowBounds)
        val visible = if (overlap.isEmpty) {
            0L
        } else {
            overlap.width.toLong() * overlap.height.toLong()
        }
        if (visible > bestVisible) {
            best = area
            bestVisible = visible
        }
    }
    if (best != null) return best

    val centerX = windowBounds.x + windowBounds.width / 2
    val centerY = windowBounds.y + windowBounds.height / 2
    return areas.minByOrNull { area ->
        val dx = centerX.toLong() - (area.x.toLong() + area.width / 2)
        val dy = centerY.toLong() - (area.y.toLong() + area.height / 2)
        dx * dx + dy * dy
    } ?: areas.first()
}

/**
 * Clamps and, when necessary, re-centres a restored frame.
 *
 * The returned frame keeps the [WindowFrame.maximized] flag from the input: the
 * caller decides whether the window should open maximized or floating. A
 * maximized frame saved by an older build has the whole work area as its size;
 * restoring that as the floating rectangle would make the first un-maximize
 * cover the screen again, so those frames fall back to [DefaultFloatingSize].
 */
internal fun sanitizeWindowFrame(
    frame: WindowFrame,
    areas: List<Rectangle>,
): WindowFrame {
    if (areas.isEmpty()) return frame

    val area = workAreaFor(frame.toRectangle(), areas)
    val maxWidth = area.width.coerceAtLeast(1)
    val maxHeight = area.height.coerceAtLeast(1)

    if (frame.maximized && (frame.width >= maxWidth || frame.height >= maxHeight)) {
        val width = DefaultFloatingSize.width.coerceAtMost(maxWidth)
        val height = DefaultFloatingSize.height.coerceAtMost(maxHeight)
        return centeredFrame(width, height, area, maximized = true)
    }

    val width = frame.width
        .coerceAtLeast(MinimumWindowSize.width)
        .coerceAtMost(maxWidth)
    val height = frame.height
        .coerceAtLeast(MinimumWindowSize.height)
        .coerceAtMost(maxHeight)
    var candidate = Rectangle(frame.x, frame.y, width, height)

    if (!hasUsableOverlap(candidate, areas)) {
        candidate = Rectangle(
            area.x + (area.width - width) / 2,
            area.y + (area.height - height) / 2,
            width,
            height,
        )
    }

    return candidate.toWindowFrame(maximized = frame.maximized)
}

private fun centeredFrame(
    width: Int,
    height: Int,
    area: Rectangle,
    maximized: Boolean,
): WindowFrame = WindowFrame(
    x = area.x + (area.width - width) / 2,
    y = area.y + (area.height - height) / 2,
    width = width,
    height = height,
    maximized = maximized,
)

/**
 * A window is usable when its title row is fully on a monitor: the user needs
 * a 96 x 48 dp grip to drag it back. Merely showing the bottom half of a window
 * that sits above the screen is not enough for a borderless frame.
 */
private fun hasUsableOverlap(candidate: Rectangle, areas: List<Rectangle>): Boolean =
    areas.any { area ->
        val overlap = area.intersection(candidate)
        !overlap.isEmpty &&
            overlap.width >= MinimumVisibleWidth &&
            overlap.y <= candidate.y &&
            overlap.height >= MinimumVisibleHeight
    }

/**
 * Frame to persist when the window closes.
 *
 * While maximized the live bounds are the monitor's work area, so persist the
 * floating rectangle instead; otherwise the next launch would restore a
 * floating window the size of the whole screen.
 */
internal fun frameToPersist(
    state: WindowState,
    maximized: Boolean,
    restoreBounds: Rectangle?,
): WindowFrame {
    if (maximized && restoreBounds != null) {
        return restoreBounds.toWindowFrame(maximized = true)
    }
    return windowFrame(state).copy(maximized = maximized)
}
