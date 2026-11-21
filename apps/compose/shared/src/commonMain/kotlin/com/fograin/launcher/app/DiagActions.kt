package com.fograin.launcher.app

import com.fograin.launcher.model.Instance
import com.fograin.launcher.platform.CoreDiagExport
import com.fograin.launcher.platform.CoreDiagLogs
import com.fograin.launcher.platform.CoreDiagnostics
import com.fograin.launcher.platform.CoreFinding
import com.fograin.launcher.platform.FileSavePick
import com.fograin.launcher.platform.decode
import com.fograin.launcher.ui.AppDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Crash analysis page (M9): log sources, ranked findings and the support
 * bundle. Analysis is local, so opening the page never touches the network.
 */
internal class DiagActions(private val model: AppModel) {
    private var generation = 0

    /** Opens the diagnostics page for [instance] and loads its logs. */
    fun open(instance: Instance) {
        model.setState {
            it.copy(
                destination = AppDestination.LIBRARY,
                libraryStage = LibraryStage.DIAGNOSTICS,
                libraryDetailId = instance.id,
                currentInstanceId = instance.id,
                diag = DiagUiState(
                    instanceId = instance.id,
                    isLoading = true,
                ),
            )
        }
        load(instance.id)
    }

    fun refresh() {
        val instanceId = model.state.value.diag.instanceId ?: return
        load(instanceId)
    }

    fun selectTab(tab: DiagTab) {
        model.setState { it.copy(diag = it.diag.copy(tab = tab)) }
    }

