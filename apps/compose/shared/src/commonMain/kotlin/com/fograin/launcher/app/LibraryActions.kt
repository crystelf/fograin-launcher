package com.fograin.launcher.app

import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.DirectoryPick
import com.fograin.launcher.ui.AppDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Instance-library commands: create, favourite, delete and import.
 *
 * The active filter/search/sort live in [AppUiState] and are pure view state,
 * so they stay on [AppModel]; everything that crosses the RPC boundary is here.
 */
internal class LibraryActions(private val model: AppModel) {

    /** 新建实例 / 添加实例 routes to the download page (HMCL/PCL behaviour). */
    fun openAddInstance() {
        model.setState {
            it.copy(
                download = it.download.copy(category = DownloadsCategory.GAME),
                destination = AppDestination.DOWNLOADS,
            )
        }
        model.scope.launch { model.downloads.loadVersions() }
    }

    /**
     * Safe directory id for a display name. CJK names keep their display name
     * and fall back to a generic id; collisions get a numeric suffix so the
     * download page never fails just because an instance already exists.
     */
    fun uniqueInstanceID(name: String, version: String): String {
        val sanitized = InstanceFormat.sanitizedInstanceID(name)
        val base = sanitized.ifEmpty {
            InstanceFormat.sanitizedInstanceID(version).ifEmpty { "instance" }
        }
        if (model.state.value.instances.none { it.id == base }) return base
        var suffix = 2
        while (model.state.value.instances.any { it.id == "$base-$suffix" }) {
            suffix += 1
        }
        return "$base-$suffix"
    }

    /** Registers an external `.minecraft` directory without moving files. */
    fun importMinecraftDirectory(directory: String) {
        model.scope.launch {
            try {
                model.core.rpc(
                    "instances.import",
                    buildJsonObject { put("directory", directory) },
                )
                val name = directory.trimEnd('/').substringAfterLast('/').ifEmpty { directory }
                model.showNotice("已导入“$name”", Notice.Tone.SUCCESS)
                model.refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("导入 .minecraft 目录失败", error)
            }
        }
    }

    /**
     * Opens the platform's native directory picker. Desktop uses
     * `JFileChooser`, Android uses SAF; [DirectoryPick.Unavailable] tells the
     * caller to fall back to the in-app browser.
     */
    suspend fun pickImportDirectory(): DirectoryPick =
        model.platform.directories.pick(
            title = "导入 .minecraft 目录",
            initialPath = model.state.value.settings.dataRoot.ifBlank { null },
        )

    fun toggleFavorite(instance: Instance) {
        model.callCore(
            "instances.favorite",
            buildJsonObject {
                put("id", instance.id)
                put("favorite", !instance.isFavorite)
            },
        )
    }

    fun deleteInstance(instance: Instance) {
        model.scope.launch {
            try {
                model.core.rpc("instances.delete", buildJsonObject { put("id", instance.id) })
                model.content.clear(instance.id)
                model.setState {
                    it.copy(
                        libraryStage = LibraryStage.LIST,
                        libraryDetailId = null,
                        librarySelection = null,
                    )
                }
                model.showNotice("已删除“${instance.name}”", Notice.Tone.SUCCESS)
                model.refreshCoreData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showError("无法删除“${instance.name}”", error)
            }
        }
    }
}
