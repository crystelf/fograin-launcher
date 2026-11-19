package com.fograin.launcher.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AccountStage
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.ContentDependencyPrompt
import com.fograin.launcher.app.ContentManagerTab
import com.fograin.launcher.app.DownloadStage
import com.fograin.launcher.app.LibraryStage
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.app.ModpackUiState
import com.fograin.launcher.app.DiagUiState
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.platform.CoreContentInstallItem
import com.fograin.launcher.platform.CoreModpack
import com.fograin.launcher.platform.CorePackFile
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
 * R2 acceptance: render every page, the instance detail/settings pages, the
 * remaining sheets and the game install flow with deterministic data, in both
 * dark and light themes plus compact and landscape phone-sized layouts. Each
 * frame is written to a temp PNG and printed so a reviewer can compare against
 * the pen; the test fails when a page crashes or renders (nearly) blank.
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
        assertTrue(base.account.accounts.isNotEmpty(), "accounts.list was not applied")

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

        model.setDownloadCategory(DownloadsCategory.MOD)
        model.openContentCategory(DownloadsCategory.MOD)
        val contentBrowser = model.state.value
        assertTrue(contentBrowser.contentBrowser.projects.isNotEmpty(), "content.search was not applied")
        model.openContentProject(contentBrowser.contentBrowser.projects.first())
        val contentProjectState = model.state.value
        assertTrue(contentProjectState.contentBrowser.versions.isNotEmpty(), "content.versions was not applied")

        model.closeContentProject()
        model.setDownloadCategory(DownloadsCategory.MODPACK)
        val modpackCategory = model.state.value
        model.openContentProject(modpackCategory.contentBrowser.projects.first())
        val modpackProjectSheet = model.state.value
        val dependencyPromptSheet = contentProjectState.copy(
            contentBrowser = contentProjectState.contentBrowser.copy(
                dependencyPrompt = ContentDependencyPrompt(
                    projectName = "Sodium",
                    items = listOf(
                        CoreContentInstallItem(
                            fileName = "fabric-api-0.119.4+1.21.4.jar",
                            projectId = "P7dR8mSH",
                            projectName = "Fabric API",
                            versionId = "api-v",
                            versionNumber = "0.119.4+1.21.4",
                            dependency = true,
                            size = 2_120_000,
                        ),
                    ),
                ),
            ),
        )
        model.closeContentProject()
        model.setDownloadCategory(DownloadsCategory.DATAPACK)
        val datapackCategory = model.state.value
        model.setDownloadCategory(DownloadsCategory.RESOURCEPACK)
        val resourcepackCategory = model.state.value

        model.openInstanceDetail(base.instances.first(), ContentManagerTab.MODS)
        val instanceDetail = model.state.value
        assertTrue(instanceDetail.content.items.isNotEmpty(), "content.local was not applied")
        model.checkContentUpdates()
        // Notices are transient; drop the snackbar so page captures show the
        // page itself rather than a toast from the previous action.
        model.state.value.notice?.let { model.consumeNotice(it.id) }
        val instanceDetailUpdates = model.state.value
        assertTrue(instanceDetailUpdates.content.updates.isNotEmpty(), "content.updates was not applied")
        model.setContentTab(ContentManagerTab.WORLDS)
        val instanceDetailWorlds = model.state.value
        assertTrue(instanceDetailWorlds.content.worlds.isNotEmpty(), "worlds.list was not applied")

        model.setContentTab(ContentManagerTab.DATAPACKS)
        val instanceDetailDatapacks = model.state.value
        assertTrue(
            instanceDetailDatapacks.content.datapacks.isNotEmpty(),
            "datapacks.list was not applied",
        )

        model.openDiagnostics(base.instances.first())
        val diagnosticsPage = model.state.value
        assertTrue(
            diagnosticsPage.diag.findings.isNotEmpty(),
            "diag.analyze was not applied",
        )
        model.exportDiagnostics()
        model.state.value.notice?.let { model.consumeNotice(it.id) }
        val diagnosticsExported = model.state.value
        assertNotNull(diagnosticsExported.diag.bundlePath, "diag.export was not applied")

        val diagnosticsClean = diagnosticsPage.copy(
            diag = diagnosticsPage.diag.copy(
                analysis = diagnosticsPage.diag.analysis?.copy(
                    findings = emptyList(),
                    severity = null,
                    summary = "没有匹配到已知问题。如果游戏仍然无法启动，请查看日志末尾的异常堆栈。",
                ),
            ),
        )

        val modpackSheet = base.copy(
            destination = AppDestination.LIBRARY,
            modpack = ModpackUiState(
                pack = CoreModpack(
                    kind = "modrinth",
                    name = "Calm Pack",
                    version = "1.2.0",
                    minecraft = "1.21.4",
                    loaderKind = "fabric",
                    loaderVersion = "0.16.9",
                    summary = "A quiet Fabric pack for building.",
                    files = listOf(
                        CorePackFile("mods/sodium.jar", 1306799, sha1 = "aa"),
                        CorePackFile("mods/lithium.jar", 920000, sha1 = "cc"),
                    ),
                    sourcePath = "/tmp/Calm Pack.mrpack",
                ),
                name = "Calm Pack",
            ),
        )
        val instanceDetailEmpty = instanceDetail.copy(
            content = com.fograin.launcher.app.ContentManagerUiState(
                instanceId = instanceDetail.content.instanceId,
            ),
        )

        val pages = listOf(
            "home" to base.copy(destination = AppDestination.HOME),
            "library" to base.copy(destination = AppDestination.LIBRARY),
            "downloads" to base.copy(destination = AppDestination.DOWNLOADS),
            "download-queue" to base.copy(downloadQueueOpen = true),
            "settings" to base.copy(destination = AppDestination.SETTINGS),
            "java-page" to javaPage.copy(
                destination = AppDestination.SETTINGS,
                settingsStage = com.fograin.launcher.app.SettingsStage.JAVA,
            ),
            "instance-detail" to instanceDetail,
            "instance-detail-updates" to instanceDetailUpdates,
            "instance-detail-worlds" to instanceDetailWorlds,
            "instance-detail-datapacks" to instanceDetailDatapacks,
            "diagnostics" to diagnosticsPage,
            "diagnostics-clean" to diagnosticsClean,
            "diagnostics-exported" to diagnosticsExported,
            "modpack-import-sheet" to modpackSheet,
            "instance-detail-empty" to instanceDetailEmpty,
            "instance-settings-page" to instanceDetail.copy(
                libraryStage = LibraryStage.SETTINGS,
            ),
            "global-search-sheet" to base.copy(
                globalSearchOpen = true,
                globalSearchQuery = "1.21",
            ),
            "install-page" to installPage,
            "loader-versions-page" to installPage.copy(
                download = installDownload.copy(stage = DownloadStage.LOADER_VERSIONS),
            ),
            "account-sheet" to base.copy(account = base.account.copy(open = true)),
            "account-offline-sheet" to base.copy(
                account = base.account.copy(open = true, stage = AccountStage.OFFLINE),
            ),
            "account-yggdrasil-sheet" to base.copy(
                account = base.account.copy(open = true, stage = AccountStage.YGGDRASIL),
            ),
            "account-profiles-sheet" to base.copy(
                account = base.account.copy(
                    open = true,
                    stage = AccountStage.PROFILES,
                    pendingId = "pending-1",
                    pendingServerName = "LittleSkin",
                    pendingProfiles = listOf(
                        com.fograin.launcher.platform.CoreYggdrasilProfile("profile-1", "Fograin"),
                        com.fograin.launcher.platform.CoreYggdrasilProfile("profile-2", "FograinAlt"),
                    ),
                ),
            ),
            "content-browser" to contentBrowser.copy(
                destination = AppDestination.DOWNLOADS,
            ),
            "content-project-sheet" to contentProjectState.copy(
                destination = AppDestination.DOWNLOADS,
            ),
            "content-dependency-dialog" to dependencyPromptSheet.copy(
                destination = AppDestination.DOWNLOADS,
            ),
            "downloads-modpack" to modpackCategory.copy(destination = AppDestination.DOWNLOADS),
            "downloads-modpack-sheet" to modpackProjectSheet.copy(
                destination = AppDestination.DOWNLOADS,
            ),
            "downloads-datapack" to datapackCategory.copy(destination = AppDestination.DOWNLOADS),
            "downloads-resourcepack" to resourcepackCategory.copy(
                destination = AppDestination.DOWNLOADS,
            ),
            "modpack-drop-overlay" to base.copy(
                destination = AppDestination.HOME,
                modpack = ModpackUiState(dragActive = true),
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
        model.openInstanceDetail(base.instances.first())
        val instanceDetail = model.state.value.copy(theme = ThemeMode.LIGHT)
        model.openDiagnostics(base.instances.first())
        val lightDiagnostics = model.state.value.copy(theme = ThemeMode.LIGHT)

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
            "download-queue" to base.copy(downloadQueueOpen = true),
            "settings" to base.copy(destination = AppDestination.SETTINGS),
            "java-page" to base.copy(
                destination = AppDestination.SETTINGS,
                settingsStage = com.fograin.launcher.app.SettingsStage.JAVA,
            ),
            "instance-detail" to instanceDetail,
            "instance-settings-page" to instanceDetail.copy(
                libraryStage = LibraryStage.SETTINGS,
            ),
            "diagnostics" to lightDiagnostics,
            "account-sheet" to base.copy(account = base.account.copy(open = true)),
            "content-browser" to base.copy(
                destination = AppDestination.DOWNLOADS,
                download = base.download.copy(category = DownloadsCategory.MOD),
                contentBrowser = base.contentBrowser.copy(
                    projects = base.contentBrowser.projects,
                ),
            ),
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

        model.setDownloadCategory(DownloadsCategory.MOD)
        val compactContentBrowser = model.state.value
        model.openContentProject(compactContentBrowser.contentBrowser.projects.first())
        val compactProjectSheet = model.state.value
        model.closeContentProject()
        model.openInstanceDetail(base.instances.first(), ContentManagerTab.MODS)
        val compactInstanceDetail = model.state.value
        model.openInstanceDetail(base.instances.first(), ContentManagerTab.DATAPACKS)
        val compactDatapacks = model.state.value
        model.setDownloadCategory(DownloadsCategory.MODPACK)
        val compactModpack = model.state.value.copy(destination = AppDestination.DOWNLOADS)
        model.setDownloadCategory(DownloadsCategory.DATAPACK)
        val compactDatapackBrowser = model.state.value.copy(destination = AppDestination.DOWNLOADS)
        model.openDiagnostics(base.instances.first())
        val compactDiagnostics = model.state.value

        val pages = listOf(
            "home" to base.copy(destination = AppDestination.HOME),
            "library" to base.copy(destination = AppDestination.LIBRARY),
            "settings" to base.copy(destination = AppDestination.SETTINGS),
            "downloads" to base.copy(destination = AppDestination.DOWNLOADS),
            "download-queue" to base.copy(downloadQueueOpen = true),
            "content-browser" to compactContentBrowser.copy(
                destination = AppDestination.DOWNLOADS,
            ),
            "content-project-sheet" to compactProjectSheet.copy(
                destination = AppDestination.DOWNLOADS,
            ),
            "downloads-modpack" to compactModpack,
            "downloads-datapack" to compactDatapackBrowser,
            "instance-detail" to compactInstanceDetail,
            "instance-settings-page" to compactInstanceDetail.copy(
                libraryStage = LibraryStage.SETTINGS,
            ),
            "install-page" to base.copy(
                destination = AppDestination.DOWNLOADS,
                download = installDownload,
            ),
            "loader-versions-page" to base.copy(
                destination = AppDestination.DOWNLOADS,
                download = installDownload.copy(stage = DownloadStage.LOADER_VERSIONS),
            ),
            "diagnostics" to compactDiagnostics,
            "instance-detail-datapacks" to compactDatapacks,
        )

        pages.forEach { (name, state) ->
            val bytes = render(model, state, width = 420, height = 900)
            val output = Files.createTempFile("fograin-compact-$name-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_COMPACT_PNG_$name=$output")
            assertTrue(bytes.size > 6_000, "$name rendered suspiciously small (${bytes.size} bytes)")
        }

        // Landscape phones are too short for the summary card; the detail page
        // must still leave usable room for the content list.
        val landscape = render(model, compactInstanceDetail, width = 900, height = 420)
        val landscapeOutput = Files.createTempFile("fograin-compact-landscape-instance-detail-", ".png")
        landscapeOutput.toFile().writeBytes(landscape)
        println("FOGRAIN_COMPACT_PNG_landscape-instance-detail=$landscapeOutput")
        assertTrue(landscape.size > 6_000, "landscape instance detail rendered too small")
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
