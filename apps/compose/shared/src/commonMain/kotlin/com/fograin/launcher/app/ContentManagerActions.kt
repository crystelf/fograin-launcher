package com.fograin.launcher.app

import com.fograin.launcher.platform.CoreContentCounts
import com.fograin.launcher.platform.CoreContentInstall
import com.fograin.launcher.platform.CoreContentLocal
import com.fograin.launcher.platform.CoreContentUpdate
import com.fograin.launcher.platform.CoreContentUpdates
import com.fograin.launcher.platform.CoreDatapack
import com.fograin.launcher.platform.CoreDatapackChange
import com.fograin.launcher.platform.CoreDatapacks
import com.fograin.launcher.platform.CoreLocalContent
import com.fograin.launcher.platform.CoreWorldBackup
import com.fograin.launcher.platform.CoreWorlds
import com.fograin.launcher.platform.CoreWorldsChanged
import com.fograin.launcher.platform.FilePick
import com.fograin.launcher.platform.decode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Instance content management: the mod / resource pack / shader / world /
 * data pack tabs of the instance detail page.
 *
 * Network-heavy calls happen inside coroutines; the UI only observes
 * [ContentManagerUiState]. Installs go through the same task graph as every
 * other download, so progress arrives as `task.*` events.
 */
class ContentManagerActions(private val model: AppModel) {
    private var managerGeneration = 0
    private val updateTasks = mutableSetOf<String>()

    private var browserGeneration = 0
    private var versionsGeneration = 0
    private var searchJob: Job? = null

    // ------------------------------------------------------------------
    // Instance content manager.
    // ------------------------------------------------------------------

    fun open(instanceId: String, tab: ContentManagerTab) {
        if (instanceId.isBlank()) return
        managerGeneration += 1
        model.setState {
            it.copy(
                content = it.content.copy(
                    instanceId = instanceId,
                    tab = tab,
                    query = "",
                    items = emptyList(),
                    counts = CoreContentCounts(),
                    worlds = emptyList(),
                    backups = emptyList(),
                    updates = emptyMap(),
                    updateUnknown = 0,
                    isLoading = true,
                    error = null,
                    busyFile = null,
                    busyWorld = null,
                ),
            )
        }
        refresh()
    }

    /** Clears the content state when its instance was deleted. */
    fun clear(instanceId: String) {
        managerGeneration += 1
        if (model.state.value.content.instanceId != instanceId) return
        model.setState {
            it.copy(
                content = ContentManagerUiState(),
                libraryDetailId = null,
                libraryStage = LibraryStage.LIST,
            )
        }
    }

    fun setTab(tab: ContentManagerTab) {
        if (tab == model.state.value.content.tab) return
        model.setState { it.copy(content = it.content.copy(tab = tab)) }
        if (tab == ContentManagerTab.DATAPACKS) ensureDatapacks()
    }

    /** Loads the selected world's data packs when the tab has none yet. */
    private fun ensureDatapacks() {
        val state = model.state.value.content
        val instanceId = state.instanceId ?: return
        val world = state.datapackWorld ?: state.worlds.firstOrNull()?.name ?: return
        if (state.datapackWorld != world) {
            model.setState { it.copy(content = it.content.copy(datapackWorld = world)) }
        }
        loadDatapacks(instanceId, world)
    }

    fun selectDatapackWorld(world: String) {
        val state = model.state.value.content
        if (state.datapackWorld == world) return
        model.setState { it.copy(content = it.content.copy(datapackWorld = world)) }
        val instanceId = state.instanceId ?: return
        loadDatapacks(instanceId, world)
    }

    fun refreshDatapacks() {
        val state = model.state.value.content
        val instanceId = state.instanceId ?: return
        val world = state.datapackWorld ?: return
        loadDatapacks(instanceId, world)
    }

