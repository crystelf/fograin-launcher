package com.fograin.launcher.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.community.CommunityVideoCloseTag
import com.fograin.launcher.ui.community.CommunityVideoControls
import com.fograin.launcher.ui.community.CommunityVideoFullscreenTag
import com.fograin.launcher.ui.community.CommunityVideoPlayTag
import com.fograin.launcher.ui.community.CommunityVideoSeekTag
import com.fograin.launcher.ui.community.CommunityVideoState
import com.fograin.launcher.ui.community.CommunityVideoVolumeSliderTag
import com.fograin.launcher.ui.community.CommunityVideoVolumeTag
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.theme.FograinTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The floating chrome is Composed on every platform, so desktop tests exercise
 * it directly: once as a rendered frame (accent seek bar, floating buttons),
 * once through touch input to prove every control is wired.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class CommunityVideoControlsTest {
    private val state = CommunityVideoState(
        isPlaying = false,
        positionMillis = 12_000,
        durationMillis = 61_000,
        volume = 0.4f,
    )

    @Test
    fun rendersInlineAndFullscreenControls() {
        val compact = render(width = 360, height = 160, showTime = false)
        val compactFile = Files.createTempFile("fograin-video-controls-compact-", ".png")
            .also { it.toFile().writeBytes(compact) }
        println("FOGRAIN_VIDEO_CONTROLS_COMPACT=$compactFile")
        assertTrue(compact.size > 700, "compact controls frame too small: ${compact.size}")

        val wide = render(width = 640, height = 200, showTime = true)
        val wideFile = Files.createTempFile("fograin-video-controls-wide-", ".png")
            .also { it.toFile().writeBytes(wide) }
        println("FOGRAIN_VIDEO_CONTROLS_WIDE=$wideFile")
        assertTrue(wide.size > 1_500, "wide controls frame too small: ${wide.size}")
    }

    @Test
    fun controlsDispatchPlayMuteFullscreenAndVolume() = runDesktopComposeUiTest(width = 560, height = 220) {
        val video = mutableStateOf(state)
        var toggles = 0
        var mutes = 0
        var fullscreens = 0
        var closes = 0
        var seeks = 0
        var seekFraction = -1f
        var volume = state.volume
        setContent {
            FograinTheme(themeMode = ThemeMode.DARK) {
                AppLocale(AppLanguage.SIMPLIFIED_CHINESE) {
                    CommunityVideoControls(
                        state = video.value,
                        fullscreen = false,
                        showTime = true,
                        onTogglePlay = {
                            toggles++
                            video.value = video.value.copy(isPlaying = !video.value.isPlaying)
                        },
                        onSeek = {
                            seeks++
                            seekFraction = it
                        },
                        onVolumeChange = {
                            volume = it
                            video.value = video.value.copy(volume = it)
                        },
                        onToggleMute = { mutes++ },
                        onFullscreen = { fullscreens++ },
                        modifier = Modifier.fillMaxSize(),
                        onClose = { closes++ },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag(CommunityVideoPlayTag).performClick()
        assertEquals(1, toggles)
        onNodeWithTag(CommunityVideoVolumeTag).performClick()
        assertEquals(1, mutes)
        onNodeWithTag(CommunityVideoFullscreenTag).performClick()
        assertEquals(1, fullscreens)
        onNodeWithTag(CommunityVideoCloseTag).performClick()
        assertEquals(1, closes)

        onNodeWithTag(CommunityVideoVolumeSliderTag).performTouchInput {
            down(Offset(width * 0.75f, centerY))
            up()
        }
        assertTrue(volume in 0.7f..0.8f, "volume should follow the touch, was $volume")

        onNodeWithTag(CommunityVideoSeekTag).performTouchInput {
            down(Offset(width * 0.5f, centerY))
            up()
        }
        assertEquals(1, seeks)
        assertTrue(seekFraction in 0.45f..0.55f, "seek should use the bar, was $seekFraction")
    }

    private fun render(
        width: Int,
        height: Int,
        showTime: Boolean,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            FograinTheme(themeMode = ThemeMode.DARK) {
                AppLocale(AppLanguage.SIMPLIFIED_CHINESE) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1B1B1B)),
                    ) {
                        CommunityVideoControls(
                            state = state,
                            fullscreen = !showTime,
                            showTime = showTime,
                            onTogglePlay = {},
                            onSeek = {},
                            onVolumeChange = {},
                            onToggleMute = {},
                            onFullscreen = {},
                            modifier = Modifier.align(Alignment.BottomCenter),
                            onClose = {},
                        )
                    }
                }
            }
        }
        return try {
            repeat(6) { index -> scene.render((index + 1) * 100_000_000L) }
            val image = scene.render(700_000_000L)
            image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }
}
