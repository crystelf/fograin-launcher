package com.fograin.launcher.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.fograin.launcher.App
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.ContentManagerTab
import com.fograin.launcher.app.DownloadStage
import com.fograin.launcher.app.LibraryStage
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.GlobalSearchButtonTag
import com.fograin.launcher.ui.accounts.AccountAddOfflineTag
import com.fograin.launcher.ui.accounts.AccountOfflineNameTag
import com.fograin.launcher.ui.accounts.AccountSheetTag
import com.fograin.launcher.ui.home.AccountAvatarTag
import com.fograin.launcher.ui.home.HomeDownloadQueueTag
import com.fograin.launcher.ui.home.HomeImportModpackTag
import com.fograin.launcher.ui.home.HomeListTag
import com.fograin.launcher.ui.home.HomeOverflowTag
import com.fograin.launcher.ui.home.HomeTaskQueueTag
import com.fograin.launcher.ui.home.homeDownloadPauseTag
import com.fograin.launcher.ui.home.homeDownloadResumeTag
import com.fograin.launcher.ui.NavBarTag
import com.fograin.launcher.ui.NavRailTag
import com.fograin.launcher.ui.content.ContentCheckUpdatesTag
import com.fograin.launcher.ui.content.ContentDependencyAutoTag
import com.fograin.launcher.ui.content.ContentDependencyDialogTag
import com.fograin.launcher.ui.content.ContentInstallTag
import com.fograin.launcher.ui.content.ContentVersionChannelTag
import com.fograin.launcher.ui.content.contentProjectTag
import com.fograin.launcher.ui.content.contentDependencyItemTag
import com.fograin.launcher.ui.content.contentVersionTag
import com.fograin.launcher.ui.content.contentTabTag
import com.fograin.launcher.ui.downloads.ContentTargetTag
import com.fograin.launcher.ui.downloads.DownloadQueueTag
import com.fograin.launcher.ui.downloads.DownloadsQueueCardTag
import com.fograin.launcher.ui.downloads.contentTargetOptionTag
import com.fograin.launcher.ui.downloads.downloadQueuePauseTag
import com.fograin.launcher.ui.downloads.downloadQueueResumeTag
import com.fograin.launcher.ui.downloads.downloadQueueRowTag
import com.fograin.launcher.ui.downloads.downloadQueueStopTag
import com.fograin.launcher.ui.downloads.InstallDownloadTag
import com.fograin.launcher.ui.downloads.downloadVersionTag
import com.fograin.launcher.ui.downloads.downloadsCategoryTag
import com.fograin.launcher.ui.instances.InstanceDetailTag
import com.fograin.launcher.ui.diagnostics.DiagnosticsPageTag
import com.fograin.launcher.ui.diagnostics.DiagnosticsExportTag
import com.fograin.launcher.ui.instances.InstanceFavoriteTag
import com.fograin.launcher.ui.instances.InstanceOverflowTag
import com.fograin.launcher.ui.instances.InstanceQuickStartTag
import com.fograin.launcher.ui.instances.InstanceSettingsPageTag
import com.fograin.launcher.ui.library.libraryRowTag
import com.fograin.launcher.ui.search.GlobalSearchFieldTag
import com.fograin.launcher.ui.search.globalSearchCommandTag
import com.fograin.launcher.ui.modpack.ModpackImportConfirmTag
import com.fograin.launcher.ui.modpack.ModpackImportPageTag
import com.fograin.launcher.app.SettingsSection
import com.fograin.launcher.ui.settings.JavaScreenTag
import com.fograin.launcher.ui.settings.SettingsJavaOpenTag
import com.fograin.launcher.ui.settings.SettingsCompactBackTag
import com.fograin.launcher.ui.settings.SettingsCompactCloseTag
import com.fograin.launcher.ui.settings.SettingsCompactListTag
import com.fograin.launcher.ui.settings.SettingsLanguageRowTag
import com.fograin.launcher.ui.settings.SettingsWindowBackTag
import com.fograin.launcher.ui.settings.SettingsPageListTag
import com.fograin.launcher.ui.settings.SettingsContentPageTag
import com.fograin.launcher.ui.settings.SettingsContentTag
import com.fograin.launcher.ui.settings.SettingsSidebarPageTag
import com.fograin.launcher.ui.settings.SettingsSidebarTag
import com.fograin.launcher.ui.settings.SettingsSplitterTag
import com.fograin.launcher.ui.settings.SettingsWindowTag
import com.fograin.launcher.ui.settings.settingsNavTag
import com.fograin.launcher.ui.components.PageHeaderBackTag
import com.fograin.launcher.ui.navItemTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Compose UI coverage for the shell: the adaptive breakpoint and the four
 * destinations, rendered with the deterministic fake core.
 *
 * These run headless (Skiko software rendering), so CI needs no display server.
 * The 840 dp breakpoint means a 960-px window already shows the rail; the
 * compact case is a phone-sized or half-screen window.
 */
