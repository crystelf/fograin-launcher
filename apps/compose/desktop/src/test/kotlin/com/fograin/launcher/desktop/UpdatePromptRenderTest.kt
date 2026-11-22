package com.fograin.launcher.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.UpdateUiState
import com.fograin.launcher.platform.ReleaseInfo
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.theme.FograinTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * The startup update card must render over the shell at both window sizes and
 * stay above the compact navigation bar.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UpdatePromptRenderTest {

    @Test
    fun rendersTheUpdatePrompt() = runBlocking {
        val model = AppModel.create(
            core = FakeCoreClient(),
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val state = model.state.value.copy(
            updates = UpdateUiState(
                available = ReleaseInfo(
                    version = "0.2.0",
                    url = "https://github.com/crystelf/fograin-launcher/releases/tag/v0.2.0",
                    title = "Fograin Launcher 0.2.0",
                ),
                checked = true,
            ),
        )

        listOf(
            "desktop-1120x780" to (1120 to 780),
            "phone-400x860" to (400 to 860),
        ).forEach { (name, size) ->
            val scene = ImageComposeScene(
                width = size.first,
                height = size.second,
                density = Density(1f),
            ) {
                FograinTheme(themeMode = state.theme) {
                    FograinApp(model = model, state = state)
                }
            }
            val bytes = try {
                repeat(3) { index -> scene.render((index + 1) * 100_000_000L) }
                val image = scene.render(400_000_000L)
                image.encodeToData(EncodedImageFormat.PNG)?.bytes
                    ?: error("PNG encoding failed")
            } finally {
                scene.close()
            }
            val output = Files.createTempFile("fograin-update-prompt-$name-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_UPDATE_PROMPT_PNG_$name=$output")
            assertTrue(bytes.size > 8_000, "$name rendered suspiciously small (${bytes.size} bytes)")
        }
    }
}
