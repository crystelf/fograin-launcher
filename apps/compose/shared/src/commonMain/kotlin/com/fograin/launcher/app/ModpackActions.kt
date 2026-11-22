package com.fograin.launcher.app

import com.fograin.launcher.model.Instance
import com.fograin.launcher.platform.CoreExportPlan
import com.fograin.launcher.platform.CoreModpack
import com.fograin.launcher.platform.CoreModpackExport
import com.fograin.launcher.platform.CoreModpackImport
import com.fograin.launcher.platform.FilePick
import com.fograin.launcher.platform.FileSavePick
import com.fograin.launcher.platform.decode
import com.fograin.launcher.ui.AppDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Modpack import and export.
 *
 * Import is a two-phase core task (base game + loader, then pack files); this
 * controller only inspects the file, confirms the target name and reports the
 * queued tasks. The downloads page's task queue owns the progress UI.
 */
class ModpackActions(private val model: AppModel) {
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

    // ------------------------------------------------------------------
    // Export wizard
    // ------------------------------------------------------------------

    /** Opens the two-step export page and loads the instance's content plan. */
    fun beginExport(instance: Instance) {
        val account = model.state.value.account.selected
        val author = account?.email?.takeIf { it.isNotBlank() } ?: account?.name.orEmpty()
        model.setState {
            it.copy(
                destination = AppDestination.LIBRARY,
                libraryDetailId = instance.id,
                currentInstanceId = instance.id,
                libraryStage = LibraryStage.EXPORT,
                modpackExport = ExportModpackUiState(
                    instanceId = instance.id,
                    name = instance.name,
                    author = author,
                    version = "1.0",
                    isLoadingPlan = true,
                ),
            )
        }
        loadPlan(instance.id)
    }