@OptIn(ExperimentalTestApi::class)
class FograinAppUiTest {

    /**
     * UI tests assert Simplified Chinese labels; force the language instead of
     * inheriting the CI machine's locale (the launcher defaults to "follow
     * system", so an English runner would otherwise render English).
     */
    private fun model(): AppModel {
        val created = AppModel.create(
            core = FakeCoreClient(),
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        created.setLanguage(com.fograin.launcher.settings.AppLanguage.SIMPLIFIED_CHINESE)
        return created
    }

    @Test
    fun narrowWindowKeepsBottomBar() = runDesktopComposeUiTest(width = 720, height = 900) {
        val appModel = model()
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(NavBarTag).assertIsDisplayed()
        onNodeWithTag(NavRailTag).assertDoesNotExist()
        AppDestination.entries.forEach { onNodeWithTag(navItemTag(it)).assertIsDisplayed() }

        // On macOS the narrow header leaves the traffic-light strip above it;
        // on the other desktop platforms it stays at the top edge.
        if (platformServices().platformName == "macos") {
            val avatarTop = onNodeWithTag(AccountAvatarTag)
                .fetchSemanticsNode().boundsInRoot.top
            assertTrue(avatarTop >= 40f, "narrow header should sit below the lights; was $avatarTop")
        }
    }

    @Test
    fun wideWindowSwapsToNavigationRail() = runDesktopComposeUiTest(width = 1280, height = 860) {
        val appModel = model()
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(NavRailTag).assertIsDisplayed()
        onNodeWithTag(NavBarTag).assertDoesNotExist()
        onNodeWithTag(GlobalSearchButtonTag).assertIsDisplayed()
        AppDestination.entries.forEach { onNodeWithTag(navItemTag(it)).assertIsDisplayed() }

        // Home stays focused: the old runtime/news panels were removed.
        onNodeWithText("运行环境").assertDoesNotExist()
        onNodeWithText("版本动态").assertDoesNotExist()

        // The page title row sits at the window edge (not below a reserved
        // title-bar strip), while the rail search button is lowered to the
        // first content card.
        val avatarTop = onNodeWithTag(AccountAvatarTag)
            .fetchSemanticsNode().boundsInRoot.top
        val searchTop = onNodeWithTag(GlobalSearchButtonTag)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(avatarTop < 10f, "page header should hug the window top; was $avatarTop")
        assertTrue(searchTop >= 56f, "rail search should sit below the title row; was $searchTop")

        // The three content tabs sit a proportional gap below the top edge and
        // Settings is pinned to the bottom of the rail.
        val homeTop = onNodeWithTag(navItemTag(AppDestination.HOME))
            .fetchSemanticsNode().boundsInRoot.top
        val settingsTop = onNodeWithTag(navItemTag(AppDestination.SETTINGS))
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(homeTop > 40f, "content tabs should not hug the top edge")
        assertTrue(settingsTop > homeTop + 100f, "Settings belongs at the bottom")
    }

    @Test
    fun laptopWindowUsesRailToo() = runDesktopComposeUiTest(width = 960, height = 640) {
        val appModel = model()
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(NavRailTag).assertIsDisplayed()
        onAllNodesWithTag(NavBarTag).assertCountEquals(0)
    }



    @Test
    fun instanceSettingsOpensDiagnosticsAndExports() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val appModel = model()
        appModel.navigate(AppDestination.LIBRARY)
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(libraryRowTag("fog-forest")).performClick()
        waitForIdle()
        onNodeWithTag(InstanceOverflowTag).performClick()
        waitForIdle()
        onNodeWithText("游戏设置").performClick()
        waitForIdle()

        onNodeWithText("日志与诊断").performScrollTo().performClick()
        waitForIdle()

        assertEquals(LibraryStage.DIAGNOSTICS, appModel.state.value.libraryStage)
        onNodeWithTag(DiagnosticsPageTag).assertIsDisplayed()
        onNodeWithText("内存不足").assertIsDisplayed()

        onNodeWithTag(DiagnosticsExportTag).performClick()
        waitForIdle()
        assertTrue(appModel.state.value.diag.bundlePath != null)

        onNodeWithTag(PageHeaderBackTag).performClick()
        waitForIdle()
        assertEquals(LibraryStage.SETTINGS, appModel.state.value.libraryStage)
    }

    @Test
    fun datapackTabLoadsTheSelectedWorld() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val appModel = model()
        appModel.navigate(AppDestination.LIBRARY)
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(libraryRowTag("fog-forest")).performClick()
        waitForIdle()
        onNodeWithTag(contentTabTag(ContentManagerTab.DATAPACKS)).performClick()
        waitForIdle()

