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
            "error.launch.arm64_natives" -> "这个版本只有 x86_64 原生库。请开启 Java 自动下载，或改用 x86_64 Java 8（Rosetta）。"
            "error.java.none_installed" -> "未发现 Java 运行时。请先在设置中下载，或开启自动下载。"
            "error.java.required_missing" -> "没有找到匹配的 Java 版本，请安装对应版本的 Java。"
            "error.java.runtime_unsupported" -> "当前平台没有可自动下载的官方 Java。请手动安装系统 Java。"
            "error.java.x86_64_required" -> "这个旧版本需要 x86_64 Java 8（Rosetta）。请开启自动下载或在 Java 运行时页手动下载。"
            "error.java.runtime_install" -> "Java 运行时下载没有完成，请检查网络后重试。"
            "error.java.runtime_manifest", "error.java.runtime_manifest_hash",
            "error.java.runtime_manifest_empty", "error.java.runtime_index",
            "error.java.runtime_index_empty", "error.java.runtime_incomplete",
            "error.java.runtime_plan_version", "error.java.runtime_marker_missing" ->
                "Java 运行时的官方清单无法读取或校验失败。"
            "error.java.runtime_in_use" -> "这个 Java 运行时仍在使用，退出游戏或等待下载完成后再删除。"
            "error.java.runtime_not_found" -> "这个 Java 运行时已经不存在了。"
            "error.java.runtime_id", "error.java.runtime_path", "error.java.runtime_major",
            "error.java.runtime_link", "error.java.runtime_permission" ->
                "Java 运行时参数或本地文件不正确。"
            "error.java.runtime_plan_serialize", "error.java.runtime_marker" ->
                "Java 运行时状态无法保存。"
            "error.java.runtime_remove" -> "删除 Java 运行时失败，请检查文件权限。"
            "error.java.too_new" -> "只找到更新的 Java，旧版本游戏可能会启动失败。"
            "error.java.too_old" -> "只找到较旧的 Java，新版本游戏可能会启动失败。"
            "error.instance.duplicate" -> "同名实例已经存在，请换一个实例名称。"
            "error.instance.delete_running" -> "游戏正在运行，退出后才能删除实例。"
            "error.instance.version_mismatch" -> "这个实例的游戏版本与下载版本不一致。"
            "error.launch.already_running" -> "已经有一个游戏在运行了。"
            "error.launch.exit_code" -> "游戏异常退出。请查看日志确认原因。"
            "error.task.cancelled" -> "任务已取消。"
            "error.task.failed" -> "任务执行失败，请查看详情。"
            "error.task.not_found" -> "找不到这个任务。"
            "error.task.already_running" -> "已有下载任务在进行，请等待完成或取消。"
            "error.network" -> "网络请求失败，请检查网络或代理设置。"
            "error.install.failed" -> "安装没有完成，请重试。"
            "error.loader.unknown_kind" -> "不支持的加载器类型。"
            "error.loader.unsupported_minecraft" -> "这个加载器没有适配该 Minecraft 版本。"
            "error.loader.version_invalid" -> "加载器版本格式不正确。"
            "error.loader.version_missing" -> "请选择加载器版本。"
            "error.loader.profile_unavailable" -> "无法获取加载器版本信息，请稍后重试。"
            "error.loader.profile_parse", "error.loader.install_profile",
            "error.loader.version_json", "error.loader.version_json_missing" ->
                "加载器安装文件无法解析。"
            "error.loader.installer_download" -> "加载器安装包下载失败，请检查网络或镜像设置。"
            "error.loader.installer_zip", "error.loader.installer_entry" ->
                "加载器安装包不完整或已损坏。"
            "error.loader.installer_missing" -> "加载器安装包缺失，请重新下载。"
            "error.loader.processor_failed", "error.loader.processor_spawn",
            "error.loader.processor_jar", "error.loader.processor_classpath",
            "error.loader.processor_manifest" ->
                "Forge/NeoForge 安装处理器执行失败，请查看日志。"
            "error.loader.patcher_failed", "error.loader.patcher_spawn",
            "error.loader.client_jar" -> "OptiFine 补丁执行失败，请确认游戏文件完整。"
            "error.loader.java_required" -> "安装这个加载器需要 Java。请在设置中下载或开启自动下载。"
            "error.loader.output_hash" -> "加载器安装产物校验失败，请重试或更换镜像。"
            "error.loader.minecraft_mismatch" -> "加载器安装包与所选 Minecraft 版本不一致。"
            "error.loader.mappings_missing" -> "该版本缺少 Mojang 映射文件，无法安装 Forge/NeoForge。"
            "error.instance.invalid_id" -> "实例名称无法转换为安全的目录名。"
            "error.auth.server_url" -> "第三方登录地址不正确，请输入 https:// 开头的 Yggdrasil 地址。"
            "error.auth.server_unreachable" -> "无法连接第三方登录服务器，请检查地址、网络或代理设置。"
            "error.auth.server_metadata", "error.auth.server_status" ->
                "第三方登录服务器返回了无法识别的响应，请确认这是 authlib-injector / Yggdrasil 服务。"
            "error.auth.credentials_required" -> "请输入账户和密码。"
            "error.auth.invalid_credentials" -> "用户名或密码不正确。"
            "error.auth.forbidden" -> "第三方登录服务器拒绝了这个请求。"
            "error.auth.response" -> "第三方登录响应不完整，请稍后重试。"
            "error.auth.no_profiles" -> "这个账户还没有可用的游戏角色。"
            "error.auth.profile_not_found", "error.auth.profile_required" ->
                "请选择要使用的游戏角色。"
            "error.auth.selection_expired" -> "角色选择已过期，请重新登录。"
            "error.auth.login_required" -> "这个账户需要重新登录。"
            "error.auth.expired" -> "登录已过期，请在账户页重新登录。"
            "error.auth.account_not_found", "error.auth.no_account" ->
                "找不到这个账户，请重新选择或添加账户。"
            "error.auth.kind_mismatch", "error.auth.injector_required",
            "error.auth.injector_metadata", "error.auth.injector_download" ->
                "第三方登录组件不可用，请检查网络后重试。"
            "error.auth.microsoft_pending" -> "正版登录将在后续版本提供。"
            "error.auth.conflicting_account" -> "不能同时指定账户和离线用户名。"
            "error.auth.accounts_parse", "error.auth.accounts_read",
            "error.auth.accounts_write", "error.auth.accounts_serialize",
            "error.auth.credentials_write", "error.auth.accounts_schema" ->
                "账户数据无法读取或保存，请检查数据目录权限。"
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

