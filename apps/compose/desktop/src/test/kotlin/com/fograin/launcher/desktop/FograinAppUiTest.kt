package com.fograin.launcher.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.fograin.launcher.App
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.DownloadStage
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.GlobalSearchButtonTag
import com.fograin.launcher.ui.accounts.AccountAddOfflineTag
import com.fograin.launcher.ui.accounts.AccountOfflineNameTag
import com.fograin.launcher.ui.accounts.AccountSheetTag
import com.fograin.launcher.ui.home.AccountAvatarTag
import com.fograin.launcher.ui.NavBarTag
import com.fograin.launcher.ui.NavRailTag
import com.fograin.launcher.ui.downloads.InstallDownloadTag
import com.fograin.launcher.ui.downloads.downloadVersionTag
import com.fograin.launcher.ui.search.GlobalSearchFieldTag
import com.fograin.launcher.ui.search.globalSearchCommandTag
import com.fograin.launcher.ui.settings.JavaScreenTag
import com.fograin.launcher.ui.settings.SettingsJavaManageTag
import com.fograin.launcher.ui.navItemTag
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun model(): AppModel = AppModel.create(
        core = FakeCoreClient(),
        platform = platformServices(),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun narrowWindowKeepsBottomBar() = runDesktopComposeUiTest(width = 720, height = 900) {
        val appModel = model()
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(NavBarTag).assertIsDisplayed()
        onNodeWithTag(NavRailTag).assertDoesNotExist()
        AppDestination.entries.forEach { onNodeWithTag(navItemTag(it)).assertIsDisplayed() }
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
    fun javaManagementOpensFromSettingsAndBackReturns() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val appModel = model()
        appModel.navigate(AppDestination.SETTINGS)
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(SettingsJavaManageTag).performClick()
        waitForIdle()

        onNodeWithTag(JavaScreenTag).assertIsDisplayed()
        assertEquals(
            com.fograin.launcher.app.SettingsStage.JAVA,
            appModel.state.value.settingsStage,
        )

        appModel.back()
        waitForIdle()
        onNodeWithTag(JavaScreenTag).assertDoesNotExist()
        onNodeWithTag(SettingsJavaManageTag).assertIsDisplayed()
    }

    @Test
    fun railSearchOpensGlobalSearchAndNavigates() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val appModel = model()
        setContent { App(appModel) }
        waitForIdle()

        onNodeWithTag(GlobalSearchButtonTag).performClick()
        waitForIdle()

        onNodeWithTag(GlobalSearchFieldTag).assertIsDisplayed()
        onNodeWithTag(globalSearchCommandTag("java")).performClick()
        waitForIdle()

        assertEquals(
            com.fograin.launcher.app.SettingsStage.JAVA,
            appModel.state.value.settingsStage,
        )
        onNodeWithTag(JavaScreenTag).assertIsDisplayed()
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
