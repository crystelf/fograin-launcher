package com.fograin.launcher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.fograin.launcher.App
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.components.LocalWindowControlsInset
import com.fograin.launcher.ui.components.WindowChromeMetrics
import com.fograin.launcher.ui.home.AccountAvatarTag
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.theme.FograinTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders the app-drawn Windows header: the shared page header plus the
 * app-drawn caption buttons. The shell composes these two pieces outside of
 * the shared App, so an off-screen render is the cheapest way to confirm the
 * right inset and the hover-control geometry on every CI platform.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class WindowChromeRenderTest {

    private fun model(): AppModel = AppModel.create(
        core = FakeCoreClient(),
        platform = testPlatform(),
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).also { it.settings.setLanguage(AppLanguage.SIMPLIFIED_CHINESE) }

    @androidx.compose.runtime.Composable
    private fun controls() = WindowControls(
        isMaximized = false,
        focused = true,
        labels = WindowControlLabels(
            minimize = "最小化",
            maximize = "最大化",
            restore = "还原",
            close = "关闭窗口",
        ),
        onMinimize = {},
        onToggleMaximize = {},
        onClose = {},
    )

    @Test
    fun rendersTheBorderlessHeaderInBothThemes() = runBlocking {
        val model = model()
        val state = model.state.value.copy(destination = AppDestination.LIBRARY)

        listOf(
            "dark" to ThemeMode.DARK,
            "light" to ThemeMode.LIGHT,
        ).forEach { (name, theme) ->
            val scene = ImageComposeScene(width = 1120, height = 780, density = Density(1f)) {
                FograinTheme(themeMode = theme) {
                    AppLocale(language = AppLanguage.SIMPLIFIED_CHINESE) {
                        CompositionLocalProvider(
                            LocalWindowControlsInset provides WindowChromeMetrics.ControlsInset,
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                FograinApp(model = model, state = state)
                                Box(Modifier.align(Alignment.TopEnd)) { controls() }
                            }
                        }
                    }
                }
            }
            try {
                repeat(3) { index -> scene.render((index + 1) * 100_000_000L) }
                val image = scene.render(400_000_000L)
                val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
                    ?: error("PNG encoding failed")
                val output = Files.createTempFile("fograin-window-chrome-$name-", ".png")
                output.toFile().writeBytes(bytes)
                println("FOGRAIN_WINDOW_CHROME_PNG_$name=$output")
                assertTrue(bytes.size > 10_000, "$name render is suspiciously small")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun rendersSettingsUnderTheCaptionButtons() = runBlocking {
        val model = model()
        model.openSettings()
        val scene = ImageComposeScene(width = 1120, height = 780, density = Density(1f)) {
            FograinTheme(themeMode = ThemeMode.DARK) {
                AppLocale(language = AppLanguage.SIMPLIFIED_CHINESE) {
                    CompositionLocalProvider(
                        LocalWindowControlsInset provides WindowChromeMetrics.ControlsInset,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            App(model = model)
                            Box(Modifier.align(Alignment.TopEnd)) { controls() }
                        }
                    }
                }
            }
        }
        try {
            repeat(8) { index -> scene.render((index + 1) * 100_000_000L) }
            val image = scene.render(400_000_000L)
            val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
                ?: error("PNG encoding failed")
            val output = Files.createTempFile("fograin-window-chrome-settings-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_WINDOW_CHROME_PNG_settings=$output")
            assertTrue(bytes.size > 10_000, "settings render is suspiciously small")
        } finally {
            scene.close()
        }
    }

    @Test
    fun captionButtonsClearTheHeaderActions() = runDesktopComposeUiTest(width = 1280, height = 860) {
        val model = model()
        setContent {
            FograinTheme(themeMode = ThemeMode.DARK) {
                AppLocale(language = AppLanguage.SIMPLIFIED_CHINESE) {
                    CompositionLocalProvider(
                        LocalWindowControlsInset provides WindowChromeMetrics.ControlsInset,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            FograinApp(model = model, state = model.state.value)
                            Box(Modifier.align(Alignment.TopEnd)) { controls() }
                        }
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithTag(AccountAvatarTag).assertIsDisplayed()
        onNodeWithTag(WindowCloseTag).assertIsDisplayed()

        val avatar = onNodeWithTag(AccountAvatarTag).fetchSemanticsNode().boundsInRoot
        val close = onNodeWithTag(WindowCloseTag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            avatar.right <= close.left,
            "header actions should end before the caption buttons: $avatar vs $close",
        )
        // Three 46 dp targets and an 8 dp breathing gap.
        assertTrue(close.width >= 40f, "close target is too narrow: ${close.width}")
    }
}
