package com.fograin.launcher.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.ui.AppDestination
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
 * The two desktop window sizes must both
 * render, with the rail instead of the bottom bar once the window is wide.
 *
 * Writes one PNG per size and prints the paths so the reviewer can compare.
 */
@OptIn(ExperimentalComposeUiApi::class)
class AdaptiveLayoutRenderTest {

    @Test
    fun rendersCompactAndExpandedWindows() = runBlocking {
        val model = AppModel.create(
            core = FakeCoreClient(),
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val state = model.state.value.copy(destination = AppDestination.LIBRARY)

        // 1280×860 and 960×640 are the backlog acceptance sizes (both ≥ 840 dp,
        // so both show the rail); 720×900 and 400×860 cover the compact header
        // and the phone layout.
        listOf(
            "phone-400x860" to (400 to 860),
            "compact-720x900" to (720 to 900),
            "laptop-960x640" to (960 to 640),
            "desktop-1280x860" to (1280 to 860),
        ).forEach { (name, size) ->
            val bytes = render(model, state, size.first, size.second)
            val output = Files.createTempFile("fograin-r4-$name-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_R4_PNG_$name=$output")
            assertTrue(bytes.size > 8_000, "$name rendered suspiciously small (${bytes.size} bytes)")
        }
    }

    private fun render(model: AppModel, state: AppUiState, width: Int, height: Int): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            FograinTheme(themeMode = state.theme) {
                FograinApp(
                    model = model,
                    state = state,
                )
            }
        }
        return try {
            repeat(3) { index -> scene.render((index + 1) * 100_000_000L) }
            val image = scene.render(400_000_000L)
            image.encodeToData(EncodedImageFormat.PNG)?.bytes
                ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }
}
