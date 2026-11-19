package com.fograin.launcher.app

import com.fograin.launcher.model.ContentVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.platform.CoreContentInstall
import com.fograin.launcher.platform.CoreContentCounts
import com.fograin.launcher.platform.CoreContentLocal
import com.fograin.launcher.platform.CoreContentPlan
import com.fograin.launcher.platform.CoreContentProject
import com.fograin.launcher.platform.CoreContentSearchPage
import com.fograin.launcher.platform.CoreContentUpdate
import com.fograin.launcher.platform.CoreContentUpdates
import com.fograin.launcher.platform.CoreContentVersion
import com.fograin.launcher.platform.CoreContentVersions
import com.fograin.launcher.platform.CoreModpackInstallTask
import com.fograin.launcher.platform.CoreDatapack
import com.fograin.launcher.platform.CoreDatapackChange
import com.fograin.launcher.platform.CoreDatapacks
import com.fograin.launcher.platform.CoreLocalContent
import com.fograin.launcher.platform.FilePick
import com.fograin.launcher.platform.CoreWorldBackup
import com.fograin.launcher.platform.CoreWorlds
import com.fograin.launcher.platform.CoreWorldsChanged
import com.fograin.launcher.platform.decode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M8 content flows: the instance content manager (mods, resource packs,
 * shaders, worlds) and the content browser on the downloads page.
 *
 * Network-heavy calls happen inside coroutines; the UI only observes
 * [ContentManagerUiState] / [ContentBrowserUiState]. Content installation
 * itself is a normal task graph, so progress arrives through `task.*` events
 * instead of this controller.
 */
internal class ContentActions(private val model: AppModel) {
    private var managerGeneration = 0
    private var browserGeneration = 0
    private var versionsGeneration = 0
    private var searchJob: Job? = null
    private val updateTasks = mutableSetOf<String>()

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

    // ------------------------------------------------------------------
    // Downloads content browser.
    // ------------------------------------------------------------------

    fun openCategory(category: DownloadsCategory) {
        if (!category.isContent) return
        val current = model.state.value
        val target = if (category.installsToNewInstance) {
            ""
        } else {
            current.contentBrowser.targetInstanceId
                .takeIf { id -> current.instances.any { it.id == id } }
                ?: current.currentInstanceId
                    .takeIf { id -> current.instances.any { it.id == id } }
                ?: current.instances.firstOrNull()?.id.orEmpty()
        }
        model.setState {
            it.copy(
                contentBrowser = it.contentBrowser.copy(
                    targetInstanceId = target,
                    targetWorld = "",
                    worlds = emptyList(),
                    isLoadingWorlds = false,
                    projects = emptyList(),
                    total = 0,
                    error = null,
                    selectedProject = null,
                    versions = emptyList(),
                    selectedVersionId = null,
                    versionChannel = ContentVersionChannel.RELEASE,
                    isCheckingDependencies = false,
                    dependencyPrompt = null,
                    installTaskId = null,
                    isInstalling = false,
                ),
            )
        }
        if (category.worldScoped && target.isNotBlank()) loadWorlds(target)
        search()
    }

    fun setSource(source: ContentSourceOption) {
        if (source == model.state.value.contentBrowser.source) return
        model.setState {
            it.copy(contentBrowser = it.contentBrowser.copy(source = source))
        }
        search()
    }

    fun setTargetInstance(instanceId: String) {
        if (instanceId == model.state.value.contentBrowser.targetInstanceId) return
        val project = model.state.value.contentBrowser.selectedProject
        model.setState {
            it.copy(
                contentBrowser = it.contentBrowser.copy(
                    targetInstanceId = instanceId,
                    targetWorld = "",
                    worlds = emptyList(),
                ),
            )
        }
        if (model.state.value.download.category.worldScoped) loadWorlds(instanceId)
        // The project sheet must stay open while the version list is reloaded
        // for the new instance; only the browser behind it performs a search.
        if (project != null) {
            openProject(project)
        } else {
            search()
        }
    }

