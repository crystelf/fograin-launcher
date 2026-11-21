package com.fograin.launcher.app

import com.fograin.launcher.model.ContentLoaderFilter
import com.fograin.launcher.model.ContentVersionChannel
import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.platform.CoreAppSnapshot
import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.platform.CoreContentProject
import com.fograin.launcher.platform.CoreContentUpdate
import com.fograin.launcher.platform.CoreLocalContent
import com.fograin.launcher.platform.CoreEvent
import com.fograin.launcher.platform.CoreException
import com.fograin.launcher.platform.CoreGameConfig
import com.fograin.launcher.platform.CoreInstanceRow
import com.fograin.launcher.platform.CoreInstanceSettings
import com.fograin.launcher.platform.CoreJavaList
import com.fograin.launcher.platform.CoreDownloadSettings
import com.fograin.launcher.platform.CoreJavaRuntime
import com.fograin.launcher.platform.CoreNetworkSettings
import com.fograin.launcher.platform.CoreLoaderVersion
import com.fograin.launcher.platform.CoreTaskRow
import com.fograin.launcher.platform.CoreWorldBackup
import com.fograin.launcher.platform.DirectoryPick
import com.fograin.launcher.platform.PlatformServices
import com.fograin.launcher.platform.decode
import com.fograin.launcher.platform.objectRows
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.platform.resultRows
import com.fograin.launcher.settings.AccentPreference
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.settings.InitialPage
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.AppDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Launcher state shared by Android and desktop.
 *
 * The Rust core is the source of truth: every instance, task and session value
 * comes from RPC responses or the event queue. This facade owns the state
 * container, the event pump (250 ms), the notice/alert channels and navigation;
 * the feature flows live in [LibraryActions], [DownloadsActions],
 * [LaunchActions] and [InstanceSettingsActions] so no single file has to hold
 * every RPC.
 */
