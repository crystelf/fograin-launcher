package com.fograin.launcher.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.theme.FograinTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * R2 acceptance: render the four pages and the three sheets with deterministic
 * data, in both dark and light themes. Each frame is written to a temp PNG and
 * printed so a reviewer can compare against the pen; the test fails when a page
 * crashes or renders (nearly) blank.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreensRenderTest {
    @Test
    fun rendersEveryPageAndSheet() = runBlocking {
        val core = FakeCoreClient()
        val model = AppModel.create(
            core = core,
            platform = platformServices(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.loadDownloadVersions()

        val base = model.state.value
        assertEquals(3, base.instances.size, "instances.list was not applied")
        assertTrue(base.download.versions.isNotEmpty(), "versions.list was not applied")

        val pages = listOf(
            "home" to base.copy(destination = AppDestination.HOME),
            "library" to base.copy(destination = AppDestination.LIBRARY),
            "downloads" to base.copy(destination = AppDestination.DOWNLOADS),
            "settings" to base.copy(destination = AppDestination.SETTINGS),
            "preview-sheet" to base.copy(
                destination = AppDestination.LIBRARY,
                previewInstanceId = "fog-forest",
            ),
            "instance-settings-sheet" to base.copy(instanceSettingsId = "fog-forest"),
            "install-sheet" to base.copy(
                download = base.download.copy(installSheetVersion = "1.21.5"),
            ),
        )

        pages.forEach { (name, state) ->
            val bytes = render(model, state)
            val output = Files.createTempFile("fograin-r2-$name-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_R2_PNG_$name=$output")
            assertTrue(bytes.size > 8_000, "$name rendered suspiciously small (${bytes.size} bytes)")
        }
    }

    @Test
    fun rendersLightThemePages() = runBlocking {
        val core = FakeCoreClient()
        val model = AppModel.create(
            core = core,
            platform = platformServices(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.loadDownloadVersions()
        val base = model.state.value.copy(theme = ThemeMode.LIGHT)

        val pages = listOf(
            "home" to base.copy(destination = AppDestination.HOME),
            "library" to base.copy(destination = AppDestination.LIBRARY),
            "downloads" to base.copy(destination = AppDestination.DOWNLOADS),
            "settings" to base.copy(destination = AppDestination.SETTINGS),
            "instance-settings-sheet" to base.copy(instanceSettingsId = "fog-forest"),
        )

        pages.forEach { (name, state) ->
            val bytes = render(model, state)
            val output = Files.createTempFile("fograin-light-$name-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_LIGHT_PNG_$name=$output")
            assertTrue(bytes.size > 8_000, "$name rendered suspiciously small (${bytes.size} bytes)")
        }
    }

    private fun render(model: AppModel, state: AppUiState): ByteArray {
        val scene = ImageComposeScene(width = 1120, height = 780, density = Density(1f)) {
            FograinTheme(themeMode = state.theme, dynamicColor = false) {
                FograinApp(
                    model = model,
                    state = state,
                    dynamicColorEnabled = false,
                    onDynamicColorChange = {},
                )
            }
        }
        return try {
            // ModalBottomSheet enters with a spring animation, so advance
            // several frames before capturing instead of rendering once.
            repeat(8) { index ->
                scene.render((index + 1) * 100_000_000L)
                Thread.sleep(40)
            }
            val image = scene.render(900_000_000L)
            image.encodeToData(EncodedImageFormat.PNG)?.bytes
                ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }
}
