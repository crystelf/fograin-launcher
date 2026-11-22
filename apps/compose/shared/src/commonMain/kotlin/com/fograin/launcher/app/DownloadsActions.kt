package com.fograin.launcher.app

import com.fograin.launcher.model.ContentLoaderFilter
import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.TaskState
import com.fograin.launcher.platform.CoreEvent
import com.fograin.launcher.platform.CoreException
import com.fograin.launcher.platform.CoreLoaderVersion
import com.fograin.launcher.platform.CorePlanPreview
import com.fograin.launcher.platform.CoreVersionEntry
import com.fograin.launcher.platform.decode
import com.fograin.launcher.platform.objectRows
import com.fograin.launcher.platform.stringOrNull
import com.fograin.launcher.ui.AppDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Download page state machine: manifest loading, the pinned install page, the
 * loader version picker and the task that `install.start` spawns.
 *
 * The old flow picked a version in the list, previewed it in a side card and
 * then opened a second sheet for loader/name. The new flow is a small stack of
 * pages, so the loader and instance name live next to the version details:
 *
 * ```
 * BROWSER ──pick version──▶ INSTALL ──pick loader version──▶ LOADER_VERSIONS
 *    ▲                         │                                   │
 *    └──────── back ───────────┘◀──────────── select ──────────────┘
 * ```
 *
 * Owns the "our download" bookkeeping ([pendingDownloadTaskId] /
 * [pendingDownloadInstanceId]) so the shell can jump to the new instance when
 * the task finishes — the one piece of cross-screen flow in the launcher.
 * State is written through [AppModel.setState]; nothing here renders.
 */
class DownloadsActions(private val model: AppModel) {

    private var pendingDownloadTaskId: String? = null
    private var pendingDownloadInstanceId: String? = null
    private var downloadVersionsGeneration = 0
    private var loaderVersionsGeneration = 0
    private var planGeneration = 0
    private var loadingDownloadChannel: DownloadVersionChannel? = null

    /** Loads the manifest for the selected channel after the page appeared. */
    suspend fun loadVersions() {
        val channel = model.state.value.download.channel
        if (model.state.value.download.versionsChannel == channel) return
        fetchVersions()
    }

    /** Called when the channel dropdown changes; clears stale rows first. */
    suspend fun reloadVersions() {
        model.setState {
            it.copy(
                download = it.download
                    .copy(
                        versions = emptyList(),
                        versionsChannel = null,
                        versionsError = null,
                        query = "",
                    )
                    .clearInstallFlow(),
            )
        }
        fetchVersions()
    }

