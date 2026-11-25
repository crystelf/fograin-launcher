package com.fograin.launcher.desktop

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The native window procedure asks this registry on every mouse move, so it
 * must track the Compose title strips (including the settings right half) and
 * forget them as soon as they leave composition.
 */
class WindowsCaptionRegionsTest {
    @Test
    fun tracksCaptionsAndRemovesThem() {
        val regions = WindowsCaptionRegions()
        val title = Any()

        regions.update(title, Rect(0f, 0f, 640f, 48f))

        assertTrue(regions.contains(12f, 12f))
        assertFalse(regions.contains(700f, 12f))
        assertFalse(regions.contains(12f, 80f))

        regions.remove(title)
        assertFalse(regions.contains(12f, 12f))
    }

    @Test
    fun anEmptyLayoutForgetsTheRegion() {
        val regions = WindowsCaptionRegions()
        val strip = Any()

        regions.update(strip, Rect(10f, 0f, 810f, 38f))
        assertTrue(regions.contains(500f, 20f))

        // A hidden/zero-sized drag area must not keep swallowing clicks.
        regions.update(strip, Rect(10f, 0f, 10f, 38f))
        assertFalse(regions.contains(500f, 20f))
    }

    @Test
    fun keepsMultipleRegionsApart() {
        val regions = WindowsCaptionRegions()
        val header = Any()
        val settings = Any()

        regions.update(header, Rect(0f, 0f, 500f, 48f))
        regions.update(settings, Rect(520f, 0f, 1280f, 12f))

        assertTrue(regions.contains(100f, 20f))
        assertTrue(regions.contains(900f, 4f))
        assertFalse(regions.contains(900f, 20f))
    }
}