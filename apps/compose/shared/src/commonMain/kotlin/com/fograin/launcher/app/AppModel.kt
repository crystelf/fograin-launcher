package com.fograin.launcher.app

import com.fograin.launcher.model.ContentLoaderFilter
import com.fograin.launcher.model.DownloadVersionChannel
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
import com.fograin.launcher.platform.CorePlanPreview
import com.fograin.launcher.platform.CoreTaskRow
import com.fograin.launcher.platform.CoreVersionEntry
import com.fograin.launcher.platform.PlatformServices
import com.fograin.launcher.platform.booleanOrNull
import com.fograin.launcher.platform.decode
import com.fograin.launcher.platform.objectRows
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.platform.resultRows
import com.fograin.launcher.platform.stringOrNull
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Launcher state shared by Android and desktop.
 *
 * The Rust core is the source of truth: every instance, task and session value
 * comes from RPC responses or the event queue. The model owns the event pump
 * (250 ms), the install/launch flows and the
 * transient notice/alert channels; Compose only renders [state].
 */
class AppModel internal constructor(
    private val core: CoreClient,
    private val platform: PlatformServices,
    private val scope: CoroutineScope,
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

    private val refreshMutex = Mutex()

    private val _state: MutableStateFlow<AppUiState>
    val state: StateFlow<AppUiState>

    private var idGenerator = 0L
    private var pendingDownloadTaskId: String? = null
    private var pendingDownloadInstanceId: String? = null
    private var stopRequested = false
    private var noticeJob: Job? = null
    private var pollJob: Job? = null
    private var downloadVersionsGeneration = 0
    private var loadingDownloadChannel: DownloadVersionChannel? = null

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

    private val username: String
        get() = state.value.gameConfig?.username ?: "Steve"

    private fun nextId(): Long {
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

    fun navigate(destination: AppDestination) {
        _state.update { it.copy(destination = destination) }
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
                showError("无法保存外观设置", error)
            }
        }
    }

    /** Loads the manifest for the selected channel after the page appeared. */
    suspend fun loadDownloadVersions() {
        val channel = state.value.download.channel
        if (state.value.download.versionsChannel == channel) {
            refreshDownloadPlan()
            return
        }
        fetchDownloadVersions()
    }

    /** Called when the channel dropdown changes; clears stale rows first. */
    suspend fun reloadDownloadVersions() {
        _state.update {
            it.copy(
                download = it.download.copy(
                    versions = emptyList(),
                    versionsChannel = null,
                    selectedVersionId = null,
                    plan = null,
                    planError = null,
                    versionsError = null,
                    query = "",
                ),
            )
        }
        fetchDownloadVersions()
    }

    private suspend fun fetchDownloadVersions() {
        val channel = state.value.download.channel
        if (loadingDownloadChannel == channel) return
        loadingDownloadChannel = channel
        downloadVersionsGeneration += 1
        val generation = downloadVersionsGeneration
        _state.update {
            it.copy(download = it.download.copy(isLoadingVersions = true, versionsError = null))
        }

        try {
            val payload = core.rpc(
                "versions.list",
                buildJsonObject {
                    put("kind", channel.kindValue)
                    put("limit", 500)
                    put("refresh", false)
                },
            )
            if (generation != downloadVersionsGeneration ||
                channel != state.value.download.channel
            ) {
                return
            }

            val versions = payload.objectRows("versions")
                .mapNotNull { row -> runCatching { row.decode<CoreVersionEntry>() }.getOrNull() }
            _state.update {
                it.copy(
                    download = it.download.copy(
                        versions = versions,
                        versionsChannel = channel,
                        selectedVersionId = versions.firstOrNull()?.id,
                        plan = null,
                        planError = null,
                        isLoadingVersions = false,
                    ),
                )
            }
            if (versions.isNotEmpty()) refreshDownloadPlan()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (generation != downloadVersionsGeneration ||
                channel != state.value.download.channel
            ) {
                return
            }
            _state.update {
                it.copy(
                    download = it.download.copy(
                        versionsError = errorMessage(error),
                        isLoadingVersions = false,
                    ),
                )
            }
            showError("无法读取版本清单", error)
        } finally {
            if (generation == downloadVersionsGeneration) {
                loadingDownloadChannel = null
            }
        }
    }

    fun setDownloadCategory(category: DownloadsCategory) {
        _state.update { it.copy(download = it.download.copy(category = category)) }
        if (category == DownloadsCategory.GAME) {
            scope.launch { loadDownloadVersions() }
        }
    }

    fun setDownloadChannel(channel: DownloadVersionChannel) {
        if (channel == state.value.download.channel) return
        _state.update { it.copy(download = it.download.copy(channel = channel)) }
        scope.launch { reloadDownloadVersions() }
    }

    fun setDownloadContentLoader(loader: ContentLoaderFilter) {
        _state.update { it.copy(download = it.download.copy(contentLoader = loader.name)) }
    }

    fun setDownloadQuery(query: String) {
        _state.update { it.copy(download = it.download.copy(query = query)) }
    }

    fun selectDownloadVersion(versionId: String) {
        _state.update {
            it.copy(
                download = it.download.copy(
                    selectedVersionId = versionId,
                    plan = null,
                    planError = null,
                ),
            )
        }
        scope.launch { refreshDownloadPlan() }
    }

    fun refreshDownloadPlan() {
        scope.launch { refreshDownloadPlanNow() }
    }

    private suspend fun refreshDownloadPlanNow() {
        val version = state.value.download.selectedVersionId ?: run {
            _state.update { it.copy(download = it.download.copy(plan = null, planError = null)) }
            return
        }
        _state.update { it.copy(download = it.download.copy(planError = null)) }
        try {
            val payload = core.rpc(
                "version.plan",
                buildJsonObject { put("version", version) },
            )
            if (state.value.download.selectedVersionId != version) return
            val plan = payload.decode<CorePlanPreview>()
            _state.update { it.copy(download = it.download.copy(plan = plan)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (state.value.download.selectedVersionId != version) return
            _state.update {
                it.copy(download = it.download.copy(plan = null, planError = errorMessage(error)))
            }
        }
    }

    fun openInstallSheet(version: String? = null) {
        if (!version.isNullOrEmpty()) {
            _state.update { it.copy(download = it.download.copy(selectedVersionId = version)) }
        }
        _state.update {
            it.copy(download = it.download.copy(installSheetVersion = version.orEmpty()))
        }
    }

    fun closeInstallSheet() {
        _state.update { it.copy(download = it.download.copy(installSheetVersion = null)) }
    }

    /** 新建实例 / 添加实例 routes to the download page (HMCL/PCL behaviour). */
    fun openAddInstance() {
        _state.update {
            it.copy(
                download = it.download.copy(category = DownloadsCategory.GAME),
                destination = AppDestination.DOWNLOADS,
            )
        }
        scope.launch { loadDownloadVersions() }
    }

    /**
     * Safe directory id for a display name. CJK names keep their display name
     * and fall back to a generic id; collisions get a numeric suffix so the
     * download page never fails just because an instance already exists.
     */
    fun uniqueInstanceID(name: String, version: String): String {
        val sanitized = InstanceFormat.sanitizedInstanceID(name)
        val base = sanitized.ifEmpty {
            InstanceFormat.sanitizedInstanceID(version).ifEmpty { "instance" }
        }
        if (state.value.instances.none { it.id == base }) return base
        var suffix = 2
        while (state.value.instances.any { it.id == "$base-$suffix" }) {
            suffix += 1
        }
        return "$base-$suffix"
    }

    /**
     * Starts the download after the install sheet confirms loader, version and
     * name. After `task.finished` the launcher jumps to 游戏设置 (PCL flow).
     */
    suspend fun startGameInstall(version: String, instanceName: String) {
        if (state.value.isInitialLoading) refreshCoreData()
        if (state.value.download.isGameDownloadRunning) {
            throw CoreException(
                "conflict",
                "error.task.already_running",
                "已有下载任务在进行，请等待完成或取消。",
            )
        }
        val name = instanceName.trim()
        val id = uniqueInstanceID(name, version)
        val response = core.rpc(
            "install.start",
            buildJsonObject {
                put("version", version)
                put("instanceId", id)
                put("instanceName", name.ifEmpty { id })
            },
        )
        pendingDownloadTaskId = response.stringOrNull("taskId")
        pendingDownloadInstanceId = id
        _state.update {
            it.copy(
                download = it.download.copy(
                    isGameDownloadRunning = true,
                    category = DownloadsCategory.GAME,
                    installSheetVersion = null,
                ),
                destination = AppDestination.DOWNLOADS,
            )
        }
        showNotice("已开始安装 $version", Notice.Tone.INFO)
        refreshCoreData()
    }

    private suspend fun finishGameDownload() {
        _state.update {
            it.copy(download = it.download.copy(isGameDownloadRunning = false))
        }
        pendingDownloadTaskId = null
        val instanceId = pendingDownloadInstanceId ?: return
        pendingDownloadInstanceId = null
        refreshCoreData()
        if (state.value.instances.none { it.id == instanceId }) return
        _state.update {
            it.copy(
                currentInstanceId = instanceId,
                librarySelection = instanceId,
                destination = AppDestination.LIBRARY,
                instanceSettingsId = instanceId,
            )
        }
    }

    fun installVersion(version: String) {
        callCore(
            "install.start",
            buildJsonObject { put("version", version) },
            notice = "已开始安装 $version",
        )
    }

    fun startGame(instance: Instance) {
        _state.update { it.copy(currentInstanceId = instance.id) }
        if (!instance.isInstalled) {
            if (isDownloading(instance)) {
                showNotice("“${instance.name}”的资源仍在下载，请稍候再启动")
            } else {
                completeResources(instance)
            }
            return
        }
        if (state.value.sessionRunning) {
            showError("无法启动游戏", "已经有一个游戏在运行了。")
            return
        }
        if (state.value.isSessionLoading) return
        launchGame(instance)
    }

    private fun launchGame(instance: Instance) {
        _state.update { it.copy(isSessionLoading = true) }
        scope.launch {
            try {
                core.rpc(
                    "launch.start",
                    buildJsonObject {
                        put("instanceId", instance.id)
                        put("username", username)
                    },
                )
                // Keep the spinner until `game.started` arrives.
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(isSessionLoading = false) }
                showError("无法启动“${instance.name}”", error)
            }
        }
    }

    fun stopGame() {
        if (!state.value.sessionRunning) {
            showNotice("没有正在运行的游戏")
            return
        }
        stopRequested = true
        scope.launch {
            try {
                val payload = core.rpc("session.stop")
                if (payload.booleanOrNull("stopped") != true) {
                    stopRequested = false
                    showNotice("没有正在运行的游戏")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stopRequested = false
                showError("无法停止游戏", error)
            }
        }
    }

    /** PCL-style: pressing play on an incomplete instance starts its download. */
    fun completeResources(instance: Instance) {
        if (state.value.download.isGameDownloadRunning) {
            showNotice("已有下载任务在进行")
            return
        }
        scope.launch {
            try {
                val response = core.rpc(
                    "install.start",
                    buildJsonObject {
                        put("version", instance.minecraftVersion)
                        put("instanceId", instance.id)
                        put("instanceName", instance.name)
                    },
                )
                pendingDownloadInstanceId = instance.id
                pendingDownloadTaskId = response.stringOrNull("taskId")
                _state.update {
                    it.copy(
                        download = it.download.copy(isGameDownloadRunning = true),
                        destination = AppDestination.DOWNLOADS,
                    )
                }
                showNotice("正在补全“${instance.name}”的游戏资源")
                refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError("无法补全“${instance.name}”的资源", error)
            }
        }
    }

    fun isDownloading(instance: Instance): Boolean =
        pendingDownloadInstanceId == instance.id || state.value.isDownloading(instance)

    fun toggleFavorite(instance: Instance) {
        callCore(
            "instances.favorite",
            buildJsonObject {
                put("id", instance.id)
                put("favorite", !instance.isFavorite)
            },
        )
    }

    fun deleteInstance(instance: Instance) {
        scope.launch {
            try {
                core.rpc("instances.delete", buildJsonObject { put("id", instance.id) })
                _state.update {
                    it.copy(
                        instanceSettingsId = null,
                        previewInstanceId = null,
                        librarySelection = null,
                    )
                }
                showNotice("已删除“${instance.name}”", Notice.Tone.SUCCESS)
                refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError("无法删除“${instance.name}”", error)
            }
        }
    }

    /** Registers an external `.minecraft` directory without moving files. */
    fun importMinecraftDirectory(directory: String) {
        scope.launch {
            try {
                core.rpc(
                    "instances.import",
                    buildJsonObject { put("directory", directory) },
                )
                val name = directory.trimEnd('/').substringAfterLast('/').ifEmpty { directory }
                showNotice("已导入“$name”", Notice.Tone.SUCCESS)
                refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError("导入 .minecraft 目录失败", error)
            }
        }
    }

    /** Persists one instance setting; `value == null` inherits `[game]`. */
    /** Reads the effective values, overrides and globals for one instance. */
    suspend fun loadInstanceSettings(id: String): CoreInstanceSettings =
        core.rpc(
            "instances.settings.get",
            buildJsonObject { put("id", id) },
        ).decode()

    fun configureInstance(id: String, setting: String, value: JsonElement?) {
        val update = buildJsonObject {
            put("setting", setting)
            put("value", value ?: JsonNull)
        }
        val params = buildJsonObject {
            put("id", id)
            put("updates", buildJsonArray { add(update) })
        }
        scope.launch {
            try {
                core.rpc("instances.settings.set", params)
                refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError("设置保存失败", error)
            }
        }
    }

    /**
     * Android has no Finder equivalent for app-private storage, so the folder
     * action copies the instance directory path instead of opening a file
     * manager. The data root is the app's external files directory and is
     * reachable over USB.
     */
    fun copyInstanceDirectoryPath(instance: Instance) {
        scope.launch {
            try {
                val payload = core.rpc(
                    "instances.dir",
                    buildJsonObject { put("id", instance.id) },
                )
                val directory = payload.stringOrNull("gameDir")
                    ?: "${state.value.settings.dataRoot}/instances/${instance.id}"
                platform.clipboard.copy(directory)
                showNotice("实例目录路径已复制")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError("无法复制实例目录路径", error)
            }
        }
    }

    private fun startEventPump() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(250)
                val events = withContext(Dispatchers.IO) { core.nextEvents() }
                if (events.isEmpty()) continue
                events.forEach { raw -> CoreEvent.parse(raw)?.let(::handle) }
                refreshDownloadsFromTasks()
            }
        }
    }

    private fun handle(event: CoreEvent) {
        when (event) {
            is CoreEvent.TaskProgress -> Unit

            is CoreEvent.TaskFinished -> handleTaskFinished(event)

            is CoreEvent.GameStarted -> {
                stopRequested = false
                _state.update {
                    it.copy(
                        sessionRunning = true,
                        isSessionLoading = false,
                    )
                }
                showNotice("游戏已启动，进程 ${event.pid ?: 0}", Notice.Tone.SUCCESS)
            }

            is CoreEvent.GameWindowReady -> Unit

            // Log lines are consumed by the core's recent-log buffer; the log
            // viewer itself is M9.
            is CoreEvent.GameLine -> Unit

            is CoreEvent.GameExited -> {
                val wasStopped = stopRequested
                stopRequested = false
                _state.update {
                    it.copy(
                        sessionRunning = false,
                        isSessionLoading = false,
                    )
                }
                if (wasStopped) {
                    showNotice("游戏已停止")
                } else if (event.exitCode == 0) {
                    val minutes = (event.playSeconds / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    showNotice(
                        "游戏已退出（${InstanceFormat.playTime(minutes)}）",
                        Notice.Tone.SUCCESS,
                    )
                } else {
                    showError(
                        "游戏异常退出",
                        "游戏进程以代码 ${event.exitCode ?: "signal"} 结束。",
                        "请查看实例目录 logs/ 下的最新日志。",
                    )
                }
                scope.launch { refreshCoreData() }
            }
        }
    }

    private fun handleTaskFinished(event: CoreEvent.TaskFinished) {
        val title = InstanceFormat.taskTitle(event.name)
        val isOurDownload = event.taskId == pendingDownloadTaskId
        when (event.state) {
            "succeeded" -> {
                showNotice("$title 完成", Notice.Tone.SUCCESS)
                scope.launch {
                    if (isOurDownload) finishGameDownload() else refreshCoreData()
                }
            }

            "failed" -> {
                val message = event.detail.firstOrNull() ?: "未知原因"
                showError(
                    "$title 失败",
                    message,
                    event.detail.drop(1).joinToString("\n").ifBlank { null },
                )
                if (isOurDownload) clearPendingDownload()
                scope.launch { refreshCoreData() }
            }

            "cancelled" -> {
                showNotice("$title 已取消")
                if (isOurDownload) clearPendingDownload()
                scope.launch { refreshCoreData() }
            }

            else -> Unit
        }
    }

    private fun clearPendingDownload() {
        _state.update { it.copy(download = it.download.copy(isGameDownloadRunning = false)) }
        pendingDownloadTaskId = null
        pendingDownloadInstanceId = null
    }

    /** Convenience for the settings screen's error banner. */
    fun errorMessage(error: Throwable): String = when (error) {
        is CoreException -> error.payload.userMessage
        else -> error.message ?: error::class.simpleName.orEmpty()
    }
}
