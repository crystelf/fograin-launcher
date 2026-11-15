package com.fograin.launcher.app

import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.CoreEvent
import com.fograin.launcher.platform.booleanOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One game session at a time: launch, stop, and the `game.*` events that keep
 * [AppUiState.sessionRunning] honest.
 *
 * `install.start` for an incomplete instance is delegated to
 * [DownloadsActions.beginCompletion] so the download bookkeeping stays in one
 * place.
 */
internal class LaunchActions(private val model: AppModel) {

    private var stopRequested = false

    fun start(instance: Instance) {
        model.setState { it.copy(currentInstanceId = instance.id) }
        if (!instance.isInstalled) {
            if (model.downloads.isDownloading(instance)) {
                model.showNotice("“${instance.name}”的资源仍在下载，请稍候再启动")
            } else {
                model.downloads.beginCompletion(instance)
            }
            return
        }
        if (model.state.value.sessionRunning) {
            model.showError("无法启动游戏", "已经有一个游戏在运行了。")
            return
        }
        if (model.state.value.isSessionLoading) return
        val accountId = model.selectedAccountId
        if (accountId == null) {
            model.showNotice("请先添加一个账户")
            model.openAccounts()
            return
        }
        launch(instance, accountId)
    }

    private fun launch(instance: Instance, accountId: String) {
        model.setState { it.copy(isSessionLoading = true) }
        model.scope.launch {
            try {
                model.core.rpc(
                    "launch.start",
                    buildJsonObject {
                        put("instanceId", instance.id)
                        put("accountId", accountId)
                    },
                )
                // Keep the spinner until `game.started` arrives.
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState { it.copy(isSessionLoading = false) }
                model.showError("无法启动“${instance.name}”", error)
            }
        }
    }

    fun stop() {
        if (!model.state.value.sessionRunning) {
            model.showNotice("没有正在运行的游戏")
            return
        }
        stopRequested = true
        model.scope.launch {
            try {
                val payload = model.core.rpc("session.stop")
                if (payload.booleanOrNull("stopped") != true) {
                    stopRequested = false
                    model.showNotice("没有正在运行的游戏")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stopRequested = false
                model.showError("无法停止游戏", error)
            }
        }
    }

    /** `game.*` events from the 250 ms pump; task events stay in [DownloadsActions]. */
    fun onEvent(event: CoreEvent) {
        when (event) {
            is CoreEvent.GameStarted -> {
                stopRequested = false
                model.setState {
                    it.copy(
                        sessionRunning = true,
                        isSessionLoading = false,
                    )
                }
                model.showNotice("游戏已启动，进程 ${event.pid ?: 0}", Notice.Tone.SUCCESS)
            }

            is CoreEvent.GameWindowReady -> Unit

            // Log lines are consumed by the core's recent-log buffer; the log
            // viewer itself is M9.
            is CoreEvent.GameLine -> Unit

            is CoreEvent.GameExited -> {
                val wasStopped = stopRequested
                stopRequested = false
                model.setState {
                    it.copy(
                        sessionRunning = false,
                        isSessionLoading = false,
                    )
                }
                if (wasStopped) {
                    model.showNotice("游戏已停止")
                } else if (event.exitCode == 0) {
                    val minutes = (event.playSeconds / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    model.showNotice(
                        "游戏已退出（${InstanceFormat.playTime(minutes)}）",
                        Notice.Tone.SUCCESS,
                    )
                } else {
                    model.showError(
                        "游戏异常退出",
                        "游戏进程以代码 ${event.exitCode ?: "signal"} 结束。",
                        "请查看实例目录 logs/ 下的最新日志。",
                    )
                }
                model.scope.launch { model.refreshCoreData() }
            }

            else -> Unit
        }
    }
}
