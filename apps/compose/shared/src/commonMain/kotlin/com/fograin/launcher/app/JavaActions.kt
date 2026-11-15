package com.fograin.launcher.app

import com.fograin.launcher.platform.CoreEvent
import com.fograin.launcher.platform.CoreJavaCatalog
import com.fograin.launcher.platform.CoreJavaList
import com.fograin.launcher.platform.CoreJavaRuntime
import com.fograin.launcher.platform.decode
import com.fograin.launcher.platform.stringOrNull
import com.fograin.launcher.ui.AppDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Java runtime inventory, policy and downloads (M6).
 *
 * The settings destination owns a Java sub-page instead of a fifth top-level
 * tab: it is a launcher-maintenance surface, and keeping it inside Settings
 * means the bottom bar keeps its four M4 destinations.
 */
internal class JavaActions(private val model: AppModel) {

    private var generation = 0
    private var loading = false

    fun open() {
        model.setState {
            it.copy(
                destination = AppDestination.SETTINGS,
                settingsStage = SettingsStage.JAVA,
            )
        }
        refresh()
    }

    fun close() {
        model.setState { it.copy(settingsStage = SettingsStage.MAIN) }
    }

    fun refresh() {
        model.scope.launch { load(includeCatalog = true) }
    }

    /** Loads the installed inventory; the catalog is optional and network-bound. */
    suspend fun load(includeCatalog: Boolean) {
        if (loading) return
        loading = true
        generation += 1
        val current = generation
        model.setState {
            it.copy(
                java = it.java.copy(isLoading = true, error = null),
            )
        }
        try {
            val listed = model.core.rpc("java.list").decode<CoreJavaList>()
            if (current != generation) return
            model.setState {
                it.copy(
                    java = it.java.copy(
                        runtimes = listed.runtimes,
                        autoDownload = listed.autoDownload,
                        supported = listed.supported,
                        hostArch = listed.hostArch,
                        error = null,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (current != generation) return
            model.setState {
                it.copy(
                    java = it.java.copy(
                        isLoading = false,
                        error = model.errorMessage(error),
                    ),
                )
            }
            loading = false
            return
        }

        if (includeCatalog) {
            try {
                val catalog = model.core.rpc("java.catalog").decode<CoreJavaCatalog>()
                if (current != generation) return
                model.setState {
                    it.copy(
                        java = it.java.copy(
                            catalog = catalog.versions,
                            catalogError = null,
                            supported = catalog.supported,
                            hostArch = catalog.hostArch,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (current != generation) return
                model.setState {
                    it.copy(
                        java = it.java.copy(
                            catalogError = model.errorMessage(error),
                        ),
                    )
                }
            }
        }

        if (current == generation) {
            model.setState { it.copy(java = it.java.copy(isLoading = false)) }
        }
        loading = false
    }

    /** Fast inventory read used by the instance settings Java picker. */
    suspend fun runtimes(): List<CoreJavaRuntime> =
        model.core.rpc("java.list").decode<CoreJavaList>().runtimes

    fun setAutoDownload(enabled: Boolean) {
        val previous = model.state.value.java.autoDownload
        if (previous == enabled) return
        model.setState { it.copy(java = it.java.copy(autoDownload = enabled)) }
        model.scope.launch {
            try {
                model.core.rpc(
                    "java.policy.set",
                    buildJsonObject { put("autoDownload", enabled) },
                )
                model.showNotice(
                    if (enabled) "缺失的 Java 会自动下载" else "已关闭 Java 自动下载",
                    Notice.Tone.SUCCESS,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState { it.copy(java = it.java.copy(autoDownload = previous)) }
                model.showError("无法保存 Java 设置", error)
            }
        }
    }

    fun install(major: Int) {
        val current = model.state.value.java
        if (current.installTaskId != null) {
            model.showNotice("Java ${current.installingMajor ?: major} 正在下载，请稍候")
            return
        }
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "java.install",
                    buildJsonObject { put("major", major) },
                )
                val taskId = payload.stringOrNull("taskId")
                if (taskId.isNullOrBlank()) {
                    model.showError("无法开始 Java 下载", "启动器核心没有返回任务 id。")
                    return@launch
                }
                model.setState {
                    it.copy(
                        java = it.java.copy(
                            installTaskId = taskId,
                            installingMajor = major,
                            catalogError = null,
                        ),
                    )
                }
                model.showNotice("开始下载 Java $major", Notice.Tone.INFO)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法下载 Java $major", error)
            }
        }
    }

    fun remove(id: String) {
        model.scope.launch {
            try {
                model.core.rpc(
                    "java.remove",
                    buildJsonObject { put("id", id) },
                )
                model.showNotice("已删除 Java 运行时", Notice.Tone.SUCCESS)
                load(includeCatalog = true)
                model.refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法删除 Java 运行时", error)
            }
        }
    }

    /** Called from the event pump for every `task.finished`. */
    fun onTaskFinished(event: CoreEvent.TaskFinished) {
        val owned = event.taskId == model.state.value.java.installTaskId
        if (!owned && !event.name.startsWith("java:")) return

        model.setState {
            it.copy(
                java = it.java.copy(
                    installTaskId = null,
                    installingMajor = null,
                ),
            )
        }
        when (event.state) {
            "failed" -> {
                val detail = event.detail.firstOrNull() ?: "未知原因"
                model.showError(
                    "Java 运行时下载失败",
                    detail,
                    event.detail.drop(1).joinToString("\n").ifBlank { null },
                )
            }

            "cancelled" -> model.showNotice("已取消 Java 下载")
            "succeeded" -> model.showNotice("Java 运行时安装完成", Notice.Tone.SUCCESS)
        }
        model.scope.launch {
            load(includeCatalog = true)
            model.refreshCoreData()
        }
    }
}