    fun selectWorld(world: String) {
        if (world == model.state.value.contentBrowser.targetWorld) return
        model.setState {
            it.copy(contentBrowser = it.contentBrowser.copy(targetWorld = world))
        }
    }

    /** Loads the worlds of the data pack target instance. */
    fun loadWorlds(instanceId: String) {
        if (instanceId.isBlank()) return
        model.setState {
            it.copy(contentBrowser = it.contentBrowser.copy(isLoadingWorlds = true))
        }
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "worlds.list",
                    buildJsonObject { put("instanceId", instanceId) },
                ).decode<CoreWorlds>()
                model.setState { current ->
                    if (current.contentBrowser.targetInstanceId != instanceId) {
                        current
                    } else {
                        val world = current.contentBrowser.targetWorld
                            .takeIf { selected -> payload.worlds.any { it.name == selected } }
                            ?: payload.worlds.firstOrNull()?.name.orEmpty()
                        current.copy(
                            contentBrowser = current.contentBrowser.copy(
                                worlds = payload.worlds,
                                targetWorld = world,
                                isLoadingWorlds = false,
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState { current ->
                    current.copy(contentBrowser = current.contentBrowser.copy(isLoadingWorlds = false))
                }
                model.showError("无法读取世界列表", error)
            }
        }
    }

    /** Debounced search; [DownloadsActions.setQuery] owns the query text. */
    fun scheduleSearch() {
        val category = model.state.value.download.category
        if (!category.isContent) return
        searchJob?.cancel()
        searchJob = model.scope.launch {
            delay(350)
            search()
        }
    }

    fun search() {
        val category = model.state.value.download.category
        val kind = category.contentKind ?: return
        val state = model.state.value.contentBrowser
        browserGeneration += 1
        val generation = browserGeneration
        model.setState {
            it.copy(
                contentBrowser = it.contentBrowser.copy(
                    isLoading = true,
                    error = null,
                    selectedProject = null,
                    versions = emptyList(),
                    selectedVersionId = null,
                    isCheckingDependencies = false,
                    dependencyPrompt = null,
                ),
            )
        }
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "content.search",
                    buildJsonObject {
                        put("source", state.source.wire)
                        put("kind", kind)
                        put("query", model.state.value.download.query)
                        if (category.installsToNewInstance) {
                            loaderFilterWire()?.let { put("loader", it) }
                        } else if (state.targetInstanceId.isNotBlank()) {
                            put("instanceId", state.targetInstanceId)
                        }
                        put("offset", 0)
                        put("limit", 50)
                    },
                ).decode<CoreContentSearchPage>()
                if (generation != browserGeneration) return@launch
                model.setState {
                    it.copy(
                        contentBrowser = it.contentBrowser.copy(
                            projects = payload.projects,
                            total = payload.total,
                            isLoading = false,
                            error = null,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != browserGeneration) return@launch
                model.setState {
                    it.copy(
                        contentBrowser = it.contentBrowser.copy(
                            isLoading = false,
                            error = model.errorMessage(error),
                        ),
                    )
                }
            }
        }
    }

