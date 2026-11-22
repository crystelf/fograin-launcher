package com.fograin.launcher.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.fograin.launcher.platform.WindowFrame
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Borderless windows cannot use AWT's MAXIMIZED_BOTH (it covers the taskbar),
 * so the shell owns the work-area math, restore geometry and off-screen
 * recovery. These tests pin that logic without opening a window server.
 */
class WindowGeometryTest {
    private val primary = Rectangle(0, 0, 1920, 1040)
    private val secondary = Rectangle(-1920, 0, 1920, 1040)

    @Test
    fun keepsFramesThatAreAlreadyVisible() {
        val frame = WindowFrame(x = 120, y = 80, width = 1120, height = 780)

        val sanitized = sanitizeWindowFrame(frame, listOf(primary))

        assertEquals(frame, sanitized)
    }

    @Test
    fun recentresFramesLeftOnADisconnectedMonitor() {
        val frame = WindowFrame(x = 5000, y = 5000, width = 800, height = 600)

        val sanitized = sanitizeWindowFrame(frame, listOf(primary))

        assertEquals(800, sanitized.width)
        assertEquals(600, sanitized.height)
        assertTrue(sanitized.x in 0..1120, "expected a centred x, got ${sanitized.x}")
        assertTrue(sanitized.y in 0..440, "expected a centred y, got ${sanitized.y}")
    }

    @Test
    fun recentresFramesWhoseTitleRowIsAboveTheScreen() {
        val frame = WindowFrame(x = 200, y = -500, width = 800, height = 600)

        val sanitized = sanitizeWindowFrame(frame, listOf(primary))

        assertTrue(
            sanitized.y >= 0,
            "the title row must be reachable; got y=${sanitized.y}",
        )
    }

    @Test
    fun clampsOversizedFloatingFrames() {
        val frame = WindowFrame(x = 0, y = 0, width = 3000, height = 2000)

        val sanitized = sanitizeWindowFrame(frame, listOf(primary))

        assertEquals(1920, sanitized.width)
        assertEquals(1040, sanitized.height)
    }

    @Test
    fun agreesToTheSmallestWindowSize() {
        val frame = WindowFrame(x = 10, y = 10, width = 120, height = 90)

        val sanitized = sanitizeWindowFrame(frame, listOf(primary))

        assertEquals(MinimumWindowSize.width, sanitized.width)
        assertEquals(MinimumWindowSize.height, sanitized.height)
    }

    @Test
    fun restoresAFrameOnTheSecondaryMonitor() {
        val frame = WindowFrame(x = -1500, y = 100, width = 800, height = 600)

        val sanitized = sanitizeWindowFrame(frame, listOf(primary, secondary))

        assertEquals(frame, sanitized)
    }

    @Test
    fun replacesTheOldMaximizedSizeAsTheFloatingGeometry() {
        val frame = WindowFrame(
            x = 0,
            y = 0,
            width = 1920,
            height = 1040,
            maximized = true,
        )

        val sanitized = sanitizeWindowFrame(frame, listOf(primary))

        assertEquals(DefaultFloatingSize.width, sanitized.width)
        assertEquals(DefaultFloatingSize.height, sanitized.height)
        assertTrue(sanitized.maximized)
        // Centred in the 1920 x 1040 work area.
        assertEquals((1920 - 1120) / 2, sanitized.x)
        assertEquals((1040 - 780) / 2, sanitized.y)
    }

    @Test
    fun picksTheMonitorWithTheLargestOverlap() {
        val window = Rectangle(-900, 100, 800, 600)

        assertEquals(secondary, workAreaFor(window, listOf(primary, secondary)))
    }

    @Test
    fun persistsFloatingBoundsWhileMaximized() {
        val state = WindowState(
            placement = WindowPlacement.Floating,
            position = WindowPosition(100.dp, 100.dp),
            size = DpSize(900.dp, 700.dp),
        )
        val restore = Rectangle(40, 60, 900, 700)

        val frame = frameToPersist(state, maximized = true, restoreBounds = restore)

        assertEquals(WindowFrame(40, 60, 900, 700, maximized = true), frame)
    }

    @Test
    fun persistsLiveBoundsWhileFloating() {
        val state = WindowState(
            placement = WindowPlacement.Floating,
            position = WindowPosition(100.dp, 100.dp),
            size = DpSize(900.dp, 700.dp),
        )

        val frame = frameToPersist(state, maximized = false, restoreBounds = null)

        assertEquals(WindowFrame(100, 100, 900, 700, maximized = false), frame)
    }
}