package com.fograin.launcher.platform

import android.content.Context
import android.util.Log
import com.fograin.launcher.settings.ThemeMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Error payload used by every Rust FFI call (§3.1 of docs/design.md). */
data class CoreErrorPayload(
    val kind: String,
    val messageKey: String,
    val detail: String?,
) {
    /** Technical description for logs and diagnostics. */
    val localizedDescription: String
        get() = detail ?: messageKey

    /**
     * User-facing Chinese copy for the common core errors. Unknown keys fall
     * back to the technical detail so diagnostics are never lost.
     */
    val userMessage: String
        get() = when (messageKey) {
            "error.launch.not_installed" -> "游戏资源尚未安装完成，请先在下载页补全资源。"
            "error.launch.incomplete" -> "游戏文件不完整，上次下载可能没有完成。请重新下载缺失文件。"
            "error.launch.arm64_natives" -> "这个版本只有 x86_64 原生库，无法在当前设备上启动。"
            "error.java.none_installed" -> "未发现 Java 运行时。M6 接入自动下载前，请先准备 Java。"
            "error.java.required_missing" -> "没有找到匹配的 Java 版本，请安装对应版本的 Java。"
            "error.java.too_new" -> "只找到更新的 Java，旧版本游戏可能会启动失败。"
            "error.java.too_old" -> "只找到较旧的 Java，新版本游戏可能会启动失败。"
            "error.instance.duplicate" -> "同名实例已经存在，请换一个实例名称。"
            "error.instance.delete_running" -> "游戏正在运行，退出后才能删除实例。"
            "error.instance.version_mismatch" -> "这个实例的游戏版本与下载版本不一致。"
            "error.launch.already_running" -> "已经有一个游戏在运行了。"
            "error.launch.exit_code" -> "游戏异常退出。请查看日志确认原因。"
            "error.task.cancelled" -> "任务已取消。"
            "error.task.already_running" -> "已有下载任务在进行，请等待完成或取消。"
            "error.network" -> "网络请求失败，请检查网络或代理设置。"
            "error.install.failed" -> "安装没有完成，请重试。"
            "error.instance.invalid_id" -> "实例名称无法转换为安全的目录名。"
            "error.core.unavailable" -> "无法连接启动器核心。请确认应用已正确安装原生库。"
            "error.data_root.already_set", "error.data_root.service_started" ->
                "启动器核心已经启动，无法再切换数据目录。请重启应用。"
            "error.rpc.unknown_method", "error.rpc.parse", "error.rpc.params" ->
                "启动器核心拒绝了这次请求。"
            else -> detail ?: messageKey
        }

    companion object {
        fun from(json: JSONObject): CoreErrorPayload = CoreErrorPayload(
            kind = json.optString("kind", "internal"),
            messageKey = json.optString("messageKey", "error.internal"),
            detail = json.optionalString("detail"),
        )
    }
}

/** Thrown when the core returns `{ ok: false, error }`. */
class CoreException(val payload: CoreErrorPayload) : Exception(payload.userMessage) {
    constructor(kind: String, messageKey: String, detail: String? = null) :
        this(CoreErrorPayload(kind, messageKey, detail))
}

/** Snapshot of the settings the shell needs before it renders its first frame. */
data class CoreSettingsSnapshot(
    val abiVersion: Int,
    val appVersion: String,
    val platform: String,
    val theme: ThemeMode,
    val dataRoot: String,
    val configPath: String,
    val configError: CoreErrorPayload? = null,
) {
    companion object {
        fun from(json: JSONObject): CoreSettingsSnapshot = CoreSettingsSnapshot(
            abiVersion = json.optInt("abiVersion", 0),
            appVersion = json.optString("appVersion", "0.1.0"),
            platform = json.optString("platform", "android"),
            theme = ThemeMode.fromWire(json.optString("theme", "system")),
            dataRoot = json.optString("dataRoot", ""),
            configPath = json.optString("configPath", ""),
            configError = json.optJSONObject("configError")?.let(CoreErrorPayload::from),
        )

        /** Used when the native library is missing or returns malformed JSON. */
        fun fallback(dataRoot: String): CoreSettingsSnapshot = CoreSettingsSnapshot(
            abiVersion = 0,
            appVersion = "0.1.0",
            platform = "android",
            theme = ThemeMode.SYSTEM,
            dataRoot = dataRoot,
            configPath = if (dataRoot.isBlank()) "" else "$dataRoot/launcher.toml",
        )
    }
}

/**
 * The only place the Android client talks to the Rust core.
 *
 * The JNI calls block (RPC dispatches on the core's Tokio runtime), so every
 * caller goes through [rpcAsync] on [Dispatchers.IO]. Initialization reads
 * `launcher.toml` once before the first Compose frame, mirroring the macOS
 * `CoreBridge.settingsSnapshot()` call.
 */
object CoreBridge {
    private const val TAG = "CoreBridge"
    private const val LIBRARY_NAME = "fg_ffi_jni"

    @Volatile
    private var initialized = false

    @Volatile
    private var libraryAvailable = false

    @Volatile
    var settings: CoreSettingsSnapshot = CoreSettingsSnapshot.fallback("")
        private set

