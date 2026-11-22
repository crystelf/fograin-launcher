package com.fograin.launcher.model

/**
 * Lifecycle of a queued task, as reported by `tasks.list` and the
 * `task.finished` event.
 *
 * The wire value is what the Rust core sends; [UNKNOWN] keeps a response from
 * a newer core renderable instead of crashing the queue.
 */
enum class TaskState(val wire: String) {
    RUNNING("running"),
    PAUSED("paused"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    UNKNOWN("");

    /** True while the task still occupies a queue slot. */
    val isActive: Boolean
        get() = this == RUNNING || this == PAUSED

    /** True once the task will not resume; the controls become read-only. */
    val isFinished: Boolean
        get() = !isActive

    companion object {
        fun fromWire(value: String?): TaskState =
            entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}