    private fun load(instanceId: String) {
        generation += 1
        val current = generation
        model.setState {
            if (it.diag.instanceId == instanceId) {
                it.copy(diag = it.diag.copy(isLoading = true, error = null))
            } else {
                it
            }
        }
        model.scope.launch {
            try {
                val logs = model.core.rpc(
                    "diag.logs",
                    buildJsonObject { put("instanceId", instanceId) },
                ).decode<CoreDiagLogs>()
                val analysis = model.core.rpc(
                    "diag.analyze",
                    buildJsonObject { put("instanceId", instanceId) },
                ).decode<CoreDiagnostics>()
                if (current != generation) return@launch
                model.setState {
                    val next = it.diag.copy(
                        logs = logs,
                        analysis = analysis,
                        isLoading = false,
                        error = null,
                    )
                    it.copy(diag = next.copy(tab = next.activeTab))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (current != generation) return@launch
                model.setState {
                    it.copy(
                        diag = it.diag.copy(
                            isLoading = false,
                            error = model.errorMessage(error),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Runs the analysis after a non-zero game exit so the alert can name the
     * likely cause and the diagnostics page opens pre-filled.
     */
    suspend fun prefillCrash(instanceId: String): CoreDiagnostics? = try {
        val analysis = model.core.rpc(
            "diag.analyze",
            buildJsonObject { put("instanceId", instanceId) },
        ).decode<CoreDiagnostics>()
        model.setState {
            it.copy(diag = DiagUiState(instanceId = instanceId, analysis = analysis))
        }
        analysis
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    fun export() {
        val state = model.state.value
        val instanceId = state.diag.instanceId ?: return
        if (state.diag.isExporting) return
        val instanceName = state.libraryDetailInstance?.name.orEmpty()
        val suggested = buildString {
            append(
                instanceName.takeIf { it.isNotBlank() }
                    ?.map { if (it.isLetterOrDigit() || it in "-_.") it else '-' }
                    ?.joinToString("")
                    ?.trim('-', '.')
                    ?.ifBlank { null }
                    ?: "fograin",
            )
            append("-diagnostics.zip")
        }
        model.setState {
            it.copy(diag = it.diag.copy(isExporting = true, error = null))
        }
        model.scope.launch {
            val target = try {
                model.platform.files.save("导出诊断包", suggested, listOf("zip"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState { it.copy(diag = it.diag.copy(isExporting = false)) }
                model.showError("无法打开保存位置选择器", error)
                return@launch
            }
            if (target is FileSavePick.Cancelled) {
                model.setState { it.copy(diag = it.diag.copy(isExporting = false)) }
                return@launch
            }
            if (target is FileSavePick.Unavailable) {
                model.setState { it.copy(diag = it.diag.copy(isExporting = false)) }
                model.showError("无法选择保存位置", target.reason)
                return@launch
            }
            val picked = target as FileSavePick.Picked
            val destination = if (picked.direct) {
                picked.copy(
                    path = ensureExtension(picked.path, "zip"),
                    displayPath = ensureExtension(picked.displayPath, "zip"),
                )
            } else {
                picked
            }
            try {
                val exported = model.core.rpc(
                    "diag.export",
                    buildJsonObject { put("instanceId", instanceId) },
                ).decode<CoreDiagExport>()
                // The core writes the bundle into `diagnostics/`; store the
                // same bytes at the location the user picked and drop the
                // intermediate copy.
                val sameFile = destination.path == exported.bundle.path
                if (!sameFile &&
                    !model.platform.files.writePicked(destination, exported.bundle.path)
                ) {
                    model.setState { it.copy(diag = it.diag.copy(isExporting = false)) }
                    model.showError("无法写入所选位置", "请检查目标文件夹是否可写，然后重试。")
                    return@launch
                }
                if (!sameFile) model.platform.files.discard(exported.bundle.path)
                val finalPath = destination.displayPath
                model.setState {
                    it.copy(
                        diag = it.diag.copy(
                            isExporting = false,
                            bundlePath = finalPath,
                            analysis = exported.analysis,
                        ),
                    )
                }
                model.showNotice("已导出诊断包", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState {
                    it.copy(diag = it.diag.copy(isExporting = false))
                }
                model.showError("无法导出诊断包", error)
            }
        }
    }

    private fun ensureExtension(path: String, extension: String): String =
        if (path.endsWith(".$extension", ignoreCase = true)) path else "$path.$extension"

    fun copyBundlePath() {
        val path = model.state.value.diag.bundlePath ?: return
        model.platform.clipboard.copy(path)
        model.showNotice("诊断包路径已复制", Notice.Tone.SUCCESS)
    }

    /**
     * Runs the repair suggested by a finding. The actions are deliberately
     * small: open the page that fixes it, or explain the manual step.
     */
    fun applyFix(finding: CoreFinding) {
        when (finding.fix) {
            "installJava", "openJavaSettings" -> {
                model.setState { it.copy(libraryStage = LibraryStage.LIST) }
                model.openJavaSettings()
            }

            "openContent" -> {
                val instanceId = model.state.value.diag.instanceId ?: return
                model.setState {
                    it.copy(
                        libraryStage = LibraryStage.DETAIL,
                        libraryDetailId = instanceId,
                        destination = AppDestination.LIBRARY,
                    )
                }
                model.showInstanceContent(ContentManagerTab.MODS)
            }

            "disableMod" -> {
                val instanceId = model.state.value.diag.instanceId ?: return
                model.setState {
                    it.copy(
                        libraryStage = LibraryStage.DETAIL,
                        libraryDetailId = instanceId,
                        destination = AppDestination.LIBRARY,
                    )
                }
                model.showInstanceContent(ContentManagerTab.MODS)
                model.showNotice("在模组列表里逐个禁用最近加入的模组")
            }

            "openGraphics" -> model.showError(
                "显卡与 OpenGL",
                "请更新显卡驱动后重试；远程桌面、部分虚拟机和旧显卡不提供游戏所需的 OpenGL 版本。",
                "如果使用 Apple 芯片，请确认没有强制用 x86_64 Java 启动；旧版本可尝试 -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true。",
            )
            "openLogs" -> {
                val tab = if (model.state.value.diag.logs?.texts?.crash.isNullOrBlank()) {
                    DiagTab.LOG
                } else {
                    DiagTab.CRASH
                }
                selectTab(tab)
            }

            else -> model.showNotice("已记录这条建议")
        }
    }
}