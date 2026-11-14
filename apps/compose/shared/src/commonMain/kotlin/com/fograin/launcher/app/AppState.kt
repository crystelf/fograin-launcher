package com.fograin.launcher.app

import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.platform.CoreAppSnapshot
import com.fograin.launcher.platform.CoreGameConfig
import com.fograin.launcher.platform.CorePlanPreview
import com.fograin.launcher.platform.CoreSettings
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
)

/** Scope of the home instance list, matching the pen's recent filter. */
enum class HomeFilter {
    RECENT,
    FAVORITES,
    ALL,
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
    val settings: CoreSettings,
    val theme: ThemeMode,
    val destination: AppDestination = AppDestination.HOME,
    val instances: List<Instance> = emptyList(),
    val downloads: List<DownloadRow> = emptyList(),
    val appSnapshot: CoreAppSnapshot? = null,
    val gameConfig: CoreGameConfig? = null,
    val currentInstanceId: String = "",
    val libraryQuery: String = "",
    /** Incremented by the desktop menu to open and focus the library search field. */
    val librarySearchRequest: Long = 0,
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
