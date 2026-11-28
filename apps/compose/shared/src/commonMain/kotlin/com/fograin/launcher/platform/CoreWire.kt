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
    /** `system`, `zh-Hans`, `zh-Hant`, `en` or `ja` (see `AppLanguage`). */
    val language: String = "system",
    /** `auto` or a `#RRGGBB` seed colour. */
    val accent: String = "auto",
    /** `home`, `library` or `downloads`. */
    val initialPage: String = "home",
    /** `community` or `launcher`; controls the home identity. */
    val defaultDisplayAccount: String = "community",
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
            language = "system",
            accent = "auto",
            initialPage = "home",
            defaultDisplayAccount = "community",
            dataRoot = dataRoot,
            configPath = if (dataRoot.isBlank()) "" else "$dataRoot/launcher.toml",
        )
    }
}

/** Error payload returned by every Rust FFI call. */
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
     * User-facing copy for the core errors the UI knows about; unknown keys
     * fall back to the technical detail so diagnostics are never lost.
     *
     * The table lives in `CoreErrorCopy.kt`.
     */
    val userMessage: String
        get() = coreErrorCopy(messageKey, detail)
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

/** `[download]` engine limits and source preferences. */
@Serializable
data class CoreDownloadSettings(
    val source: String = "auto",
    val versionSource: String = "auto",
    val concurrency: Int = 0,
    val perHost: Int = 32,
    val rateLimitKib: Int = 0,
    val segments: Int = 4,
    val segmentThresholdMb: Int = 16,
    val retries: Int = 5,
)

/** `[network]` proxy and timeout settings. */
@Serializable
data class CoreNetworkSettings(
    val proxy: String = "auto",
    val timeoutSecs: Int = 10,
)

/** Launcher-wide settings returned by `settings.get`. */
@Serializable
data class CoreLauncherSettings(
    val theme: String = "system",
    val language: String = "system",
    val accent: String = "auto",
    val initialPage: String = "home",
    val defaultDisplayAccount: String = "community",
    val download: CoreDownloadSettings = CoreDownloadSettings(),
    val network: CoreNetworkSettings = CoreNetworkSettings(),
)

