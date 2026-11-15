package com.fograin.launcher.app

import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.platform.CoreAccount
import com.fograin.launcher.platform.CoreAppSnapshot
import com.fograin.launcher.platform.CoreGameConfig
import com.fograin.launcher.platform.CoreJavaCatalogEntry
import com.fograin.launcher.platform.CoreJavaRuntime
import com.fograin.launcher.platform.CoreLoaderVersion
import com.fograin.launcher.platform.CorePlanPreview
import com.fograin.launcher.platform.CoreSettings
import com.fograin.launcher.platform.CoreVersionEntry
import com.fograin.launcher.platform.CoreYggdrasilProfile
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

/** Sub-pages of the settings destination. */
enum class SettingsStage {
    MAIN,
    JAVA,
}

/** Java runtime inventory and download state owned by [AppModel]. */
data class JavaUiState(
    val runtimes: List<CoreJavaRuntime> = emptyList(),
    val catalog: List<CoreJavaCatalogEntry> = emptyList(),
    val autoDownload: Boolean = true,
    val supported: Boolean = true,
    val hostArch: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val catalogError: String? = null,
    val installTaskId: String? = null,
    val installingMajor: Int? = null,
) {
    val managedCount: Int
        get() = runtimes.count { it.managed }

    /** Progress for the runtime download currently owned by this page. */
    fun installFraction(downloads: List<DownloadRow>): Double? {
        val taskId = installTaskId ?: return null
        return downloads.firstOrNull { it.id == taskId }?.fraction
    }
}

/**
 * Sub-pages of the account panel. Skins are deliberately absent from M7: the
 * panel only manages identities (offline and third-party sign-in).
 */
enum class AccountStage {
    LIST,
    OFFLINE,
    YGGDRASIL,
    PROFILES,
}

/** Account panel state owned by [AppModel]. */
data class AccountUiState(
    val open: Boolean = false,
    val accounts: List<CoreAccount> = emptyList(),
    val selectedId: String? = null,
    val stage: AccountStage = AccountStage.LIST,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /** Server metadata for the profile picker after a multi-profile login. */
    val pendingId: String? = null,
    val pendingServerName: String = "",
    val pendingProfiles: List<CoreYggdrasilProfile> = emptyList(),
) {
    val selected: CoreAccount?
        get() = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()

    /** Display name for the greeting and avatar; stable before the first load. */
    val displayName: String
        get() = selected?.name ?: "Steve"

    val initial: String
        get() = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** Scope of the home instance list, matching the pen's recent filter. *//** Scope of the home instance list, matching the pen's recent filter. */
enum class HomeFilter {
    RECENT,
    FAVORITES,
    ALL,
}

/**
 * Pages inside the 下载 destination. The version list is the browser; picking a
 * row opens the install page for that pinned version, and the loader version
 * picker opens on top of it. All three share the downloads header and nav bar.
 */
enum class DownloadStage {
    BROWSER,
    INSTALL,
    LOADER_VERSIONS,
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
    val versionsError: String? = null,
    val isGameDownloadRunning: Boolean = false,
    val stage: DownloadStage = DownloadStage.BROWSER,
    /**
     * Version pinned by the install page. The page never offers another
     * version; users go back to the list to choose a different one.
     */
    val installVersionId: String? = null,
    val installLoader: GameLoaderKind = GameLoaderKind.VANILLA,
    val installLoaderVersion: String = "",
    val installInstanceName: String = "",
    val loaderVersions: List<CoreLoaderVersion> = emptyList(),
    val isLoadingLoaderVersions: Boolean = false,
    val loaderVersionsError: String? = null,
    val isInstallRequesting: Boolean = false,
    val installError: String? = null,
    /** `version.plan` for the pinned version, including the selected loader. */
    val plan: CorePlanPreview? = null,
    val planError: String? = null,
) {
    /** Entry backing the install page; falls back to an id-only row. */
    val installVersionEntry: CoreVersionEntry?
        get() = installVersionId?.let { id ->
            versions.firstOrNull { it.id == id } ?: CoreVersionEntry(id = id)
        }

    /** True while the loader choice is complete enough to start a download. */
    val canStartInstall: Boolean
        get() = installVersionId != null &&
            !isInstallRequesting &&
            !isGameDownloadRunning &&
            (installLoader == GameLoaderKind.VANILLA || installLoaderVersion.isNotEmpty())
}

/** Everything the Compose shell renders. */
data class AppUiState(
    val settings: CoreSettings,
    val theme: ThemeMode,
    val destination: AppDestination = AppDestination.HOME,
    val settingsStage: SettingsStage = SettingsStage.MAIN,
    val instances: List<Instance> = emptyList(),
    val downloads: List<DownloadRow> = emptyList(),
    val appSnapshot: CoreAppSnapshot? = null,
    val gameConfig: CoreGameConfig? = null,
    val currentInstanceId: String = "",
    val libraryQuery: String = "",
    /** Incremented by the desktop menu to open and focus the library search field. */
    val librarySearchRequest: Long = 0,
    /** Global palette-style search opened from the rail or Cmd/Ctrl+F. */
    val globalSearchOpen: Boolean = false,
    val globalSearchQuery: String = "",
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
    val java: JavaUiState = JavaUiState(),
    val account: AccountUiState = AccountUiState(),
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
