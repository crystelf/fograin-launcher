package com.fograin.launcher.platform

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopAccentTest {
    @Test
    fun readsRgbFromTheDwmDword() {
        // AARRGGBB: alpha is ignored, the launcher only needs the RGB seed.
        assertEquals(
            Color(0x12, 0x34, 0x56),
            DesktopAccent.colorizationColor(0xC0123456.toInt()),
        )
    }

    @Test
    fun automaticColorizationFallsBackToTheDefaultAccent() {
        assertNull(DesktopAccent.colorizationColor(0))
        assertNull(DesktopAccent.colorizationColor(0xFF000000.toInt()))
    }
}
