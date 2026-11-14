package com.fograin.launcher.app

import com.fograin.launcher.model.ContentLoaderFilter
import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.CoreEvent
import com.fograin.launcher.platform.CoreException
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
 * Download page state machine: manifest loading, plan preview, the install
 * sheet and the task that `install.start` spawns.
 *
 * Owns the "our download" bookkeeping ([pendingDownloadTaskId] /
 * [pendingDownloadInstanceId]) so the shell can jump to the new instance when
 * the task finishes — the one piece of cross-screen flow in the launcher.
 * State is written through [AppModel.setState]; nothing here renders.
 */
internal class DownloadsActions(private val model: AppModel) {

    private var pendingDownloadTaskId: String? = null
    private var pendingDownloadInstanceId: String? = null
    private var downloadVersionsGeneration = 0
    private var loadingDownloadChannel: DownloadVersionChannel? = null

    /** Loads the manifest for the selected channel after the page appeared. */
    suspend fun loadVersions() {
        val channel = model.state.value.download.channel
        if (model.state.value.download.versionsChannel == channel) {
            refreshPlan()
            return
        }
        fetchVersions()
    }

    /** Called when the channel dropdown changes; clears stale rows first. */
    suspend fun reloadVersions() {
        model.setState {
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
                        selectedVersionId = versions.firstOrNull()?.id,
                        plan = null,
                        planError = null,
                        isLoadingVersions = false,
                    ),
                )
            }
            if (versions.isNotEmpty()) refreshPlan()
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
        model.setState { it.copy(download = it.download.copy(category = category)) }
        if (category == DownloadsCategory.GAME) {
            model.scope.launch { loadVersions() }
        }
    }

    fun setChannel(channel: DownloadVersionChannel) {
        if (channel == model.state.value.download.channel) return
        model.setState { it.copy(download = it.download.copy(channel = channel)) }
        model.scope.launch { reloadVersions() }
    }

    fun setContentLoader(loader: ContentLoaderFilter) {
        model.setState { it.copy(download = it.download.copy(contentLoader = loader.name)) }
    }

    fun setQuery(query: String) {
        model.setState { it.copy(download = it.download.copy(query = query)) }
    }

    fun selectVersion(versionId: String) {
        model.setState {
            it.copy(
                download = it.download.copy(
                    selectedVersionId = versionId,
                    plan = null,
                    planError = null,
                ),
            )
        }
        model.scope.launch { refreshPlan() }
    }

    fun requestPlanRefresh() {
        model.scope.launch { refreshPlan() }
    }

    private suspend fun refreshPlan() {
        val version = model.state.value.download.selectedVersionId ?: run {
            model.setState { it.copy(download = it.download.copy(plan = null, planError = null)) }
            return
        }
        model.setState { it.copy(download = it.download.copy(planError = null)) }
        try {
            val payload = model.core.rpc(
                "version.plan",
                buildJsonObject { put("version", version) },
            )
            if (model.state.value.download.selectedVersionId != version) return
            val plan = payload.decode<CorePlanPreview>()
            model.setState { it.copy(download = it.download.copy(plan = plan)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (model.state.value.download.selectedVersionId != version) return
            model.setState {
                it.copy(download = it.download.copy(plan = null, planError = model.errorMessage(error)))
            }
        }
    }

    fun openInstallSheet(version: String?) {
        if (!version.isNullOrEmpty()) {
            model.setState { it.copy(download = it.download.copy(selectedVersionId = version)) }
        }
        model.setState { it.copy(download = it.download.copy(installSheetVersion = version.orEmpty())) }
    }

    fun closeInstallSheet() {
        model.setState { it.copy(download = it.download.copy(installSheetVersion = null)) }
    }

    fun installVersion(version: String) {
        model.callCore(
            "install.start",
            buildJsonObject { put("version", version) },
            notice = "已开始安装 $version",
        )
    }

    /**
     * Starts the download after the install sheet confirms loader, version and
     * name. After `task.finished` the launcher jumps to 游戏设置 (PCL flow).
     */
    suspend fun startGameInstall(version: String, instanceName: String) {
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
            },
        )
        pendingDownloadTaskId = response.stringOrNull("taskId")
        pendingDownloadInstanceId = id
        model.setState {
            it.copy(
                download = it.download.copy(
                    isGameDownloadRunning = true,
                    category = DownloadsCategory.GAME,
                    installSheetVersion = null,
                ),
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
                    },
                )
                pendingDownloadInstanceId = instance.id
                pendingDownloadTaskId = response.stringOrNull("taskId")
                model.setState {
                    it.copy(
                        download = it.download.copy(isGameDownloadRunning = true),
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

    /** `task.finished` for any task; only ours drives the post-install jump. */
    fun onTaskFinished(event: CoreEvent.TaskFinished) {
        val title = InstanceFormat.taskTitle(event.name)
        val isOurDownload = event.taskId == pendingDownloadTaskId
        when (event.state) {
            "succeeded" -> {
                model.showNotice("$title 完成", Notice.Tone.SUCCESS)
                model.scope.launch {
                    if (isOurDownload) finishGameDownload() else model.refreshCoreData()
                }
            }

            "failed" -> {
                val message = event.detail.firstOrNull() ?: "未知原因"
                model.showError(
                    "$title 失败",
                    message,
                    event.detail.drop(1).joinToString("\n").ifBlank { null },
                )
                if (isOurDownload) clearPendingDownload()
                model.scope.launch { model.refreshCoreData() }
            }

            "cancelled" -> {
                model.showNotice("$title 已取消")
                if (isOurDownload) clearPendingDownload()
                model.scope.launch { model.refreshCoreData() }
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
        if (model.state.value.instances.none { it.id == instanceId }) return
        model.setState {
            it.copy(
                currentInstanceId = instanceId,
                librarySelection = instanceId,
                destination = AppDestination.LIBRARY,
                instanceSettingsId = instanceId,
            )
        }
    }

    private fun clearPendingDownload() {
        model.setState { it.copy(download = it.download.copy(isGameDownloadRunning = false)) }
        pendingDownloadTaskId = null
        pendingDownloadInstanceId = null
    }
}
