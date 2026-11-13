package com.fograin.launcher.platform

import kotlinx.serialization.json.JsonObject

/**
 * Event pushed by the core event queue (camelCase, `bindings/fg-ffi`).
 *
 * Keeps the `task.*` and `game.*` payloads identical across platforms.
 */
sealed interface CoreEvent {
    data class TaskProgress(
        val taskId: String,
        val name: String,
        val fraction: Double,
        val speed: Double,
    ) : CoreEvent

    data class TaskFinished(
        val taskId: String,
        val name: String,
        val state: String,
        val detail: List<String>,
    ) : CoreEvent

    data class GameStarted(val instanceId: String, val pid: Int?) : CoreEvent

    data class GameWindowReady(val instanceId: String) : CoreEvent

    data class GameLine(val instanceId: String, val text: String) : CoreEvent

    data class GameExited(
        val instanceId: String,
        val exitCode: Int?,
        val playSeconds: Long,
    ) : CoreEvent

    companion object {
        fun parse(json: JsonObject): CoreEvent? = when (json.stringOrNull("type")) {
            "task.progress" -> {
                val taskId = json.stringOrNull("taskId") ?: return null
                TaskProgress(
                    taskId = taskId,
                    name = json.stringOrNull("name") ?: taskId,
                    fraction = json.doubleOrNull("fraction") ?: 0.0,
                    speed = json.doubleOrNull("speed") ?: 0.0,
                )
            }

            "task.finished" -> {
                val taskId = json.stringOrNull("taskId") ?: return null
                TaskFinished(
                    taskId = taskId,
                    name = json.stringOrNull("name") ?: taskId,
                    state = json.stringOrNull("state") ?: "succeeded",
                    detail = json.stringRows("detail"),
                )
            }

            "game.started" -> GameStarted(
                instanceId = json.stringOrNull("instanceId").orEmpty(),
                pid = json.intOrNull("pid"),
            )

            "game.windowReady" -> GameWindowReady(json.stringOrNull("instanceId").orEmpty())

            "game.line" -> GameLine(
                instanceId = json.stringOrNull("instanceId").orEmpty(),
                text = json.stringOrNull("text").orEmpty(),
            )

            "game.exited" -> GameExited(
                instanceId = json.stringOrNull("instanceId").orEmpty(),
                exitCode = json.intOrNull("exitCode"),
                playSeconds = json.longOrNull("playSeconds") ?: 0,
            )

            else -> null
        }
    }
}
