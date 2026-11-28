package com.fograin.launcher.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.community.CommunityZoomableImage
import com.fograin.launcher.ui.community.CommunityZoomableImageTag
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.theme.FograinTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The viewer's chrome is gone: direct manipulation is the only way out.
 * A tap or a downward swipe dismisses; the double-tap path zooms and is
 * covered by the same gesture stream.
 */
@OptIn(ExperimentalTestApi::class)
class CommunityImageViewerTest {
    @Test
    fun aSingleTapClosesTheViewer() = runDesktopComposeUiTest(width = 420, height = 900) {
        var dismissed = 0
        setContent {
            FograinTheme(themeMode = ThemeMode.DARK) {
                AppLocale(AppLanguage.SIMPLIFIED_CHINESE) {
                    CommunityZoomableImage(
                        url = "https://example.invalid/image.jpg",
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(CommunityZoomableImageTag).performClick()
        // The tap waits for the double-tap window before it settles.
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun swipingDownClosesTheViewer() = runDesktopComposeUiTest(width = 420, height = 900) {
        var dismissed = 0
        setContent {
            FograinTheme(themeMode = ThemeMode.DARK) {
                AppLocale(AppLanguage.SIMPLIFIED_CHINESE) {
                    CommunityZoomableImage(
                        url = "https://example.invalid/image.jpg",
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithTag(CommunityZoomableImageTag).performTouchInput {
            down(center)
            moveBy(Offset(0f, 320f))
            up()
        }
        mainClock.advanceTimeBy(900)
        assertEquals(1, dismissed)
    }
}