/** `settings.set` echoes the normalized settings plus a fresh snapshot. */
@Serializable
data class CoreLauncherSettingsUpdate(
    val settings: CoreLauncherSettings = CoreLauncherSettings(),
    val snapshot: CoreSettings = CoreSettings(),
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
    /** Login email/username for third-party accounts; null for offline. */
    val email: String? = null,
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
// Content and worlds
// ---------------------------------------------------------------------------

/** One installed mod/resource pack/shader pack from `content.local`. */
@Serializable
data class CoreLocalContent(
    val kind: String = "mod",
    val fileName: String,
    val displayName: String = fileName,
    val enabled: Boolean = true,
    val path: String = "",
    val sizeBytes: Long = 0,
    val modifiedMs: Long? = null,
    val title: String = displayName,
    val version: String? = null,
    val loader: String? = null,
    val description: String? = null,
    val packFormat: Int? = null,
    val source: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    val versionId: String? = null,
    val iconUrl: String? = null,
)

/** Per-kind counts included in `content.local`. */
@Serializable
data class CoreContentCounts(
    val mod: Int = 0,
    val resourcepack: Int = 0,
    val shaderpack: Int = 0,
    val enabled: Int = 0,
)

/** `content.local` envelope. */
@Serializable
data class CoreContentLocal(
    val items: List<CoreLocalContent> = emptyList(),
    val counts: CoreContentCounts = CoreContentCounts(),
    val indexPath: String = "",
)

/** One search hit from Modrinth or CurseForge. */
@Serializable
data class CoreContentProject(
    val source: String = "modrinth",
    val projectId: String,
    val slug: String = "",
    val title: String = projectId,
    val description: String = "",
    val downloads: Long = 0,
    val follows: Long = 0,
    val iconUrl: String? = null,
    val categories: List<String> = emptyList(),
    val updated: String? = null,
)

/** `content.search` envelope. */
@Serializable
data class CoreContentSearchPage(
    val source: String = "modrinth",
    val projects: List<CoreContentProject> = emptyList(),
    val total: Long = 0,
    val offset: Int = 0,
    val limit: Int = 20,
)

/** Download file of a content version. */
@Serializable
data class CoreContentFile(
    val fileName: String = "",
    val url: String = "",
    val size: Long = 0,
    val sha1: String? = null,
    val sha512: String? = null,
)

/** Required/optional dependency of a content version. */
@Serializable
data class CoreContentDependency(
    val projectId: String? = null,
    val versionId: String? = null,
    val kind: String = "optional",
    val name: String? = null,
)

/** One version of a content project. */
@Serializable
data class CoreContentVersion(
    val source: String = "modrinth",
    val projectId: String = "",
    val versionId: String,
    val name: String = versionId,
    val versionNumber: String = name,
    val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val downloads: Long = 0,
    val versionType: String = "release",
    val published: String? = null,
    val file: CoreContentFile? = null,
    val dependencies: List<CoreContentDependency> = emptyList(),
)

/** `content.versions` envelope. */
@Serializable
data class CoreContentVersions(
    val source: String = "modrinth",
    val project: CoreContentProject? = null,
    val versions: List<CoreContentVersion> = emptyList(),
)

/** One file selected by `content.install`. */
@Serializable
data class CoreContentInstallItem(
    val fileName: String = "",
    val projectId: String = "",
    val projectName: String = "",
    val versionId: String = "",
    val versionNumber: String = "",
    val dependency: Boolean = false,
    val size: Long = 0,
)

/** `content.install` result; the download itself is a task. */
@Serializable
data class CoreContentInstall(
    val taskId: String? = null,
    val source: String = "modrinth",
    val projectId: String = "",
    val projectName: String = "",
    val versionId: String = "",
    val versionNumber: String = "",
    val kind: String = "mod",
    val files: Int = 0,
    val totalBytes: Long = 0,
    val warnings: List<String> = emptyList(),
    val items: List<CoreContentInstallItem> = emptyList(),
)

/** `content.plan` result: the files an install would fetch, without a task. */
@Serializable
data class CoreContentPlan(
    val source: String = "modrinth",
    val projectId: String = "",
    val projectName: String = "",
    val versionId: String = "",
    val versionNumber: String = "",
    val kind: String = "mod",
    val files: Int = 0,
    val totalBytes: Long = 0,
    val warnings: List<String> = emptyList(),
    val items: List<CoreContentInstallItem> = emptyList(),
) {
    val dependencies: List<CoreContentInstallItem>
        get() = items.filter { it.dependency }
}

/** One available content update. */
@Serializable
data class CoreContentUpdate(
    val kind: String = "mod",
    val fileName: String,
    val displayName: String = fileName,
    val source: String = "modrinth",
    val projectId: String = "",
    val projectName: String = displayName,
    val currentVersion: String = "",
    val latestVersion: String = "",
    val latestVersionId: String = "",
    val latestFileName: String? = null,
    val latestSize: Long = 0,
)

/** `content.updates` envelope. */
@Serializable
data class CoreContentUpdates(
    val checked: Int = 0,
    val updates: List<CoreContentUpdate> = emptyList(),
    val unknown: Int = 0,
    val warnings: List<String> = emptyList(),
)

/** One world folder from `worlds.list`. */
@Serializable
data class CoreWorld(
    val name: String,
    val path: String = "",
    val sizeBytes: Long = 0,
    val modifiedMs: Long? = null,
    val hasIcon: Boolean = false,
    /** `level.dat` display name; [name] stays the folder key. */
    val levelName: String? = null,
    val gameMode: String? = null,
    val version: String? = null,
    val lastPlayedMs: Long? = null,
    val seed: Long? = null,
)

/** One world backup. */
@Serializable
data class CoreWorldBackup(
    val name: String,
    val path: String = "",
    val sizeBytes: Long = 0,
    val modifiedMs: Long? = null,
)

/** `worlds.list` envelope. */
@Serializable
data class CoreWorlds(
    val worlds: List<CoreWorld> = emptyList(),
    val backups: List<CoreWorldBackup> = emptyList(),
)

/** `worlds.backups` envelope. */
@Serializable
data class CoreWorldBackups(
    val backups: List<CoreWorldBackup> = emptyList(),
)

/** `worlds.backup` / `worlds.restore` / `worlds.delete` envelope. */
@Serializable
data class CoreWorldsChanged(
    val ok: Boolean = true,
    val backup: CoreWorldBackup? = null,
    val backups: List<CoreWorldBackup> = emptyList(),
    val worlds: List<CoreWorld> = emptyList(),
    val path: String = "",
)

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------

/** Header of the newest crash report. */
@Serializable
data class CoreCrashSummary(
    val description: String = "",
    val minecraft: String = "",
    val java: String = "",
    val file: String = "",
    val modifiedMs: Long? = null,
)

/** One diagnostic source row. */
@Serializable
data class CoreDiagSource(
    val kind: String = "log",
    val name: String = "",
    val path: String = "",
    val exists: Boolean = false,
    val sizeBytes: Long = 0,
    val modifiedMs: Long? = null,
    val lines: Int = 0,
)

/** Raw log text by source kind (`stdout` / `log` / `crash` / `hsErr`). */
@Serializable
data class CoreDiagTexts(
    val stdout: String? = null,
    val log: String? = null,
    val crash: String? = null,
    val hsErr: String? = null,
)

/** `diag.logs` envelope. */
@Serializable
data class CoreDiagLogs(
    val sources: List<CoreDiagSource> = emptyList(),
    val crash: CoreCrashSummary? = null,
    val texts: CoreDiagTexts = CoreDiagTexts(),
    val rules: Int = 0,
)

/** One matched analysis rule. */
@Serializable
data class CoreFinding(
    val id: String,
    val severity: String = "warning",
    val confidence: Float = 0f,
    val title: String = id,
    val body: String = "",
    val excerpt: String = "",
    val line: Int = 0,
    val source: String = "",
    val sourceKind: String = "log",
    /** `installJava`, `openJavaSettings`, `openContent`, `disableMod`, … */
    val fix: String = "none",
)

/** `diag.analyze` envelope. */
@Serializable
data class CoreDiagnostics(
    val findings: List<CoreFinding> = emptyList(),
    val severity: String? = null,
    val summary: String = "",
    val scannedLines: Int = 0,
    val rules: Int = 0,
    val sources: List<CoreDiagSource> = emptyList(),
    val crash: CoreCrashSummary? = null,
    val analyzedAtMs: Long = 0,
)

/** Written diagnostic bundle. */
@Serializable
data class CoreDiagBundle(
    val path: String = "",
    val sizeBytes: Long = 0,
    val files: List<String> = emptyList(),
)

/** `diag.export` envelope: the bundle plus the analysis it contains. */
@Serializable
data class CoreDiagExport(
    val bundle: CoreDiagBundle = CoreDiagBundle(),
    val analysis: CoreDiagnostics = CoreDiagnostics(),
)

// ---------------------------------------------------------------------------
// Data packs
// ---------------------------------------------------------------------------

/** One data pack inside a world. */
@Serializable
data class CoreDatapack(
    val fileName: String,
    val title: String = fileName,
    val enabled: Boolean = true,
    val sizeBytes: Long = 0,
    val modifiedMs: Long? = null,
    val packFormat: Int? = null,
    val description: String? = null,
)

/** `datapacks.*` envelope. */
@Serializable
data class CoreDatapacks(
    val items: List<CoreDatapack> = emptyList(),
    val count: Int = items.size,
)

/** `datapacks.import` and `datapacks.toggle` add the new file name. */
@Serializable
data class CoreDatapackChange(
    val fileName: String? = null,
    val items: List<CoreDatapack> = emptyList(),
)

// ---------------------------------------------------------------------------
// Modpacks
// ---------------------------------------------------------------------------

/** One file managed by a modpack. */
@Serializable
data class CorePackFile(
    val path: String,
    val size: Long = 0,
    val sha1: String? = null,
    val sha512: String? = null,
    val url: String? = null,
    val projectId: String? = null,
    val fileId: Long? = null,
)

/** `modpack.inspect` result. */
@Serializable
data class CoreModpack(
    /** `modrinth`, `curseForge`, `hmcl` or `multiMc`. */
    val kind: String = "modrinth",
    val name: String,
    val version: String = "",
    val minecraft: String = "",
    val loaderKind: String = "vanilla",
    val loaderVersion: String = "",
    val summary: String? = null,
    val author: String? = null,
    /** Markdown/plain-text README shown on the import confirmation page. */
    val readme: String? = null,
    val files: List<CorePackFile> = emptyList(),
    val overrides: String? = null,
    val sourcePath: String = "",
    val gameDir: String? = null,
    val external: Boolean = false,
    val skipped: Int = 0,
) {
    val fileCount: Int
        get() = files.size

    val totalBytes: Long
        get() = files.sumOf { it.size }
}

/** `modpack.import` result; the actual work is a task. */
@Serializable
data class CoreModpackImport(
    val instanceId: String,
    val taskId: String? = null,
    val external: Boolean = false,
    val pack: CoreModpack? = null,
)

/** `modpack.install` result: the archive download task. */
@Serializable
data class CoreModpackInstallTask(
    val taskId: String? = null,
    /** Instance pinned by the core before the archive download starts. */
    val instanceId: String = "",
    val name: String = "",
    val versionId: String = "",
    val versionNumber: String = "",
    val source: String = "modrinth",
)

/** One checkbox on the modpack export wizard. */
@Serializable
data class CoreExportSection(
    val id: String,
    val label: String = id,
    val files: Int = 0,
    val bytes: Long = 0,
    val default: Boolean = false,
    val folder: Boolean = false,
)

/** `modpack.export.plan` result. */
@Serializable
data class CoreExportPlan(
    val sections: List<CoreExportSection> = emptyList(),
)

/** `modpack.export` result. */
@Serializable
data class CoreModpackExport(
    val path: String = "",
    val sizeBytes: Long = 0,
    val files: Int = 0,
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
