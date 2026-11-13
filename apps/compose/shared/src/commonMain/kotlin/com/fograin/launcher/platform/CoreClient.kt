package com.fograin.launcher.platform

import kotlinx.serialization.json.JsonObject

/** Settings snapshot returned by `fg_settings_snapshot()`. */
data class CoreSettings(
    val abiVersion: Int,
    val appVersion: String,
    val platform: String,
    val theme: String,
    val dataRoot: String,
    val configPath: String,
)

/**
 * The UI-facing view of the Rust core.
 *
 * `commonMain` only sees this interface; the JNI implementation lives in
 * `jvmMain` so Android and desktop share it, and each platform supplies nothing
 * more than a factory actual.
 */
interface CoreClient {
    /** Non-null when the native library or the settings snapshot failed. */
    val initError: String?

    val settings: CoreSettings?

    val abiVersion: Int

    /** `app.snapshot`: platform, version, data root and counters. */
    suspend fun appSnapshot(): JsonObject
}

expect fun createCoreClient(): CoreClient

/**
 * Process-wide values the platform shell must provide before the first core
 * call. Android sets [dataRoot] to its app-private directory; desktop leaves it
 * null so the Rust core uses `dirs` (or `FOGRAIN_HOME`).
 */
object AppRuntime {
    var dataRoot: String? = null
}
