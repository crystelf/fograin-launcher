package com.fograin.launcher.app

import com.fograin.launcher.model.Instance
import com.fograin.launcher.platform.CoreModpack
import com.fograin.launcher.platform.CoreModpackExport
import com.fograin.launcher.platform.CoreModpackImport
import com.fograin.launcher.platform.FilePick
import com.fograin.launcher.platform.decode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Modpack import and export (M9).
 *
 * Import is a two-phase core task (base game + loader, then pack files); this
 * controller only inspects the file, confirms the target name and reports the
 * queued tasks. The downloads page's task queue owns the progress UI.
 */
internal class ModpackActions(private val model: AppModel) {
    private var generation = 0

    /** Opens the file picker; a cancelled or unavailable picker stays silent. */
    fun startImport() {
        if (model.state.value.modpack.inspecting) return
        generation += 1
        val current = generation
        model.setState {
            it.copy(modpack = it.modpack.copy(inspecting = true, error = null))
        }
        model.scope.launch {
            val pick = try {
                model.platform.files.pick("导入整合包", listOf("mrpack", "zip"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState { it.copy(modpack = it.modpack.copy(inspecting = false)) }
                model.showError("无法打开文件选择器", error)
                return@launch
            }
            if (current != generation) return@launch
            when (pick) {
                is FilePick.Picked -> inspect(pick.path)
                FilePick.Cancelled -> {
                    model.setState { it.copy(modpack = it.modpack.copy(inspecting = false)) }
                }

                is FilePick.Unavailable -> {
                    model.setState { it.copy(modpack = it.modpack.copy(inspecting = false)) }
                    model.showError("无法读取整合包", pick.reason)
                }
            }
        }
    }

    /** Shared entry point for the home menu and the desktop drop target. */
    fun importPath(path: String) {
        if (model.state.value.modpack.inspecting) return
        inspect(path)
    }

    fun setDragActive(active: Boolean) {
        if (model.state.value.modpack.dragActive == active) return
        model.setState { it.copy(modpack = it.modpack.copy(dragActive = active)) }
    }

    private fun inspect(path: String) {
        generation += 1
        val current = generation
        model.setState {
            it.copy(modpack = it.modpack.copy(inspecting = true, error = null))
        }
        model.scope.launch {
            try {
                val pack = model.core.rpc(
                    "modpack.inspect",
                    buildJsonObject { put("path", path) },
                ).decode<CoreModpack>()
                if (current != generation) return@launch
                model.setState {
                    it.copy(
                        modpack = it.modpack.copy(
                            inspecting = false,
                            pack = pack,
                            name = pack.name,
                            error = null,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (current != generation) return@launch
                model.setState {
                    it.copy(
                        modpack = it.modpack.copy(
                            inspecting = false,
                            error = model.errorMessage(error),
                        ),
                    )
                }
                model.showError("无法读取整合包", error)
            }
        }
    }

    fun setName(name: String) {
        model.setState { it.copy(modpack = it.modpack.copy(name = name)) }
    }

    fun dismiss() {
        if (model.state.value.modpack.isImporting) return
        generation += 1
        model.setState { it.copy(modpack = ModpackUiState()) }
    }

    fun confirmImport() {
        val state = model.state.value.modpack
        val pack = state.pack ?: return
        if (!state.canImport) return
        model.setState {
            it.copy(modpack = it.modpack.copy(isImporting = true, error = null))
        }
        model.scope.launch {
            try {
                val result = model.core.rpc(
                    "modpack.import",
                    buildJsonObject {
                        put("path", pack.sourcePath)
                        put("name", state.name.trim())
                    },
                ).decode<CoreModpackImport>()
                model.setState {
                    it.copy(
                        modpack = ModpackUiState(
                            taskId = result.taskId,
                            importedInstanceId = result.instanceId,
                        ),
                    )
                }
                model.showNotice(
                    if (result.external) {
                        "已导入实例 ${state.name}"
                    } else {
                        "正在导入 ${state.name}，可在任务队列查看进度"
                    },
                    Notice.Tone.SUCCESS,
                )
                model.refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState {
                    it.copy(modpack = it.modpack.copy(isImporting = false))
                }
                model.showError("无法导入整合包", error)
            }
        }
    }

    /** Exports the instance as `.mrpack` under `<data root>/exports`. */
    fun export(instance: Instance) {
        model.scope.launch {
            try {
                val result = model.core.rpc(
                    "modpack.export",
                    buildJsonObject { put("instanceId", instance.id) },
                ).decode<CoreModpackExport>()
                model.setState {
                    it.copy(modpack = it.modpack.copy(exportResult = result))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法导出整合包", error)
            }
        }
    }

    fun dismissExport() {
        model.setState { it.copy(modpack = it.modpack.copy(exportResult = null)) }
    }

    fun copyExportPath() {
        val path = model.state.value.modpack.exportResult?.path ?: return
        model.platform.clipboard.copy(path)
        model.showNotice("整合包路径已复制", Notice.Tone.SUCCESS)
    }

    fun onTaskFinished(taskId: String) {
        if (model.state.value.modpack.taskId != taskId) return
        model.scope.launch { model.refreshCoreData() }
    }
}