    private fun loadDatapacks(instanceId: String, world: String) {
        model.setState {
            if (it.content.instanceId == instanceId) {
                it.copy(content = it.content.copy(datapacksLoading = true, datapackError = null))
            } else {
                it
            }
        }
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "datapacks.list",
                    buildJsonObject {
                        put("instanceId", instanceId)
                        put("world", world)
                    },
                ).decode<CoreDatapacks>()
                model.setState { current ->
                    if (current.content.instanceId == instanceId &&
                        current.content.datapackWorld == world
                    ) {
                        current.copy(
                            content = current.content.copy(
                                datapacks = payload.items,
                                datapacksLoading = false,
                                datapackError = null,
                            ),
                        )
                    } else {
                        current
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState { current ->
                    if (current.content.instanceId == instanceId) {
                        current.copy(
                            content = current.content.copy(
                                datapacksLoading = false,
                                datapackError = model.errorMessage(error),
                            ),
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun toggleDatapack(item: CoreDatapack) {
        val state = model.state.value.content
        val instanceId = state.instanceId ?: return
        val world = state.datapackWorld ?: return
        if (state.datapacksLoading) return
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "datapacks.toggle",
                    buildJsonObject {
                        put("instanceId", instanceId)
                        put("world", world)
                        put("fileName", item.fileName)
                        put("enabled", !item.enabled)
                    },
                ).decode<CoreDatapackChange>()
                model.setState { current ->
                    if (current.content.instanceId == instanceId &&
                        current.content.datapackWorld == world
                    ) {
                        current.copy(content = current.content.copy(datapacks = payload.items))
                    } else {
                        current
                    }
                }
                model.showNotice(
                    if (item.enabled) "已禁用 ${item.title}" else "已启用 ${item.title}",
                    Notice.Tone.SUCCESS,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法切换 ${item.title}", error)
            }
        }
    }

    fun deleteDatapack(item: CoreDatapack) {
        val state = model.state.value.content
        val instanceId = state.instanceId ?: return
        val world = state.datapackWorld ?: return
        if (state.datapacksLoading) return
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "datapacks.delete",
                    buildJsonObject {
                        put("instanceId", instanceId)
                        put("world", world)
                        put("fileName", item.fileName)
                    },
                ).decode<CoreDatapacks>()
                model.setState { current ->
                    if (current.content.instanceId == instanceId &&
                        current.content.datapackWorld == world
                    ) {
                        current.copy(content = current.content.copy(datapacks = payload.items))
                    } else {
                        current
                    }
                }
                model.showNotice("已删除 ${item.title}", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法删除 ${item.title}", error)
            }
        }
    }

    /** Opens the platform file picker and imports the chosen `.zip`. */
    fun importFromFile() {
        val state = model.state.value.content
        if (state.datapackWorld == null) {
            model.showNotice("先选择一个世界")
            return
        }
        model.scope.launch {
            when (val pick = model.platform.files.pick("导入数据包", listOf("zip"))) {
                is FilePick.Picked -> importDatapack(pick.path)
                FilePick.Cancelled -> Unit
                is FilePick.Unavailable -> model.showError("无法读取数据包", pick.reason)
            }
        }
    }

    fun importDatapack(path: String) {
        val state = model.state.value.content
        val instanceId = state.instanceId ?: return
        val world = state.datapackWorld ?: return
        model.setState { it.copy(content = it.content.copy(datapacksLoading = true)) }
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "datapacks.import",
                    buildJsonObject {
                        put("instanceId", instanceId)
                        put("world", world)
                        put("path", path)
                    },
                ).decode<CoreDatapackChange>()
                model.setState { current ->
                    if (current.content.instanceId == instanceId &&
                        current.content.datapackWorld == world
                    ) {
                        current.copy(
                            content = current.content.copy(
                                datapacks = payload.items,
                                datapacksLoading = false,
                            ),
                        )
                    } else {
                        current
                    }
                }
                model.showNotice(
                    "已导入 ${payload.fileName.orEmpty()}",
                    Notice.Tone.SUCCESS,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState { it.copy(content = it.content.copy(datapacksLoading = false)) }
                model.showError("无法导入数据包", error)
            }
        }
    }

    fun setQuery(query: String) {
        if (query == model.state.value.content.query) return
        model.setState { it.copy(content = it.content.copy(query = query)) }
    }

    fun refresh() {
        val instanceId = model.state.value.content.instanceId ?: return
        managerGeneration += 1
        val generation = managerGeneration
        model.scope.launch {
            model.setState {
                if (it.content.instanceId == instanceId) {
                    it.copy(content = it.content.copy(isLoading = true, error = null))
                } else {
                    it
                }
            }
            try {
                val local = model.core.rpc(
                    "content.local",
                    buildJsonObject { put("instanceId", instanceId) },
                ).decode<CoreContentLocal>()
                val worlds = model.core.rpc(
                    "worlds.list",
                    buildJsonObject { put("instanceId", instanceId) },
                ).decode<CoreWorlds>()
                if (generation != managerGeneration) return@launch
                model.setState {
                    val world = it.content.datapackWorld
                        ?.takeIf { name -> worlds.worlds.any { row -> row.name == name } }
                        ?: worlds.worlds.firstOrNull()?.name
                    it.copy(
                        content = it.content.copy(
                            items = local.items,
                            counts = local.counts,
                            worlds = worlds.worlds,
                            backups = worlds.backups,
                            datapackWorld = world,
                            isLoading = false,
                            error = null,
                        ),
                    )
                }
                if (model.state.value.content.tab == ContentManagerTab.DATAPACKS) {
                    ensureDatapacks()
                } else {
                    // Populate the tab count without waiting for the user to
                    // open the data pack page.
                    model.state.value.content.datapackWorld?.let { world ->
                        loadDatapacks(instanceId, world)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != managerGeneration) return@launch
                model.setState {
                    it.copy(
                        content = it.content.copy(
                            isLoading = false,
                            error = model.errorMessage(error),
                        ),
                    )
                }
            }
        }
    }

    fun toggle(item: CoreLocalContent) {
        val instanceId = model.state.value.content.instanceId ?: return
        if (model.state.value.content.busyFile != null) return
        model.setState { it.copy(content = it.content.copy(busyFile = item.fileName)) }
        model.scope.launch {
            try {
                model.core.rpc(
                    "content.toggle",
                    buildJsonObject {
                        put("instanceId", instanceId)
                        put("kind", item.kind)
                        put("fileName", item.fileName)
                        put("enabled", !item.enabled)
                    },
                )
                model.showNotice(
                    if (item.enabled) "已禁用 ${item.title}" else "已启用 ${item.title}",
                    Notice.Tone.SUCCESS,
                )
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法切换 ${item.title}", error)
            } finally {
                model.setState { it.copy(content = it.content.copy(busyFile = null)) }
            }
        }
    }

    fun delete(item: CoreLocalContent) {
        val instanceId = model.state.value.content.instanceId ?: return
        if (model.state.value.content.busyFile != null) return
        model.setState { it.copy(content = it.content.copy(busyFile = item.fileName)) }
        model.scope.launch {
            try {
                model.core.rpc(
                    "content.delete",
                    buildJsonObject {
                        put("instanceId", instanceId)
                        put("kind", item.kind)
                        put("fileName", item.fileName)
                    },
                )
                model.showNotice("已删除 ${item.title}", Notice.Tone.SUCCESS)
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法删除 ${item.title}", error)
            } finally {
                model.setState { it.copy(content = it.content.copy(busyFile = null)) }
            }
        }
    }

    fun checkUpdates() {
        val state = model.state.value.content
        val instanceId = state.instanceId ?: return
        if (state.isCheckingUpdates) return
        model.setState { it.copy(content = it.content.copy(isCheckingUpdates = true)) }
        model.scope.launch {
            try {
                val check = model.core.rpc(
                    "content.updates",
                    buildJsonObject { put("instanceId", instanceId) },
                ).decode<CoreContentUpdates>()
                model.setState { current ->
                    current.copy(
                        content = current.content.copy(
                            isCheckingUpdates = false,
                            updates = check.updates.associateBy { it.fileName },
                            updateUnknown = check.unknown,
                        ),
                    )
                }
                model.showNotice(
                    if (check.updates.isEmpty()) {
                        "已检查 ${check.checked} 个文件，没有可用更新"
                    } else {
                        "${check.updates.size} 个内容可以更新"
                    },
                    if (check.updates.isEmpty()) Notice.Tone.INFO else Notice.Tone.SUCCESS,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState { it.copy(content = it.content.copy(isCheckingUpdates = false)) }
                model.showError("检查内容更新失败", error)
            }
        }
    }

    fun installUpdate(update: CoreContentUpdate) {
        val state = model.state.value.content
        val instanceId = state.instanceId ?: return
        if (state.busyFile != null) return
        model.setState { it.copy(content = it.content.copy(busyFile = update.fileName)) }
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "content.install",
                    buildJsonObject {
                        put("source", update.source)
                        put("projectId", update.projectId)
                        put("versionId", update.latestVersionId)
                        put("instanceId", instanceId)
                        put("kind", update.kind)
                        put("replaceFiles", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive(update.fileName))
                        })
                    },
                ).decode<CoreContentInstall>()
                payload.taskId?.let { updateTasks += it }
                model.setState { current ->
                    current.copy(
                        content = current.content.copy(
                            updates = current.content.updates - update.fileName,
                        ),
                    )
                }
                model.showNotice(
                    "正在更新 ${update.projectName} ${update.latestVersion}",
                    Notice.Tone.INFO,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法更新 ${update.projectName}", error)
            } finally {
                model.setState { it.copy(content = it.content.copy(busyFile = null)) }
            }
        }
    }

    fun backupWorld(worldName: String) {
        val instanceId = model.state.value.content.instanceId ?: return
        if (model.state.value.content.busyWorld != null) return
        model.setState { it.copy(content = it.content.copy(busyWorld = worldName)) }
        model.scope.launch {
            try {
                val changed = model.core.rpc(
                    "worlds.backup",
                    buildJsonObject {
                        put("instanceId", instanceId)
                        put("name", worldName)
                    },
                ).decode<CoreWorldsChanged>()
                model.setState { current ->
                    current.copy(content = current.content.copy(backups = changed.backups))
                }
                model.showNotice("已备份 $worldName", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法备份 $worldName", error)
            } finally {
                model.setState { it.copy(content = it.content.copy(busyWorld = null)) }
            }
        }
    }

    fun deleteWorld(worldName: String) {
        val instanceId = model.state.value.content.instanceId ?: return
        if (model.state.value.content.busyWorld != null) return
        model.setState { it.copy(content = it.content.copy(busyWorld = worldName)) }
        model.scope.launch {
            try {
                val changed = model.core.rpc(
                    "worlds.delete",
                    buildJsonObject {
                        put("instanceId", instanceId)
                        put("name", worldName)
                    },
                ).decode<CoreWorldsChanged>()
                model.setState { current ->
                    current.copy(content = current.content.copy(worlds = changed.worlds))
                }
                model.showNotice("已删除世界 $worldName", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法删除世界 $worldName", error)
            } finally {
                model.setState { it.copy(content = it.content.copy(busyWorld = null)) }
            }
        }
    }

    fun restoreBackup(backup: CoreWorldBackup, worldName: String) {
        val instanceId = model.state.value.content.instanceId ?: return
        val name = worldName.trim()
        if (name.isEmpty()) {
            model.showError("恢复世界", "请输入新的世界名称。")
            return
        }
        model.scope.launch {
            try {
                val changed = model.core.rpc(
                    "worlds.restore",
                    buildJsonObject {
                        put("instanceId", instanceId)
                        put("backup", backup.name)
                        put("name", name)
                    },
                ).decode<CoreWorldsChanged>()
                model.setState { current ->
                    current.copy(
                        content = current.content.copy(
                            worlds = changed.worlds,
                            backups = changed.backups,
                        ),
                    )
                }
                model.showNotice("已恢复到世界 $name", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法恢复备份", error)
            }
        }
    }

    fun deleteBackup(backup: CoreWorldBackup) {
        val instanceId = model.state.value.content.instanceId ?: return
        model.scope.launch {
            try {
                val changed = model.core.rpc(
                    "worlds.backup.delete",
                    buildJsonObject {
                        put("instanceId", instanceId)
                        put("backup", backup.name)
                    },
                ).decode<CoreWorldsChanged>()
                model.setState { current ->
                    current.copy(content = current.content.copy(backups = changed.backups))
                }
                model.showNotice("已删除备份 ${backup.name}", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法删除备份", error)
            }
        }
    }


    /** Refreshes the manager when a content update task completes. */
    fun onTaskFinished(taskId: String) {
        if (!updateTasks.remove(taskId)) return
        if (model.state.value.libraryStage != LibraryStage.LIST) refresh()
    }
}
