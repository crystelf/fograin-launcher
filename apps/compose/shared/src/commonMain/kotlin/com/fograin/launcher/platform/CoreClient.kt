package com.fograin.launcher.platform

import com.fograin.launcher.settings.ThemeMode
import kotlinx.serialization.json.JsonObject

/**
 * The UI-facing view of the Rust core.
 *
 * `commonMain` only sees this interface; the JNI implementation lives in
 * `jvmMain` so Android and desktop share it, and each platform supplies a
 * factory through [PlatformServices.coreFactory].
 *
 * Every method crosses `bindings/fg-ffi-jni` and returns the same JSON envelope
 * as the C ABI. All payloads are decoded with `kotlinx.serialization`.
 */
interface CoreClient {
    /** Non-null when the native library or the settings snapshot failed. */
    val initError: String?

    /** Settings read before the first Compose frame; never null. */
    val settings: CoreSettings

    val abiVersion: Int

    /** True once the JNI library loaded and `fg_settings_snapshot` answered. */
    val isAvailable: Boolean

    /**
     * Dispatches one JSON command. Object results are merged into the
     * envelope (`{"ok":true, ...fields}`); array/scalar results stay under
     * `result`. Throws [CoreException] for `{ok:false,error}` responses.
     */
    suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject

    /** Persists `appearance.theme` and returns the refreshed snapshot. */
    suspend fun setTheme(theme: ThemeMode): CoreSettings

    /** Drains pending progress/game events. Never blocks for long. */
    fun nextEvents(): List<JsonObject>
}

/** Creates the process-wide client through the installed platform services. */
fun createCoreClient(): CoreClient = platformServices().coreFactory.create()
