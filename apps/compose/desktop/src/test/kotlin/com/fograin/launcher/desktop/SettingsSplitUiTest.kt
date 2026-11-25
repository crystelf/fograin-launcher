package com.fograin.launcher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.ui.settings.SettingsCompactListTag
import com.fograin.launcher.ui.settings.SettingsSidebarTag
import com.fograin.launcher.ui.settings.SettingsSplitterTag
import com.fograin.launcher.ui.settings.SettingsWindowRoot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Regression test for the settings divider across a full-screen-like resize.
 *
 * The first implementation clamped the stored width to the current pane band
 * on every width change. macOS reports intermediate (sometimes zero-sized)
 * widths while it moves a window into a full-screen Space, so the divider was
 * permanently dragged to the 32 % floor. The preference must stay at 500 dp
 * while only the displayed width is clamped.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSplitUiTest {
    @Test
    fun dividerReturnsToItsPreviousPositionAfterAWiderWindow() =
        runDesktopComposeUiTest(width = 1800, height = 900) {
            val preferences = platformServices().preferences
            val original = preferences.settingsSidebarWidth
            try {
                preferences.settingsSidebarWidth = 500f
                val model = AppModel.create(
                    core = FakeCoreClient(),
                    platform = testPlatform(),
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )
                model.settings.setLanguage(AppLanguage.SIMPLIFIED_CHINESE)
                model.openSettings()
                // Start in the compact layout (below 820 dp), as a small
                // window does before macOS enters full screen.
                val width = mutableStateOf(800.dp)

                setContent {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width.value).fillMaxHeight()) {
                            SettingsWindowRoot(model = model)
                        }
                    }
                }
                waitForIdle()
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                onNodeWithTag(SettingsCompactListTag).assertExists()
                onNodeWithTag(SettingsSplitterTag).assertDoesNotExist()

                // Crossing into the wide layout must build the split view at
                // the saved 500 dp, not at the 32 % floor of 1024/3.
                runOnIdle { width.value = 1280.dp }
                waitForIdle()
                mainClock.advanceTimeBy(1_000)
                waitForIdle()

                val windowed = splitterX()
                assertTrue(
                    abs(windowed - 500f) < 2f,
                    "side bar should start at the saved 500 dp; got $windowed",
                )
                val sidebar = onNodeWithTag(SettingsSidebarTag).fetchSemanticsNode().size.width
                assertTrue(abs(sidebar - 500f) < 2f, "sidebar width was $sidebar")

                // A 1600 dp window raises the 32 % floor to 512 dp.
                runOnIdle { width.value = 1600.dp }
                waitForIdle()
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                val full = splitterX()
                assertTrue(
                    abs(full - 512f) < 2f,
                    "the displayed divider should be clamped to the wide floor; got $full",
                )

                // Return: the divider and the stored preference are untouched.
                runOnIdle { width.value = 1280.dp }
                waitForIdle()
                mainClock.advanceTimeBy(1_000)
                waitForIdle()

                val restored = splitterX()
                assertTrue(
                    abs(restored - windowed) < 2f,
                    "divider moved after a wide round trip: $windowed -> $restored",
                )
                assertEquals(500f, preferences.settingsSidebarWidth, 0.5f)
            } finally {
                preferences.settingsSidebarWidth = original
            }
        }

    @Test
    fun defaultSplitKeepsItsAbsoluteWidthAcrossAResize() =
        runDesktopComposeUiTest(width = 1800, height = 900) {
            val preferences = platformServices().preferences
            val original = preferences.settingsSidebarWidth
            try {
                // No saved width: the initial third of the window becomes the
                // absolute default and must not turn into a third of every
                // later window size.
                preferences.settingsSidebarWidth = 0f
                val model = AppModel.create(
                    core = FakeCoreClient(),
                    platform = testPlatform(),
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )
                model.settings.setLanguage(AppLanguage.SIMPLIFIED_CHINESE)
                model.openSettings()
                val width = mutableStateOf(1280.dp)

                setContent {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width.value).fillMaxHeight()) {
                            SettingsWindowRoot(model = model)
                        }
                    }
                }
                waitForIdle()
                mainClock.advanceTimeBy(1_000)
                waitForIdle()

                val initial = splitterX()
                assertTrue(
                    abs(initial - 1280f / 3f) < 2f,
                    "expected the initial third, got $initial",
                )

                // At 1600 the 32 % floor is 512, so the displayed divider is
                // clamped up; the stored default stays at 426.7.
                runOnIdle { width.value = 1600.dp }
                waitForIdle()
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                val full = splitterX()
                assertTrue(abs(full - 512f) < 2f, "expected the wide floor, got $full")

                runOnIdle { width.value = 1280.dp }
                waitForIdle()
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                val restored = splitterX()
                assertTrue(
                    abs(restored - initial) < 2f,
                    "the default divider moved: $initial -> $restored",
                )
            } finally {
                preferences.settingsSidebarWidth = original
            }
        }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.splitterX(): Float =
        onNodeWithTag(SettingsSplitterTag).fetchSemanticsNode().boundsInRoot.left
}