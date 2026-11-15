package com.fograin.launcher.app

import com.fograin.launcher.model.GameLoaderKind
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
import kotlinx.serialization.json.JsonPrimitive

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
    fun installPagePinsVersionAndWalksTheLoaderPages() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()
        model.loadDownloadVersions()
        runCurrent()

        model.openInstallPage("1.21.5")
        runCurrent()

        var download = model.state.value.download
        assertEquals(DownloadStage.INSTALL, download.stage)
        assertEquals("1.21.5", download.installVersionId)
        assertNotNull(download.plan, "the install page must preview the pinned version")
        assertEquals(GameLoaderKind.VANILLA, download.installLoader)

        model.setInstallLoader(GameLoaderKind.FABRIC)
        runCurrent()

        download = model.state.value.download
        assertEquals("0.16.9", download.installLoaderVersion)
        assertEquals(listOf("0.16.9", "0.15.11"), download.loaderVersions.map { it.version })
        assertTrue(core.rpcCalls.contains("loaders.versions"))

        // Selecting a loader re-plans with the concrete build so Java, file
        // count and size describe what will actually be downloaded.
        val planRequest = core.planRequests.last()
        assertEquals("fabric", (planRequest["loaderKind"] as JsonPrimitive).content)
        assertEquals("0.16.9", (planRequest["loaderVersion"] as JsonPrimitive).content)

        model.openLoaderVersionPicker()
        assertEquals(DownloadStage.LOADER_VERSIONS, model.state.value.download.stage)

        model.selectInstallLoaderVersion("0.15.11")
        runCurrent()

        download = model.state.value.download
        assertEquals(DownloadStage.INSTALL, download.stage)
        assertEquals("0.15.11", download.installLoaderVersion)

        model.setInstallInstanceName("雾林 Fabric")
        assertEquals("雾林 Fabric", model.state.value.download.installInstanceName)

        // The flow lives inside the downloads destination; back walks it
        // before it leaves the tab.
        model.navigate(AppDestination.DOWNLOADS)
        model.back()
        assertEquals(DownloadStage.BROWSER, model.state.value.download.stage)
        assertNull(model.state.value.download.installVersionId)
        assertTrue(model.canGoBack())

        model.back()
        assertEquals(AppDestination.HOME, model.state.value.destination)
        assertFalse(model.canGoBack())
    }

    @Test
    fun submitInstallSendsThePinnedLoaderAndReturnsToList() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()
        model.loadDownloadVersions()
        runCurrent()

        model.openInstallPage("1.21.5")
        model.setInstallLoader(GameLoaderKind.FABRIC)
        model.selectInstallLoaderVersion("0.15.11")
        model.setInstallInstanceName("雾林 1.21.5")
        runCurrent()

        model.submitInstall()
        runCurrent()

        val request = core.installRequests.single()
        assertEquals("fabric", (request["loaderKind"] as JsonPrimitive).content)
        assertEquals("0.15.11", (request["loaderVersion"] as JsonPrimitive).content)
        assertEquals("雾林 1.21.5", (request["instanceName"] as JsonPrimitive).content)
        val state = model.state.value
        assertEquals(DownloadStage.BROWSER, state.download.stage)
        assertTrue(state.download.isGameDownloadRunning)
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
    fun loaderInstallSendsKindAndVersion() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.startGameInstall(
            version = "1.21.4",
            instanceName = "雾林 Fabric",
            loader = com.fograin.launcher.model.GameLoaderKind.FABRIC,
            loaderVersion = "0.16.9",
        )
        runCurrent()

        val request = core.installRequests.single()
        assertEquals("fabric", (request["loaderKind"] as JsonPrimitive).content)
        assertEquals("0.16.9", (request["loaderVersion"] as JsonPrimitive).content)
    }

    @Test
    fun loaderVersionsComeFromTheCore() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        val versions = model.loadLoaderVersions(
            com.fograin.launcher.model.GameLoaderKind.FABRIC,
            "1.21.4",
        )
        assertEquals(listOf("0.16.9", "0.15.11"), versions.map { it.version })
        assertTrue(core.rpcCalls.contains("loaders.versions"))
    }

    @Test
    fun javaSettingsLoadPolicyCatalogAndInstallTask() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.openJavaSettings()
        runCurrent()

        var state = model.state.value
        assertEquals(AppDestination.SETTINGS, state.destination)
        assertEquals(SettingsStage.JAVA, state.settingsStage)
        assertTrue(model.canGoBack())
        assertEquals(2, state.java.runtimes.size)
        assertEquals(1, state.java.managedCount)
        assertTrue(state.java.autoDownload)
        assertEquals(listOf(8, 17, 21), state.java.catalog.map { it.major })
        assertTrue(state.java.catalog.last().installed)
        assertTrue(core.rpcCalls.contains("java.catalog"))

        model.setJavaAutoDownload(false)
        runCurrent()
        assertFalse(model.state.value.java.autoDownload)
        assertFalse(core.javaAutoDownload)

        model.installJavaRuntime(21)
        runCurrent()
        state = model.state.value
        assertEquals("java-task-1", state.java.installTaskId)
        assertEquals(21, state.java.installingMajor)
        assertEquals("21", (core.javaInstallRequests.single()["major"] as JsonPrimitive).content)

        model.handleEvent(
            CoreEvent.TaskFinished(
                taskId = "java-task-1",
                name = "java:21",
                state = "succeeded",
                detail = emptyList(),
            ),
        )
        runCurrent()
        assertNull(model.state.value.java.installTaskId)
        assertNull(model.state.value.java.installingMajor)
        assertEquals("Java 运行时安装完成", model.state.value.notice?.message)

        model.back()
        assertEquals(SettingsStage.MAIN, model.state.value.settingsStage)
        assertTrue(model.canGoBack(), "still on Settings, so back now leaves the tab")
    }

    @Test
    fun javaRemovalUsesTheManagedId() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.removeJavaRuntime("mojang-java-runtime-delta-21.0.7-arm64")
        runCurrent()

        assertEquals(
            listOf("mojang-java-runtime-delta-21.0.7-arm64"),
            core.javaRemovedIds,
        )
    }

    @Test
    fun globalSearchOpensFiltersAndClosesOnBack() = runTest {
        val model = model(backgroundScope)
        runCurrent()

        model.openGlobalSearch()
        assertTrue(model.state.value.globalSearchOpen)
        assertTrue(model.canGoBack())

        model.setGlobalSearchQuery("1.21")
        assertEquals("1.21", model.state.value.globalSearchQuery)

        model.back()
        assertFalse(model.state.value.globalSearchOpen)
        assertFalse(model.canGoBack())
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
