package com.fograin.launcher.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R4: the desktop shell restores and saves window geometry. This covers the
 * save half without a window server; the restore half is a plain read of the
 * same fields.
 */
class WindowFrameTest {
    @Test
    fun savesFloatingGeometry() {
        val state = WindowState(
            placement = WindowPlacement.Floating,
            position = WindowPosition(120.dp, 80.dp),
            size = DpSize(1120.dp, 780.dp),
        )

        val frame = windowFrame(state)

        assertEquals(120, frame.x)
        assertEquals(80, frame.y)
        assertEquals(1120, frame.width)
        assertEquals(780, frame.height)
        assertFalse(frame.maximized)
    }

    @Test
    fun keepsMaximizedFlag() {
        val state = WindowState(
            placement = WindowPlacement.Maximized,
            position = WindowPosition.PlatformDefault,
            size = DpSize(1280.dp, 860.dp),
        )

        val frame = windowFrame(state)

        assertTrue(frame.maximized)
        assertEquals(1280, frame.width)
        // PlatformDefault has no coordinates; the restore path falls back to
        // PlatformDefault when both are zero.
        assertEquals(0, frame.x)
    }
}
