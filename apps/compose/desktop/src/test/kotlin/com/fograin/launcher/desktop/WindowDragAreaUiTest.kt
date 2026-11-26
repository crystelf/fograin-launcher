package com.fograin.launcher.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.fograin.launcher.App
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.ui.components.LocalWindowCaptionRegionReporter
import com.fograin.launcher.ui.components.LocalWindowDragAreaActive
import com.fograin.launcher.ui.components.WindowCaptionRegionReporter
import com.fograin.launcher.ui.settings.SettingsDragRailTag
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The settings content pane is the right half of the window. Before this test
 * existed, only the sidebar title could start a window drag on macOS; the
 * blank strip above the section content swallowed the press. The rail reports
 * its bounds to the shell, which on Windows turns them into `HTCAPTION`.
 */
@OptIn(ExperimentalTestApi::class)
class WindowDragAreaUiTest {
    private class RecordingReporter : WindowCaptionRegionReporter {
        private val regions = ConcurrentHashMap<Any, Rect>()

        override fun update(key: Any, bounds: Rect) {
            if (bounds.width <= 0f || bounds.height <= 0f) {
                regions.remove(key)
            } else {
                regions[key] = bounds
            }
        }

        override fun remove(key: Any) {
            regions.remove(key)
        }

        fun snapshot(): List<Rect> = regions.values.toList()
    }

    @Test
    fun settingsContentTopRailIsReportedAsADragArea() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val model = AppModel.create(
                core = FakeCoreClient(),
                platform = testPlatform(),
                scope = CoroutineScope(Dispatchers.Unconfined),
            )
            model.settings.setLanguage(AppLanguage.SIMPLIFIED_CHINESE)
            val reporter = RecordingReporter()

            setContent {
                CompositionLocalProvider(
                    LocalWindowDragAreaActive provides true,
                    LocalWindowCaptionRegionReporter provides reporter,
                ) {
                    App(model)
                }
            }
            waitForIdle()
            model.openSettings()
            waitForIdle()

            onNodeWithTag(SettingsDragRailTag).assertExists()
            val rail = onNodeWithTag(SettingsDragRailTag).fetchSemanticsNode().boundsInRoot
            val reported = reporter.snapshot()
            assertTrue(
                reported.any { region ->
                    abs(region.top - rail.top) < 2f &&
                        abs(region.width - rail.width) < 2f &&
                        region.width > 400f
                },
                "settings top rail was not reported as a window drag area: rail=$rail reported=$reported",
            )
        }
}