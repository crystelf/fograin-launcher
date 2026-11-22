package com.fograin.launcher.app

import com.fograin.launcher.model.ContentVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.model.TaskState
import com.fograin.launcher.platform.CoreAppSnapshot
import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.platform.CoreDownloadSettings
import com.fograin.launcher.platform.CoreEvent
import com.fograin.launcher.platform.CoreException
import com.fograin.launcher.platform.CoreGameConfig
import com.fograin.launcher.platform.CoreInstanceRow
import com.fograin.launcher.platform.CoreJavaList
import com.fograin.launcher.platform.CoreNetworkSettings
import com.fograin.launcher.platform.CoreTaskRow
import com.fograin.launcher.platform.CoreWorldBackup
import com.fograin.launcher.platform.PlatformServices
import com.fograin.launcher.platform.decode
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
import kotlinx.serialization.json.JsonObject

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

    val library = LibraryActions(this)
    val downloads = DownloadsActions(this)
    val launch = LaunchActions(this)
    val instanceSettings = InstanceSettingsActions(this)
    val java = JavaActions(this)
    val account = AccountActions(this)
    val content = ContentManagerActions(this)
    val contentBrowser = ContentBrowserActions(this)
    val diag = DiagActions(this)
    val modpack = ModpackActions(this)
    val settings = SettingsActions(this)

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
        val snapshot = core.settings
        val initialPage = InitialPage.fromWire(snapshot.initialPage)
        _state = MutableStateFlow(
            AppUiState(
                settings = snapshot,
                theme = snapshot.theme,
                language = AppLanguage.fromWire(snapshot.language),
                accent = AccentPreference.fromWire(snapshot.accent),
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
        scope.launch { settings.load() }
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
                            state = TaskState.fromWire(row.state),
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
     * One step the back gesture can take, in priority order.
     *
     * [canGoBack] and [back] both read this list, so a new overlay only has to
     * be added once instead of in two hand-kept `when` chains.
     */
    private enum class BackStep {
        ALERT,
        MODPACK_IMPORT,
        SETTINGS,
        ACCOUNT,
        QUEUE,
        CONTENT_PROJECT,
        SEARCH,
        LIBRARY_DIAGNOSTICS,
        LIBRARY_EXPORT,
        LIBRARY_SETTINGS,
        LIBRARY_DETAIL,
        LOADER_VERSIONS,
        INSTALL_PAGE,
        LEAVE_DESTINATION,
    }

    private fun nextBackStep(): BackStep? {
        val current = state.value
        val library = current.destination == AppDestination.LIBRARY
        val downloads = current.destination == AppDestination.DOWNLOADS
        return when {
            current.alert != null -> BackStep.ALERT
            current.modpack.pack != null -> BackStep.MODPACK_IMPORT
            current.settingsWindow.open -> BackStep.SETTINGS
            current.account.open -> BackStep.ACCOUNT
            current.downloadQueueOpen -> BackStep.QUEUE
            current.contentBrowser.selectedProject != null -> BackStep.CONTENT_PROJECT
            current.globalSearchOpen -> BackStep.SEARCH
            library && current.libraryStage == LibraryStage.DIAGNOSTICS -> BackStep.LIBRARY_DIAGNOSTICS
            library && current.libraryStage == LibraryStage.EXPORT -> BackStep.LIBRARY_EXPORT
            library && current.libraryStage == LibraryStage.SETTINGS -> BackStep.LIBRARY_SETTINGS
            library && current.libraryStage == LibraryStage.DETAIL -> BackStep.LIBRARY_DETAIL
            downloads && current.download.stage == DownloadStage.LOADER_VERSIONS -> BackStep.LOADER_VERSIONS
            downloads && current.download.stage == DownloadStage.INSTALL -> BackStep.INSTALL_PAGE
            current.destination != AppDestination.HOME -> BackStep.LEAVE_DESTINATION
            else -> null
        }
    }

    /**
     * True when [back] would change something: a dialog or sheet is open, an
     * in-destination sub-page is showing, or the shell is not on the home
     * destination. Drives Android's back gesture and the desktop Escape key
     * through the same [com.fograin.launcher.platform.BackDispatcher].
     */
    fun canGoBack(): Boolean = nextBackStep() != null

    /** Dismisses the topmost overlay or sub-page, then leaves the destination. */
    fun back() {
        when (nextBackStep()) {
            BackStep.ALERT -> dismissAlert()
            BackStep.MODPACK_IMPORT -> modpack.dismiss()
            BackStep.SETTINGS -> settings.back()
            BackStep.ACCOUNT -> account.back()
            BackStep.QUEUE -> downloads.closeQueue()
            BackStep.CONTENT_PROJECT -> contentBrowser.closeProject()
            BackStep.SEARCH -> closeGlobalSearch()
            BackStep.LIBRARY_DIAGNOSTICS -> closeDiagnostics()
            BackStep.LIBRARY_EXPORT -> modpack.backExportStep()
            BackStep.LIBRARY_SETTINGS -> closeInstanceSettings()
            BackStep.LIBRARY_DETAIL -> closeInstanceDetail()
            BackStep.LOADER_VERSIONS -> downloads.closeLoaderVersionPicker()
            BackStep.INSTALL_PAGE -> downloads.closeInstallPage()
            BackStep.LEAVE_DESTINATION -> navigate(AppDestination.HOME)
            null -> Unit
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
    // Settings destination. App animates between the shell and the
    // full-window settings surface on every platform.
    // ------------------------------------------------------------------

    fun openSettings(section: SettingsSection? = null) {
        settings.refreshDataRootOverride()
        settings.open(section)
    }

    fun setGlobalAutoCompleteResources(enabled: Boolean) =
        settings.setAutoCompleteResources(enabled)

    fun setGlobalResolution(width: Int?, height: Int?) =
        settings.setResolution(width, height)

    fun updateDownloadSettings(transform: (CoreDownloadSettings) -> CoreDownloadSettings) =
        settings.updateDownload(transform)

    fun updateNetworkSettings(transform: (CoreNetworkSettings) -> CoreNetworkSettings) =
        settings.updateNetwork(transform)

    /** Flips light/dark, the pair users actually toggle; SYSTEM stays untouched. */
    fun toggleTheme() {
        val next = if (state.value.theme == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        settings.setTheme(next)
    }

    // ------------------------------------------------------------------
    // Feature facade. Each method forwards to its controller so the UI keeps
    // a single `model.` surface.
    // ------------------------------------------------------------------

    fun selectInstallLoaderVersion(version: String) =
        downloads.selectInstallLoaderVersion(version)

    /** Real loader builds for a Minecraft version, newest first. */

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    /** Selected account id for `launch.start`, or null before the first load. */
    val selectedAccountId: String?
        get() = state.value.account.selectedId ?: state.value.account.selected?.id

    // ------------------------------------------------------------------
    // Java runtime management
    // ------------------------------------------------------------------

    /** Menu/shortcut/search entry point: settings page on the Java section. */
    fun openJavaSettings() {
        settings.open(SettingsSection.JAVA)
        java.refresh()
    }

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
            contentBrowser.openCategory(category)
        }
    }

    fun restoreWorldBackup(backup: CoreWorldBackup, worldName: String) =
        content.restoreBackup(backup, worldName)

    fun setContentVersionChannel(channel: ContentVersionChannel) =
        contentBrowser.setVersionChannel(channel)

    fun confirmContentDependencyPrompt(autoDownload: Boolean) =
        contentBrowser.confirmDependencyPrompt(autoDownload)

    // ------------------------------------------------------------------
    // Servers, diagnostics and modpacks
    // ------------------------------------------------------------------

    /** Opens the instance's log and crash analysis page. */

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

    fun applyDiagFix(finding: com.fograin.launcher.platform.CoreFinding) =
        diag.applyFix(finding)

    /** Crash analysis used by the session-exit alert; never throws. */
    fun toggleDatapack(item: com.fograin.launcher.platform.CoreDatapack) =
        content.toggleDatapack(item)

    fun deleteDatapack(item: com.fograin.launcher.platform.CoreDatapack) =
        content.deleteDatapack(item)

    /** Import a file or MultiMC directory, e.g. from a desktop drag-and-drop. */

    /** Opens the two-step export page for [instance]. */

    fun setModpackExportDescription(description: String) =
        modpack.setExportDescription(description)

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
                contentBrowser.onTaskFinished(event.taskId)
                modpack.onTaskFinished(event.taskId)
            }

            is CoreEvent.TaskProgress -> Unit
            else -> launch.onEvent(event)
        }
    }

    /** Convenience for the settings screen's error banner. */
    fun errorMessage(error: Throwable): String = when (error) {
        is CoreException -> error.payload.userMessage
        else -> error.message ?: error::class.simpleName.orEmpty()
    }
}
