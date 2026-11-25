package com.fograin.launcher.app

import com.fograin.launcher.model.ContentVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.model.Instance
import com.fograin.launcher.platform.CoreEvent
import com.fograin.launcher.platform.ReleaseInfo
import com.fograin.launcher.platform.UpdateService
import com.fograin.launcher.platform.stringOrNull
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import com.fograin.launcher.model.TaskState

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
    fun updateCheckPromptsOnlyForANewerRelease() = runTest {
        val platform = FakePlatform()
        val service = object : UpdateService {
            override suspend fun fetchLatestRelease(): ReleaseInfo? = ReleaseInfo(
                version = "0.2.0",
                url = "https://github.com/crystelf/fograin-launcher/releases/tag/v0.2.0",
            )
        }
        val model = AppModel.create(
            core = FakeCore(),
            platform = platform.services.copy(updates = service),
            scope = backgroundScope,
        )
        runCurrent()

        model.checkForUpdates()
        runCurrent()
        assertEquals("0.2.0", model.state.value.updates.available?.version)
        assertNotNull(model.state.value.updates.visible)
        assertTrue(model.state.value.updates.checked)

        model.dismissUpdate()
        assertNull(model.state.value.updates.visible)
        assertNotNull(model.state.value.updates.available)
    }

    @Test
    fun updateCheckIgnoresTheCurrentVersion() = runTest {
        val platform = FakePlatform()
        val service = object : UpdateService {
            override suspend fun fetchLatestRelease(): ReleaseInfo? = ReleaseInfo(
                version = "0.1.0",
                url = "https://github.com/crystelf/fograin-launcher/releases/tag/v0.1.0",
            )
        }
        val model = AppModel.create(
            core = FakeCore(),
            platform = platform.services.copy(updates = service),
            scope = backgroundScope,
        )
        runCurrent()

        model.checkForUpdates()
        runCurrent()
        assertNull(model.state.value.updates.available)
        assertTrue(model.state.value.updates.checked)
    }

    @Test
    fun backDismissesInstancePagesBeforeLeavingDestination() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()

        model.openInstance(instance)
        assertEquals(AppDestination.LIBRARY, model.state.value.destination)
        assertEquals(LibraryStage.DETAIL, model.state.value.libraryStage)
        assertEquals(instance.id, model.state.value.libraryDetailInstance?.id)
        assertTrue(model.canGoBack())

        model.manageInstance(instance)
        assertEquals(LibraryStage.SETTINGS, model.state.value.libraryStage)

        model.back()
        assertEquals(LibraryStage.DETAIL, model.state.value.libraryStage)

        model.back()
        assertEquals(LibraryStage.LIST, model.state.value.libraryStage)
        assertEquals(AppDestination.LIBRARY, model.state.value.destination)

        model.back()
        assertEquals(AppDestination.HOME, model.state.value.destination)
        assertFalse(model.canGoBack())
    }

    @Test
    fun deleteClosesDetailAndSelection() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()

        model.openInstanceDetail(instance)
        assertEquals(LibraryStage.DETAIL, model.state.value.libraryStage)

        model.library.deleteInstance(instance)
        runCurrent()

        assertEquals(LibraryStage.LIST, model.state.value.libraryStage)
        assertNull(model.state.value.libraryDetailId)
        assertNull(model.state.value.content.instanceId)
    }

    @Test
    fun uniqueInstanceIdAvoidsCollisions() = runTest {
        val model = model(backgroundScope)
        runCurrent()

        // Sanitising keeps ASCII and lower/upper case; ids collide, so a
        // numeric suffix is appended.
        val id = model.library.uniqueInstanceID("fog-forest", "1.21.4")
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

        model.downloads.startGameInstall("1.21.5", "Neo Pack")
        runCurrent()

        val installing = model.state.value
        assertTrue(installing.download.isGameDownloadRunning)
        assertEquals(AppDestination.DOWNLOADS, installing.destination)
        assertEquals("NeoPack", core.installedInstanceIds.single())

        model.handleEvent(
            CoreEvent.TaskFinished(
                taskId = "task-42",
                name = "install:1.21.5",
                state = TaskState.SUCCEEDED,
                detail = emptyList(),
            ),
        )
        runCurrent()

        val finished = model.state.value
        assertFalse(finished.download.isGameDownloadRunning)
        // The fake keeps reporting the same two instances, so the launcher
        // stays on the download page instead of opening a page for a ghost id.
        assertEquals(AppDestination.DOWNLOADS, finished.destination)
        assertEquals(LibraryStage.LIST, finished.libraryStage)
    }

    @Test
    fun installPagePinsVersionAndWalksTheLoaderPages() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()
        model.downloads.loadVersions()
        runCurrent()

        model.downloads.openInstallPage("1.21.5")
        runCurrent()

        var download = model.state.value.download
        assertEquals(DownloadStage.INSTALL, download.stage)
        assertEquals("1.21.5", download.installVersionId)
        assertNotNull(download.plan, "the install page must preview the pinned version")
        assertEquals(GameLoaderKind.VANILLA, download.installLoader)

        model.downloads.setInstallLoader(GameLoaderKind.FABRIC)
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

        model.downloads.openLoaderVersionPicker()
        assertEquals(DownloadStage.LOADER_VERSIONS, model.state.value.download.stage)

        model.selectInstallLoaderVersion("0.15.11")
        runCurrent()

        download = model.state.value.download
        assertEquals(DownloadStage.INSTALL, download.stage)
        assertEquals("0.15.11", download.installLoaderVersion)

        model.downloads.setInstallInstanceName("雾林 Fabric")
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
        model.downloads.loadVersions()
        runCurrent()

        model.downloads.openInstallPage("1.21.5")
        model.downloads.setInstallLoader(GameLoaderKind.FABRIC)
        model.selectInstallLoaderVersion("0.15.11")
        model.downloads.setInstallInstanceName("雾林 1.21.5")
        runCurrent()

        model.downloads.submitInstall()
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

        model.launch.start(incomplete)
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

        model.downloads.startGameInstall(
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

        val versions = model.downloads.versionsFor(
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
        // Phone layout: Back pops the detail page before it closes the window.
        model.settings.setCompact(true)

        var state = model.state.value
        assertTrue(state.settingsWindow.open)
        assertEquals(SettingsSection.JAVA, state.settingsWindow.section)
        assertTrue(model.canGoBack())
        assertEquals(2, state.java.runtimes.size)
        assertEquals(1, state.java.managedCount)
        assertTrue(state.java.autoDownload)
        assertEquals(listOf(8, 17, 21), state.java.catalog.map { it.major })
        assertTrue(state.java.catalog.last().installed)
        assertTrue(core.rpcCalls.contains("java.catalog"))

        model.java.setAutoDownload(false)
        runCurrent()
        assertFalse(model.state.value.java.autoDownload)
        assertFalse(core.javaAutoDownload)

        model.java.install(21)
        runCurrent()
        state = model.state.value
        assertEquals("java-task-1", state.java.installTaskId)
        assertEquals(21, state.java.installingMajor)
        assertEquals("21", (core.javaInstallRequests.single()["major"] as JsonPrimitive).content)

        model.handleEvent(
            CoreEvent.TaskFinished(
                taskId = "java-task-1",
                name = "java:21",
                state = TaskState.SUCCEEDED,
                detail = emptyList(),
            ),
        )
        runCurrent()
        assertNull(model.state.value.java.installTaskId)
        assertNull(model.state.value.java.installingMajor)
        assertEquals("Java 运行时安装完成", model.state.value.notice?.message)

        model.back()
        assertFalse(model.state.value.settingsWindow.detailOpen)
        assertTrue(model.state.value.settingsWindow.open)

        model.back()
        assertFalse(model.state.value.settingsWindow.open)
    }

    @Test
    fun settingsWindowLoadsAndUpdatesAppearance() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.openSettings()
        runCurrent()

        var state = model.state.value
        assertTrue(state.settingsWindow.open)
        assertTrue(state.settingsWindow.loaded)
        assertTrue(core.rpcCalls.contains("settings.get"))

        model.settings.setLanguage(com.fograin.launcher.settings.AppLanguage.JAPANESE)
        runCurrent()
        state = model.state.value
        assertEquals(com.fograin.launcher.settings.AppLanguage.JAPANESE, state.language)

        model.settings.setAccent(com.fograin.launcher.settings.AccentPreference.custom("#3f8f6b"))
        runCurrent()
        state = model.state.value
        assertEquals("#3F8F6B", state.accent.value)

        model.settings.setInitialPage(com.fograin.launcher.settings.InitialPage.DOWNLOADS)
        runCurrent()
        assertEquals(
            com.fograin.launcher.settings.InitialPage.DOWNLOADS,
            model.state.value.initialPage,
        )

        model.settings.back()
        assertFalse(model.state.value.settingsWindow.open)
    }

    @Test
    fun javaRemovalUsesTheManagedId() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.java.remove("mojang-java-runtime-delta-21.0.7-arm64")
        runCurrent()

        assertEquals(
            listOf("mojang-java-runtime-delta-21.0.7-arm64"),
            core.javaRemovedIds,
        )
    }

    @Test
    fun contentCategorySearchesAndStartsAnInstallTask() = runTest {
        val model = model(backgroundScope)
        runCurrent()

        model.downloads.setCategory(DownloadsCategory.MOD)
        runCurrent()

        val browser = model.state.value.contentBrowser
        assertTrue(browser.projects.isNotEmpty(), "content.search was not applied")
        assertEquals("fog-forest", browser.targetInstanceId)

        model.contentBrowser.openProject(browser.projects.first())
        runCurrent()
        assertTrue(model.state.value.contentBrowser.versions.isNotEmpty(), "content.versions was not applied")

        model.contentBrowser.installSelected()
        runCurrent()
        assertEquals("content-task-1", model.state.value.contentBrowser.installTaskId)
        assertNull(model.state.value.contentBrowser.selectedProject)
    }

    @Test
    fun contentVersionChannelFiltersTheListAndDefaultsToRelease() = runTest {
        val model = model(backgroundScope)
        runCurrent()

        model.downloads.setCategory(DownloadsCategory.MOD)
        runCurrent()

        model.contentBrowser.openProject(model.state.value.contentBrowser.projects.first())
        runCurrent()

        val release = model.state.value.contentBrowser
        assertEquals(ContentVersionChannel.RELEASE, release.versionChannel)
        assertEquals(listOf("c3YkZvne"), release.visibleVersions.map { it.versionId })
        assertEquals("c3YkZvne", release.selectedVersion?.versionId)

        model.setContentVersionChannel(ContentVersionChannel.ALL)
        runCurrent()
        assertEquals(3, model.state.value.contentBrowser.visibleVersions.size)

        model.setContentVersionChannel(ContentVersionChannel.BETA)
        runCurrent()
        val beta = model.state.value.contentBrowser
        assertEquals(listOf("beta-v"), beta.visibleVersions.map { it.versionId })
        assertEquals("beta-v", beta.selectedVersion?.versionId)
    }

    @Test
    fun requiredDependenciesPromptBeforeInstalling() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.downloads.setCategory(DownloadsCategory.MOD)
        runCurrent()
        model.contentBrowser.openProject(model.state.value.contentBrowser.projects.first())
        runCurrent()
        model.setContentVersionChannel(ContentVersionChannel.BETA)
        runCurrent()

        model.contentBrowser.installSelected()
        runCurrent()

        val prompt = model.state.value.contentBrowser.dependencyPrompt
        assertNotNull(prompt, "missing prerequisites should open the dialog")
        assertEquals("Sodium", prompt.projectName)
        assertEquals(listOf("Fabric API"), prompt.items.map { it.projectName })
        assertTrue(core.contentInstallRequests.isEmpty(), "nothing may download before the choice")

        // "仅安装本体" installs the mod without touching the prerequisites.
        model.contentBrowser.confirmDependencyPrompt(autoDownload = false)
        runCurrent()
        assertEquals("content-task-1", model.state.value.contentBrowser.installTaskId)
        assertEquals(
            "false",
            (core.contentInstallRequests.last()["installDependencies"] as JsonPrimitive).content,
        )
        assertNull(model.state.value.contentBrowser.dependencyPrompt)

        // Reopen the beta version and let the launcher pull the prerequisites.
        model.contentBrowser.openProject(model.state.value.contentBrowser.projects.first())
        runCurrent()
        model.setContentVersionChannel(ContentVersionChannel.BETA)
        runCurrent()
        model.contentBrowser.installSelected()
        runCurrent()
        assertNotNull(model.state.value.contentBrowser.dependencyPrompt)

        model.contentBrowser.confirmDependencyPrompt(autoDownload = true)
        runCurrent()
        assertEquals(
            "true",
            (core.contentInstallRequests.last()["installDependencies"] as JsonPrimitive).content,
        )
    }

    @Test
    fun contentManagerLoadsWorldsAndChecksUpdates() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()

        model.openInstanceDetail(instance, ContentManagerTab.MODS)
        runCurrent()
        assertEquals(LibraryStage.DETAIL, model.state.value.libraryStage)
        assertTrue(model.state.value.content.items.isNotEmpty(), "content.local was not applied")
        assertTrue(model.state.value.content.worlds.isNotEmpty(), "worlds.list was not applied")

        model.content.setQuery("sodium")
        assertEquals(
            listOf("Sodium"),
            model.state.value.content.visibleItemsFor(ContentManagerTab.MODS).map { it.title },
        )
        assertTrue(model.state.value.content.visibleItemsFor(ContentManagerTab.RESOURCE_PACKS).isEmpty())
        model.content.setQuery("")

        model.content.checkUpdates()
        runCurrent()
        assertTrue(model.state.value.content.updates.isNotEmpty(), "content.updates was not applied")

        model.content.setTab(ContentManagerTab.WORLDS)
        model.content.backupWorld("New World")
        runCurrent()
        assertTrue(model.state.value.content.backups.isNotEmpty(), "worlds.backup was not applied")

        model.content.setQuery("New")
        assertEquals("New", model.state.value.content.query)
        assertTrue(model.state.value.content.visibleWorlds().isNotEmpty())
        assertTrue(model.state.value.content.visibleBackups().isEmpty())

        model.back()
        assertEquals(LibraryStage.LIST, model.state.value.libraryStage)
    }

    @Test
    fun detailEmptyStateOpensDownloadsForTheSameInstance() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()

        model.openInstanceDetail(instance)
        model.browseContentFor(ContentManagerTab.RESOURCE_PACKS)
        runCurrent()

        val state = model.state.value
        assertEquals(AppDestination.DOWNLOADS, state.destination)
        assertEquals(DownloadsCategory.RESOURCEPACK, state.download.category)
        assertEquals(instance.id, state.contentBrowser.targetInstanceId)
        assertTrue(state.contentBrowser.projects.isNotEmpty())
    }

    @Test
    fun settingsContentShortcutReturnsToTheRequestedTab() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()

        model.manageInstance(instance)
        runCurrent()
        assertEquals(LibraryStage.SETTINGS, model.state.value.libraryStage)

        model.showInstanceContent(ContentManagerTab.RESOURCE_PACKS)
        assertEquals(LibraryStage.DETAIL, model.state.value.libraryStage)
        assertEquals(ContentManagerTab.RESOURCE_PACKS, model.state.value.content.tab)

        model.manageInstance(instance)
        model.back()
        assertEquals(LibraryStage.DETAIL, model.state.value.libraryStage)
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
    @Test
    fun accountsLoadWithTheSelectedRow() = runTest {
        val model = model(backgroundScope)
        runCurrent()

        val account = model.state.value.account
        assertEquals(1, account.accounts.size)
        assertEquals("Steve_Fog", account.selected?.name)
        assertEquals("Steve_Fog", account.displayName)
        assertEquals("offline-steve", model.selectedAccountId)
    }

    @Test
    fun offlineAccountCreationSelectsTheNewRow() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.account.open()
        model.account.showOfflineForm()
        model.account.addOffline("Alex")
        runCurrent()

        val state = model.state.value.account
        assertEquals(AccountStage.LIST, state.stage)
        assertEquals("Alex", state.selected?.name)
        assertEquals("offline-Alex", model.selectedAccountId)
        assertTrue(state.accounts.any { it.name == "Alex" })
        assertTrue(core.rpcCalls.contains("accounts.addOffline"))
    }

    @Test
    fun yggdrasilLoginCanRequireAProfileChoice() = runTest {
        val core = FakeCore().apply { yggdrasilNeedsProfile = true }
        val model = model(backgroundScope, core)
        runCurrent()

        model.account.open()
        model.account.showYggdrasilForm()
        model.account.loginYggdrasil("https://littleskin.cn/api/yggdrasil", "user@example.com", "secret")
        runCurrent()

        val state = model.state.value.account
        assertEquals(AccountStage.PROFILES, state.stage)
        assertEquals("pending-1", state.pendingId)
        assertEquals("LittleSkin", state.pendingServerName)
        assertEquals(2, state.pendingProfiles.size)
        assertEquals("user@example.com", core.accountLoginRequests.single().stringOrNull("username"))

        model.account.selectYggdrasilProfile("profile-2")
        runCurrent()

        val after = model.state.value.account
        assertEquals(AccountStage.LIST, after.stage)
        assertEquals("FograinAlt", after.selected?.name)
        assertEquals("yggdrasil-profile-2", model.selectedAccountId)
    }

    @Test
    fun yggdrasilLoginWithoutAProfileChoiceSelectsTheAccount() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.account.open()
        model.account.showYggdrasilForm()
        model.account.loginYggdrasil("https://littleskin.cn/api/yggdrasil", "user@example.com", "secret")
        runCurrent()

        val state = model.state.value.account
        assertEquals(AccountStage.LIST, state.stage)
        assertEquals("Fograin", state.selected?.name)
        assertEquals("yggdrasil-profile-1", model.selectedAccountId)
        assertEquals("LittleSkin", state.selected?.serverName)
    }

    @Test
    fun launchSendsTheSelectedAccountId() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()
        val installed = model.state.value.instances.first { it.isInstalled }

        model.launch.start(installed)
        runCurrent()

        val request = core.launchRequests.single()
        assertEquals("offline-steve", request.stringOrNull("accountId"))
        assertEquals(null, request.stringOrNull("username"))
        assertEquals(installed.id, request.stringOrNull("instanceId"))
    }

    @Test
    fun modpackCategorySearchesWithoutAnInstanceAndInstalls() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.downloads.setCategory(DownloadsCategory.MODPACK)
        runCurrent()

        val request = core.contentSearchRequests.last()
        assertEquals("modpack", request.stringOrNull("kind"))
        assertNull(request.stringOrNull("instanceId"))
        val browser = model.state.value.contentBrowser
        assertEquals("", browser.targetInstanceId)
        assertTrue(browser.projects.isNotEmpty())

        model.contentBrowser.openProject(browser.projects.first())
        runCurrent()
        model.contentBrowser.installSelected()
        runCurrent()

        assertEquals("modpack-task-1", model.state.value.contentBrowser.installTaskId)
        assertNull(model.state.value.contentBrowser.selectedProject)
        assertEquals("modrinth", core.modpackInstallRequests.last().stringOrNull("source"))
        assertEquals("Sodium", core.modpackInstallRequests.last().stringOrNull("name"))
    }

    @Test
    fun downloadedModpackOpensTheNewInstanceWhenInstallFinishes() = runTest {
        val core = FakeCore()
        core.extraInstanceRow =
            """{"id":"calm","name":"Calm","minecraft":"1.21.4","loaderKind":"fabric","loaderVersion":"0.16.9","favorite":false,"installed":true,"modCount":3,"sizeBytes":100,"playSeconds":0,"lastPlayedMs":null}"""
        val model = model(backgroundScope, core)
        runCurrent()

        model.navigate(AppDestination.DOWNLOADS)
        model.downloads.setCategory(DownloadsCategory.MODPACK)
        runCurrent()
        val project = model.state.value.contentBrowser.projects.first()
        model.contentBrowser.openProject(project)
        runCurrent()
        model.contentBrowser.installSelected()
        runCurrent()

        // The archive download alone must not end the flow.
        model.handleEvent(
            CoreEvent.TaskFinished(
                taskId = "modpack-task-1",
                name = "整合包下载:${project.title}",
                state = TaskState.SUCCEEDED,
                detail = emptyList(),
            ),
        )
        runCurrent()
        assertEquals(AppDestination.DOWNLOADS, model.state.value.destination)
        assertTrue(model.state.value.notice?.message.orEmpty().contains("正在安装"))

        model.handleEvent(
            CoreEvent.ModpackFinished(
                instanceId = "calm",
                name = "Calm",
                state = TaskState.SUCCEEDED,
                detail = emptyList(),
            ),
        )
        runCurrent()

        val state = model.state.value
        assertEquals(AppDestination.LIBRARY, state.destination)
        assertEquals(LibraryStage.DETAIL, state.libraryStage)
        assertEquals("calm", state.libraryDetailId)
    }

    @Test
    fun modpackInstallFailureIsReportedAfterTheDownload() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.downloads.setCategory(DownloadsCategory.MODPACK)
        runCurrent()
        model.contentBrowser.openProject(model.state.value.contentBrowser.projects.first())
        runCurrent()
        model.contentBrowser.installSelected()
        runCurrent()

        model.handleEvent(
            CoreEvent.ModpackFinished(
                instanceId = "calm",
                name = "Calm",
                state = TaskState.FAILED,
                detail = listOf("modpack: install failed"),
            ),
        )
        runCurrent()

        val alert = model.state.value.alert
        assertNotNull(alert)
        assertTrue(alert.title.contains("Calm"))
        assertTrue(alert.message.contains("install failed"))
    }

    @Test
    fun datapackCategoryLoadsWorldsAndInstallsIntoTheWorld() = runTest {
        val core = FakeCore()
        val model = model(backgroundScope, core)
        runCurrent()

        model.downloads.setCategory(DownloadsCategory.DATAPACK)
        runCurrent()

        val browser = model.state.value.contentBrowser
        assertEquals("fog-forest", browser.targetInstanceId)
        assertEquals("New World", browser.targetWorld)
        assertEquals(1, browser.worlds.size)

        model.contentBrowser.openProject(browser.projects.first())
        runCurrent()
        model.contentBrowser.installSelected()
        runCurrent()

        val install = core.contentInstallRequests.last()
        assertEquals("datapack", install.stringOrNull("kind"))
        assertEquals("New World", install.stringOrNull("world"))
        assertEquals("content-task-1", model.state.value.contentBrowser.installTaskId)
    }

    @Test
    fun diagnosticsAnalyzeAndExportThenBackToSettings() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()

        model.openInstanceDetail(instance)
        model.manageInstance(instance)
        model.diag.open(instance)
        runCurrent()

        assertEquals(LibraryStage.DIAGNOSTICS, model.state.value.libraryStage)
        val diag = model.state.value.diag
        assertEquals(1, diag.findings.size)
        assertEquals("java.memory", diag.findings.first().id)
        assertTrue(diag.textFor(diag.activeTab).contains("OutOfMemory"))

        model.diag.export()
        runCurrent()
        assertTrue(
            model.state.value.diag.bundlePath.orEmpty().endsWith(".zip"),
            "the diagnostics export did not report a destination",
        )

        model.back()
        assertEquals(LibraryStage.SETTINGS, model.state.value.libraryStage)
    }

    @Test
    fun datapacksLoadForTheSelectedWorld() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()

        model.openInstanceDetail(instance, ContentManagerTab.DATAPACKS)
        runCurrent()

        val content = model.state.value.content
        assertEquals(ContentManagerTab.DATAPACKS, content.tab)
        assertEquals("New World", content.datapackWorld)
        assertEquals(1, content.datapacks.size)
        assertEquals("terralith.zip", content.datapacks.first().fileName)

        model.content.selectDatapackWorld("New World")
        runCurrent()
        assertTrue(content.visibleDatapacks().isNotEmpty())
    }

    @Test
    fun modpackImportInspectsTheFileAndQueuesTheTask() = runTest {
        val platform = FakePlatform().apply { nextFilePick = "/tmp/calm.mrpack" }
        val model = AppModel.create(core = FakeCore(), platform = platform.services, scope = backgroundScope)
        runCurrent()

        model.modpack.startImport()
        runCurrent()
        val pack = model.state.value.modpack.pack
        assertNotNull(pack)
        assertEquals("Calm", pack.name)
        assertEquals("1.21.4", pack.minecraft)
        assertEquals("Calm", model.state.value.modpack.name)

        model.modpack.confirmImport()
        runCurrent()
        assertEquals("task-modpack", model.state.value.modpack.taskId)
        assertEquals("calm", model.state.value.modpack.importedInstanceId)
    }

    @Test
    fun localModpackImportOpensTheNewInstanceWhenItFinishes() = runTest {
        val platform = FakePlatform().apply { nextFilePick = "/tmp/calm.mrpack" }
        val core = FakeCore()
        core.extraInstanceRow =
            """{"id":"calm","name":"Calm","minecraft":"1.21.4","loaderKind":"fabric","loaderVersion":"0.16.9","favorite":false,"installed":true,"modCount":3,"sizeBytes":100,"playSeconds":0,"lastPlayedMs":null}"""
        val model = AppModel.create(
            core = core,
            platform = platform.services,
            scope = backgroundScope,
        )
        runCurrent()

        model.modpack.startImport()
        runCurrent()
        model.modpack.confirmImport()
        runCurrent()

        model.handleEvent(
            CoreEvent.ModpackFinished(
                instanceId = "calm",
                name = "Calm",
                state = TaskState.SUCCEEDED,
                detail = emptyList(),
            ),
        )
        runCurrent()

        val state = model.state.value
        assertNull(state.modpack.importedInstanceId)
        assertEquals(AppDestination.LIBRARY, state.destination)
        assertEquals(LibraryStage.DETAIL, state.libraryStage)
        assertEquals("calm", state.libraryDetailId)
    }

    @Test
    fun modpackExportWizardPreparesMetadataAndContent() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()

        model.modpack.beginExport(instance)
        runCurrent()
        val export = model.state.value.modpackExport
        assertEquals(LibraryStage.EXPORT, model.state.value.libraryStage)
        assertEquals(instance.id, export.instanceId)
        assertEquals(instance.name, export.name)
        assertEquals("1.0", export.version)
        assertEquals(ExportStep.FORM, export.step)
        assertTrue(export.sections.isNotEmpty(), "modpack.export.plan was not applied")
        assertTrue("mods" in export.selected)
        assertTrue("configs" in export.selected)
        assertTrue("folder:kubejs" in export.selected)
        assertTrue("servers" !in export.selected)
        assertTrue("gameSettings" !in export.selected)

        model.modpack.nextExportStep()
        assertEquals(ExportStep.CONTENT, model.state.value.modpackExport.step)
        model.modpack.toggleExportSection("servers")
        assertTrue("servers" in model.state.value.modpackExport.selected)
        model.modpack.backExportStep()
        assertEquals(ExportStep.FORM, model.state.value.modpackExport.step)

        model.modpack.export()
        runCurrent()
        assertEquals("/tmp/exports/calm.mrpack", model.state.value.modpackExport.result?.path)
        model.modpack.dismissExportResult()
        assertEquals(LibraryStage.SETTINGS, model.state.value.libraryStage)
    }

    @Test
    fun modpackExportBackWalksTheWizardThenReturnsToSettings() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()
        model.modpack.beginExport(instance)
        runCurrent()
        model.modpack.nextExportStep()
        assertEquals(ExportStep.CONTENT, model.state.value.modpackExport.step)

        model.back()
        assertEquals(ExportStep.FORM, model.state.value.modpackExport.step)
        model.back()
        assertEquals(LibraryStage.SETTINGS, model.state.value.libraryStage)
    }

    @Test
    fun crashExitRunsTheAnalysisBeforeTheAlert() = runTest {
        val model = model(backgroundScope)
        runCurrent()
        val instance = model.state.value.instances.first()
        model.setState { it.copy(currentInstanceId = instance.id) }

        model.handleEvent(CoreEvent.GameExited(instance.id, 1, 12))
        runCurrent()

        val alert = model.state.value.alert
        assertNotNull(alert)
        assertEquals("游戏异常退出", alert.title)
        assertTrue(
            alert.detail.orEmpty().contains("内存不足"),
            "exit analysis should name the finding: ${alert.detail}",
        )
        assertEquals(instance.id, model.state.value.diag.instanceId)
        assertTrue(model.state.value.diag.findings.isNotEmpty())
    }

    @Test
    fun downloadQueueOpensAsItsOwnPageAndBackReturnsHome() = runTest {
        val core = FakeCore().apply {
            taskRows = """{"ok":true,"result":[
                {"id":"task-1","name":"install:1.21.4","state":"running","fraction":0.42,"speed":1200000.0},
                {"id":"task-2","name":"整合包下载:Calm","state":"paused","fraction":0.7,"speed":0.0}
            ]}"""
        }
        val model = model(backgroundScope, core)
        runCurrent()
        assertEquals(2, model.state.value.activeDownloadCount)

        model.downloads.openQueue()
        runCurrent()
        assertTrue(model.state.value.downloadQueueOpen)
        assertTrue(model.canGoBack())

        model.back()
        assertFalse(model.state.value.downloadQueueOpen)
        assertEquals(AppDestination.HOME, model.state.value.destination)
        assertFalse(model.canGoBack())
    }

    @Test
    fun downloadQueueControlsCallTheCoreAndRefreshRows() = runTest {
        val core = FakeCore().apply {
            taskRows = """{"ok":true,"result":[
                {"id":"task-1","name":"install:1.21.4","state":"running","fraction":0.42,"speed":1200000.0}
            ]}"""
        }
        val model = model(backgroundScope, core)
        runCurrent()

        model.downloads.openQueue()
        runCurrent()
        model.downloads.pause("task-1")
        runCurrent()
        assertEquals("tasks.pause" to "task-1", core.taskControlRequests.last())

        model.downloads.resume("task-1")
        runCurrent()
        assertEquals("tasks.resume" to "task-1", core.taskControlRequests.last())

        model.downloads.cancel("task-1")
        runCurrent()
        assertEquals("tasks.cancel" to "task-1", core.taskControlRequests.last())
    }

    @Test
    fun accountBackStackReturnsToListBeforeClosing() = runTest {
        val model = model(backgroundScope)
        runCurrent()

        model.account.open()
        assertTrue(model.state.value.account.open)
        model.account.showYggdrasilForm()
        assertTrue(model.canGoBack())

        model.back()
        assertEquals(AccountStage.LIST, model.state.value.account.stage)
        assertTrue(model.state.value.account.open)

        model.back()
        assertFalse(model.state.value.account.open)
        assertFalse(model.canGoBack())
    }

}
