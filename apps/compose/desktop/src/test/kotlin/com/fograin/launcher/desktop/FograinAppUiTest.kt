package com.fograin.launcher.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.fograin.launcher.App
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.DownloadStage
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.NavBarTag
import com.fograin.launcher.ui.NavRailTag
import com.fograin.launcher.ui.downloads.InstallDownloadTag
import com.fograin.launcher.ui.downloads.downloadVersionTag
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
        AppDestination.entries.forEach { onNodeWithTag(navItemTag(it)).assertIsDisplayed() }
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
}