        assertEquals(ContentManagerTab.DATAPACKS, appModel.state.value.content.tab)
        assertTrue(appModel.state.value.content.datapacks.isNotEmpty())
        onNodeWithText("Terralith 地形").assertIsDisplayed()
    }

    @Test
    fun homeOverflowOffersTheModpackImport() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val appModel = model()
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(HomeOverflowTag).performClick()
        waitForIdle()
        onNodeWithTag(HomeImportModpackTag).assertIsDisplayed()
    }

    @Test
    fun downloadQueueOpensInsideTheNavigationShell() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(HomeOverflowTag).performClick()
            waitForIdle()
            onNodeWithTag(HomeDownloadQueueTag).performClick()
            waitForIdle()

            assertTrue(appModel.state.value.downloadQueueOpen)
            onNodeWithTag(DownloadQueueTag).assertIsDisplayed()
            onNodeWithTag(downloadQueueRowTag("task-1")).assertIsDisplayed()
            // The queue shares the instance-detail chrome: rail on the left,
            // so the back button lines up with every other page.
            onNodeWithTag(NavRailTag).assertIsDisplayed()

            onNodeWithTag(PageHeaderBackTag).performClick()
            waitForIdle()
            assertEquals(false, appModel.state.value.downloadQueueOpen)
            onNodeWithTag(DownloadQueueTag).assertDoesNotExist()
        }

    @Test
    fun downloadQueueRailSwitchesDestination() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(HomeOverflowTag).performClick()
            waitForIdle()
            onNodeWithTag(HomeDownloadQueueTag).performClick()
            waitForIdle()

            onNodeWithTag(navItemTag(AppDestination.LIBRARY)).performClick()
            waitForIdle()

            assertEquals(false, appModel.state.value.downloadQueueOpen)
            assertEquals(AppDestination.LIBRARY, appModel.state.value.destination)
        }

    @Test
    fun compactDownloadQueueKeepsTheBottomBar() =
        runDesktopComposeUiTest(width = 720, height = 900) {
            val appModel = model()
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(HomeOverflowTag).performClick()
            waitForIdle()
            onNodeWithTag(HomeDownloadQueueTag).performClick()
            waitForIdle()

            onNodeWithTag(DownloadQueueTag).assertIsDisplayed()
            onNodeWithTag(NavBarTag).assertIsDisplayed()

            onNodeWithTag(PageHeaderBackTag).performClick()
            waitForIdle()
            assertEquals(false, appModel.state.value.downloadQueueOpen)
        }

    @Test
    fun downloadQueueControlsPauseResumeAndStop() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(HomeOverflowTag).performClick()
            waitForIdle()
            onNodeWithTag(HomeDownloadQueueTag).performClick()
            waitForIdle()

            onNodeWithTag(downloadQueuePauseTag("task-1")).performClick()
            waitForIdle()
            assertEquals(
                "paused",
                appModel.state.value.downloads.first { it.id == "task-1" }.state,
            )
            onNodeWithTag(downloadQueueResumeTag("task-1")).performClick()
            waitForIdle()
            assertEquals(
                "running",
                appModel.state.value.downloads.first { it.id == "task-1" }.state,
            )
            onNodeWithTag(downloadQueueStopTag("task-1")).performClick()
            waitForIdle()
            assertEquals(
                "cancelled",
                appModel.state.value.downloads.first { it.id == "task-1" }.state,
            )
            onNodeWithTag(downloadQueueRowTag("task-1")).assertDoesNotExist()
        }

    @Test
    fun downloadsActiveCardOpensTheQueue() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.DOWNLOADS)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(DownloadsQueueCardTag).performClick()
            waitForIdle()
            assertTrue(appModel.state.value.downloadQueueOpen)
            onNodeWithTag(DownloadQueueTag).assertIsDisplayed()
        }

    @Test
    fun homeQueueControlsWorkWithoutOpeningTheFullPage() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(HomeListTag)
                .performScrollToNode(hasTestTag(HomeTaskQueueTag))
            waitForIdle()
            onNodeWithTag(homeDownloadPauseTag("task-1")).performClick()
            waitForIdle()
            assertEquals(
                "paused",
                appModel.state.value.downloads.first { it.id == "task-1" }.state,
            )
            onNodeWithTag(homeDownloadResumeTag("task-1")).performClick()
            waitForIdle()
            assertEquals(
                "running",
                appModel.state.value.downloads.first { it.id == "task-1" }.state,
            )
        }

    @Test
    fun importEntryShowsTheConfirmationPage() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val appModel = model()
        setContent { App(appModel) }
        waitForIdle()

        // The home menu and the desktop drop target both call this entry point.
        appModel.importModpackPath("/tmp/Calm Pack.mrpack")
        waitForIdle()

        onNodeWithTag(ModpackImportPageTag).assertIsDisplayed()
        onNodeWithTag(ModpackImportConfirmTag).assertIsDisplayed()
    }

    @Test
    fun instanceSettingsExportsThroughTheTwoStepWizard() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.LIBRARY)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(libraryRowTag("fog-forest")).performClick()
            waitForIdle()
            onNodeWithTag(InstanceOverflowTag).performClick()
            waitForIdle()
            onNodeWithText("游戏设置").performClick()
            waitForIdle()
            onNodeWithText("导出整合包").performScrollTo().performClick()
            waitForIdle()

            assertEquals(LibraryStage.EXPORT, appModel.state.value.libraryStage)
            onNodeWithTag(com.fograin.launcher.ui.modpack.ModpackExportPageTag)
                .assertIsDisplayed()
            assertEquals("雾林生存", appModel.state.value.modpackExport.name)

            onNodeWithTag(com.fograin.launcher.ui.modpack.ModpackExportContinueTag)
                .performClick()
            waitForIdle()
            assertEquals(
                com.fograin.launcher.app.ExportStep.CONTENT,
                appModel.state.value.modpackExport.step,
            )
            assertTrue(
                appModel.state.value.modpackExport.sections.isNotEmpty(),
                "modpack.export.plan was not applied",
            )
            assertTrue("mods" in appModel.state.value.modpackExport.selected)

            onNodeWithTag(com.fograin.launcher.ui.modpack.ModpackExportConfirmTag)
                .performClick()
            waitForIdle()
            assertNotNull(appModel.state.value.modpackExport.result)
            onNodeWithText("好").performClick()
            waitForIdle()
            assertEquals(LibraryStage.SETTINGS, appModel.state.value.libraryStage)
        }

    @Test
    fun instanceDetailHeaderCarriesLaunchFavoriteAndSettingsMenu() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.LIBRARY)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(libraryRowTag("fog-forest")).performClick()
            waitForIdle()

            onNodeWithTag(InstanceQuickStartTag).assertIsDisplayed()
            onNodeWithTag(InstanceFavoriteTag).assertIsDisplayed()
            onNodeWithTag(InstanceOverflowTag).performClick()
            waitForIdle()
            onNodeWithText("游戏设置").assertIsDisplayed()
            onNodeWithText("打开实例目录").assertIsDisplayed()
        }

    @Test
    fun modpackCategoryCreatesAnInstanceFromTheProjectSheet() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.DOWNLOADS)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(downloadsCategoryTag(DownloadsCategory.MODPACK)).performClick()
            waitForIdle()

            val browser = appModel.state.value.contentBrowser
            assertEquals("", browser.targetInstanceId)
            assertTrue(browser.projects.isNotEmpty())
            onNodeWithTag(contentProjectTag(browser.projects.first())).performClick()
            waitForIdle()

            onNodeWithText("创建实例并导入").assertIsDisplayed().performClick()
            waitForIdle()

            assertEquals("modpack-task-1", appModel.state.value.contentBrowser.installTaskId)
            assertEquals(null, appModel.state.value.contentBrowser.selectedProject)
        }

    @Test
    fun modpackHeaderShowsAllLoadersInsteadOfNewInstance() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.DOWNLOADS)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(downloadsCategoryTag(DownloadsCategory.MODPACK)).performClick()
            waitForIdle()

            onNodeWithText("新实例").assertDoesNotExist()
            onNodeWithText("全部加载器").assertIsDisplayed()
        }

    @Test
    fun datapackCategoryPicksAWorldAndInstalls() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.DOWNLOADS)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(downloadsCategoryTag(DownloadsCategory.DATAPACK)).performClick()
            waitForIdle()

            val browser = appModel.state.value.contentBrowser
            assertEquals("New World", browser.targetWorld)
            onNodeWithText("雾林世界").assertIsDisplayed()
            onNodeWithTag(contentProjectTag(browser.projects.first())).performClick()
            waitForIdle()

            onNodeWithText("安装到世界").assertIsDisplayed().performClick()
            waitForIdle()

            assertEquals("content-task-1", appModel.state.value.contentBrowser.installTaskId)
        }

    @Test
    fun contentTargetSelectorSitsInTheHeader() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.DOWNLOADS)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(ContentTargetTag).assertDoesNotExist()
            onNodeWithTag(downloadsCategoryTag(DownloadsCategory.MOD)).performClick()
            waitForIdle()

            onNodeWithTag(ContentTargetTag).assertIsDisplayed()
            onNodeWithTag(ContentTargetTag).performClick()
            waitForIdle()
            onNodeWithTag(contentTargetOptionTag("machinery")).performClick()
            waitForIdle()

            assertEquals("machinery", appModel.state.value.contentBrowser.targetInstanceId)
            assertTrue(appModel.state.value.contentBrowser.projects.isNotEmpty())
        }

    @Test
    fun contentBrowserInstallsAProjectIntoTheSelectedInstance() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.DOWNLOADS)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(downloadsCategoryTag(DownloadsCategory.MOD)).performClick()
            waitForIdle()

            val browser = appModel.state.value.contentBrowser
            assertTrue(browser.projects.isNotEmpty(), "content.search was not applied")
            assertEquals("fog-forest", browser.targetInstanceId)

            onNodeWithTag(contentProjectTag(browser.projects.first())).performClick()
            waitForIdle()
            onNodeWithTag(ContentInstallTag).assertIsDisplayed().performClick()
            waitForIdle()

            assertEquals("content-task-1", appModel.state.value.contentBrowser.installTaskId)
            assertEquals(null, appModel.state.value.contentBrowser.selectedProject)
        }

    @Test
    fun versionFilterAndDependencyPromptDriveTheInstall() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.DOWNLOADS)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(downloadsCategoryTag(DownloadsCategory.MOD)).performClick()
            waitForIdle()

            val browser = appModel.state.value.contentBrowser
            onNodeWithTag(contentProjectTag(browser.projects.first())).performClick()
            waitForIdle()

            // The version dropdown defaults to 正式版; switching to 全部版本
            // exposes the beta row that carries the required dependency.
            onNodeWithTag(ContentVersionChannelTag).assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithText("全部版本 (2)").performClick()
            waitForIdle()
            onNodeWithTag(contentVersionTag("old-v")).performClick()
            waitForIdle()

            onNodeWithTag(ContentInstallTag).performClick()
            waitForIdle()

            onNodeWithTag(ContentDependencyDialogTag).assertIsDisplayed()
            onNodeWithTag(contentDependencyItemTag("P7dR8mSH")).assertIsDisplayed()
            onNodeWithTag(ContentDependencyAutoTag).performClick()
            waitForIdle()

            assertEquals("content-task-1", appModel.state.value.contentBrowser.installTaskId)
            assertEquals(null, appModel.state.value.contentBrowser.selectedProject)
        }

    @Test
    fun libraryRowOpensTheInstanceDetailPage() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.LIBRARY)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(libraryRowTag("fog-forest")).performClick()
            waitForIdle()

            assertEquals(LibraryStage.DETAIL, appModel.state.value.libraryStage)
            onNodeWithTag(InstanceDetailTag).assertIsDisplayed()
            assertTrue(appModel.state.value.content.items.isNotEmpty())

            // The old summary card and its status/metrics are gone.
            onNodeWithText("已就绪").assertDoesNotExist()
            onNodeWithText("占用空间").assertDoesNotExist()
            onNodeWithText("上次游玩").assertDoesNotExist()

            onNodeWithTag(PageHeaderBackTag).performClick()
            waitForIdle()

            assertEquals(LibraryStage.LIST, appModel.state.value.libraryStage)
            onNodeWithTag(libraryRowTag("fog-forest")).assertIsDisplayed()
        }

    @Test
    fun settingsPageOpensFromTheDetailPageAndBackReturns() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            appModel.navigate(AppDestination.LIBRARY)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(libraryRowTag("fog-forest")).performClick()
            waitForIdle()

            onNodeWithTag(InstanceOverflowTag).performClick()
            waitForIdle()
            onNodeWithText("游戏设置").performClick()
            waitForIdle()

            assertEquals(LibraryStage.SETTINGS, appModel.state.value.libraryStage)
            onNodeWithTag(InstanceSettingsPageTag).assertIsDisplayed()

            onNodeWithTag(PageHeaderBackTag).performClick()
            waitForIdle()

            assertEquals(LibraryStage.DETAIL, appModel.state.value.libraryStage)
            onNodeWithTag(InstanceDetailTag).assertIsDisplayed()
        }

    @Test
    fun contentManagerOpensWorldsAndChecksUpdates() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            val instance = appModel.state.value.instances.first()
            appModel.openInstanceDetail(instance, ContentManagerTab.MODS)
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(InstanceDetailTag).assertIsDisplayed()
            assertEquals(LibraryStage.DETAIL, appModel.state.value.libraryStage)
            assertTrue(appModel.state.value.content.items.isNotEmpty())

            onNodeWithTag(ContentCheckUpdatesTag).performClick()
            waitForIdle()
            assertTrue(appModel.state.value.content.updates.isNotEmpty())

            onNodeWithTag(contentTabTag(ContentManagerTab.WORLDS)).performClick()
            waitForIdle()
            assertTrue(appModel.state.value.content.worlds.isNotEmpty())
            assertEquals(ContentManagerTab.WORLDS, appModel.state.value.content.tab)
        }

    @Test
    fun settingsOpensInTheMainWindowWithAJavaPage() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(navItemTag(AppDestination.SETTINGS)).performClick()
            waitForIdle()

            onNodeWithTag(SettingsWindowTag).assertIsDisplayed()
            // Settings replaces the shell inside the same window.
            onNodeWithTag(NavRailTag).assertDoesNotExist()
            assertEquals(true, appModel.state.value.settingsWindow.open)
            assertEquals(SettingsSection.APPEARANCE, appModel.state.value.settingsWindow.section)

            onNodeWithTag(settingsNavTag(SettingsSection.GENERAL)).performClick()
            waitForIdle()
            assertTrue(appModel.state.value.settingsWindow.section == SettingsSection.GENERAL)
            onNodeWithTag(SettingsPageListTag)
                .performScrollToNode(hasTestTag(SettingsJavaOpenTag))
            onNodeWithTag(SettingsJavaOpenTag).performClick()
            waitForIdle()

            onNodeWithTag(JavaScreenTag).assertIsDisplayed()
            assertEquals(SettingsSection.JAVA, appModel.state.value.settingsWindow.section)
            assertEquals(
                listOf(SettingsSection.GENERAL),
                appModel.state.value.settingsWindow.sectionHistory,
            )

            // The General → Java shortcut is nested: Back returns to General
            // before it can close settings.
            onNodeWithTag(SettingsWindowBackTag).performClick()
            waitForIdle()
            assertEquals(SettingsSection.GENERAL, appModel.state.value.settingsWindow.section)
            assertEquals(true, appModel.state.value.settingsWindow.open)
            assertEquals(false, appModel.state.value.settingsWindow.sectionForward)

            onNodeWithTag(SettingsWindowBackTag).performClick()
            waitForIdle()
            assertEquals(false, appModel.state.value.settingsWindow.open)
            onNodeWithTag(SettingsWindowTag).assertDoesNotExist()
            onNodeWithTag(NavRailTag).assertIsDisplayed()
        }

    @Test
    fun compactJavaPageReturnsToGeneralThenTheSettingsList() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            val appModel = model()
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(navItemTag(AppDestination.SETTINGS)).performClick()
            waitForIdle()
            onNodeWithTag(SettingsCompactListTag).assertIsDisplayed()
            onNodeWithTag(NavBarTag).assertDoesNotExist()

            onNodeWithTag(settingsNavTag(SettingsSection.GENERAL)).performClick()
            waitForIdle()
            assertEquals(SettingsSection.GENERAL, appModel.state.value.settingsWindow.section)

            onNodeWithTag(SettingsPageListTag)
                .performScrollToNode(hasTestTag(SettingsJavaOpenTag))
            onNodeWithTag(SettingsJavaOpenTag).performClick()
            waitForIdle()
            assertEquals(SettingsSection.JAVA, appModel.state.value.settingsWindow.section)
            assertTrue(appModel.state.value.settingsWindow.sectionForward)

            // Java is a child of General, so Back returns to General, not the
            // settings category list.
            onNodeWithTag(SettingsCompactBackTag).performClick()
            waitForIdle()
            assertEquals(SettingsSection.GENERAL, appModel.state.value.settingsWindow.section)
            assertTrue(appModel.state.value.settingsWindow.detailOpen)
            assertEquals(false, appModel.state.value.settingsWindow.sectionForward)

            onNodeWithTag(SettingsCompactBackTag).performClick()
            waitForIdle()
            assertFalse(appModel.state.value.settingsWindow.detailOpen)
            assertTrue(appModel.state.value.settingsWindow.open)

            onNodeWithTag(SettingsCompactCloseTag).performClick()
            waitForIdle()
            assertFalse(appModel.state.value.settingsWindow.open)
            onNodeWithTag(SettingsWindowTag).assertDoesNotExist()
        }

    @Test
    fun settingsMemorySliderDragsToANewValue() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(navItemTag(AppDestination.SETTINGS)).performClick()
            waitForIdle()
            onNodeWithTag(
                com.fograin.launcher.ui.settings.settingsNavTag(
                    com.fograin.launcher.app.SettingsSection.GENERAL,
                ),
            ).performClick()
            waitForIdle()

            val before = appModel.state.value.gameConfig?.memoryMb
            onNodeWithTag(com.fograin.launcher.ui.settings.SettingsMemorySliderTag)
                .performTouchInput {
                    down(center)
                    moveBy(androidx.compose.ui.geometry.Offset(90f, 0f), delayMillis = 60)
                    up()
                }
            waitForIdle()

            val after = appModel.state.value.gameConfig?.memoryMb
            assertTrue(after != null && after != before, "slider drag did not change memory")
        }

    @Test
    fun languageRowOpensTheLanguagePicker() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val appModel = model()
            setContent { App(appModel) }
            waitForIdle()

            onNodeWithTag(navItemTag(AppDestination.SETTINGS)).performClick()
            waitForIdle()
            onNodeWithTag(SettingsLanguageRowTag).performClick()
            waitForIdle()

            onNodeWithText("日本語").assertIsDisplayed()
            onNodeWithText("日本語").performClick()
            waitForIdle()

            assertEquals(
                com.fograin.launcher.settings.AppLanguage.JAPANESE,
                appModel.state.value.language,
            )
            appModel.setLanguage(com.fograin.launcher.settings.AppLanguage.SYSTEM)
            waitForIdle()
            com.fograin.launcher.platform.applyAppLanguage(
                com.fograin.launcher.settings.AppLanguage.SYSTEM,
            )
        }

    @Test
    fun settingsSplitterResizesTheSidebar() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val preferences = platformServices().preferences
            val original = preferences.settingsSidebarWidth
            try {
                preferences.settingsSidebarWidth = 500f
                val appModel = model()
                setContent { App(appModel) }
                waitForIdle()

                onNodeWithTag(navItemTag(AppDestination.SETTINGS)).performClick()
                waitForIdle()

                val before = onNodeWithTag(SettingsSidebarTag)
                    .fetchSemanticsNode().boundsInRoot.width
                onNodeWithTag(SettingsSplitterTag).performTouchInput {
                    swipeRight(startX = centerX, endX = centerX + 80f)
                }
                waitForIdle()

                val after = onNodeWithTag(SettingsSidebarTag)
                    .fetchSemanticsNode().boundsInRoot.width
                assertTrue(after > before + 20f, "drag did not widen the sidebar: $before -> $after")
                assertTrue(preferences.settingsSidebarWidth > 0f, "width was not persisted")
            } finally {
                preferences.settingsSidebarWidth = original
            }
        }

    @Test
    fun settingsSplitterBouncesBackFromTheLeftEdge() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val preferences = platformServices().preferences
            val original = preferences.settingsSidebarWidth
            try {
                preferences.settingsSidebarWidth = 500f
                val appModel = model()
                setContent { App(appModel) }
                waitForIdle()
                onNodeWithTag(navItemTag(AppDestination.SETTINGS)).performClick()
                waitForIdle()

                // The splitter starts at x = 509; -500 puts the pointer at
                // x = 9 while the sidebar reaches width 0 (the left edge).
                onNodeWithTag(SettingsSplitterTag).performTouchInput {
                    down(center)
                    moveBy(Offset(-500f, 0f), delayMillis = 120)
                    up()
                }
                waitForIdle()
                mainClock.advanceTimeBy(1_200)
                waitForIdle()

                val after = onNodeWithTag(SettingsSidebarTag)
                    .fetchSemanticsNode().boundsInRoot.width
                // The left pane floor is 32 % of 1280 dp = 409.6 dp.
                assertTrue(after in 408f..412f, "sidebar did not settle at the left ratio: $after")
                assertEquals(409.6f, preferences.settingsSidebarWidth, 2f)
            } finally {
                preferences.settingsSidebarWidth = original
            }
        }

    @Test
    fun settingsSplitterResizesWithinTheBandAndFitsBothPagesAtTheLimits() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val preferences = platformServices().preferences
            val original = preferences.settingsSidebarWidth
            try {
                preferences.settingsSidebarWidth = 500f
                val appModel = model()
                setContent { App(appModel) }
                waitForIdle()
                onNodeWithTag(navItemTag(AppDestination.SETTINGS)).performClick()
                waitForIdle()

                // Push the divider past the right-hand 32 % floor. While the
                // divider travels through the band the content page resizes
                // with its pane; only beyond the floor does it freeze and get
                // covered. On release the splitter settles at the 68 % limit,
                // so the page must fill the remaining pane again instead of
                // staying at its default 2/3 width.
                onNodeWithTag(SettingsSplitterTag).performTouchInput {
                    down(center)
                    moveBy(Offset(760f, 0f), delayMillis = 120)
                    up()
                }
                waitForIdle()
                mainClock.advanceTimeBy(1_200)
                waitForIdle()

                val contentPage = onNodeWithTag(SettingsContentPageTag)
                    .fetchSemanticsNode().layoutInfo
                // 1280 - (1280 - 18) * 0.32? No: the resting sidebar is 68 %,
                // so the content pane is 427.6 dp wide at the floor.
                assertEquals(427.6f, contentPage.width.toFloat(), 2f)
                assertEquals(
                    1280f,
                    contentPage.coordinates.positionInRoot().x + contentPage.width,
                    1.5f,
                )

                // Now push the divider the other way. On release it settles
                // at the left 32 % floor; the sidebar page must fit that pane
                // exactly and stay at x = 0, keeping its normal left padding
                // instead of being frozen and clipped against the window edge.
                onNodeWithTag(SettingsSplitterTag).performTouchInput {
                    down(center)
                    moveBy(Offset(-500f, 0f), delayMillis = 120)
                    up()
                }
                waitForIdle()
                mainClock.advanceTimeBy(1_200)
                waitForIdle()

                val sidebarPage = onNodeWithTag(SettingsSidebarPageTag)
                    .fetchSemanticsNode().layoutInfo
                assertEquals(409.6f, sidebarPage.width.toFloat(), 2f)
                assertEquals(0f, sidebarPage.coordinates.positionInRoot().x, 1.5f)
            } finally {
                preferences.settingsSidebarWidth = original
            }
        }

    @Test
    fun settingsSplitterBouncesBackFromTheRightEdge() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val preferences = platformServices().preferences
            val original = preferences.settingsSidebarWidth
            try {
                preferences.settingsSidebarWidth = 500f
                val appModel = model()
                setContent { App(appModel) }
                waitForIdle()
                onNodeWithTag(navItemTag(AppDestination.SETTINGS)).performClick()
                waitForIdle()

                // The pane can travel to the whole window width; +1000 clamps
                // at 1280, so the sidebar covers the content completely before
                // it springs back to the 68 % resting limit.
                onNodeWithTag(SettingsSplitterTag).performTouchInput {
                    down(center)
                    moveBy(Offset(1_000f, 0f), delayMillis = 120)
                    up()
                }
                waitForIdle()
                mainClock.advanceTimeBy(1_200)
                waitForIdle()

                val after = onNodeWithTag(SettingsSidebarTag)
                    .fetchSemanticsNode().boundsInRoot.width
                // The right pane floor is 32 %, so the sidebar max is 852.4 dp.
                assertTrue(after in 850f..855f, "sidebar did not settle at the right ratio: $after")
                assertEquals(852.4f, preferences.settingsSidebarWidth, 2f)
            } finally {
                preferences.settingsSidebarWidth = original
            }
        }

    @Test
    fun railSearchOpensGlobalSearchAndNavigates() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val appModel = model()
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(GlobalSearchButtonTag).performClick()
        waitForIdle()

        onNodeWithTag(GlobalSearchFieldTag).assertIsDisplayed()
        // Scroll to the Java row instead of assuming it is above the fold.
        onNodeWithTag(com.fograin.launcher.ui.search.GlobalSearchScreenTag)
            .performScrollToNode(hasTestTag(globalSearchCommandTag("java")))
        onNodeWithTag(globalSearchCommandTag("java")).performClick()
        waitForIdle()

        assertEquals(SettingsSection.JAVA, appModel.state.value.settingsWindow.section)
        assertEquals(true, appModel.state.value.settingsWindow.open)
        onNodeWithTag(JavaScreenTag).assertIsDisplayed()
    }

    @Test
    fun languageSwitchRefreshesTheInterface() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val appModel = model()
        appModel.setLanguage(com.fograin.launcher.settings.AppLanguage.TRADITIONAL_CHINESE)
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithText("首頁").assertIsDisplayed()

        appModel.setLanguage(com.fograin.launcher.settings.AppLanguage.JAPANESE)
        waitForIdle()
        onNodeWithText("ホーム").assertIsDisplayed()

        appModel.setLanguage(com.fograin.launcher.settings.AppLanguage.ENGLISH)
        waitForIdle()
        onNodeWithText("Home").assertIsDisplayed()

        appModel.setLanguage(com.fograin.launcher.settings.AppLanguage.SYSTEM)
        waitForIdle()
    }

    @Test
    fun versionRowOpensThePinnedInstallPage() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val appModel = model()
        appModel.navigate(AppDestination.DOWNLOADS)
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(downloadVersionTag("1.21.5")).performClick()
        waitForIdle()

        assertEquals(DownloadStage.INSTALL, appModel.state.value.download.stage)
        onNodeWithTag(InstallDownloadTag).assertIsDisplayed()
        onNodeWithTag(downloadVersionTag("1.21.5")).assertDoesNotExist()

        // The verbose install-page hint was removed.
        onNodeWithText("安装完成后会进入实例库", substring = true).assertDoesNotExist()

        onNodeWithTag(InstallDownloadTag).performClick()
        waitForIdle()

        val afterDownload = appModel.state.value.download
        assertEquals(DownloadStage.BROWSER, afterDownload.stage)
        assertTrue(afterDownload.isGameDownloadRunning)
    }
    @Test
    fun accountAvatarOpensThePanelAndOfflineForm() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val appModel = model()
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(AccountAvatarTag).performClick()
        waitForIdle()

        onNodeWithTag(AccountSheetTag).assertIsDisplayed()
        assertEquals(true, appModel.state.value.account.open)
        assertEquals("Steve_Fog", appModel.state.value.account.displayName)

        onNodeWithTag(AccountAddOfflineTag).performClick()
        waitForIdle()

        onNodeWithTag(AccountOfflineNameTag).assertIsDisplayed()
        assertEquals(
            com.fograin.launcher.app.AccountStage.OFFLINE,
            appModel.state.value.account.stage,
        )

        onNodeWithTag(AccountOfflineNameTag).performTextInput("Alex")
        appModel.back()
        waitForIdle()
        assertEquals(
            com.fograin.launcher.app.AccountStage.LIST,
            appModel.state.value.account.stage,
        )
        onNodeWithTag(AccountAddOfflineTag).assertIsDisplayed()
    }

}
