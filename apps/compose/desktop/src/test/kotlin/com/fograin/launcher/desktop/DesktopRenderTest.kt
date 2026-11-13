package com.fograin.launcher.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fograin.launcher.App
import com.fograin.launcher.platform.AppRuntime
import com.fograin.launcher.platform.createCoreClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders the shared shell off-screen so desktop layout regressions surface
 * without a display server. The PNG path is printed for manual inspection.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopRenderTest {
    @Test
    fun rendersSharedShell() = runBlocking {
        AppRuntime.dataRoot = TestDataRoot.path
        val core = createCoreClient()
        val scene = ImageComposeScene(width = 1120, height = 780, density = Density(1f)) {
            App(core)
        }

        try {
            // The first frame is the loading state; let the IO snapshot finish
            // and render again so the cards contain real values.
            scene.render()
            Thread.sleep(1200)
            val image = scene.render(System.nanoTime())
            val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
                ?: error("PNG encoding failed")
            val output = Files.createTempFile("fograin-p0-desktop-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_RENDER_PNG=$output")
            assertTrue(bytes.size > 10_000, "rendered PNG is suspiciously small")
        } finally {
            scene.close()
        }
    }
}