class AppModel internal constructor(
    internal val core: CoreClient,
    internal val platform: PlatformServices,
    internal val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "AppModel"

        /**
         * Builds a model for a shell. Compose callers should pass the
         * composition scope (`rememberCoroutineScope`); other callers get a
         * supervisor scope on the platform main dispatcher.
         */
        fun create(
            core: CoreClient,
            platform: PlatformServices = platformServices(),
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ): AppModel = AppModel(core, platform, scope)
    }

    internal val library = LibraryActions(this)
    internal val downloads = DownloadsActions(this)
    internal val session = LaunchActions(this)
    internal val instanceSettings = InstanceSettingsActions(this)
    internal val java = JavaActions(this)
    internal val account = AccountActions(this)
    internal val content = ContentActions(this)
    internal val diag = DiagActions(this)
    internal val modpack = ModpackActions(this)
    internal val settingsPanel = SettingsActions(this)

    private val refreshMutex = Mutex()

    private val _state: MutableStateFlow<AppUiState>
    val state: StateFlow<AppUiState>

    private var idGenerator = 0L
    private var noticeJob: Job? = null
    private var pollJob: Job? = null

    /** True once the JNI library loaded and the settings snapshot answered. */
    val isCoreAvailable: Boolean
        get() = core.isAvailable

    /** Non-null when the core failed to initialize or read `launcher.toml`. */
    val initializationError: String?
        get() = core.initError

    init {
        val settings = core.settings
        val initialPage = InitialPage.fromWire(settings.initialPage)
        _state = MutableStateFlow(
            AppUiState(
                settings = settings,
                theme = settings.theme,
                language = AppLanguage.fromWire(settings.language),
                accent = AccentPreference.fromWire(settings.accent),
                initialPage = initialPage,
                destination = when (initialPage) {
                    InitialPage.HOME -> AppDestination.HOME
                    InitialPage.LIBRARY -> AppDestination.LIBRARY
                    InitialPage.DOWNLOADS -> AppDestination.DOWNLOADS
                },
                deviceMemoryBytes = platform.deviceInfo.totalMemoryBytes,
                deviceText = platform.deviceInfo.deviceText,
                socText = platform.deviceInfo.socText,
                cpuCount = platform.deviceInfo.cpuCount,
            ),
        )
        state = _state.asStateFlow()

        scope.launch { refreshCoreData() }
        // Warm the download/network settings so opening Settings does not pop
        // a loading spinner in the middle of the entrance animation.
        scope.launch { settingsPanel.load() }
        startEventPump()

        core.initError?.let { showNotice(it, Notice.Tone.WARNING) }
    }

    /** Mutable state entry point for the feature controllers in this package. */
    internal fun setState(transform: (AppUiState) -> AppUiState) {
        _state.update(transform)
    }

    internal fun nextId(): Long {
        idGenerator += 1
        return idGenerator
    }

    /** Reloads instances, the app snapshot, `[game]` and the task list. */
    suspend fun refreshCoreData() {
        refreshMutex.withLock {
            try {
                val payload = core.rpc("instances.list")
                val instances = payload.resultRows()
                    .mapNotNull { row -> runCatching { row.decode<CoreInstanceRow>() }.getOrNull() }
                    .mapNotNull { Instance.from(it) }
                _state.update { current ->
                    val currentId = current.currentInstanceId
                        .takeIf { id -> instances.any { it.id == id } }
                        ?: instances.firstOrNull()?.id.orEmpty()
                    val selection = current.librarySelection
                        ?.takeIf { id -> instances.any { it.id == id } }
                        ?: instances.firstOrNull()?.id
                    current.copy(
                        instances = instances,
                        currentInstanceId = currentId,
                        librarySelection = selection,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                platform.logger.error(TAG, "instances.list failed", error)
                if (state.value.instances.isEmpty()) {
                    showError("无法读取实例列表", error)
                }
            }

            runCatching { core.rpc("app.snapshot") }
                .onSuccess { payload ->
                    val snapshot = runCatching { payload.decode<CoreAppSnapshot>() }.getOrNull()
                    if (snapshot != null) {
                        _state.update { it.copy(appSnapshot = snapshot) }
                    }
                }

            runCatching { core.rpc("gameConfig.get") }
                .onSuccess { payload ->
                    val config = runCatching { payload.decode<CoreGameConfig>() }.getOrNull()
                    if (config != null) {
                        _state.update { it.copy(gameConfig = config) }
                    }
                }

            // Local file read; keeps the home avatar and the launch path on the
            // same selected account after external CLI changes.
            account.load()

            // Fast inventory read: Settings shows the auto-download policy and
            // the managed count without opening the network-bound catalog.
            runCatching { core.rpc("java.list") }
                .onSuccess { payload ->
                    val listed = runCatching { payload.decode<CoreJavaList>() }.getOrNull()
                    if (listed != null) {
                        _state.update {
                            it.copy(
                                java = it.java.copy(
                                    runtimes = listed.runtimes,
                                    autoDownload = listed.autoDownload,
                                    supported = listed.supported,
                                    hostArch = listed.hostArch,
                                ),
                            )
                        }
                    }
                }

            refreshTaskList()
            _state.update { it.copy(isInitialLoading = false) }
        }
    }

    internal suspend fun refreshTaskList() = refreshDownloadsFromTasks()

    private suspend fun refreshDownloadsFromTasks() {
        try {
            val payload = core.rpc("tasks.list")
            val rows = payload.resultRows()
                .mapNotNull { row -> runCatching { row.decode<CoreTaskRow>() }.getOrNull() }
            _state.update { current ->
                current.copy(
                    downloads = rows.map { row ->
                        DownloadRow(
                            id = row.id,
                            title = InstanceFormat.taskTitle(row.name),
                            fraction = row.fraction,
                            speed = row.speed,
                            state = row.state,
                            graphName = row.name,
                        )
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            platform.logger.warn(TAG, "tasks.list failed", error)
        }
    }

    /** One Rust-side command with error surfacing through the notice capsule. */
    fun callCore(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        notice: String? = null,
    ) {
        scope.launch {
            try {
                core.rpc(method, params)
                if (notice != null) showNotice(notice, Notice.Tone.SUCCESS)
                refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError(notice ?: "操作失败", error)
            }
        }
    }

    fun showNotice(message: String, tone: Notice.Tone = Notice.Tone.INFO) {
        noticeJob?.cancel()
        val notice = Notice(id = nextId(), message = message, tone = tone)
        _state.update { it.copy(notice = notice) }
        noticeJob = scope.launch {
            delay(2_800)
            _state.update { if (it.notice?.id == notice.id) it.copy(notice = null) else it }
        }
    }

    fun consumeNotice(id: Long) {
        _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    fun showError(title: String, error: Throwable) {
        platform.logger.error(TAG, title, error)
        when (error) {
            is CoreException -> _state.update {
                it.copy(
                    alert = AppAlert(
                        id = nextId(),
                        title = title,
                        message = error.payload.userMessage,
                        detail = error.payload.detail,
                    ),
                )
            }

            else -> _state.update {
                it.copy(
                    alert = AppAlert(
                        id = nextId(),
                        title = title,
                        message = error.message ?: error::class.simpleName.orEmpty(),
                    ),
                )
            }
        }
    }

    fun showError(title: String, message: String, detail: String? = null) {
        platform.logger.error(TAG, "$title: $message", null)
        _state.update {
            it.copy(alert = AppAlert(id = nextId(), title = title, message = message, detail = detail))
        }
    }

    fun dismissAlert() {
        _state.update { it.copy(alert = null) }
    }

    // ------------------------------------------------------------------
    // Navigation and view state. Everything below is pure state: no RPC.
    // ------------------------------------------------------------------

    fun navigate(destination: AppDestination) {
        // Settings is an in-window destination rendered by App, not a fifth
        // shell page. Route the rail/menu/shortcut entry point there.
        if (destination == AppDestination.SETTINGS) {
            openSettings()
            return
        }
        _state.update {
            it.copy(
                destination = destination,
                // The queue behaves like any other in-shell page: picking a
                // destination leaves it before the page transition runs.
                downloadQueueOpen = false,
                // Library sub-pages are not tabs of their own: leaving the
                // destination returns the next visit to the instance list.
                libraryStage = if (destination == AppDestination.LIBRARY) {
                    it.libraryStage
                } else {
                    LibraryStage.LIST
                },
            )
        }
    }

    /**
     * True when [back] would change something: a dialog or sheet is open, an
     * in-destination sub-page is showing, or the shell is not on the home
     * destination. Drives Android's back gesture and the desktop Escape key
     * through the same [com.fograin.launcher.platform.BackDispatcher].
     */
    fun canGoBack(): Boolean {
        val current = state.value
        val installSubPage = current.destination == AppDestination.DOWNLOADS &&
            current.download.stage != DownloadStage.BROWSER
        val librarySubPage = current.destination == AppDestination.LIBRARY &&
            current.libraryStage != LibraryStage.LIST
        return current.alert != null ||
            current.modpack.pack != null ||
            current.account.open ||
            current.downloadQueueOpen ||
            current.contentBrowser.selectedProject != null ||
            current.globalSearchOpen ||
            current.settingsWindow.open ||
            installSubPage ||
            librarySubPage ||
            current.destination != AppDestination.HOME
    }

    /** Dismisses the topmost overlay or sub-page, then leaves the destination. */
    fun back() {
        val current = state.value
        when {
            current.alert != null -> dismissAlert()
            current.modpack.pack != null -> dismissModpackImport()
            current.settingsWindow.open -> settingsPanel.back()
            current.account.open -> account.back()
            current.downloadQueueOpen -> closeDownloadQueue()
            current.contentBrowser.selectedProject != null -> closeContentProject()
            current.globalSearchOpen -> closeGlobalSearch()
            current.destination == AppDestination.LIBRARY &&
                current.libraryStage == LibraryStage.DIAGNOSTICS -> closeDiagnostics()

            current.destination == AppDestination.LIBRARY &&
                current.libraryStage == LibraryStage.EXPORT -> backModpackExportStep()

            current.destination == AppDestination.LIBRARY &&
                current.libraryStage == LibraryStage.SETTINGS -> closeInstanceSettings()

            current.destination == AppDestination.LIBRARY &&
                current.libraryStage == LibraryStage.DETAIL -> closeInstanceDetail()

            current.destination == AppDestination.DOWNLOADS &&
                current.download.stage == DownloadStage.LOADER_VERSIONS ->
                closeLoaderVersionPicker()

            current.destination == AppDestination.DOWNLOADS &&
                current.download.stage == DownloadStage.INSTALL -> closeInstallPage()

            current.destination != AppDestination.HOME -> navigate(AppDestination.HOME)
        }
    }

    fun selectHomeInstance(instance: Instance) {
        _state.update { it.copy(currentInstanceId = instance.id) }
    }

    /** Opens the instance detail page; the library list row uses this. */
    fun openInstance(instance: Instance) = openInstanceDetail(instance)

    /**
     * Enters the instance detail page and loads its local content. The tab is
     * the content category shown below the instance summary.
     */
    fun openInstanceDetail(
        instance: Instance,
        tab: ContentManagerTab = ContentManagerTab.MODS,
    ) {
        _state.update {
            it.copy(
                currentInstanceId = instance.id,
                librarySelection = instance.id,
                libraryDetailId = instance.id,
                libraryStage = LibraryStage.DETAIL,
                destination = AppDestination.LIBRARY,
            )
        }
        content.open(instance.id, tab)
    }

    /** Back from the detail page; the id stays for the exit animation. */
    fun closeInstanceDetail() {
        content.setQuery("")
        _state.update { it.copy(libraryStage = LibraryStage.LIST) }
    }

    /** Opens the per-instance settings page from the detail page or home. */
    fun manageInstance(instance: Instance) {
        val current = state.value.content
        val tab = if (current.instanceId == instance.id) current.tab else ContentManagerTab.MODS
        _state.update {
            it.copy(
                currentInstanceId = instance.id,
                librarySelection = instance.id,
                libraryDetailId = instance.id,
                libraryStage = LibraryStage.SETTINGS,
                destination = AppDestination.LIBRARY,
            )
        }
        // Load the content list in the background so returning to the detail
        // page is instant even when the settings page was opened from Home.
        if (current.instanceId != instance.id) {
            content.open(instance.id, tab)
        }
    }

    /** Back from settings to the detail page that owns the instance summary. */
    fun closeInstanceSettings() {
        _state.update {
            it.copy(
                libraryStage = if (it.libraryDetailId != null) {
                    LibraryStage.DETAIL
                } else {
                    LibraryStage.LIST
                },
            )
        }
    }

    /** Jump from a settings shortcut back to one of the detail content tabs. */
    fun showInstanceContent(tab: ContentManagerTab) {
        val instanceId = state.value.libraryDetailId ?: return
        _state.update {
            it.copy(
                libraryStage = LibraryStage.DETAIL,
                destination = AppDestination.LIBRARY,
            )
        }
        if (state.value.content.instanceId != instanceId) {
            content.open(instanceId, tab)
        } else {
            content.setTab(tab)
        }
    }

    fun openGlobalSearch() {
        _state.update { it.copy(globalSearchOpen = true, globalSearchQuery = "") }
    }

    fun closeGlobalSearch() {
        _state.update { it.copy(globalSearchOpen = false) }
    }

    fun setGlobalSearchQuery(query: String) {
        _state.update { it.copy(globalSearchQuery = query) }
    }

    /** Menu/shortcut entry point: open the library page with the search field focused. */
    fun requestLibrarySearch() {
        _state.update {
            it.copy(
                destination = AppDestination.LIBRARY,
                libraryStage = LibraryStage.LIST,
                librarySearchRequest = it.librarySearchRequest + 1,
            )
        }
    }

    fun setLibraryQuery(query: String) {
        _state.update { it.copy(libraryQuery = query) }
    }

    fun setLibraryScope(scope: LibraryScope) {
        _state.update { it.copy(libraryScope = scope) }
    }

    fun setLibrarySort(sort: LibrarySort) {
        _state.update { it.copy(librarySort = sort) }
    }

    fun setHomeFilter(filter: HomeFilter) {
        _state.update { it.copy(homeFilter = filter) }
    }

    // ------------------------------------------------------------------
    // Settings destination (M10). App animates between the shell and the
    // full-window settings surface on every platform.
    // ------------------------------------------------------------------

    fun openSettings(section: SettingsSection? = null) {
        settingsPanel.refreshDataRootOverride()
        settingsPanel.open(section)
    }

    fun closeSettings() = settingsPanel.close()

    fun settingsBack() = settingsPanel.back()

    fun setSettingsCompact(compact: Boolean) = settingsPanel.setCompact(compact)

    fun selectSettingsSection(section: SettingsSection) = settingsPanel.select(section)

    fun pushSettingsSection(section: SettingsSection) = settingsPanel.push(section)

    fun retrySettings() = settingsPanel.load()

    fun setLanguage(language: AppLanguage) = settingsPanel.setLanguage(language)

    fun setAccent(accent: AccentPreference) = settingsPanel.setAccent(accent)

    fun setInitialPage(page: InitialPage) = settingsPanel.setInitialPage(page)

    fun setGlobalMemoryMb(memoryMb: Int) = settingsPanel.setMemoryMb(memoryMb)

    fun setGlobalJvmArgs(args: String) = settingsPanel.setJvmArgs(args)

    fun setGlobalFullscreen(enabled: Boolean) = settingsPanel.setFullscreen(enabled)

    fun setGlobalShowLog(enabled: Boolean) = settingsPanel.setShowLog(enabled)

    fun setGlobalAutoCompleteResources(enabled: Boolean) =
        settingsPanel.setAutoCompleteResources(enabled)

    fun setGlobalResolution(width: Int?, height: Int?) =
        settingsPanel.setResolution(width, height)

    fun updateDownloadSettings(transform: (CoreDownloadSettings) -> CoreDownloadSettings) =
        settingsPanel.updateDownload(transform)

    fun updateNetworkSettings(transform: (CoreNetworkSettings) -> CoreNetworkSettings) =
        settingsPanel.updateNetwork(transform)

    fun setDataRootOverride(path: String?) = settingsPanel.setDataRootOverride(path)

    fun setTheme(theme: ThemeMode) = settingsPanel.setTheme(theme)

    /** Flips light/dark, the pair users actually toggle; SYSTEM stays untouched. */
    fun toggleTheme() {
        val next = if (state.value.theme == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        setTheme(next)
    }

    // ------------------------------------------------------------------
    // Feature facade. Each method forwards to its controller so the UI keeps
    // a single `model.` surface.
    // ------------------------------------------------------------------

    fun openAddInstance() = library.openAddInstance()

    fun uniqueInstanceID(name: String, version: String): String =
        library.uniqueInstanceID(name, version)

    fun importMinecraftDirectory(directory: String) = library.importMinecraftDirectory(directory)

    suspend fun pickImportDirectory(): DirectoryPick = library.pickImportDirectory()

    fun toggleFavorite(instance: Instance) = library.toggleFavorite(instance)

    fun deleteInstance(instance: Instance) = library.deleteInstance(instance)

    suspend fun loadDownloadVersions() = downloads.loadVersions()

    suspend fun reloadDownloadVersions() = downloads.reloadVersions()

    fun setDownloadCategory(category: DownloadsCategory) = downloads.setCategory(category)

    fun setDownloadChannel(channel: DownloadVersionChannel) = downloads.setChannel(channel)

    fun setDownloadContentLoader(loader: ContentLoaderFilter) = downloads.setContentLoader(loader)

    fun setDownloadQuery(query: String) = downloads.setQuery(query)

    fun openInstallPage(versionId: String) = downloads.openInstallPage(versionId)

    fun closeInstallPage() = downloads.closeInstallPage()

    fun setInstallLoader(loader: GameLoaderKind) = downloads.setInstallLoader(loader)

    fun openLoaderVersionPicker() = downloads.openLoaderVersionPicker()

    fun closeLoaderVersionPicker() = downloads.closeLoaderVersionPicker()

    fun selectInstallLoaderVersion(version: String) =
        downloads.selectInstallLoaderVersion(version)

    fun retryLoaderVersions() = downloads.retryLoaderVersions()

    fun setInstallInstanceName(name: String) = downloads.setInstallInstanceName(name)

    fun refreshInstallPlan() = downloads.refreshInstallPlan()

    fun submitInstall() = downloads.submitInstall()

    suspend fun startGameInstall(
        version: String,
        instanceName: String,
        loader: GameLoaderKind = GameLoaderKind.VANILLA,
        loaderVersion: String = "",
    ) = downloads.startGameInstall(version, instanceName, loader, loaderVersion)

    /** Real loader builds for a Minecraft version, newest first. */
    suspend fun loadLoaderVersions(
        loader: GameLoaderKind,
        minecraft: String,
    ): List<CoreLoaderVersion> {
        if (loader == GameLoaderKind.VANILLA || minecraft.isBlank()) return emptyList()
        val payload = core.rpc(
            "loaders.versions",
            buildJsonObject {
                put("kind", loader.wireName)
                put("minecraft", minecraft)
                put("limit", 50)
            },
        )
        return payload.objectRows("versions").mapNotNull { row ->
            runCatching { row.decode<CoreLoaderVersion>() }.getOrNull()
        }
    }

    fun isDownloading(instance: Instance): Boolean = downloads.isDownloading(instance)

    fun openDownloadQueue() = downloads.openQueue()

    fun closeDownloadQueue() = downloads.closeQueue()

    fun pauseDownload(taskId: String) = downloads.pause(taskId)

    fun resumeDownload(taskId: String) = downloads.resume(taskId)

    fun cancelDownload(taskId: String) = downloads.cancel(taskId)

    fun startGame(instance: Instance) = session.start(instance)

    fun stopGame() = session.stop()

    suspend fun loadInstanceSettings(id: String): CoreInstanceSettings =
        instanceSettings.load(id)

    fun configureInstance(id: String, setting: String, value: JsonElement?) =
        instanceSettings.configure(id, setting, value)

    fun openInstanceDirectory(instance: Instance) =
        instanceSettings.openDirectory(instance)

    // ------------------------------------------------------------------
    // Accounts (M7)
    // ------------------------------------------------------------------

    fun openAccounts() = account.open()

    fun closeAccounts() = account.close()

    fun showOfflineAccountForm() = account.showOfflineForm()

    fun showYggdrasilAccountForm() = account.showYggdrasilForm()

    fun refreshAccounts() = account.refresh()

    fun clearAccountError() = account.clearError()

    fun addOfflineAccount(name: String) = account.addOffline(name)

    fun loginYggdrasil(server: String, username: String, password: String) =
        account.loginYggdrasil(server, username, password)

    fun selectYggdrasilProfile(profileId: String) = account.selectYggdrasilProfile(profileId)

    fun selectAccount(id: String) = account.select(id)

    fun removeAccount(id: String) = account.remove(id)

    fun refreshAccount(id: String? = null) = account.refreshAccount(id)

    /** Selected account id for `launch.start`, or null before the first load. */
    val selectedAccountId: String?
        get() = state.value.account.selectedId ?: state.value.account.selected?.id

    // ------------------------------------------------------------------
    // Java runtime management (M6)
    // ------------------------------------------------------------------

    /** Menu/shortcut/search entry point: settings page on the Java section. */
    fun openJavaSettings() {
        settingsPanel.open(SettingsSection.JAVA)
        refreshJavaSettings()
    }

    fun closeJavaSettings() = settingsPanel.back()

    fun refreshJavaSettings() = java.refresh()

    fun setJavaAutoDownload(enabled: Boolean) = java.setAutoDownload(enabled)

    fun installJavaRuntime(major: Int) = java.install(major)

    fun removeJavaRuntime(id: String) = java.remove(id)

    suspend fun loadJavaRuntimes(): List<CoreJavaRuntime> = java.runtimes()

    /**
     * Opens the downloads content browser for this instance and matches the
     * detail page tab (mods / resource packs / shaders). The instance detail
     * page's empty state uses this so installing content never requires
     * finding the instance again in the downloads target picker.
     */
    fun browseContentFor(tab: ContentManagerTab) {
        val instance = state.value.libraryDetailInstance ?: return
        val category = when (tab) {
            ContentManagerTab.MODS -> DownloadsCategory.MOD
            ContentManagerTab.RESOURCE_PACKS -> DownloadsCategory.RESOURCEPACK
            ContentManagerTab.SHADER_PACKS -> DownloadsCategory.SHADERPACK
            ContentManagerTab.WORLDS -> return
            ContentManagerTab.DATAPACKS -> return
        }
        if (state.value.download.stage != DownloadStage.BROWSER) {
            downloads.closeInstallPage()
        }
        _state.update {
            it.copy(
                destination = AppDestination.DOWNLOADS,
                libraryStage = LibraryStage.LIST,
                contentBrowser = it.contentBrowser.copy(targetInstanceId = instance.id),
            )
        }
        if (state.value.download.category != category) {
            downloads.setCategory(category)
        } else {
            content.openCategory(category)
        }
    }

    fun setContentTab(tab: ContentManagerTab) = content.setTab(tab)

    fun setContentQuery(query: String) = content.setQuery(query)

    fun refreshContentManager() = content.refresh()

    fun toggleContent(item: CoreLocalContent) = content.toggle(item)

    fun deleteContent(item: CoreLocalContent) = content.delete(item)

    fun checkContentUpdates() = content.checkUpdates()

    fun installContentUpdate(update: CoreContentUpdate) = content.installUpdate(update)

    fun backupWorld(worldName: String) = content.backupWorld(worldName)

    fun deleteWorld(worldName: String) = content.deleteWorld(worldName)

    fun restoreWorldBackup(backup: CoreWorldBackup, worldName: String) =
        content.restoreBackup(backup, worldName)

    fun deleteWorldBackup(backup: CoreWorldBackup) = content.deleteBackup(backup)

    fun openContentCategory(category: DownloadsCategory) = content.openCategory(category)

    fun setContentSource(source: ContentSourceOption) = content.setSource(source)

    fun setContentTargetInstance(instanceId: String) = content.setTargetInstance(instanceId)

    fun selectContentWorld(world: String) = content.selectWorld(world)

    fun openContentProject(project: CoreContentProject) = content.openProject(project)

    fun closeContentProject() = content.closeProject()

    fun selectContentVersion(versionId: String) = content.selectVersion(versionId)

    fun setContentVersionChannel(channel: ContentVersionChannel) =
        content.setVersionChannel(channel)

    fun confirmContentDependencyPrompt(autoDownload: Boolean) =
        content.confirmDependencyPrompt(autoDownload)

    fun dismissContentDependencyPrompt() = content.dismissDependencyPrompt()

    fun installSelectedContent() = content.installSelected()

    // ------------------------------------------------------------------
    // Servers, diagnostics and modpacks (M9)
    // ------------------------------------------------------------------

    /** Opens the instance's log and crash analysis page. */
    fun openDiagnostics(instance: Instance) = diag.open(instance)

    fun closeDiagnostics() {
        _state.update {
            it.copy(
                libraryStage = if (it.libraryDetailId != null) {
                    LibraryStage.SETTINGS
                } else {
                    LibraryStage.LIST
                },
            )
        }
    }

    fun refreshDiagnostics() = diag.refresh()

    fun selectDiagTab(tab: DiagTab) = diag.selectTab(tab)

    fun exportDiagnostics() = diag.export()

    fun applyDiagFix(finding: com.fograin.launcher.platform.CoreFinding) =
        diag.applyFix(finding)

    fun copyDiagBundlePath() = diag.copyBundlePath()

    /** Crash analysis used by the session-exit alert; never throws. */
    suspend fun diagnoseCrash(instanceId: String) = diag.prefillCrash(instanceId)

    fun selectDatapackWorld(world: String) = content.selectDatapackWorld(world)

    fun refreshDatapacks() = content.refreshDatapacks()

    fun toggleDatapack(item: com.fograin.launcher.platform.CoreDatapack) =
        content.toggleDatapack(item)

    fun deleteDatapack(item: com.fograin.launcher.platform.CoreDatapack) =
        content.deleteDatapack(item)

    fun importDatapack(path: String) = content.importDatapack(path)

    fun importDatapackFile() = content.importFromFile()

    fun startModpackImport() = modpack.startImport()

    /** Import a file or MultiMC directory, e.g. from a desktop drag-and-drop. */
    fun importModpackPath(path: String) = modpack.importPath(path)

    fun setModpackDragActive(active: Boolean) = modpack.setDragActive(active)

    fun setModpackName(name: String) = modpack.setName(name)

    fun dismissModpackImport() = modpack.dismiss()

    fun confirmModpackImport() = modpack.confirmImport()

    /** Opens the two-step export page for [instance]. */
    fun beginModpackExport(instance: Instance) = modpack.beginExport(instance)

    fun setModpackExportName(name: String) = modpack.setExportName(name)

    fun setModpackExportAuthor(author: String) = modpack.setExportAuthor(author)

    fun setModpackExportVersion(version: String) = modpack.setExportVersion(version)

    fun setModpackExportDescription(description: String) =
        modpack.setExportDescription(description)

    fun toggleModpackExportSection(id: String) = modpack.toggleExportSection(id)

    fun nextModpackExportStep() = modpack.nextExportStep()

    fun backModpackExportStep() = modpack.backExportStep()

    fun cancelModpackExport() = modpack.cancelExport()

    fun confirmModpackExport() = modpack.export()

    fun dismissModpackExportResult() = modpack.dismissExportResult()

    fun copyModpackExportPath() = modpack.copyExportPath()

    // ------------------------------------------------------------------
    // Core event pump.
    // ------------------------------------------------------------------

    private fun startEventPump() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(250)
                val events = withContext(Dispatchers.IO) { core.nextEvents() }
                if (events.isEmpty()) continue
                events.forEach { raw -> CoreEvent.parse(raw)?.let(::handleEvent) }
                refreshDownloadsFromTasks()
            }
        }
    }

    internal fun handleEvent(event: CoreEvent) {
        when (event) {
            is CoreEvent.TaskFinished -> {
                downloads.onTaskFinished(event)
                java.onTaskFinished(event)
                content.onTaskFinished(event.taskId)
                modpack.onTaskFinished(event.taskId)
            }

            is CoreEvent.TaskProgress -> Unit
            else -> session.onEvent(event)
        }
    }

    /** Convenience for the settings screen's error banner. */
    fun errorMessage(error: Throwable): String = when (error) {
        is CoreException -> error.payload.userMessage
        else -> error.message ?: error::class.simpleName.orEmpty()
    }
}
