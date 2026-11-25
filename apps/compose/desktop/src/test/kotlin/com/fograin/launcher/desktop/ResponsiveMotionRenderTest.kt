package com.fograin.launcher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.NavBarTag
import com.fograin.launcher.ui.NavRailTag
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.theme.FograinTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Drives the shell across the 840 dp breakpoint by hand and captures the
 * intermediate frame. This is the regression guard for the "flash" on macOS
 * full screen: both navigation surfaces must be in flight together, with the
 * rail growing and the bottom bar shrinking, instead of one replacing the
 * other on a single frame.
 *
 * PNGs are written next to the test result for visual review; the assertions
 * pin the end state and the presence of the rail mid-spring.
 */
@OptIn(ExperimentalTestApi::class)
class ResponsiveMotionRenderTest {
    @Test
    fun railAndBottomBarCrossFadeThroughOneSpring() =
        runDesktopComposeUiTest(width = 1000, height = 720) {
            val model = AppModel.create(
                core = FakeCoreClient(),
                platform = testPlatform(),
                scope = CoroutineScope(Dispatchers.Unconfined),
            )
            model.settings.setLanguage(AppLanguage.SIMPLIFIED_CHINESE)
            val state = model.state.value.copy(destination = AppDestination.LIBRARY)
            val width = mutableStateOf(780.dp)

            setContent {
                FograinTheme(themeMode = ThemeMode.DARK) {
                    AppLocale(AppLanguage.SIMPLIFIED_CHINESE) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.width(width.value).fillMaxHeight()) {
                                FograinApp(model = model, state = state)
                            }
                        }
                    }
                }
            }
            fun save(name: String) {
                val bitmap = onRoot().captureToImage()
                val bytes = Image.makeFromBitmap(bitmap.asSkiaBitmap())
                    .encodeToData(EncodedImageFormat.PNG)
                    ?.bytes
                    ?: error("PNG encoding failed")
                assertTrue(bytes.size > 8_000, "$name rendered suspiciously small")
                val output = Files.createTempFile("fograin-motion-$name-", ".png")
                output.toFile().writeBytes(bytes)
                println("FOGRAIN_MOTION_PNG_$name=$output")
            }

            waitForIdle()
            onNodeWithTag(NavBarTag).assertExists()
            onNodeWithTag(NavRailTag).assertDoesNotExist()
            save("compact")

            mainClock.autoAdvance = false
            runOnIdle { width.value = 900.dp }
            mainClock.advanceTimeBy(16)
            mainClock.advanceTimeBy(60)
            // Both surfaces are laid out while the spring retargets.
            onNodeWithTag(NavRailTag).assertExists()
            save("mid")

            mainClock.advanceTimeBy(1_000)
            onNodeWithTag(NavBarTag).assertDoesNotExist()
            val rail = onNodeWithTag(NavRailTag).fetchSemanticsNode().boundsInRoot
            assertTrue(rail.width >= 78f, "rail did not settle at its full width: $rail")
            save("expanded")

            // And back, so a full-screen exit uses the same path.
            runOnIdle { width.value = 780.dp }
            mainClock.advanceTimeBy(16)
            mainClock.advanceTimeBy(1_000)
            onNodeWithTag(NavBarTag).assertExists()
            onNodeWithTag(NavRailTag).assertDoesNotExist()
            save("compact-again")
        }
}