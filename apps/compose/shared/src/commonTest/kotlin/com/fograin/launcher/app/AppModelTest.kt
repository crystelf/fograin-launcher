package com.fograin.launcher.app

import com.fograin.launcher.model.Instance
import com.fograin.launcher.platform.CoreEvent
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * State-machine coverage for [AppModel]: what `app.snapshot`, the download
 * flow and the back stack do to [AppUiState].
 *
 * The fake core answers the same envelopes as `bindings/fg-ffi`, so these
 * tests fail when a payload field or a transition drifts — the gap the audit
 * called out ("AppModel 状态机测试不存在").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModelTest {

    private suspend fun model(
        scope: kotlinx.coroutines.CoroutineScope,
        core: FakeCore = FakeCore(),
    ): AppModel = AppModel.create(core = core, platform = FakePlatform().services, scope = scope)

    @Test
    fun refreshLoadsInstancesAndSnapshot() = runTest {
        val model = model(backgroundScope)
        runCurrent()

        val state = model.state.value
        assertEquals(2, state.instances.size)
        assertEquals("fog-forest", state.currentInstanceId)
        assertEquals("Steve_Fog", state.gameConfig?.username)
        assertNotNull(state.appSnapshot)
        assertFalse(state.isInitialLoading)
    }

    @Test
    fun backDismissesSheetsBeforeLeavingDestination() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()

        model.openInstance(instance)
        assertEquals(AppDestination.LIBRARY, model.state.value.destination)
        assertFalse(model.canGoBack().not())

        model.showPreview(instance)
        assertNotNull(model.state.value.previewInstance)

        model.back()
        assertNull(model.state.value.previewInstance)
        assertEquals(AppDestination.LIBRARY, model.state.value.destination)

        model.back()
        assertEquals(AppDestination.HOME, model.state.value.destination)
        assertFalse(model.canGoBack())
    }

    @Test
    fun deleteClosesPreviewAndSelection() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()

        model.showPreview(instance)
        model.deleteInstance(instance)
        runCurrent()

        assertNull(model.state.value.previewInstanceId)
        assertNull(model.state.value.instanceSettingsId)
    }

    @Test
    fun uniqueInstanceIdAvoidsCollisions() = runTest {
        val model = model(backgroundScope)
        runCurrent()

        // Sanitising keeps ASCII and lower/upper case; ids collide, so a
        // numeric suffix is appended.
        val id = model.uniqueInstanceID("fog-forest", "1.21.4")
        assertEquals("fog-forest-2", id)
    }

    @Test
    fun toggleThemePersistsThroughCore() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()
        assertEquals(ThemeMode.DARK, model.state.value.theme)

        model.toggleTheme()
        runCurrent()

        assertEquals(ThemeMode.LIGHT, model.state.value.theme)
        assertEquals(ThemeMode.LIGHT, core.settings.theme)
    }

    @Test
    fun installFlowJumpsToNewInstanceAfterTaskFinishes() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.startGameInstall("1.21.5", "Neo Pack")
        runCurrent()

        val installing = model.state.value
        assertTrue(installing.download.isGameDownloadRunning)
        assertEquals(AppDestination.DOWNLOADS, installing.destination)
        assertEquals("NeoPack", core.installedInstanceIds.single())

        model.handleEvent(
            CoreEvent.TaskFinished(
                taskId = "task-42",
                name = "install:1.21.5",
                state = "succeeded",
                detail = emptyList(),
            ),
        )
        runCurrent()

        val finished = model.state.value
        assertFalse(finished.download.isGameDownloadRunning)
        // The fake keeps reporting the same two instances, so the launcher
        // stays on the download page instead of opening a sheet for a ghost id.
        assertEquals(AppDestination.DOWNLOADS, finished.destination)
        assertNull(finished.instanceSettingsId)
    }

    @Test
    fun launchOnIncompleteInstanceStartsCompletionDownload() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()
        val incomplete = model.state.value.instances.first { !it.isInstalled }

        model.startGame(incomplete)
        runCurrent()

        assertTrue(model.state.value.download.isGameDownloadRunning)
        assertEquals("vanilla", core.installedInstanceIds.single())
        assertEquals(AppDestination.DOWNLOADS, model.state.value.destination)
    }

    @Test
    fun gameExitedClearsSessionAndRefreshes() = runTest {
        val model = model(backgroundScope)
        runCurrent()

        model.handleEvent(CoreEvent.GameStarted(instanceId = "fog-forest", pid = 42))
        runCurrent()
        assertTrue(model.state.value.sessionRunning)

        model.handleEvent(
            CoreEvent.GameExited(instanceId = "fog-forest", exitCode = 0, playSeconds = 125),
        )
        runCurrent()

        assertFalse(model.state.value.sessionRunning)
        assertEquals("游戏已退出（2 分钟）", model.state.value.notice?.message)
    }

    @Test
    fun searchRequestOpensLibrary() = runTest {
        val model = model(backgroundScope)
        runCurrent()

        model.requestLibrarySearch()
        assertEquals(AppDestination.LIBRARY, model.state.value.destination)
        assertEquals(1L, model.state.value.librarySearchRequest)
    }

    @Test
    fun libraryFiltersAreViewStateOnly() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()
        core.rpcCalls.clear()

        val instance: Instance = model.state.value.instances.first()
        model.setLibraryQuery("雾")
        model.setLibrarySort(com.fograin.launcher.model.LibrarySort.NAME)
        model.setHomeFilter(HomeFilter.FAVORITES)

        assertEquals("雾", model.state.value.libraryQuery)
        assertEquals(HomeFilter.FAVORITES, model.state.value.homeFilter)
        assertTrue(core.rpcCalls.isEmpty(), "view state must not call the core")
        assertNotNull(instance)
    }
}