    @Volatile
    var initializationError: String? = null
        private set

    val isAvailable: Boolean
        get() = libraryAvailable

    /**
     * Loads the JNI library and installs the app-private data root.
     *
     * Must run before the first RPC call; `fg_set_data_root` refuses to change
     * the root once the service exists.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val root = (context.getExternalFilesDir(null) ?: context.filesDir)
            .absolutePath
        runCatching { File(root).mkdirs() }

        libraryAvailable = try {
            CoreNative.nativeAbiVersion()
            true
        } catch (error: Throwable) {
            Log.e(TAG, "unable to load $LIBRARY_NAME", error)
            initializationError = "原生库加载失败：${error.message ?: error.javaClass.simpleName}"
            false
        }

        if (!libraryAvailable) {
            settings = CoreSettingsSnapshot.fallback(root)
            return
        }

        try {
            val rootResult = JSONObject(CoreNative.nativeSetDataRoot(root))
            if (!rootResult.optBoolean("ok")) {
                val payload = rootResult.optJSONObject("error")?.let(CoreErrorPayload::from)
                initializationError = payload?.userMessage ?: "无法设置数据目录"
                Log.w(TAG, "set data root rejected: ${payload?.localizedDescription}")
            }

            val snapshot = JSONObject(CoreNative.nativeSettingsSnapshot())
            settings = CoreSettingsSnapshot.from(snapshot)
            initializationError = snapshot.optJSONObject("configError")
                ?.let(CoreErrorPayload::from)
                ?.let { "读取 launcher.toml 失败，已使用默认外观。${it.localizedDescription}" }
        } catch (error: Throwable) {
            Log.e(TAG, "core initialization failed", error)
            initializationError = error.message ?: "核心初始化失败"
            settings = CoreSettingsSnapshot.fallback(root)
        }
    }

    /** Dispatches one JSON command; success responses merge into the envelope. */
    @Throws(CoreException::class)
    fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
        if (!libraryAvailable) {
            throw CoreException(
                kind = "internal",
                messageKey = "error.core.unavailable",
                detail = initializationError,
            )
        }

        val request = JSONObject()
            .put("method", method)
            .put("params", params)
            .toString()
        val raw = try {
            CoreNative.nativeRpcCall(request)
        } catch (error: Throwable) {
            throw CoreException("internal", "error.core.unavailable", error.message)
        }
        return parseEnvelope(raw)
    }

    /** Async variant used by page loads and install/launch actions. */
    @Throws(CoreException::class)
    suspend fun rpcAsync(method: String, params: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) { rpc(method, params) }

    /** Persists `appearance.theme` and returns the refreshed snapshot. */
    @Throws(CoreException::class)
    suspend fun setTheme(theme: ThemeMode): CoreSettingsSnapshot = withContext(Dispatchers.IO) {
        if (!libraryAvailable) {
            throw CoreException("internal", "error.core.unavailable", initializationError)
        }

        val result = try {
            JSONObject(CoreNative.nativeSetTheme(theme.wireValue))
        } catch (error: Throwable) {
            throw CoreException("internal", "error.core.unavailable", error.message)
        }

        if (!result.optBoolean("ok")) {
            throw CoreException(
                result.optJSONObject("error")?.let(CoreErrorPayload::from)
                    ?: CoreErrorPayload("internal", "error.settings.write", null),
            )
        }

        val snapshot = result.optJSONObject("snapshot")?.let(CoreSettingsSnapshot::from)
            ?: throw CoreException("internal", "error.settings.write", "missing snapshot")
        settings = snapshot
        snapshot
    }

    /** Drains pending progress/game events. Never blocks for long. */
    fun nextEvents(): List<JSONObject> {
        if (!libraryAvailable) return emptyList()
        val raw = try {
            CoreNative.nativeEventsNext()
        } catch (error: Throwable) {
            Log.e(TAG, "events_next failed", error)
            return emptyList()
        }
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::add)
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "malformed event payload: $raw", error)
            emptyList()
        }
    }

    private fun parseEnvelope(raw: String?): JSONObject {
        if (raw == null) {
            throw CoreException("internal", "error.core.unavailable", "core returned NULL")
        }
        val json = try {
            JSONObject(raw)
        } catch (error: Throwable) {
            throw CoreException("internal", "error.rpc.parse", error.message)
        }
        if (!json.optBoolean("ok")) {
            throw CoreException(
                json.optJSONObject("error")?.let(CoreErrorPayload::from)
                    ?: CoreErrorPayload("internal", "error.rpc.parse", raw),
            )
        }
        return json
    }
}

/** Reads a string field, treating JSON `null` and missing keys as absent. */
internal fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotEmpty() }
}

/** Reads an int field that may arrive as `null` from the Rust side. */
internal fun JSONObject.optionalInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}

/** Reads a long field that may arrive as `null` from the Rust side. */
internal fun JSONObject.optionalLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return optLong(name)
}

/** Reads an array of ints, e.g. `resolution: [1920, 1080]`. */
internal fun JSONObject.optionalIntArray(name: String): List<Int>? {
    if (!has(name) || isNull(name)) return null
    val array = optJSONArray(name) ?: return null
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            add(array.optInt(index))
        }
    }
}
