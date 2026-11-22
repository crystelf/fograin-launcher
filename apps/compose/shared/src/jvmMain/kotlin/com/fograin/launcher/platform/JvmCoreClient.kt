package com.fograin.launcher.platform

import com.fograin.launcher.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * JNI-backed [CoreClient], shared by Android and desktop.
 *
 * Every call crosses `bindings/fg-ffi-jni` and returns the same JSON envelope
 * as the C ABI. Calls block, so RPC runs on [Dispatchers.IO]; the settings
 * snapshot is read once during construction (before the first Compose frame).
 */
internal class JvmCoreClient : CoreClient {
    override val initError: String?
    override val abiVersion: Int
    override val isAvailable: Boolean

    @Volatile
    private var currentSettings: CoreSettings

    override val settings: CoreSettings
        get() = currentSettings

    init {
        var failure: String? = null
        var snapshot = CoreSettings.fallback(
            dataRoot = AppRuntime.dataRoot.orEmpty(),
            platform = runCatching { platformServices().platformName }.getOrDefault("unknown"),
        )
        var abi = 0
        var available = false

        try {
            ensureNativeLibraryLoaded()

            AppRuntime.dataRoot?.takeIf { it.isNotBlank() }?.let { root ->
                val raw = CoreNative.nativeSetDataRoot(root)
                    ?: throw IllegalStateException("fg_set_data_root returned null")
                val response = raw.asObject()
                if (response.booleanOrNull("ok") != true) {
                    // The service may already own another root (e.g. the shell
                    // was recreated). Keep going; the snapshot still works.
                    failure = response.errorPayload()?.userMessage ?: "无法设置数据目录"
                }
            }

            val raw = CoreNative.nativeSettingsSnapshot()
                ?: throw IllegalStateException("fg_settings_snapshot returned null")
            val response = raw.asObject()
            snapshot = if (response.booleanOrNull("ok") == false) {
                throw CoreException(response.errorPayload() ?: CoreErrorPayload())
            } else {
                response.decode()
            }
            abi = CoreNative.nativeAbiVersion()
            available = true
            snapshot.configError?.let { error ->
                failure = "读取 launcher.toml 失败，已使用默认外观。${error.localizedDescription}"
            }
        } catch (t: Throwable) {
            failure = t.message ?: t::class.simpleName
            JvmLogger.error("Core", "initialization failed", t)
        }

        initError = failure
        currentSettings = snapshot
        abiVersion = abi
        isAvailable = available
    }

    override suspend fun rpc(method: String, params: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            if (!isAvailable) {
                throw CoreException("internal", "error.core.unavailable", initError)
            }
            val request = buildJsonObject {
                put("method", method)
                put("params", params)
            }
            val raw = try {
                CoreNative.nativeRpcCall(request.toString())
            } catch (error: Throwable) {
                throw CoreException("internal", "error.core.unavailable", error.message)
            } ?: throw CoreException("internal", "error.core.unavailable", "core returned NULL")

            val response = try {
                raw.asObject()
            } catch (error: Throwable) {
                throw CoreException("internal", "error.rpc.parse", error.message)
            }
            if (response.booleanOrNull("ok") != true) {
                throw CoreException(response.errorPayload() ?: CoreErrorPayload())
            }
            response
        }

    override suspend fun setTheme(theme: ThemeMode): CoreSettings = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            throw CoreException("internal", "error.core.unavailable", initError)
        }
        val raw = try {
            CoreNative.nativeSetTheme(theme.wireValue)
        } catch (error: Throwable) {
            throw CoreException("internal", "error.core.unavailable", error.message)
        } ?: throw CoreException("internal", "error.settings.write", "core returned NULL")

        val response = raw.asObject()
        if (response.booleanOrNull("ok") != true) {
            throw CoreException(response.errorPayload() ?: CoreErrorPayload())
        }
        val snapshot = response["snapshot"]?.jsonObject?.decode<CoreSettings>()
            ?: throw CoreException("internal", "error.settings.write", "missing snapshot")
        currentSettings = snapshot
        snapshot
    }

    override fun nextEvents(): List<JsonObject> {
        if (!isAvailable) return emptyList()
        val raw = try {
            CoreNative.nativeEventsNext()
        } catch (error: Throwable) {
            JvmLogger.warn("Core", "events_next failed: ${error.message}", error)
            return emptyList()
        } ?: return emptyList()
        return try {
            when (val element = CoreJson.parseToJsonElement(raw)) {
                is JsonArray -> element.mapNotNull { it as? JsonObject }
                is JsonObject -> {
                    JvmLogger.warn("Core", "events_next error: ${element.errorPayload()?.localizedDescription}", null)
                    emptyList()
                }

                else -> emptyList()
            }
        } catch (error: Throwable) {
            JvmLogger.warn("Core", "malformed event payload: $raw", null)
            emptyList()
        }
    }
}

/** Parses a native string into a JSON object, mapping failures to [CoreException]. */
private fun String.asObject(): JsonObject = try {
    CoreJson.parseToJsonElement(this).jsonObject
} catch (error: Throwable) {
    throw CoreException("internal", "error.rpc.parse", error.message)
}

/** Extracts the `error` payload from a failed envelope, if present. */
internal fun JsonObject.errorPayload(): CoreErrorPayload? =
    this["error"]?.let { element ->
        try {
            element.jsonObject.decode<CoreErrorPayload>()
        } catch (_: Throwable) {
            null
        }
    }

private val sharedClient: CoreClient by lazy { JvmCoreClient() }

/** Called by the Android/desktop platform service factories. */
internal fun createJvmCoreClient(): CoreClient = sharedClient
