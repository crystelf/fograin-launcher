package com.fograin.launcher.platform

import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict camelCase wire models decoded from `bindings/fg-ffi` JSON (§13).
 *
 * The shapes mirror the `fg-ffi` JSON contract.
 * Dynamic download payloads stay as [JSONObject] until they reach a screen.
 */
data class CoreInstanceRow(
    val id: String,
    val name: String,
    val minecraft: String,
    val loaderKind: String,
    val loaderVersion: String,
    val favorite: Boolean,
    val installed: Boolean,
    val modCount: Int?,
    val sizeBytes: Long,
    val playSeconds: Long,
    val lastPlayedMs: Long?,
) {
    companion object {
        fun from(json: JSONObject): CoreInstanceRow? {
            val id = json.optionalString("id") ?: return null
            return CoreInstanceRow(
                id = id,
                name = json.optString("name", id),
                minecraft = json.optString("minecraft", ""),
                loaderKind = json.optString("loaderKind", "vanilla"),
                loaderVersion = json.optString("loaderVersion", ""),
                favorite = json.optBoolean("favorite", false),
                installed = json.optBoolean("installed", false),
                modCount = json.optionalInt("modCount"),
                sizeBytes = json.optLong("sizeBytes", 0),
                playSeconds = json.optLong("playSeconds", 0),
                lastPlayedMs = json.optionalLong("lastPlayedMs"),
            )
        }
    }
}

/** `[game]` section as the core serializes it. */
data class CoreGameConfig(
    val username: String,
    val memoryMb: Int,
    val jvmArgs: String,
    val resolution: List<Int>?,
    val fullscreen: Boolean,
    val showLog: Boolean,
    val autoCompleteResources: Boolean,
) {
    companion object {
        fun from(json: JSONObject): CoreGameConfig = CoreGameConfig(
            username = json.optString("username", "Steve"),
            memoryMb = json.optInt("memoryMb", 0),
            jvmArgs = json.optString("jvmArgs", ""),
            resolution = json.optionalIntArray("resolution"),
            fullscreen = json.optBoolean("fullscreen", false),
            showLog = json.optBoolean("showLog", false),
            autoCompleteResources = json.optBoolean("autoCompleteResources", true),
        )
    }
}

/** Aggregate returned by `app.snapshot`. */
data class CoreAppSnapshot(
    val platform: String,
    val appVersion: String,
    val dataRoot: String,
    val javaCount: Int,
    val activeTasks: Int,
    val diskFreeBytes: Long,
    val totalMemoryBytes: Long,
    val instanceCount: Int,
) {
    companion object {
        fun from(json: JSONObject): CoreAppSnapshot = CoreAppSnapshot(
            platform = json.optString("platform", "android"),
            appVersion = json.optString("appVersion", "0.1.0"),
            dataRoot = json.optString("dataRoot", ""),
            javaCount = json.optInt("javaCount", 0),
            activeTasks = json.optInt("activeTasks", 0),
            diskFreeBytes = json.optLong("diskFreeBytes", 0),
            totalMemoryBytes = json.optLong("totalMemoryBytes", 0),
            instanceCount = json.optInt("instanceCount", 0),
        )
    }
}

/** One row of `tasks.list`. */
data class CoreTaskRow(
    val id: String,
    val name: String,
    val state: String,
    val fraction: Double,
    val speed: Double,
) {
    companion object {
        fun from(json: JSONObject): CoreTaskRow? {
            val id = json.optionalString("id") ?: return null
            return CoreTaskRow(
                id = id,
                name = json.optString("name", id),
                state = json.optString("state", "running"),
                fraction = json.optDouble("fraction", 0.0),
                speed = json.optDouble("speed", 0.0),
            )
        }
    }
}

/** One entry of `versions.list`. */
data class CoreVersionEntry(
    val id: String,
    val kind: String,
    val released: String?,
) {
    companion object {
        fun from(json: JSONObject): CoreVersionEntry? {
            val id = json.optionalString("id") ?: return null
            return CoreVersionEntry(
                id = id,
                kind = json.optString("kind", "unknown"),
                released = json.optionalString("released"),
            )
        }
    }
}

/** Download preview from `version.plan`. */
data class CorePlanPreview(
    val versionId: String,
    val baseId: String,
    val javaMajor: Int,
    val files: Int,
    val totalBytes: Long,
) {
    companion object {
        fun from(json: JSONObject): CorePlanPreview? {
            val versionId = json.optionalString("versionId") ?: return null
            return CorePlanPreview(
                versionId = versionId,
                baseId = json.optString("baseId", versionId),
                javaMajor = json.optInt("javaMajor", 21),
                files = json.optInt("files", 0),
                totalBytes = json.optLong("totalBytes", 0),
            )
        }
    }
}

/** Per-instance overrides; `null` means "inherit the global `[game]` value". */
data class CoreInstanceOverrides(
    val java: String?,
    val memoryMb: Int?,
    val jvmArgs: String?,
    val resolution: List<Int>?,
    val fullscreen: Boolean?,
    val gameDir: String?,
) {
    companion object {
        fun from(json: JSONObject): CoreInstanceOverrides = CoreInstanceOverrides(
            java = json.optionalString("java"),
            memoryMb = json.optionalInt("memoryMb"),
            jvmArgs = json.optionalString("jvmArgs"),
            resolution = json.optionalIntArray("resolution"),
            fullscreen = if (json.has("fullscreen") && !json.isNull("fullscreen")) {
                json.optBoolean("fullscreen")
            } else {
                null
            },
            gameDir = json.optionalString("gameDir"),
        )
    }
}

/** Response of `instances.settings.get`. */
data class CoreInstanceSettings(
    val effective: CoreGameConfig,
    val overrides: CoreInstanceOverrides,
    val globals: CoreGameConfig,
) {
    companion object {
        fun from(json: JSONObject): CoreInstanceSettings {
            val globals = json.optJSONObject("globals")?.let(CoreGameConfig::from)
                ?: CoreGameConfig("Steve", 0, "", null, false, false, true)
            return CoreInstanceSettings(
                effective = json.optJSONObject("effective")?.let(CoreGameConfig::from)
                    ?: globals,
                overrides = json.optJSONObject("overrides")?.let(CoreInstanceOverrides::from)
                    ?: CoreInstanceOverrides(null, null, null, null, null, null),
                globals = globals,
            )
        }
    }
}

/** Reads the object array carried under `key` (RPC arrays land in `result`). */
internal fun JSONObject.objectArray(key: String): List<JSONObject> {
    val array: JSONArray = optJSONArray(key) ?: return emptyList()
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(::add)
        }
    }
}

/** Reads a string array carried under `key`. */
internal fun JSONObject.stringArray(key: String): List<String> {
    val array: JSONArray = optJSONArray(key) ?: return emptyList()
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            if (!array.isNull(index)) add(array.optString(index))
        }
    }
}
