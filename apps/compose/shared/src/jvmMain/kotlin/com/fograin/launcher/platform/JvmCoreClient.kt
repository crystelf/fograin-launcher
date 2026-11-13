package com.fograin.launcher.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JNI-backed [CoreClient], shared by Android and desktop.
 *
 * Every call crosses `bindings/fg-ffi-jni` and returns the same JSON envelope
 * as the C ABI. Calls block, so RPC runs on [Dispatchers.IO]; the settings
 * snapshot is read once during construction (before the first Compose frame).
 */
internal class JvmCoreClient : CoreClient {
    override val initError: String?
    override val settings: CoreSettings?
    override val abiVersion: Int

    init {
        var failure: String? = null
        var parsed: CoreSettings? = null
        var abi = 0

        try {
            ensureNativeLibraryLoaded()
            AppRuntime.dataRoot?.let { CoreNative.nativeSetDataRoot(it) }
            val raw = CoreNative.nativeSettingsSnapshot()
                ?: throw IllegalStateException("fg_settings_snapshot returned null")
            parsed = parseSettings(json.parseToJsonElement(raw).jsonObject)
            abi = CoreNative.nativeAbiVersion()
        } catch (t: Throwable) {
            failure = t.message ?: t::class.simpleName
        }

        initError = failure
        settings = parsed
        abiVersion = abi
    }

    override suspend fun appSnapshot(): JsonObject = withContext(Dispatchers.IO) {
        val raw = CoreNative.nativeRpcCall("""{"method":"app.snapshot","params":{}}""")
            ?: throw IllegalStateException("fg_rpc_call returned null")
        val root = json.parseToJsonElement(raw).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            val failure = root["error"]?.jsonObject
            val key = failure?.get("messageKey")?.jsonPrimitive?.contentOrNull ?: "error.rpc"
            val detail = failure?.get("detail")?.jsonPrimitive?.contentOrNull
            throw IllegalStateException(if (detail.isNullOrBlank()) key else "$key: $detail")
        }
        // Object results are merged into the envelope ({"ok":true, ...fields});
        // array/scalar results are nested under "result".
        (root["result"] ?: root) as? JsonObject
            ?: throw IllegalStateException("app.snapshot did not return an object")
    }

    private fun parseSettings(root: JsonObject): CoreSettings = CoreSettings(
        abiVersion = root["abiVersion"]?.jsonPrimitive?.intOrNull ?: 0,
        appVersion = root["appVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        platform = root["platform"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        theme = root["theme"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        dataRoot = root["dataRoot"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        configPath = root["configPath"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

private val sharedClient: CoreClient by lazy { JvmCoreClient() }

/** Called by the Android/desktop actual factories. */
internal fun createJvmCoreClient(): CoreClient = sharedClient