    private suspend fun fetchVersions() {
        val channel = model.state.value.download.channel
        if (loadingDownloadChannel == channel) return
        loadingDownloadChannel = channel
        downloadVersionsGeneration += 1
        val generation = downloadVersionsGeneration
        model.setState {
            it.copy(download = it.download.copy(isLoadingVersions = true, versionsError = null))
        }

        try {
            val payload = model.core.rpc(
                "versions.list",
                buildJsonObject {
                    put("kind", channel.kindValue)
                    put("limit", 500)
                    put("refresh", false)
                },
            )
            if (generation != downloadVersionsGeneration ||
                channel != model.state.value.download.channel
            ) {
                return
            }

            val versions = payload.objectRows("versions")
                .mapNotNull { row -> runCatching { row.decode<CoreVersionEntry>() }.getOrNull() }
            model.setState {
                it.copy(
                    download = it.download.copy(
                        versions = versions,
                        versionsChannel = channel,
                        isLoadingVersions = false,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (generation != downloadVersionsGeneration ||
                channel != model.state.value.download.channel
            ) {
                return
            }
            model.setState {
                it.copy(
                    download = it.download.copy(
                        versionsError = model.errorMessage(error),
                        isLoadingVersions = false,
                    ),
                )
            }
            model.showError("无法读取版本清单", error)
        } finally {
            if (generation == downloadVersionsGeneration) {
                loadingDownloadChannel = null
            }
        }
    }

    fun setCategory(category: DownloadsCategory) {
        if (category == model.state.value.download.category) return
        model.setState {
            val download = it.download.copy(category = category, query = "")
            it.copy(
                download = if (category == DownloadsCategory.GAME) {
                    download
                } else {
                    download.clearInstallFlow()
                },
            )
        }
        when {
            category == DownloadsCategory.GAME -> model.scope.launch { loadVersions() }
            category.isContent -> model.contentBrowser.openCategory(category)
        }
    }

    fun setChannel(channel: DownloadVersionChannel) {
        if (channel == model.state.value.download.channel) return
        model.setState {
            it.copy(
                download = it.download.copy(channel = channel).clearInstallFlow(),
            )
        }
        model.scope.launch { reloadVersions() }
    }

    fun setContentLoader(loader: ContentLoaderFilter) {
        if (loader.name == model.state.value.download.contentLoader) return
        model.setState { it.copy(download = it.download.copy(contentLoader = loader.name)) }
        if (model.state.value.download.category == DownloadsCategory.MODPACK) {
            model.contentBrowser.search()
        }
    }

    fun setQuery(query: String) {
        model.setState { it.copy(download = it.download.copy(query = query)) }
        if (model.state.value.download.category.isContent) {
            model.contentBrowser.scheduleSearch()
        }
    }

    // ------------------------------------------------------------------
    // Install page: pinned version, loader family, loader version, name.
    // ------------------------------------------------------------------

    /** Opens the install page for [versionId]; the page can never change it. */
    fun openInstallPage(versionId: String) {
        if (versionId.isBlank()) return
        planGeneration += 1
        loaderVersionsGeneration += 1
        model.setState {
            it.copy(
                download = it.download.copy(
                    stage = DownloadStage.INSTALL,
                    installVersionId = versionId,
                    installLoader = GameLoaderKind.VANILLA,
                    installLoaderVersion = "",
                    installInstanceName = "",
                    loaderVersions = emptyList(),
                    isLoadingLoaderVersions = false,
                    loaderVersionsError = null,
                    isInstallRequesting = false,
                    installError = null,
                    plan = null,
                    planError = null,
                ),
            )
        }
        // Populate the version entry (deep links from home) and the plan in
        // parallel; the plan is what the page shows as soon as possible.
        model.scope.launch { loadVersions() }
        model.scope.launch { refreshPlan() }
    }

    /** Back from the install page to the version list. */
    fun closeInstallPage() {
        planGeneration += 1
        loaderVersionsGeneration += 1
        model.setState { it.copy(download = it.download.clearInstallFlow()) }
    }

    fun setInstallLoader(loader: GameLoaderKind) {
        if (loader == model.state.value.download.installLoader) return
        planGeneration += 1
        loaderVersionsGeneration += 1
        model.setState {
            it.copy(
                download = it.download.copy(
                    installLoader = loader,
                    installLoaderVersion = "",
                    loaderVersions = emptyList(),
                    isLoadingLoaderVersions = loader != GameLoaderKind.VANILLA,
                    loaderVersionsError = null,
                    installError = null,
                    plan = null,
                    planError = null,
                ),
            )
        }
        when (loader) {
            GameLoaderKind.VANILLA -> model.scope.launch { refreshPlan() }
            else -> model.scope.launch {
                loadLoaderVersions(loader)
                refreshPlan()
            }
        }
    }

    /** Opens the loader-version page; the install page stays behind it. */
    fun openLoaderVersionPicker() {
        val download = model.state.value.download
        if (download.installVersionId == null || download.installLoader == GameLoaderKind.VANILLA) {
            return
        }
        model.setState {
            it.copy(download = it.download.copy(stage = DownloadStage.LOADER_VERSIONS))
        }
        if (download.loaderVersions.isEmpty() &&
            !download.isLoadingLoaderVersions &&
            download.loaderVersionsError == null
        ) {
            model.scope.launch { loadLoaderVersions(download.installLoader) }
        }
    }

    fun closeLoaderVersionPicker() {
        if (model.state.value.download.stage != DownloadStage.LOADER_VERSIONS) return
        model.setState { it.copy(download = it.download.copy(stage = DownloadStage.INSTALL)) }
    }

    fun selectInstallLoaderVersion(version: String) {
        val download = model.state.value.download
        if (download.installLoader == GameLoaderKind.VANILLA || version.isBlank()) return
        planGeneration += 1
        model.setState {
            it.copy(
                download = it.download.copy(
                    stage = DownloadStage.INSTALL,
                    installLoaderVersion = version,
                    installError = null,
                    plan = null,
                    planError = null,
                ),
            )
        }
        model.scope.launch { refreshPlan() }
    }

    fun retryLoaderVersions() {
        val download = model.state.value.download
        if (download.installLoader == GameLoaderKind.VANILLA) return
        model.scope.launch {
            loadLoaderVersions(download.installLoader)
            refreshPlan()
        }
    }

    fun setInstallInstanceName(name: String) {
        model.setState {
            it.copy(download = it.download.copy(installInstanceName = name, installError = null))
        }
    }

    fun refreshInstallPlan() {
        model.scope.launch { refreshPlan() }
    }

    /**
     * Real loader builds for a Minecraft version, newest first.
     *
     * Vanilla has no loader versions, so the page shows nothing for it.
     */
    suspend fun versionsFor(loader: GameLoaderKind, minecraft: String): List<CoreLoaderVersion> {
        if (loader == GameLoaderKind.VANILLA || minecraft.isBlank()) return emptyList()
        val payload = model.core.rpc(
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

    private suspend fun loadLoaderVersions(kind: GameLoaderKind) {
        val version = model.state.value.download.installVersionId ?: return
        loaderVersionsGeneration += 1
        val generation = loaderVersionsGeneration
        model.setState {
            it.copy(
                download = it.download.copy(
                    isLoadingLoaderVersions = true,
                    loaderVersionsError = null,
                ),
            )
        }
        try {
            val versions = versionsFor(kind, version)
            if (generation != loaderVersionsGeneration) return
            val current = model.state.value.download
            if (current.installLoader != kind || current.installVersionId != version) return
            val selected = current.installLoaderVersion
                .takeIf { value -> value.isNotEmpty() && versions.any { it.version == value } }
                ?: versions.firstOrNull { it.stable }?.version
                ?: versions.firstOrNull()?.version
                ?: ""
            val error = if (versions.isEmpty()) {
                "${kind.title} 没有适配 Minecraft $version 的版本。"
            } else {
                null
            }
            model.setState {
                it.copy(
                    download = it.download.copy(
                        loaderVersions = versions,
                        installLoaderVersion = selected,
                        isLoadingLoaderVersions = false,
                        loaderVersionsError = error,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (generation != loaderVersionsGeneration) return
            model.setState {
                it.copy(
                    download = it.download.copy(
                        isLoadingLoaderVersions = false,
                        loaderVersionsError = model.errorMessage(error),
                    ),
                )
            }
        }
    }

    /** Recomputes Java/files/size for the pinned version and loader. */
    private suspend fun refreshPlan() {
        val download = model.state.value.download
        val version = download.installVersionId ?: run {
            model.setState { it.copy(download = it.download.copy(plan = null, planError = null)) }
            return
        }
        val loader = download.installLoader
        val loaderVersion = download.installLoaderVersion
        val withLoader = loader != GameLoaderKind.VANILLA && loaderVersion.isNotEmpty()

        planGeneration += 1
        val generation = planGeneration
        model.setState { it.copy(download = it.download.copy(planError = null)) }
        try {
            val payload = model.core.rpc(
                "version.plan",
                buildJsonObject {
                    put("version", version)
                    if (withLoader) {
                        put("loaderKind", loader.wireName)
                        put("loaderVersion", loaderVersion)
                    }
                },
            )
            if (generation != planGeneration) return
            val current = model.state.value.download
            if (current.installVersionId != version ||
                current.installLoader != loader ||
                current.installLoaderVersion != loaderVersion
            ) {
                return
            }
            val plan = payload.decode<CorePlanPreview>()
            model.setState { it.copy(download = it.download.copy(plan = plan)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (generation != planGeneration) return
            val current = model.state.value.download
            if (current.installVersionId != version ||
                current.installLoader != loader ||
                current.installLoaderVersion != loaderVersion
            ) {
                return
            }
            model.setState {
                it.copy(download = it.download.copy(plan = null, planError = model.errorMessage(error)))
            }
        }
    }

    /** Download button on the install page. */
    fun submitInstall() {
        val download = model.state.value.download
        val version = download.installVersionId ?: return
        if (download.isInstallRequesting || download.isGameDownloadRunning) return
        if (download.installLoader != GameLoaderKind.VANILLA &&
            download.installLoaderVersion.isEmpty()
        ) {
            model.setState {
                it.copy(download = it.download.copy(installError = "请先选择加载器版本"))
            }
            return
        }
        model.setState {
            it.copy(download = it.download.copy(isInstallRequesting = true, installError = null))
        }
        model.scope.launch {
            try {
                startGameInstall(
                    version = version,
                    instanceName = download.installInstanceName,
                    loader = download.installLoader,
                    loaderVersion = download.installLoaderVersion,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState {
                    it.copy(download = it.download.copy(installError = model.errorMessage(error)))
                }
            } finally {
                model.setState {
                    it.copy(download = it.download.copy(isInstallRequesting = false))
                }
            }
        }
    }

    /**
     * Starts the download after the install page confirms loader, version and
     * name. After `task.finished` the launcher jumps to 游戏设置 (PCL flow).
     */
    suspend fun startGameInstall(
        version: String,
        instanceName: String,
        loader: GameLoaderKind = GameLoaderKind.VANILLA,
        loaderVersion: String = "",
    ) {
        if (model.state.value.isInitialLoading) model.refreshCoreData()
        if (model.state.value.download.isGameDownloadRunning) {
            throw CoreException(
                "conflict",
                "error.task.already_running",
                "已有下载任务在进行，请等待完成或取消。",
            )
        }
        val name = instanceName.trim()
        val id = model.library.uniqueInstanceID(name, version)
        val response = model.core.rpc(
            "install.start",
            buildJsonObject {
                put("version", version)
                put("instanceId", id)
                put("instanceName", name.ifEmpty { id })
                if (loader != GameLoaderKind.VANILLA) {
                    put("loaderKind", loader.wireName)
                    put("loaderVersion", loaderVersion)
                }
            },
        )
        pendingDownloadTaskId = response.stringOrNull("taskId")
        pendingDownloadInstanceId = id
        model.setState {
            it.copy(
                download = it.download
                    .copy(
                        isGameDownloadRunning = true,
                        isInstallRequesting = false,
                        category = DownloadsCategory.GAME,
                    )
                    .clearInstallFlow(),
                destination = AppDestination.DOWNLOADS,
            )
        }
        model.showNotice("已开始安装 $version", Notice.Tone.INFO)
        model.refreshCoreData()
    }

    /** PCL-style: pressing play on an incomplete instance starts its download. */
    fun beginCompletion(instance: Instance) {
        if (model.state.value.download.isGameDownloadRunning) {
            model.showNotice("已有下载任务在进行")
            return
        }
        model.scope.launch {
            try {
                val response = model.core.rpc(
                    "install.start",
                    buildJsonObject {
                        put("version", instance.minecraftVersion)
                        put("instanceId", instance.id)
                        put("instanceName", instance.name)
                        if (instance.loader != com.fograin.launcher.model.LoaderKind.VANILLA) {
                            put("loaderKind", instance.loader.name.lowercase())
                            put("loaderVersion", instance.loaderVersion)
                        }
                    },
                )
                pendingDownloadInstanceId = instance.id
                pendingDownloadTaskId = response.stringOrNull("taskId")
                model.setState {
                    it.copy(
                        download = it.download
                            .copy(isGameDownloadRunning = true)
                            .clearInstallFlow(),
                        destination = AppDestination.DOWNLOADS,
                    )
                }
                model.showNotice("正在补全“${instance.name}”的游戏资源")
                model.refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法补全“${instance.name}”的资源", error)
            }
        }
    }

    fun isDownloading(instance: Instance): Boolean =
        pendingDownloadInstanceId == instance.id || model.state.value.isDownloading(instance)

    // ------------------------------------------------------------------
    // Download queue: one independent page plus inline task controls.
    // ------------------------------------------------------------------

    fun openQueue() {
        model.setState { it.copy(downloadQueueOpen = true) }
        model.scope.launch { model.refreshTaskList() }
    }

    fun closeQueue() {
        model.setState { it.copy(downloadQueueOpen = false) }
    }

    fun pause(taskId: String) {
        if (taskId.isBlank()) return
        controlTask("tasks.pause", taskId)
    }

    fun cancel(taskId: String) {
        if (taskId.isBlank()) return
        controlTask("tasks.cancel", taskId)
    }

    fun resume(taskId: String) {
        if (taskId.isBlank()) return
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "tasks.resume",
                    buildJsonObject { put("taskId", taskId) },
                )
                val newTaskId = payload.stringOrNull("taskId")
                if (newTaskId != null) {
                    if (pendingDownloadTaskId == taskId) {
                        pendingDownloadTaskId = newTaskId
                        model.setState {
                            it.copy(download = it.download.copy(isGameDownloadRunning = true))
                        }
                    }
                }
                model.showNotice("已继续下载", Notice.Tone.SUCCESS)
                model.refreshTaskList()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法继续下载", error)
                model.scope.launch { model.refreshTaskList() }
            }
        }
    }

    private fun controlTask(method: String, taskId: String) {
        model.scope.launch {
            try {
                model.core.rpc(method, buildJsonObject { put("taskId", taskId) })
                model.refreshTaskList()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError(
                    if (method == "tasks.pause") "无法暂停下载" else "无法停止下载",
                    error,
                )
                model.refreshTaskList()
            }
        }
    }

    /** `task.finished` for any task; only ours drives the post-install jump. */
    fun onTaskFinished(event: CoreEvent.TaskFinished) {
        // Java runtime downloads own their notices and reload path in
        // JavaActions; letting both controllers announce one task would double
        // every snackbar.
        if (event.name.startsWith("java:")) return
        val title = InstanceFormat.taskTitle(event.name)
        val isOurDownload = event.taskId == pendingDownloadTaskId
        when (event.state) {
            TaskState.RUNNING -> Unit
            TaskState.SUCCEEDED -> {
                model.showNotice("$title 完成", Notice.Tone.SUCCESS)
                model.scope.launch {
                    if (isOurDownload) finishGameDownload() else model.refreshCoreData()
                }
            }

            TaskState.FAILED -> {
                val message = event.detail.firstOrNull() ?: "未知原因"
                model.showError(
                    "$title 失败",
                    message,
                    event.detail.drop(1).joinToString("\n").ifBlank { null },
                )
                if (isOurDownload) clearPendingDownload()
                model.scope.launch { model.refreshCoreData() }
            }

            TaskState.CANCELLED -> {
                model.showNotice("$title 已取消")
                if (isOurDownload) clearPendingDownload()
                model.scope.launch { model.refreshCoreData() }
            }

            TaskState.PAUSED -> {
                model.showNotice("$title 已暂停")
                // Keep the pending task id so resuming from the queue still
                // opens the new instance once the replayed graph finishes.
                if (isOurDownload) {
                    model.setState {
                        it.copy(download = it.download.copy(isGameDownloadRunning = false))
                    }
                }
            }

            else -> Unit
        }
    }

    private suspend fun finishGameDownload() {
        model.setState { it.copy(download = it.download.copy(isGameDownloadRunning = false)) }
        pendingDownloadTaskId = null
        val instanceId = pendingDownloadInstanceId ?: return
        pendingDownloadInstanceId = null
        model.refreshCoreData()
        val instance = model.state.value.instances.firstOrNull { it.id == instanceId } ?: return
        model.openInstanceDetail(instance)
    }

    private fun clearPendingDownload() {
        model.setState { it.copy(download = it.download.copy(isGameDownloadRunning = false)) }
        pendingDownloadTaskId = null
        pendingDownloadInstanceId = null
    }
}

/** Drops every install-page value and returns to the version list. */
private fun DownloadUiState.clearInstallFlow(): DownloadUiState = copy(
    stage = DownloadStage.BROWSER,
    installVersionId = null,
    installLoader = GameLoaderKind.VANILLA,
    installLoaderVersion = "",
    installInstanceName = "",
    loaderVersions = emptyList(),
    isLoadingLoaderVersions = false,
    loaderVersionsError = null,
    installError = null,
    plan = null,
    planError = null,
)
