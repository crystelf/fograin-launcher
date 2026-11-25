package com.fograin.launcher.ui.settings

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The divider used to be clamped in place on every window resize, so the
 * transient sizes reported while macOS reparented the window for full screen
 * permanently moved it. The preference is now intent: only the displayed
 * width is clamped, and the original width comes back when the window does.
 */
class SettingsSplitTargetTest {
    @Test
    fun keepsThePreferredWidthAcrossAFullscreenRoundTrip() {
        val preferred = 500.dp

        val windowed = settingsSplitTarget(preferred, 1280.dp)
        assertEquals(preferred, windowed)

        // Full screen raises the 32 % floor to 819 dp. The sidebar may be
        // displayed wider there, but that clamp is not the user's intent.
        assertEquals(819.2f, settingsSplitTarget(preferred, 2560.dp).value, 0.01f)

        // Back to the windowed size: exactly the same divider position the
        // user left behind, not the full-screen floor.
        assertEquals(windowed, settingsSplitTarget(preferred, 1280.dp))
    }

    @Test
    fun clampsOnlyTheDisplayedValueWhenTheWindowGetsNarrow() {
        val preferred = 500.dp
        val windowed = 1280.dp
        val narrow = 500.dp

        assertEquals(500.dp, settingsSplitTarget(preferred, windowed))

        // At 500 dp the sidebar may use at most 500 - 18 - 160 = 322 dp.
        assertEquals(322.dp, settingsSplitTarget(preferred, narrow))

        // The stored intent was never touched, so growing back restores it.
        assertEquals(500.dp, settingsSplitTarget(preferred, windowed))
    }

    @Test
    fun survivesAZeroWidthReparent() {
        // macOS can report a zero-sized content view for a frame while moving
        // a window into/out of a full-screen Space.
        assertEquals(0.dp, settingsSplitTarget(500.dp, 0.dp))
        assertEquals(500.dp, settingsSplitTarget(500.dp, 1280.dp))
    }
}