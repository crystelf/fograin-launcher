package com.fograin.launcher.platform

import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.settings.ThemeModeSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer

/**
 * Shared JSON configuration for every `fg-ffi` payload.
 *
 * `ignoreUnknownKeys` keeps the UI compatible with newer cores that add
 * fields; `coerceInputValues` turns explicit `null` on a non-null field into
 * its default. Every wire type below is a `kotlinx.serialization` model; the
 * shared client has no `org.json` dependency.
 */
val CoreJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** Settings snapshot returned by `fg_settings_snapshot()`. */
@Serializable
data class CoreSettings(
    val abiVersion: Int = 0,
    val appVersion: String = "0.1.0",
    val platform: String = "unknown",
    @Serializable(with = ThemeModeSerializer::class)
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dataRoot: String = "",
    val configPath: String = "",
    val configError: CoreErrorPayload? = null,
) {
    companion object {
        /** Used when the native library is missing or returns malformed JSON. */
        fun fallback(dataRoot: String, platform: String = "unknown"): CoreSettings = CoreSettings(
            abiVersion = 0,
            appVersion = "0.1.0",
            platform = platform,
            theme = ThemeMode.SYSTEM,
            dataRoot = dataRoot,
            configPath = if (dataRoot.isBlank()) "" else "$dataRoot/launcher.toml",
        )
    }
}

/** Error payload used by every Rust FFI call (§3.1 of docs/design.md). */
@Serializable
data class CoreErrorPayload(
    val kind: String = "internal",
    val messageKey: String = "error.internal",
    val detail: String? = null,
) {
    /** Technical description for logs and diagnostics. */
    val localizedDescription: String
        get() = detail ?: messageKey

    /**
     * User-facing Chinese copy for the common core errors. Unknown keys fall
     * back to the technical detail so diagnostics are never lost. The strings
     * move to compose resources with the M10 i18n pass.
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
}

/** Thrown when the core returns `{ ok: false, error }`. */
class CoreException(val payload: CoreErrorPayload) : Exception(payload.userMessage) {
    constructor(kind: String, messageKey: String, detail: String? = null) :
        this(CoreErrorPayload(kind, messageKey, detail))
}

/** One row of `instances.list` / `instances.summary`. */
@Serializable
data class CoreInstanceRow(
    val id: String,
    val name: String = id,
    val minecraft: String = "",
    @SerialName("loaderKind")
    val loaderKind: String = "vanilla",
    @SerialName("loaderVersion")
    val loaderVersion: String = "",
    val favorite: Boolean = false,
    val installed: Boolean = false,
    val modCount: Int? = null,
    val sizeBytes: Long = 0,
    val playSeconds: Long = 0,
    val lastPlayedMs: Long? = null,
)

/** `[game]` section as the core serializes it. */
@Serializable
data class CoreGameConfig(
    val username: String = "Steve",
    val memoryMb: Int = 0,
    val java: String? = null,
    val jvmArgs: String = "",
    val resolution: List<Int>? = null,
    val fullscreen: Boolean = false,
    val showLog: Boolean = false,
    val autoCompleteResources: Boolean = true,
)

/** Aggregate returned by `app.snapshot`. */
@Serializable
data class CoreAppSnapshot(
    val platform: String = "unknown",
    val appVersion: String = "0.1.0",
    val dataRoot: String = "",
    val javaCount: Int = 0,
    val activeTasks: Int = 0,
    val diskFreeBytes: Long = 0,
    val totalMemoryBytes: Long = 0,
    val instanceCount: Int = 0,
)

/** One row of `tasks.list`. */
@Serializable
data class CoreTaskRow(
    val id: String,
    val name: String = id,
    val state: String = "running",
    val fraction: Double = 0.0,
    val speed: Double = 0.0,
)

/** One entry of `versions.list`. */
@Serializable
data class CoreVersionEntry(
    val id: String,
    val kind: String = "unknown",
    val released: String? = null,
)

/** Download preview from `version.plan`. */
@Serializable
data class CorePlanPreview(
    val versionId: String,
    val baseId: String = versionId,
    val javaMajor: Int = 21,
    val files: Int = 0,
    val totalBytes: Long = 0,
)

/** Per-instance overrides; `null` means "inherit the global `[game]` value". */
@Serializable
data class CoreInstanceOverrides(
    val java: String? = null,
    val memoryMb: Int? = null,
    val jvmArgs: String? = null,
    val resolution: List<Int>? = null,
    val fullscreen: Boolean? = null,
    val gameDir: String? = null,
)

/** Response of `instances.settings.get`. */
@Serializable
data class CoreInstanceSettings(
    val effective: CoreGameConfig = CoreGameConfig(),
    val overrides: CoreInstanceOverrides = CoreInstanceOverrides(),
    val globals: CoreGameConfig = CoreGameConfig(),
)

// ---------------------------------------------------------------------------
// JsonObject helpers used by the model and RPC layer.
// ---------------------------------------------------------------------------

/** Decodes a merged RPC envelope into a wire model. */
inline fun <reified T> JsonObject.decode(): T =
    CoreJson.decodeFromJsonElement(serializer<T>(), this)

/** Decodes one array element (e.g. `instances.list` rows). */
inline fun <reified T> JsonElement.decodeElement(): T =
    CoreJson.decodeFromJsonElement(serializer<T>(), this)

/** Reads a string field, treating JSON `null` and empty strings as absent. */
fun JsonObject.stringOrNull(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.contentOrNull?.takeIf { it.isNotEmpty() }
}

/** Reads a boolean field that may arrive as `null` from the Rust side. */
fun JsonObject.booleanOrNull(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

/** Reads an int field that may arrive as `null`. */
fun JsonObject.intOrNull(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

/** Reads a long field that may arrive as `null`. */
fun JsonObject.longOrNull(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

/** Reads a floating-point field that may arrive as `null`. */
fun JsonObject.doubleOrNull(name: String): Double? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

/** Reads an object array carried under [key]. */
fun JsonObject.objectRows(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Reads the rows wrapped by the C ABI under `result` (array results only). */
fun JsonObject.resultRows(): List<JsonObject> = objectRows("result")

/** Reads a string array carried under [key]. */
fun JsonObject.stringRows(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value !is JsonNull }?.contentOrNull }
        .orEmpty()
