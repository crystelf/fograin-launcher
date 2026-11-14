package com.fograin.launcher.app

import com.fograin.launcher.model.Instance
import com.fograin.launcher.platform.CoreInstanceSettings
import com.fograin.launcher.platform.decode
import com.fograin.launcher.platform.stringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per-instance settings: read the effective values, persist one override, and
 * copy the game directory path (Android has no Finder for app-private storage).
 */
internal class InstanceSettingsActions(private val model: AppModel) {

    /** Reads the effective values, overrides and globals for one instance. */
    suspend fun load(id: String): CoreInstanceSettings =
        model.core.rpc(
            "instances.settings.get",
            buildJsonObject { put("id", id) },
        ).decode()

    /** Persists one instance setting; `value == null` inherits `[game]`. */
    fun configure(id: String, setting: String, value: JsonElement?) {
        val update = buildJsonObject {
            put("setting", setting)
            put("value", value ?: JsonNull)
        }
        val params = buildJsonObject {
            put("id", id)
            put("updates", buildJsonArray { add(update) })
        }
        model.scope.launch {
            try {
                model.core.rpc("instances.settings.set", params)
                model.refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("设置保存失败", error)
            }
        }
    }

    /**
     * The data root is the app's external files directory on Android and is
     * reachable over USB, so the folder action copies the path instead of
     * opening a file manager.
     */
    fun copyDirectoryPath(instance: Instance) {
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "instances.dir",
                    buildJsonObject { put("id", instance.id) },
                )
                val directory = payload.stringOrNull("gameDir")
                    ?: "${model.state.value.settings.dataRoot}/instances/${instance.id}"
                model.platform.clipboard.copy(directory)
                model.showNotice("实例目录路径已复制")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法复制实例目录路径", error)
            }
        }
    }
}
