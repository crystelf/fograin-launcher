package com.fograin.launcher.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.DownloadStage
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.theme.FograinTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * R2 acceptance: render every page, the inspector sheets and the game install
 * flow with deterministic data, in both dark and light themes plus a compact
 * phone-sized layout. Each frame is written to a temp PNG and printed so a
 * reviewer can compare against the pen; the test fails when a page crashes or
 * renders (nearly) blank.
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
        model.openJavaSettings()
        val javaPage = model.state.value
        assertTrue(javaPage.java.runtimes.isNotEmpty(), "java.list was not applied")
        assertTrue(javaPage.java.catalog.isNotEmpty(), "java.catalog was not applied")
        assertEquals(3, base.instances.size, "instances.list was not applied")
        assertTrue(base.download.versions.isNotEmpty(), "versions.list was not applied")

        model.openInstallPage("1.21.5")
        model.setInstallLoader(GameLoaderKind.FABRIC)
        model.setInstallInstanceName("雾林 1.21.5")
        val installDownload = model.state.value.download
        assertEquals(DownloadStage.INSTALL, installDownload.stage)
        assertNotNull(installDownload.plan, "version.plan was not applied")
        assertTrue(
            installDownload.loaderVersions.isNotEmpty(),
            "loaders.versions was not applied",
        )
        val installPage = base.copy(
            destination = AppDestination.DOWNLOADS,
            download = installDownload,
        )

        val pages = listOf(
            "home" to base.copy(destination = AppDestination.HOME),
            "library" to base.copy(destination = AppDestination.LIBRARY),
            "downloads" to base.copy(destination = AppDestination.DOWNLOADS),
            "settings" to base.copy(destination = AppDestination.SETTINGS),
            "java-page" to javaPage.copy(
                destination = AppDestination.SETTINGS,
                settingsStage = com.fograin.launcher.app.SettingsStage.JAVA,
            ),
            "preview-sheet" to base.copy(
                destination = AppDestination.LIBRARY,
                previewInstanceId = "fog-forest",
            ),
            "instance-settings-sheet" to base.copy(instanceSettingsId = "fog-forest"),
            "global-search-sheet" to base.copy(
                globalSearchOpen = true,
                globalSearchQuery = "1.21",
            ),
            "install-page" to installPage,
            "loader-versions-page" to installPage.copy(
                download = installDownload.copy(stage = DownloadStage.LOADER_VERSIONS),
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

        val installPage = base.copy(
            destination = AppDestination.DOWNLOADS,
            download = base.download.copy(
                stage = DownloadStage.INSTALL,
                installVersionId = "1.21.5",
                installLoader = GameLoaderKind.FABRIC,
                installLoaderVersion = "0.16.9",
            ),
        )

        val pages = listOf(
            "home" to base.copy(destination = AppDestination.HOME),
            "library" to base.copy(destination = AppDestination.LIBRARY),
            "downloads" to base.copy(destination = AppDestination.DOWNLOADS),
            "settings" to base.copy(destination = AppDestination.SETTINGS),
            "java-page" to base.copy(
                destination = AppDestination.SETTINGS,
                settingsStage = com.fograin.launcher.app.SettingsStage.JAVA,
            ),
            "instance-settings-sheet" to base.copy(instanceSettingsId = "fog-forest"),
            "install-page" to installPage,
        )

        pages.forEach { (name, state) ->
            val bytes = render(model, state)
            val output = Files.createTempFile("fograin-light-$name-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_LIGHT_PNG_$name=$output")
            assertTrue(bytes.size > 8_000, "$name rendered suspiciously small (${bytes.size} bytes)")
        }
    }

    @Test
    fun rendersCompactFlow() = runBlocking {
        val core = FakeCoreClient()
        val model = AppModel.create(
            core = core,
            platform = platformServices(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.loadDownloadVersions()
        val base = model.state.value
        model.openInstallPage("1.21.5")
        model.setInstallLoader(GameLoaderKind.FABRIC)
        val installDownload = model.state.value.download
        assertNotNull(installDownload.plan, "version.plan was not applied")

        val pages = listOf(
            "downloads" to base.copy(destination = AppDestination.DOWNLOADS),
            "install-page" to base.copy(
                destination = AppDestination.DOWNLOADS,
                download = installDownload,
            ),
            "loader-versions-page" to base.copy(
                destination = AppDestination.DOWNLOADS,
                download = installDownload.copy(stage = DownloadStage.LOADER_VERSIONS),
            ),
        )

        pages.forEach { (name, state) ->
            val bytes = render(model, state, width = 420, height = 900)
            val output = Files.createTempFile("fograin-compact-$name-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_COMPACT_PNG_$name=$output")
            assertTrue(bytes.size > 6_000, "$name rendered suspiciously small (${bytes.size} bytes)")
        }
    }

    private fun render(
        model: AppModel,
        state: AppUiState,
        width: Int = 1120,
        height: Int = 780,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
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
