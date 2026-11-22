package com.fograin.launcher.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.fograin.launcher.App
import com.fograin.launcher.app.AccountStage
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.ContentDependencyPrompt
import com.fograin.launcher.app.ContentManagerTab
import com.fograin.launcher.app.DownloadStage
import com.fograin.launcher.app.ExportModpackUiState
import com.fograin.launcher.app.ExportStep
import com.fograin.launcher.app.LibraryStage
import com.fograin.launcher.app.ModpackUiState
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.platform.CoreContentInstallItem
import com.fograin.launcher.platform.CoreExportSection
import com.fograin.launcher.platform.CoreModpack
import com.fograin.launcher.platform.CorePackFile
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.modpack.ModpackImportPage
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
 * Renders every page, the instance detail/settings pages, the
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
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.downloads.loadVersions()

        val base = model.state.value
        model.openJavaSettings()
        val javaPage = model.state.value
        assertTrue(javaPage.java.runtimes.isNotEmpty(), "java.list was not applied")
        assertTrue(javaPage.java.catalog.isNotEmpty(), "java.catalog was not applied")
        assertEquals(3, base.instances.size, "instances.list was not applied")
        assertTrue(base.download.versions.isNotEmpty(), "versions.list was not applied")
        assertTrue(base.account.accounts.isNotEmpty(), "accounts.list was not applied")

        model.downloads.openInstallPage("1.21.5")
        model.downloads.setInstallLoader(GameLoaderKind.FABRIC)
        model.downloads.setInstallInstanceName("雾林 1.21.5")
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

        model.downloads.setCategory(DownloadsCategory.MOD)
        model.contentBrowser.openCategory(DownloadsCategory.MOD)
        val contentBrowser = model.state.value
        assertTrue(contentBrowser.contentBrowser.projects.isNotEmpty(), "content.search was not applied")
        model.contentBrowser.openProject(contentBrowser.contentBrowser.projects.first())
        val contentProjectState = model.state.value
        assertTrue(contentProjectState.contentBrowser.versions.isNotEmpty(), "content.versions was not applied")

        model.contentBrowser.closeProject()
        model.downloads.setCategory(DownloadsCategory.MODPACK)
        val modpackCategory = model.state.value
        model.contentBrowser.openProject(modpackCategory.contentBrowser.projects.first())
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
        model.contentBrowser.closeProject()
        model.downloads.setCategory(DownloadsCategory.DATAPACK)
        val datapackCategory = model.state.value
        model.downloads.setCategory(DownloadsCategory.RESOURCEPACK)
        val resourcepackCategory = model.state.value

        model.openInstanceDetail(base.instances.first(), ContentManagerTab.MODS)
        val instanceDetail = model.state.value
        assertTrue(instanceDetail.content.items.isNotEmpty(), "content.local was not applied")
        model.content.checkUpdates()
        // Notices are transient; drop the snackbar so page captures show the
        // page itself rather than a toast from the previous action.
        model.state.value.notice?.let { model.consumeNotice(it.id) }
        val instanceDetailUpdates = model.state.value
        assertTrue(instanceDetailUpdates.content.updates.isNotEmpty(), "content.updates was not applied")
        model.content.setTab(ContentManagerTab.WORLDS)
        val instanceDetailWorlds = model.state.value
        assertTrue(instanceDetailWorlds.content.worlds.isNotEmpty(), "worlds.list was not applied")

        model.content.setTab(ContentManagerTab.DATAPACKS)
        val instanceDetailDatapacks = model.state.value
        assertTrue(
            instanceDetailDatapacks.content.datapacks.isNotEmpty(),
            "datapacks.list was not applied",
        )

        model.diag.open(base.instances.first())
        val diagnosticsPage = model.state.value
        assertTrue(
            diagnosticsPage.diag.findings.isNotEmpty(),
            "diag.analyze was not applied",
        )
        model.diag.export()
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
        val exportSections = listOf(
            CoreExportSection("servers", "servers", 2, 4_096, default = false),
            CoreExportSection("configs", "configs", 31, 262_144, default = true),
            CoreExportSection("mods", "mods", 42, 134_217_728, default = true),
            CoreExportSection("shaderpacks", "shaderpacks", 3, 5_242_880, default = true),
            CoreExportSection("resourcepacks", "resourcepacks", 5, 20_971_520, default = true),
            CoreExportSection("gameSettings", "gameSettings", 1, 8_192, default = false),
            CoreExportSection("folder:kubejs", "kubejs", 12, 73_400, default = true, folder = true),
        )
        val exportForm = instanceDetail.copy(
            libraryStage = LibraryStage.EXPORT,
            modpackExport = ExportModpackUiState(
                instanceId = "fog-forest",
                name = "雾林生存",
                author = "jerry@example.com",
                version = "1.0",
                description = "一个安静的 Fabric 生存整合包。\n\n- 含 **Sodium** 与 Lithium\n- 推荐 6 GB 内存",
            ),
        )
        val exportContent = exportForm.copy(
            modpackExport = exportForm.modpackExport.copy(
                step = ExportStep.CONTENT,
                sections = exportSections,
                selected = setOf("configs", "mods", "shaderpacks", "resourcepacks", "folder:kubejs"),
            ),
        )

        val pages = listOf(
            "home" to base.copy(destination = AppDestination.HOME),
            "library" to base.copy(destination = AppDestination.LIBRARY),
            "downloads" to base.copy(destination = AppDestination.DOWNLOADS),
            "download-queue" to base.copy(downloadQueueOpen = true),
            "instance-detail" to instanceDetail,
            "instance-detail-updates" to instanceDetailUpdates,
            "instance-detail-worlds" to instanceDetailWorlds,
            "instance-detail-datapacks" to instanceDetailDatapacks,
            "diagnostics" to diagnosticsPage,
            "diagnostics-clean" to diagnosticsClean,
            "diagnostics-exported" to diagnosticsExported,
            "modpack-import-page" to modpackSheet,
            "modpack-export-form" to exportForm,
            "modpack-export-content" to exportContent,
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
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.downloads.loadVersions()
        val base = model.state.value.copy(theme = ThemeMode.LIGHT)
        model.openInstanceDetail(base.instances.first())
        val instanceDetail = model.state.value.copy(theme = ThemeMode.LIGHT)
        model.diag.open(base.instances.first())
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
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.downloads.loadVersions()
        val base = model.state.value
        model.downloads.openInstallPage("1.21.5")
        model.downloads.setInstallLoader(GameLoaderKind.FABRIC)
        val installDownload = model.state.value.download
        assertNotNull(installDownload.plan, "version.plan was not applied")

        model.downloads.setCategory(DownloadsCategory.MOD)
        val compactContentBrowser = model.state.value
        model.contentBrowser.openProject(compactContentBrowser.contentBrowser.projects.first())
        val compactProjectSheet = model.state.value
        model.contentBrowser.closeProject()
        model.openInstanceDetail(base.instances.first(), ContentManagerTab.MODS)
        val compactInstanceDetail = model.state.value
        model.openInstanceDetail(base.instances.first(), ContentManagerTab.DATAPACKS)
        val compactDatapacks = model.state.value
        model.downloads.setCategory(DownloadsCategory.MODPACK)
        val compactModpack = model.state.value.copy(destination = AppDestination.DOWNLOADS)
        model.downloads.setCategory(DownloadsCategory.DATAPACK)
        val compactDatapackBrowser = model.state.value.copy(destination = AppDestination.DOWNLOADS)
        model.diag.open(base.instances.first())
        val compactDiagnostics = model.state.value

        val pages = listOf(
            "home" to base.copy(destination = AppDestination.HOME),
            "library" to base.copy(destination = AppDestination.LIBRARY),
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

    @Test
    fun rendersEverySettingsSection() = runBlocking {
        val core = FakeCoreClient()
        val model = AppModel.create(
            core = core,
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.openSettings()
        repeat(4) { kotlinx.coroutines.yield() }

        assertTrue(model.state.value.settingsWindow.loaded, "settings.get was not applied")

        com.fograin.launcher.app.SettingsSection.entries.forEach { section ->
            model.settings.select(section)
            val bytes = renderSettings(model, width = 1120, height = 780)
            val output = Files.createTempFile("fograin-settings-${section.name.lowercase()}-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_SETTINGS_PNG_${section.name.lowercase()}=$output")
            assertTrue(bytes.size > 8_000, "$section rendered suspiciously small (${bytes.size} bytes)")
        }

        // Compact list, then a compact detail page.
        model.state.value.let { model.settings.close() }
        model.openSettings()
        val list = renderSettings(model, width = 420, height = 900, compactList = true)
        val listOutput = Files.createTempFile("fograin-settings-compact-list-", ".png")
        listOutput.toFile().writeBytes(list)
        println("FOGRAIN_SETTINGS_PNG_compact-list=$listOutput")
        assertTrue(list.size > 6_000)

        model.settings.select(com.fograin.launcher.app.SettingsSection.APPEARANCE)
        val detail = renderSettings(model, width = 420, height = 900)
        val detailOutput = Files.createTempFile("fograin-settings-compact-detail-", ".png")
        detailOutput.toFile().writeBytes(detail)
        println("FOGRAIN_SETTINGS_PNG_compact-detail=$detailOutput")
        assertTrue(detail.size > 6_000)
    }

    @Test
    fun rendersTheSettingsTransition() = runBlocking {
        val core = FakeCoreClient()
        val model = AppModel.create(
            core = core,
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.settings.setLanguage(com.fograin.launcher.settings.AppLanguage.SIMPLIFIED_CHINESE)
        repeat(4) { kotlinx.coroutines.yield() }

        val scene = ImageComposeScene(width = 1120, height = 780, density = Density(1f)) {
            App(model)
        }
        try {
            scene.render(0L)
            model.openSettings()
            listOf(16L, 48L, 96L, 160L, 240L, 320L).forEach { offsetMs ->
                val image = scene.render(offsetMs * 1_000_000L)
                val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: byteArrayOf()
                val output = Files.createTempFile("fograin-settings-transition-$offsetMs-", ".png")
                output.toFile().writeBytes(bytes)
                println("FOGRAIN_SETTINGS_TRANSITION_PNG_$offsetMs=$output")
                assertTrue(bytes.size > 8_000, "transition frame at $offsetMs was blank")
            }
        } finally {
            scene.close()
        }
        com.fograin.launcher.platform.applyAppLanguage(
            com.fograin.launcher.settings.AppLanguage.SYSTEM,
        )
    }

    @Test
    fun rendersSettingsExitTransition() = runBlocking {
        val model = AppModel.create(
            core = FakeCoreClient(),
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.settings.setLanguage(com.fograin.launcher.settings.AppLanguage.SIMPLIFIED_CHINESE)
        repeat(4) { kotlinx.coroutines.yield() }
        model.openSettings()
        model.settings.select(com.fograin.launcher.app.SettingsSection.APPEARANCE)

        val scene = ImageComposeScene(width = 1120, height = 780, density = Density(1f)) {
            App(model)
        }
        try {
            repeat(40) { scene.render(it * 16_000_000L) }
            scene.render(700_000_000L)
            model.settings.close()
            listOf(16L, 48L, 96L, 160L, 240L, 320L, 420L).forEach { offsetMs ->
                val image = scene.render((700L + offsetMs) * 1_000_000L)
                val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: byteArrayOf()
                val output = Files.createTempFile("fograin-settings-exit-$offsetMs-", ".png")
                output.toFile().writeBytes(bytes)
                println("FOGRAIN_SETTINGS_EXIT_PNG_$offsetMs=$output")
                assertTrue(bytes.size > 8_000, "settings exit frame at $offsetMs was blank")
            }
        } finally {
            scene.close()
        }
        com.fograin.launcher.platform.applyAppLanguage(
            com.fograin.launcher.settings.AppLanguage.SYSTEM,
        )
    }

    @Test
    fun rendersCompactSettingsTransition() = runBlocking {
        val model = AppModel.create(
            core = FakeCoreClient(),
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.settings.setLanguage(com.fograin.launcher.settings.AppLanguage.SIMPLIFIED_CHINESE)
        repeat(4) { kotlinx.coroutines.yield() }

        val scene = ImageComposeScene(width = 420, height = 900, density = Density(1f)) {
            App(model)
        }
        try {
            scene.render(0L)
            model.openSettings()
            listOf(16L, 48L, 96L, 160L, 240L, 320L, 420L).forEach { offsetMs ->
                val image = scene.render(offsetMs * 1_000_000L)
                val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: byteArrayOf()
                val output = Files.createTempFile("fograin-compact-settings-transition-$offsetMs-", ".png")
                output.toFile().writeBytes(bytes)
                println("FOGRAIN_COMPACT_SETTINGS_TRANSITION_PNG_$offsetMs=$output")
                assertTrue(bytes.size > 6_000, "compact transition frame at $offsetMs was blank")
            }
        } finally {
            scene.close()
        }
        com.fograin.launcher.platform.applyAppLanguage(
            com.fograin.launcher.settings.AppLanguage.SYSTEM,
        )
    }

    @Test
    fun rendersLightSettings() = runBlocking {
        val core = FakeCoreClient()
        val model = AppModel.create(
            core = core,
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.openSettings()
        model.settings.select(com.fograin.launcher.app.SettingsSection.APPEARANCE)
        repeat(4) { kotlinx.coroutines.yield() }
        model.settings.setTheme(com.fograin.launcher.settings.ThemeMode.LIGHT)
        repeat(4) { kotlinx.coroutines.yield() }

        val bytes = renderSettings(model, width = 1120, height = 780)
        val output = Files.createTempFile("fograin-settings-light-", ".png")
        output.toFile().writeBytes(bytes)
        println("FOGRAIN_SETTINGS_LIGHT_PNG=$output")
        assertTrue(bytes.size > 8_000)
    }

    @Test
    fun rendersSettingsAtBothSplitterExtremes() = runBlocking {
        val core = FakeCoreClient()
        val model = AppModel.create(
            core = core,
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.openSettings()
        model.settings.select(com.fograin.launcher.app.SettingsSection.APPEARANCE)
        repeat(4) { kotlinx.coroutines.yield() }

        val preferences = platformServices().preferences
        val original = preferences.settingsSidebarWidth
        try {
            listOf("sidebar-narrow" to 300f, "sidebar-wide" to 900f).forEach { (name, width) ->
                preferences.settingsSidebarWidth = width
                val bytes = renderSettings(model, width = 1120, height = 780)
                val output = Files.createTempFile("fograin-settings-split-$name-", ".png")
                output.toFile().writeBytes(bytes)
                println("FOGRAIN_SETTINGS_SPLIT_PNG_$name=$output")
                assertTrue(bytes.size > 8_000, "$name split rendered too small")
            }
        } finally {
            preferences.settingsSidebarWidth = original
        }
    }

    @Test
    fun rendersSettingsSidebarCoveringWholeWindow() = runBlocking {
        val model = AppModel.create(
            core = FakeCoreClient(),
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.openSettings()
        model.settings.select(com.fograin.launcher.app.SettingsSection.APPEARANCE)
        repeat(4) { kotlinx.coroutines.yield() }

        val preferences = platformServices().preferences
        val original = preferences.settingsSidebarWidth
        preferences.settingsSidebarWidth = 500f
        val scene = ImageComposeScene(width = 1120, height = 780, density = Density(1f)) {
            com.fograin.launcher.ui.settings.SettingsWindowRoot(model)
        }
        try {
            repeat(30) { scene.render(it * 16_000_000L) }
            scene.render(600_000_000L)
            // Drag the divider all the way past the right window edge: the
            // sidebar must cover the whole content pane without a leftover
            // splitter-wide strip.
            scene.sendPointerEvent(PointerEventType.Press, Offset(509f, 390f))
            scene.render(620_000_000L)
            scene.sendPointerEvent(PointerEventType.Move, Offset(1400f, 390f))
            scene.render(650_000_000L)
            val image = scene.render(680_000_000L)
            val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: byteArrayOf()
            val output = Files.createTempFile("fograin-settings-full-cover-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_SETTINGS_FULL_COVER_PNG=$output")
            assertTrue(bytes.size > 8_000, "full-cover settings rendered too small")
        } finally {
            scene.close()
            preferences.settingsSidebarWidth = original
        }
    }

    @Test
    fun rendersSettingsRightPushMidDrag() = runBlocking {
        val model = AppModel.create(
            core = FakeCoreClient(),
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.openSettings()
        model.settings.select(com.fograin.launcher.app.SettingsSection.APPEARANCE)
        repeat(4) { kotlinx.coroutines.yield() }

        val preferences = platformServices().preferences
        val original = preferences.settingsSidebarWidth
        preferences.settingsSidebarWidth = 500f
        val scene = ImageComposeScene(width = 1120, height = 780, density = Density(1f)) {
            com.fograin.launcher.ui.settings.SettingsWindowRoot(model)
        }
        try {
            repeat(5) { scene.render(it * 16_000_000L) }
            // Grab the splitter at x = 500 and drag far past the 68 % limit
            // without releasing, so the content page's right-push state is
            // captured.
            scene.sendPointerEvent(PointerEventType.Press, Offset(509f, 390f))
            scene.render(120_000_000L)
            scene.sendPointerEvent(PointerEventType.Move, Offset(1000f, 390f))
            scene.render(240_000_000L)
            val image = scene.render(360_000_000L)
            val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: byteArrayOf()
            val output = Files.createTempFile("fograin-settings-right-push-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_SETTINGS_RIGHT_PUSH_PNG=$output")
            assertTrue(bytes.size > 8_000, "right-push settings rendered too small")
        } finally {
            scene.close()
            preferences.settingsSidebarWidth = original
        }
    }

    @Test
    fun rendersSettingsInEveryLanguage() = runBlocking {
        val core = FakeCoreClient()
        val model = AppModel.create(
            core = core,
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.openSettings()
        model.settings.select(com.fograin.launcher.app.SettingsSection.APPEARANCE)
        repeat(4) { kotlinx.coroutines.yield() }

        listOf(
            "en" to com.fograin.launcher.settings.AppLanguage.ENGLISH,
            "ja" to com.fograin.launcher.settings.AppLanguage.JAPANESE,
            "zh-hant" to com.fograin.launcher.settings.AppLanguage.TRADITIONAL_CHINESE,
        ).forEach { (name, language) ->
            model.settings.setLanguage(language)
            repeat(4) { kotlinx.coroutines.yield() }
            val bytes = renderSettings(model, width = 1120, height = 780)
            val output = Files.createTempFile("fograin-settings-$name-", ".png")
            output.toFile().writeBytes(bytes)
            println("FOGRAIN_SETTINGS_LANG_PNG_$name=$output")
            assertTrue(bytes.size > 8_000, "$name settings rendered suspiciously small")

            val compactBytes = renderSettings(model, width = 420, height = 900)
            val compactOutput = Files.createTempFile("fograin-settings-$name-compact-", ".png")
            compactOutput.toFile().writeBytes(compactBytes)
            println("FOGRAIN_SETTINGS_LANG_PNG_${name}_compact=$compactOutput")
            assertTrue(compactBytes.size > 6_000, "$name compact settings too small")
        }
        model.settings.setLanguage(com.fograin.launcher.settings.AppLanguage.SYSTEM)
        com.fograin.launcher.platform.applyAppLanguage(
            com.fograin.launcher.settings.AppLanguage.SYSTEM,
        )
    }

    private fun renderSettings(
        model: AppModel,
        width: Int,
        height: Int,
        compactList: Boolean = false,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            if (compactList) {
                // Re-opening settings always lands on the category list.
                com.fograin.launcher.ui.settings.SettingsWindowRoot(model)
            } else {
                com.fograin.launcher.ui.settings.SettingsWindowRoot(model)
            }
        }
        return try {
            // render() takes nanoseconds; the pane entrance runs for 420 ms.
            repeat(30) { scene.render(it * 16_000_000L) }
            scene.render(600_000_000L).encodeToData(EncodedImageFormat.PNG)?.bytes
                ?: error("settings scene produced no PNG")
        } finally {
            scene.close()
        }
    }

    private fun render(
        model: AppModel,
        state: AppUiState,
        width: Int = 1120,
        height: Int = 780,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            FograinTheme(themeMode = state.theme, accent = state.accent.value) {
                AppLocale(state.language) {
                    if (state.modpack.pack != null) {
                        ModpackImportPage(model = model, state = state)
                    } else {
                        FograinApp(model = model, state = state)
                    }
                }
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
