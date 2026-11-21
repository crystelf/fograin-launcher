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
 * open the game directory in the host file manager.
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

    /** Opens the instance game directory in the host file manager. */
    fun openDirectory(instance: Instance) {
        model.scope.launch {
            try {
                val payload = model.core.rpc(
                    "instances.dir",
                    buildJsonObject { put("id", instance.id) },
                )
                val directory = payload.stringOrNull("gameDir")
                    ?: "${model.state.value.settings.dataRoot}/instances/${instance.id}"
                if (!model.platform.links.revealDirectory(directory)) {
                    model.showNotice("无法打开文件管理器，请从系统存储中手动进入实例目录")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法打开实例目录", error)
            }
        }
    }
}
