package com.fograin.launcher.platform

import org.json.JSONObject

/**
 * Event pushed by the core event queue (camelCase, `bindings/fg-ffi`).
 *
 * Mirrors `CoreEvent` in the desktop shell so both shells react to the same
 * `task.*` and `game.*` payloads.
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
        fun parse(json: JSONObject): CoreEvent? = when (json.optString("type")) {
            "task.progress" -> {
                val taskId = json.optionalString("taskId") ?: return null
                TaskProgress(
                    taskId = taskId,
                    name = json.optString("name", taskId),
                    fraction = json.optDouble("fraction", 0.0),
                    speed = json.optDouble("speed", 0.0),
                )
            }

            "task.finished" -> {
                val taskId = json.optionalString("taskId") ?: return null
                TaskFinished(
                    taskId = taskId,
                    name = json.optString("name", taskId),
                    state = json.optString("state", "succeeded"),
                    detail = json.stringArray("detail"),
                )
            }

            "game.started" -> GameStarted(
                instanceId = json.optString("instanceId", ""),
                pid = json.optionalInt("pid"),
            )

            "game.windowReady" -> GameWindowReady(json.optString("instanceId", ""))

            "game.line" -> GameLine(
                instanceId = json.optString("instanceId", ""),
                text = json.optString("text", ""),
            )

            "game.exited" -> GameExited(
                instanceId = json.optString("instanceId", ""),
                exitCode = json.optionalInt("exitCode"),
                playSeconds = json.optLong("playSeconds", 0),
            )

            else -> null
        }
    }
}