    fun openProject(project: CoreContentProject) {
        versionsGeneration += 1
        val generation = versionsGeneration
        model.setState {
            it.copy(
                contentBrowser = it.contentBrowser.copy(
                    selectedProject = project,
                    versions = emptyList(),
                    selectedVersionId = null,
                    versionsError = null,
                    isLoadingVersions = true,
                    isCheckingDependencies = false,
                    dependencyPrompt = null,
                ),
            )
        }
        val category = model.state.value.download.category
        val kind = category.contentKind ?: project.projectId
        val state = model.state.value.contentBrowser
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "content.versions",
                    buildJsonObject {
                        put("source", project.source)
                        put("projectId", project.projectId)
                        put("kind", kind)
                        if (category.installsToNewInstance) {
                            loaderFilterWire()?.let { put("loader", it) }
                        } else if (state.targetInstanceId.isNotBlank()) {
                            put("instanceId", state.targetInstanceId)
                        }
                    },
                ).decode<CoreContentVersions>()
                if (generation != versionsGeneration) return@launch
                model.setState {
                    // The release channel can change while the list is loading;
                    // always select the first row that the current filter shows.
                    val channel = it.contentBrowser.versionChannel
                    val visible = payload.versions.filter { version ->
                        channel.matches(version.versionType)
                    }
                    it.copy(
                        contentBrowser = it.contentBrowser.copy(
                            versions = payload.versions,
                            selectedVersionId = visible.firstOrNull()?.versionId,
                            isLoadingVersions = false,
                            versionsError = null,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != versionsGeneration) return@launch
                model.setState {
                    it.copy(
                        contentBrowser = it.contentBrowser.copy(
                            isLoadingVersions = false,
                            versionsError = model.errorMessage(error),
                        ),
                    )
                }
            }
        }
    }

    fun closeProject() {
        versionsGeneration += 1
        model.setState {
            it.copy(
                contentBrowser = it.contentBrowser.copy(
                    selectedProject = null,
                    versions = emptyList(),
                    selectedVersionId = null,
                    versionsError = null,
                    isLoadingVersions = false,
                    isCheckingDependencies = false,
                    dependencyPrompt = null,
                ),
            )
        }
        // The target may have changed while the sheet was open.
        if (model.state.value.download.category.isContent) search()
    }

    fun selectVersion(versionId: String) {
        model.setState {
            it.copy(
                contentBrowser = it.contentBrowser.copy(
                    selectedVersionId = versionId,
                    // A preview belongs to the selected version; choosing
                    // another one invalidates the in-flight result.
                    isCheckingDependencies = false,
                    dependencyPrompt = null,
                ),
            )
        }
    }

    /** Switches 正式版 / 测试版 / 内测版 / 全部版本, keeping the selection if visible. */
    fun setVersionChannel(channel: ContentVersionChannel) {
        val state = model.state.value.contentBrowser
        if (channel == state.versionChannel) return
        model.setState {
            val current = it.contentBrowser.versions
                .firstOrNull { version -> version.versionId == it.contentBrowser.selectedVersionId }
            val keep = current?.takeIf { version -> channel.matches(version.versionType) }
            val next = keep ?: it.contentBrowser.versions
                .firstOrNull { version -> channel.matches(version.versionType) }
            it.copy(
                contentBrowser = it.contentBrowser.copy(
                    versionChannel = channel,
                    selectedVersionId = next?.versionId,
                    isCheckingDependencies = false,
                    dependencyPrompt = null,
                ),
            )
        }
    }

    fun installSelected() {
        val state = model.state.value.contentBrowser
        val project = state.selectedProject ?: return
        val version = state.selectedVersion ?: return
        if (state.isInstalling || state.isCheckingDependencies) return
        when (val category = model.state.value.download.category) {
            DownloadsCategory.MODPACK -> installModpack(project, version)
            DownloadsCategory.DATAPACK -> installDatapack(category, project, version)
            // Mods are the only kind with a meaningful prerequisite graph, so
            // they get a preview dialog before anything is queued.
            DownloadsCategory.MOD -> {
                val required = version.dependencies.any { dependency ->
                    dependency.kind == "required" && !dependency.projectId.isNullOrBlank()
                }
                if (required) {
                    previewDependencies(category, project, version)
                } else {
                    installIntoInstance(
                        category,
                        project,
                        version,
                        installDependencies = true,
                    )
                }
            }

            else -> installIntoInstance(
                category,
                project,
                version,
                installDependencies = true,
            )
        }
    }

    /** Asks the core which prerequisites are still missing for [version]. */
    private fun previewDependencies(
        category: DownloadsCategory,
        project: CoreContentProject,
        version: CoreContentVersion,
    ) {
        val state = model.state.value.contentBrowser
        if (state.targetInstanceId.isBlank()) {
            model.showNotice("请先选择要安装到的实例")
            return
        }
        val instanceId = state.targetInstanceId
        model.setState {
            it.copy(contentBrowser = it.contentBrowser.copy(isCheckingDependencies = true))
        }
        model.scope.launch {
            try {
                val plan = model.core.rpc(
                    "content.plan",
                    buildJsonObject {
                        put("source", project.source)
                        put("projectId", project.projectId)
                        put("versionId", version.versionId)
                        put("instanceId", instanceId)
                        put("kind", category.contentKind ?: "mod")
                    },
                ).decode<CoreContentPlan>()
                if (!isCurrentSelection(project, version, instanceId)) return@launch
                val missing = plan.dependencies
                if (missing.isEmpty() && plan.warnings.isEmpty()) {
                    model.setState {
                        it.copy(
                            contentBrowser = it.contentBrowser.copy(
                                isCheckingDependencies = false,
                            ),
                        )
                    }
                    installIntoInstance(
                        category,
                        project,
                        version,
                        installDependencies = true,
                    )
                } else {
                    model.setState {
                        it.copy(
                            contentBrowser = it.contentBrowser.copy(
                                isCheckingDependencies = false,
                                dependencyPrompt = ContentDependencyPrompt(
                                    projectName = project.title,
                                    items = missing,
                                    warnings = plan.warnings,
                                ),
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentSelection(project, version, instanceId)) return@launch
                model.setState {
                    it.copy(contentBrowser = it.contentBrowser.copy(isCheckingDependencies = false))
                }
                model.showError("无法检查 ${project.title} 的前置", error)
            }
        }
    }

    /**
     * Applies the prerequisite choice: `true` downloads the compatible
     * prerequisites first, `false` installs only the project itself.
     */
    fun confirmDependencyPrompt(autoDownload: Boolean) {
        val state = model.state.value.contentBrowser
        if (state.dependencyPrompt == null) return
        val project = state.selectedProject
        val version = state.selectedVersion
        model.setState {
            it.copy(contentBrowser = it.contentBrowser.copy(dependencyPrompt = null))
        }
        if (project == null || version == null) return
        val category = model.state.value.download.category
        if (!category.isContent || category.installsToNewInstance) return
        if (!autoDownload) {
            model.showNotice("已跳过前置，模组可能无法正常加载", Notice.Tone.WARNING)
        }
        installIntoInstance(category, project, version, autoDownload)
    }

    fun dismissDependencyPrompt() {
        model.setState {
            it.copy(contentBrowser = it.contentBrowser.copy(dependencyPrompt = null))
        }
    }

    private fun isCurrentSelection(
        project: CoreContentProject,
        version: CoreContentVersion,
        instanceId: String,
    ): Boolean {
        val current = model.state.value.contentBrowser
        val currentProject = current.selectedProject ?: return false
        return currentProject.projectId == project.projectId &&
            currentProject.source == project.source &&
            current.selectedVersion?.versionId == version.versionId &&
            current.targetInstanceId == instanceId
    }

    private fun installIntoInstance(
        category: DownloadsCategory,
        project: CoreContentProject,
        version: CoreContentVersion,
        installDependencies: Boolean,
    ) {
        val state = model.state.value.contentBrowser
        if (state.targetInstanceId.isBlank()) {
            model.showNotice("请先选择要安装到的实例")
            return
        }
        val kind = category.contentKind ?: return
        model.setState {
            it.copy(
                contentBrowser = it.contentBrowser.copy(
                    isInstalling = true,
                    dependencyPrompt = null,
                ),
            )
        }
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "content.install",
                    buildJsonObject {
                        put("source", project.source)
                        put("projectId", project.projectId)
                        put("versionId", version.versionId)
                        put("instanceId", state.targetInstanceId)
                        put("kind", kind)
                        put("installDependencies", installDependencies)
                    },
                ).decode<CoreContentInstall>()
                closeAfterInstall(taskId = payload.taskId)
                model.showNotice(
                    "开始安装 ${payload.projectName} ${payload.versionNumber}",
                    Notice.Tone.INFO,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState {
                    it.copy(contentBrowser = it.contentBrowser.copy(isInstalling = false))
                }
                model.showError("无法安装 ${project.title}", error)
            }
        }
    }

    private fun installDatapack(
        category: DownloadsCategory,
        project: CoreContentProject,
        version: CoreContentVersion,
    ) {
        val state = model.state.value.contentBrowser
        if (state.targetInstanceId.isBlank()) {
            model.showNotice("请先选择要安装到的实例")
            return
        }
        if (state.targetWorld.isBlank()) {
            model.showNotice("请先选择要安装到的世界")
            return
        }
        val kind = category.contentKind ?: return
        model.setState { it.copy(contentBrowser = it.contentBrowser.copy(isInstalling = true)) }
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "content.install",
                    buildJsonObject {
                        put("source", project.source)
                        put("projectId", project.projectId)
                        put("versionId", version.versionId)
                        put("instanceId", state.targetInstanceId)
                        put("kind", kind)
                        put("world", state.targetWorld)
                    },
                ).decode<CoreContentInstall>()
                closeAfterInstall(taskId = payload.taskId)
                model.showNotice(
                    "开始安装 ${payload.projectName} 到 ${state.targetWorld}",
                    Notice.Tone.INFO,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState {
                    it.copy(contentBrowser = it.contentBrowser.copy(isInstalling = false))
                }
                model.showError("无法安装 ${project.title}", error)
            }
        }
    }

    private fun installModpack(project: CoreContentProject, version: CoreContentVersion) {
        model.setState { it.copy(contentBrowser = it.contentBrowser.copy(isInstalling = true)) }
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "modpack.install",
                    buildJsonObject {
                        put("source", project.source)
                        put("projectId", project.projectId)
                        put("versionId", version.versionId)
                        put("name", project.title)
                    },
                ).decode<CoreModpackInstallTask>()
                closeAfterInstall(taskId = payload.taskId)
                model.showNotice(
                    "正在下载整合包 ${payload.name.ifBlank { project.title }}，完成后会自动创建实例",
                    Notice.Tone.INFO,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState {
                    it.copy(contentBrowser = it.contentBrowser.copy(isInstalling = false))
                }
                model.showError("无法安装 ${project.title}", error)
            }
        }
    }

    private fun closeAfterInstall(taskId: String? = null) {
        model.setState {
            it.copy(
                contentBrowser = it.contentBrowser.copy(
                    isInstalling = false,
                    isCheckingDependencies = false,
                    dependencyPrompt = null,
                    installTaskId = taskId,
                    selectedProject = null,
                    versions = emptyList(),
                    selectedVersionId = null,
                ),
            )
        }
    }

    /** Lowercase loader wire value for the modpack filter, or null for all. */
    private fun loaderFilterWire(): String? {
        val filter = model.state.value.download.contentLoader
        return when (filter.uppercase()) {
            "FABRIC" -> "fabric"
            "QUILT" -> "quilt"
            "FORGE" -> "forge"
            "NEOFORGE" -> "neoforge"
            else -> null
        }
    }

    fun onTaskFinished(taskId: String) {
        if (updateTasks.remove(taskId)) {
            if (model.state.value.libraryStage != LibraryStage.LIST) refresh()
            return
        }
        if (model.state.value.contentBrowser.installTaskId == taskId) {
            model.setState {
                it.copy(contentBrowser = it.contentBrowser.copy(installTaskId = null))
            }
        }
    }
}