    private fun loadPlan(instanceId: String) {
        model.scope.launch {
            try {
                val plan = model.core.rpc(
                    "modpack.export.plan",
                    buildJsonObject { put("instanceId", instanceId) },
                ).decode<CoreExportPlan>()
                model.setState { state ->
                    if (state.modpackExport.instanceId != instanceId) return@setState state
                    state.copy(
                        modpackExport = state.modpackExport.copy(
                            sections = plan.sections,
                            selected = plan.sections
                                .filter { section -> section.default && section.files > 0 }
                                .map { section -> section.id }
                                .toSet(),
                            isLoadingPlan = false,
                            error = null,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState { state ->
                    state.copy(
                        modpackExport = state.modpackExport.copy(
                            isLoadingPlan = false,
                            error = model.errorMessage(error),
                        ),
                    )
                }
            }
        }
    }

    fun setExportName(name: String) {
        model.setState { it.copy(modpackExport = it.modpackExport.copy(name = name)) }
    }

    fun setExportAuthor(author: String) {
        model.setState { it.copy(modpackExport = it.modpackExport.copy(author = author)) }
    }

    fun setExportVersion(version: String) {
        model.setState { it.copy(modpackExport = it.modpackExport.copy(version = version)) }
    }

    fun setExportDescription(description: String) {
        model.setState {
            it.copy(modpackExport = it.modpackExport.copy(description = description))
        }
    }

    fun toggleExportSection(id: String) {
        model.setState { state ->
            val export = state.modpackExport
            val section = export.sections.firstOrNull { it.id == id } ?: return@setState state
            if (section.files == 0) return@setState state
            val selected = if (id in export.selected) {
                export.selected - id
            } else {
                export.selected + id
            }
            state.copy(modpackExport = export.copy(selected = selected))
        }
    }

    /** "继续" on the metadata step; validation keeps an empty name out. */
    fun nextExportStep() {
        val export = model.state.value.modpackExport
        if (!export.canContinue) return
        model.setState {
            it.copy(
                modpackExport = it.modpackExport.copy(
                    step = ExportStep.CONTENT,
                    name = it.modpackExport.name.trim(),
                    author = it.modpackExport.author.trim(),
                    version = it.modpackExport.version.trim().ifBlank { "1.0" },
                    error = null,
                ),
            )
        }
    }

    /** Header / "上一步": content step returns to the form, the form closes. */
    fun backExportStep() {
        val export = model.state.value.modpackExport
        if (export.step == ExportStep.CONTENT) {
            model.setState {
                it.copy(modpackExport = it.modpackExport.copy(step = ExportStep.FORM))
            }
        } else {
            cancelExport()
        }
    }

    fun cancelExport() {
        model.setState {
            it.copy(
                modpackExport = ExportModpackUiState(),
                libraryStage = if (it.libraryDetailId != null) {
                    LibraryStage.SETTINGS
                } else {
                    LibraryStage.LIST
                },
            )
        }
    }

    fun dismissExportResult() {
        cancelExport()
    }

    fun copyExportPath() {
        val path = model.state.value.modpackExport.result?.path ?: return
        model.platform.clipboard.copy(path)
        model.showNotice("整合包路径已复制", Notice.Tone.SUCCESS)
    }

    /** Picks a save location and writes the selected sections there. */
    fun export() {
        val state = model.state.value
        val export = state.modpackExport
        val instance = state.libraryDetailInstance
        if (instance == null || !export.canExport || export.isExporting) return
        val suggested = exportFileName(export.name, export.version)
        model.scope.launch {
            model.setState {
                it.copy(modpackExport = it.modpackExport.copy(isExporting = true, error = null))
            }
            val target = try {
                model.platform.files.save("导出整合包", suggested, listOf("mrpack"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState {
                    it.copy(modpackExport = it.modpackExport.copy(isExporting = false))
                }
                model.showError("无法打开保存位置选择器", error)
                return@launch
            }
            if (target is FileSavePick.Cancelled) {
                model.setState {
                    it.copy(modpackExport = it.modpackExport.copy(isExporting = false))
                }
                return@launch
            }
            if (target is FileSavePick.Unavailable) {
                model.setState {
                    it.copy(modpackExport = it.modpackExport.copy(isExporting = false))
                }
                model.showError("无法选择保存位置", target.reason)
                return@launch
            }
            val picked = target as FileSavePick.Picked
            val outputPath = if (picked.direct) {
                ensureExtension(picked.path, "mrpack")
            } else {
                picked.path
            }
            try {
                val params = buildJsonObject {
                    put("instanceId", instance.id)
                    put("name", export.name.trim())
                    put("author", export.author.trim())
                    put("version", export.version.trim().ifBlank { "1.0" })
                    put("description", export.description)
                    putJsonArray("sections") {
                        export.selected.sorted().forEach { section -> add(kotlinx.serialization.json.JsonPrimitive(section)) }
                    }
                    if (picked.direct) put("outputPath", outputPath)
                }
                val written = model.core.rpc("modpack.export", params)
                    .decode<CoreModpackExport>()
                val result = if (picked.direct) {
                    written
                } else {
                    val stored = model.platform.files.writePicked(picked, written.path)
                    if (!stored) {
                        model.platform.files.discard(written.path)
                        model.setState {
                            it.copy(modpackExport = it.modpackExport.copy(isExporting = false))
                        }
                        model.showError("无法写入所选位置", "请检查目标文件夹是否可写，然后重试。")
                        return@launch
                    }
                    model.platform.files.discard(written.path)
                    written.copy(path = picked.displayPath)
                }
                model.setState {
                    it.copy(
                        modpackExport = it.modpackExport.copy(
                            isExporting = false,
                            result = result,
                            error = null,
                        ),
                    )
                }
                model.showNotice("整合包已导出", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState {
                    it.copy(
                        modpackExport = it.modpackExport.copy(
                            isExporting = false,
                            error = model.errorMessage(error),
                        ),
                    )
                }
                model.showError("无法导出整合包", error)
            }
        }
    }

    private fun ensureExtension(path: String, extension: String): String =
        if (path.endsWith(".$extension", ignoreCase = true)) path else "$path.$extension"

    private fun exportFileName(name: String, version: String): String {
        fun sanitize(value: String): String {
            val builder = StringBuilder()
            value.trim().forEach { character ->
                builder.append(
                    if (character.isLetterOrDigit() || character in "-_.") character else '-',
                )
            }
            var text = builder.toString()
            while ("--" in text) text = text.replace("--", "-")
            return text.trim('-', '.').ifEmpty { "pack" }
        }
        val suffix = if (version.isBlank()) "" else "-${sanitize(version)}"
        return "${sanitize(name)}$suffix.mrpack"
    }

    fun onTaskFinished(taskId: String) {
        if (model.state.value.modpack.taskId != taskId) return
        model.scope.launch { model.refreshCoreData() }
    }
}