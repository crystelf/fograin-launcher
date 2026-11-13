package com.fograin.launcher.app

import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.platform.CoreAppSnapshot
import com.fograin.launcher.platform.CoreGameConfig
import com.fograin.launcher.platform.CorePlanPreview
import com.fograin.launcher.platform.CoreSettingsSnapshot
import com.fograin.launcher.platform.CoreVersionEntry
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.AppDestination

/** Transient status capsule shown above the bottom bar. */
data class Notice(
    val id: Long,
    val message: String,
    val tone: Tone = Tone.INFO,
) {
    enum class Tone { INFO, SUCCESS, WARNING }
}

/** Prominent modal error, so a failed install or launch cannot look healthy. */
data class AppAlert(
    val id: Long,
    val title: String,
    val message: String,
    val detail: String? = null,
) {
    val combinedMessage: String
        get() {
            if (detail.isNullOrBlank()) return message
            val trimmed = if (detail.length > 480) detail.take(480) + "…" else detail
            return "$message\n\n$trimmed"
        }
}

/** One running download shown by the home queue and the downloads page. */
data class DownloadRow(
    val id: String,
    val title: String,
    val fraction: Double,
    val speed: Double,
    val state: String,
) {
    val speedText: String
        get() = if (speed <= 0) "—" else InstanceFormat.byteRate(speed) + "/s"

    val stateDescription: String
        get() = when (state) {
            "succeeded" -> "已完成"
            "failed" -> "失败"
            "paused" -> "已暂停"
            "cancelled" -> "已取消"
            else -> "${(fraction * 100).toInt()}%"
        }
}

/** Scope of the home instance list, matching desktop `RecentFilter`. */
enum class HomeFilter(val title: String) {
    RECENT("最近"),
    FAVORITES("收藏"),
    ALL("全部"),
}

/** Downloads page state owned by [AppModel]. */
data class DownloadUiState(
    val category: DownloadsCategory = DownloadsCategory.GAME,
    val channel: DownloadVersionChannel = DownloadVersionChannel.RELEASE,
    val contentLoader: String = "ALL",
    val query: String = "",
    val versions: List<CoreVersionEntry> = emptyList(),
    val versionsChannel: DownloadVersionChannel? = null,
    val isLoadingVersions: Boolean = false,
    val selectedVersionId: String? = null,
    val plan: CorePlanPreview? = null,
    val planError: String? = null,
    val versionsError: String? = null,
    val isGameDownloadRunning: Boolean = false,
    /** Non-null when the install sheet is open; the value is the initial version. */
    val installSheetVersion: String? = null,
)

/** Everything the Compose shell renders. */
data class AppUiState(
    val settings: CoreSettingsSnapshot,
    val theme: ThemeMode,
    val destination: AppDestination = AppDestination.HOME,
    val instances: List<Instance> = emptyList(),
    val downloads: List<DownloadRow> = emptyList(),
    val appSnapshot: CoreAppSnapshot? = null,
    val gameConfig: CoreGameConfig? = null,
    val currentInstanceId: String = "",
    val libraryQuery: String = "",
    val librarySelection: String? = null,
    val libraryScope: LibraryScope = LibraryScope.ALL,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val homeFilter: HomeFilter = HomeFilter.RECENT,
    val sessionRunning: Boolean = false,
    val isSessionLoading: Boolean = false,
    val isInitialLoading: Boolean = true,
    val notice: Notice? = null,
    val alert: AppAlert? = null,
    val download: DownloadUiState = DownloadUiState(),
    /** Non-null when the instance settings sheet is open. */
    val instanceSettingsId: String? = null,
    /** Instance shown by the library preview sheet. */
    val previewInstanceId: String? = null,
    val deviceMemoryBytes: Long = 0,
    val deviceText: String = "",
    val socText: String = "",
) {
    val currentInstance: Instance?
        get() = instances.firstOrNull { it.id == currentInstanceId } ?: instances.firstOrNull()

    val previewInstance: Instance?
        get() = previewInstanceId?.let { id -> instances.firstOrNull { it.id == id } }

    val instanceSettings: Instance?
        get() = instanceSettingsId?.let { id -> instances.firstOrNull { it.id == id } }

    /** Tasks still working; finished rows stay for history. */
    val activeDownloads: List<DownloadRow>
        get() = downloads.filter { it.state == "running" || it.state == "paused" }

    val activeDownloadCount: Int
        get() = activeDownloads.size

    val downloadsSpeedText: String
        get() {
            val total = activeDownloads.sumOf { it.speed }
            if (total <= 0) return if (activeDownloads.isEmpty()) "空闲" else "—"
            return InstanceFormat.byteRate(total) + "/s"
        }

    val memorySummary: String
        get() {
            val memoryMb = gameConfig?.memoryMb ?: 0
            val physical = DeviceInfoText.memory(deviceMemoryBytes)
            return if (memoryMb == 0) {
                "自动 · 物理内存 $physical"
            } else {
                "${maxOf(1, memoryMb / 1024)} GB / $physical"
            }
        }

    val javaSummary: String
        get() {
            val count = appSnapshot?.javaCount ?: return "扫描中…"
            return if (count == 0) "未发现 Java" else "已发现 $count 个运行时"
        }

    val homeSubtitle: String
        get() {
            val lastPlayed = instances.mapNotNull { it.lastPlayedEpochMillis }.maxOrNull()
            return "${instances.size} 个实例 · 上次游玩 ${InstanceFormat.relativeTime(lastPlayed)}"
        }

    val librarySubtitle: String
        get() = "${instances.size} 个实例 · 占用 ${InstanceFormat.totalSize(instances)}"

    /** The instances shown by the home list under the selected filter. */
    fun homeInstances(): List<Instance> = when (homeFilter) {
        HomeFilter.RECENT -> instances
            .sortedByDescending { it.lastPlayedEpochMillis ?: Long.MIN_VALUE }
            .take(6)

        HomeFilter.FAVORITES -> instances.filter { it.isFavorite }
        HomeFilter.ALL -> instances
    }

    fun isDownloading(instance: Instance): Boolean =
        activeDownloads.any { it.title.contains(instance.minecraftVersion) }
}

/** Keeps the state class free of Android framework calls in unit tests. */
internal object DeviceInfoText {
    fun memory(bytes: Long): String {
        if (bytes <= 0) return "未知"
        val gigabytes = bytes / 1_073_741_824.0
        return "${maxOf(1, (gigabytes + 0.5).toInt())} GB"
    }
}
