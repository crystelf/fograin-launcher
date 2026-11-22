package com.fograin.launcher.desktop

import com.fograin.launcher.platform.WindowFrame
import com.fograin.launcher.platform.platformServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Window geometry must survive a restart. Exercises the real
 * `java.util.prefs` implementation and restores the previous value so a
 * developer's own window layout is not clobbered.
 */
class DesktopPreferencesTest {

    @Test
    fun windowFrameRoundTrips() {
        val preferences = platformServices().preferences
        val original = preferences.windowFrame
        try {
            val frame = WindowFrame(x = 11, y = 22, width = 1000, height = 700, maximized = true)
            preferences.windowFrame = frame
            assertEquals(frame, preferences.windowFrame)

            preferences.windowFrame = null
            assertNull(preferences.windowFrame)
        } finally {
            preferences.windowFrame = original
        }
    }
}
