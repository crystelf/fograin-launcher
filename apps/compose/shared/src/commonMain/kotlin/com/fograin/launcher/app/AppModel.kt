package com.fograin.launcher.app

import com.fograin.launcher.model.ContentLoaderFilter
import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.platform.CoreAppSnapshot
import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.platform.CoreEvent
import com.fograin.launcher.platform.CoreException
import com.fograin.launcher.platform.CoreGameConfig
import com.fograin.launcher.platform.CoreInstanceRow
import com.fograin.launcher.platform.CoreInstanceSettings
import com.fograin.launcher.platform.CoreLoaderVersion
import com.fograin.launcher.platform.CoreTaskRow
import com.fograin.launcher.platform.DirectoryPick
import com.fograin.launcher.platform.PlatformServices
import com.fograin.launcher.platform.decode
import com.fograin.launcher.platform.objectRows
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.platform.resultRows
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
        _state = MutableStateFlow(
            AppUiState(
                settings = settings,
                theme = settings.theme,
                deviceMemoryBytes = platform.deviceInfo.totalMemoryBytes,
                deviceText = platform.deviceInfo.deviceText,
                socText = platform.deviceInfo.socText,
            ),
        )
        state = _state.asStateFlow()

        scope.launch { refreshCoreData() }
        startEventPump()

        core.initError?.let { showNotice(it, Notice.Tone.WARNING) }
    }

    /** Mutable state entry point for the feature controllers in this package. */
    internal fun setState(transform: (AppUiState) -> AppUiState) {
        _state.update(transform)
    }

    internal val username: String
        get() = state.value.gameConfig?.username ?: "Steve"

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

            refreshDownloadsFromTasks()
            _state.update { it.copy(isInitialLoading = false) }
        }
    }

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
        _state.update { it.copy(destination = destination) }
    }

    /**
     * True when [back] would change something: a dialog or sheet is open, or
     * the shell is not on the home destination. Drives Android's back gesture
     * and the desktop Escape key through the same [com.fograin.launcher.platform.BackDispatcher].
     */
    fun canGoBack(): Boolean {
        val current = state.value
        return current.alert != null ||
            current.previewInstanceId != null ||
            current.instanceSettingsId != null ||
            current.download.installSheetVersion != null ||
            current.destination != AppDestination.HOME
    }

    /** Dismisses the topmost overlay, then falls back to the home destination. */
    fun back() {
        val current = state.value
        when {
            current.alert != null -> dismissAlert()
            current.previewInstanceId != null -> closePreview()
            current.instanceSettingsId != null -> closeInstanceSettings()
            current.download.installSheetVersion != null -> closeInstallSheet()
            current.destination != AppDestination.HOME -> navigate(AppDestination.HOME)
        }
    }

    fun selectHomeInstance(instance: Instance) {
        _state.update { it.copy(currentInstanceId = instance.id) }
    }

    fun openInstance(instance: Instance) {
        _state.update {
            it.copy(
                currentInstanceId = instance.id,
                librarySelection = instance.id,
                destination = AppDestination.LIBRARY,
            )
        }
    }

    fun showPreview(instance: Instance) {
        _state.update { it.copy(previewInstanceId = instance.id, librarySelection = instance.id) }
    }

    fun closePreview() {
        _state.update { it.copy(previewInstanceId = null) }
    }

    fun manageInstance(instance: Instance) {
        _state.update {
            it.copy(
                currentInstanceId = instance.id,
                librarySelection = instance.id,
                previewInstanceId = null,
                instanceSettingsId = instance.id,
            )
        }
    }

    fun closeInstanceSettings() {
        _state.update { it.copy(instanceSettingsId = null) }
    }

    /** Menu/shortcut entry point: open the library page with the search field focused. */
    fun requestLibrarySearch() {
        _state.update {
            it.copy(
                destination = AppDestination.LIBRARY,
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

    fun setTheme(theme: ThemeMode) {
        if (theme == state.value.theme) return
        scope.launch {
            try {
                val snapshot = core.setTheme(theme)
                _state.update { it.copy(settings = snapshot, theme = snapshot.theme) }
                showNotice("外观已切换", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError("外观切换失败", error)
            }
        }
    }

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

    fun selectDownloadVersion(versionId: String) = downloads.selectVersion(versionId)

    fun refreshDownloadPlan() = downloads.requestPlanRefresh()

    fun openInstallSheet(version: String? = null) = downloads.openInstallSheet(version)

    fun closeInstallSheet() = downloads.closeInstallSheet()

    fun installVersion(version: String) = downloads.installVersion(version)

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

    fun startGame(instance: Instance) = session.start(instance)

    fun stopGame() = session.stop()

    suspend fun loadInstanceSettings(id: String): CoreInstanceSettings =
        instanceSettings.load(id)

    fun configureInstance(id: String, setting: String, value: JsonElement?) =
        instanceSettings.configure(id, setting, value)

    fun copyInstanceDirectoryPath(instance: Instance) =
        instanceSettings.copyDirectoryPath(instance)

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
            is CoreEvent.TaskFinished -> downloads.onTaskFinished(event)
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