/** One row of `java.list`. */
@Serializable
data class CoreJavaRuntime(
    val id: String? = null,
    val vendor: String = "",
    val version: String = "",
    val major: Int? = null,
    val arch: String = "",
    val home: String = "",
    val executable: String = "",
    val source: String = "",
    val managed: Boolean = false,
    val sizeBytes: Long = 0,
    val installedAtMs: Long? = null,
    val component: String? = null,
    val platform: String? = null,
)

/** `java.list` envelope. */
@Serializable
data class CoreJavaList(
    val runtimes: List<CoreJavaRuntime> = emptyList(),
    val autoDownload: Boolean = true,
    val supported: Boolean = true,
    val platform: String = "unknown",
    val hostArch: String = "unknown",
)

/** One row of `java.catalog`. */
@Serializable
data class CoreJavaCatalogEntry(
    val runtimeId: String,
    val major: Int,
    val component: String = "",
    val version: String = "",
    val arch: String = "",
    val platform: String = "",
    val fallback: Boolean = false,
    val installed: Boolean = false,
    val installedVersion: String? = null,
)

/** `java.catalog` envelope. */
@Serializable
data class CoreJavaCatalog(
    val versions: List<CoreJavaCatalogEntry> = emptyList(),
    val autoDownload: Boolean = true,
    val supported: Boolean = true,
    val platform: String = "unknown",
    val hostArch: String = "unknown",
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

/** One entry of `loaders.versions`. */
@Serializable
data class CoreLoaderVersion(
    val version: String,
    val stable: Boolean = true,
    val label: String? = null,
    val note: String? = null,
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

/** One row of `accounts.list`; never contains tokens. */
@Serializable
data class CoreAccount(
    val id: String,
    val kind: String = "offline",
    val name: String = id,
    val uuid: String = "",
    val server: String? = null,
    val serverName: String? = null,
    val selected: Boolean = false,
)

/** `accounts.list` envelope. */
@Serializable
data class CoreAccountList(
    val accounts: List<CoreAccount> = emptyList(),
    val selectedId: String? = null,
)

/** `accounts.yggdrasil.discover` result. */
@Serializable
data class CoreYggdrasilServer(
    val server: String,
    val serverName: String = "",
    val implementationName: String? = null,
    val implementationVersion: String? = null,
)

/** One selectable Yggdrasil game profile. */
@Serializable
data class CoreYggdrasilProfile(
    val id: String,
    val name: String = id,
)

/** `accounts.yggdrasil.login` result: a saved account or a profile choice. */
@Serializable
data class CoreYggdrasilLoginResult(
    val account: CoreAccount? = null,
    val selectedId: String? = null,
    val selectionRequired: Boolean = false,
    val pendingId: String? = null,
    val serverName: String = "",
    val profiles: List<CoreYggdrasilProfile> = emptyList(),
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
