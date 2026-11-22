package com.fograin.launcher.app

import com.fograin.launcher.model.ContentVersionChannel
import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.model.TaskState
import com.fograin.launcher.platform.CoreAccount
import com.fograin.launcher.platform.CoreAppSnapshot
import com.fograin.launcher.platform.CoreContentCounts
import com.fograin.launcher.platform.CoreContentInstallItem
import com.fograin.launcher.platform.CoreContentProject
import com.fograin.launcher.platform.CoreContentUpdate
import com.fograin.launcher.platform.CoreContentVersion
import com.fograin.launcher.platform.CoreDatapack
import com.fograin.launcher.platform.CoreDiagLogs
import com.fograin.launcher.platform.CoreDiagnostics
import com.fograin.launcher.platform.CoreDownloadSettings
import com.fograin.launcher.platform.CoreExportSection
import com.fograin.launcher.platform.CoreFinding
import com.fograin.launcher.platform.CoreGameConfig
import com.fograin.launcher.platform.CoreJavaCatalogEntry
import com.fograin.launcher.platform.CoreJavaRuntime
import com.fograin.launcher.platform.CoreLoaderVersion
import com.fograin.launcher.platform.CoreLocalContent
import com.fograin.launcher.platform.CoreModpack
import com.fograin.launcher.platform.CoreModpackExport
import com.fograin.launcher.platform.CoreNetworkSettings
import com.fograin.launcher.platform.CorePlanPreview
import com.fograin.launcher.platform.CoreSettings
import com.fograin.launcher.platform.CoreVersionEntry
import com.fograin.launcher.platform.ReleaseInfo
import com.fograin.launcher.platform.CoreWorld
import com.fograin.launcher.platform.CoreWorldBackup
import com.fograin.launcher.platform.CoreYggdrasilProfile
import com.fograin.launcher.settings.AccentPreference
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.settings.InitialPage
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
    val state: TaskState,
    /** Raw task graph name such as `install:1.21.5`; used for localization. */
    val graphName: String = "",
)

/** Categories shown by the settings surface. */
enum class SettingsSection {
    APPEARANCE,
    GENERAL,
    JAVA,
    DOWNLOAD,
    NETWORK,
    STORAGE,
    ABOUT,
}

/** A settings write/read failure kept as a key so the UI can localize it. */
data class SettingsFailure(
    val messageKey: String = "",
    val detail: String? = null,
)

/** Settings destination state; the section survives closing so reopening is instant. */
data class SettingsUiState(
    val open: Boolean = false,
    val section: SettingsSection = SettingsSection.APPEARANCE,
    /**
     * Compact layout only: `true` shows the category content, `false` the
     * category list. Wide layouts always show both at once.
     */
    val detailOpen: Boolean = false,
    /** True while the window is in the phone / narrow layout. */
    val compact: Boolean = false,
    /**
     * Previous sections pushed while navigating inside the compact detail
     * page. "General → Java" pushes General, so Back returns there.
     */
    val sectionHistory: List<SettingsSection> = emptyList(),
    /** Direction of the next section animation; false means Back. */
    val sectionForward: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val loaded: Boolean = false,
    val error: SettingsFailure? = null,
    val download: CoreDownloadSettings = CoreDownloadSettings(),
    val network: CoreNetworkSettings = CoreNetworkSettings(),
    /** Data folder applied after the next restart; `null` means the default. */
    val dataRootOverride: String? = null,
)

/**
 * Pages inside the 实例库 destination. The instance list is the root; a row
 * opens the detail page (overview + content management), and the detail page's
 * settings action opens the per-instance settings page. All three share the
 * library header and nav bar, exactly like the downloads flow.
 */
enum class LibraryStage {
    LIST,
    DETAIL,
    SETTINGS,
    EXPORT,
    DIAGNOSTICS,
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
 * Sub-pages of the account panel. Skins are deliberately absent: the
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

/** Tabs of the instance content manager. */
enum class ContentManagerTab(val kind: String?) {
    MODS("mod"),
    RESOURCE_PACKS("resourcepack"),
    SHADER_PACKS("shaderpack"),
    WORLDS(null),
    DATAPACKS(null),
}

/** Source switcher on the downloads content tabs. */
enum class ContentSourceOption(val wire: String) {
    MODRINTH("modrinth"),
    CURSEFORGE("curseforge"),
}

/** Instance content manager state. */
data class ContentManagerUiState(
    val instanceId: String? = null,
    val tab: ContentManagerTab = ContentManagerTab.MODS,
    /** Search text for the installed-content lists. */
    val query: String = "",
    val items: List<CoreLocalContent> = emptyList(),
    val counts: CoreContentCounts = CoreContentCounts(),
    val worlds: List<CoreWorld> = emptyList(),
    val backups: List<CoreWorldBackup> = emptyList(),
    /** Selected world for the data pack tab; defaults to the first world. */
    val datapackWorld: String? = null,
    val datapacks: List<CoreDatapack> = emptyList(),
    val datapacksLoading: Boolean = false,
    val datapackError: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isCheckingUpdates: Boolean = false,
    val updates: Map<String, CoreContentUpdate> = emptyMap(),
    val updateUnknown: Int = 0,
    val busyFile: String? = null,
    val busyWorld: String? = null,
) {
    fun itemsFor(tab: ContentManagerTab): List<CoreLocalContent> =
        items.filter { it.kind == tab.kind }

    fun countFor(tab: ContentManagerTab): Int = when (tab) {
        ContentManagerTab.WORLDS -> worlds.size
        ContentManagerTab.DATAPACKS -> datapacks.size
        else -> itemsFor(tab).size
    }

    fun updateFor(fileName: String): CoreContentUpdate? = updates[fileName]

    /** [itemsFor] narrowed by the page search field. */
    fun visibleItemsFor(tab: ContentManagerTab): List<CoreLocalContent> {
        val term = query.trim()
        if (term.isEmpty()) return itemsFor(tab)
        return itemsFor(tab).filter { item ->
            listOfNotNull(item.title, item.fileName, item.version, item.description)
                .any { it.contains(term, ignoreCase = true) }
        }
    }

    /** Worlds narrowed by the page search field. */
    fun visibleWorlds(): List<CoreWorld> {
        val term = query.trim()
        if (term.isEmpty()) return worlds
        return worlds.filter { it.name.contains(term, ignoreCase = true) }
    }

    /** Backups narrowed by the page search field. */
    fun visibleBackups(): List<CoreWorldBackup> {
        val term = query.trim()
        if (term.isEmpty()) return backups
        return backups.filter { it.name.contains(term, ignoreCase = true) }
    }

    /** Data packs narrowed by the page search field. */
    fun visibleDatapacks(): List<CoreDatapack> {
        val term = query.trim()
        if (term.isEmpty()) return datapacks
        return datapacks.filter { item ->
            listOfNotNull(item.title, item.fileName, item.description)
                .any { it.contains(term, ignoreCase = true) }
        }
    }
}

/** Log sources available on the diagnostics page. */
enum class DiagTab(val kind: String) {
    LOG("log"),
    CRASH("crash"),
    SESSION("stdout"),
    JVM("hsErr"),
}

/** Diagnostics page state. */
data class DiagUiState(
    val instanceId: String? = null,
    val analysis: CoreDiagnostics? = null,
    val logs: CoreDiagLogs? = null,
    val tab: DiagTab = DiagTab.LOG,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val error: String? = null,
    val bundlePath: String? = null,
) {
    val findings: List<CoreFinding>
        get() = analysis?.findings.orEmpty()

    /** Sources that actually contain text, in display order. */
    val availableTabs: List<DiagTab>
        get() {
            val texts = logs?.texts ?: return listOf(DiagTab.LOG)
            val available = DiagTab.entries.filter { tab ->
                when (tab) {
                    DiagTab.LOG -> !texts.log.isNullOrBlank()
                    DiagTab.CRASH -> !texts.crash.isNullOrBlank()
                    DiagTab.SESSION -> !texts.stdout.isNullOrBlank()
                    DiagTab.JVM -> !texts.hsErr.isNullOrBlank()
                }
            }
            return available.ifEmpty { listOf(DiagTab.LOG) }
        }

    fun textFor(tab: DiagTab): String {
        val texts = logs?.texts ?: return ""
        return when (tab) {
            DiagTab.LOG -> texts.log
            DiagTab.CRASH -> texts.crash
            DiagTab.SESSION -> texts.stdout
            DiagTab.JVM -> texts.hsErr
        }.orEmpty()
    }

    /** The selected tab may disappear after a refresh; fall back to the first. */
    val activeTab: DiagTab
        get() = if (tab in availableTabs) tab else availableTabs.first()
}

/** Steps of the modpack export page. */
enum class ExportStep {
    FORM,
    CONTENT,
}

/** Modpack export wizard state; one instance at a time. */
data class ExportModpackUiState(
    val instanceId: String? = null,
    val step: ExportStep = ExportStep.FORM,
    val name: String = "",
    val author: String = "",
    val version: String = "1.0",
    val description: String = "",
    val sections: List<CoreExportSection> = emptyList(),
    val selected: Set<String> = emptySet(),
    val isLoadingPlan: Boolean = false,
    val isExporting: Boolean = false,
    val error: String? = null,
    /** Non-null after a successful write; drives the result dialog. */
    val result: CoreModpackExport? = null,
) {
    val canContinue: Boolean
        get() = name.isNotBlank() && !isExporting

    val canExport: Boolean
        get() = !isExporting && !isLoadingPlan
}

/** Modpack import state; export has its own wizard state. */
data class ModpackUiState(
    val inspecting: Boolean = false,
    /** Non-null while the import confirmation page is open. */
    val pack: CoreModpack? = null,
    val name: String = "",
    val isImporting: Boolean = false,
    val error: String? = null,
    val taskId: String? = null,
    val importedInstanceId: String? = null,
    /** True while a file is dragged over the desktop window. */
    val dragActive: Boolean = false,
) {
    val canImport: Boolean
        get() = pack != null && name.isNotBlank() && !isImporting
}

/** Missing prerequisites shown before a mod install is queued. */
data class ContentDependencyPrompt(
    val projectName: String,
    val items: List<CoreContentInstallItem> = emptyList(),
    /** Core warnings such as a prerequisite that could not be resolved. */
    val warnings: List<String> = emptyList(),
)

/** Downloads page content browser state. */
data class ContentBrowserUiState(
    val source: ContentSourceOption = ContentSourceOption.MODRINTH,
    val targetInstanceId: String = "",
    /** Selected world for data packs; blank for the other categories. */
    val targetWorld: String = "",
    val worlds: List<CoreWorld> = emptyList(),
    val isLoadingWorlds: Boolean = false,
    val projects: List<CoreContentProject> = emptyList(),
    val total: Long = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedProject: CoreContentProject? = null,
    val versions: List<CoreContentVersion> = emptyList(),
    val isLoadingVersions: Boolean = false,
    val versionsError: String? = null,
    val selectedVersionId: String? = null,
    /** Release channel picked in the sheet's 版本 dropdown. */
    val versionChannel: ContentVersionChannel = ContentVersionChannel.RELEASE,
    val isCheckingDependencies: Boolean = false,
    val dependencyPrompt: ContentDependencyPrompt? = null,
    val isInstalling: Boolean = false,
    val installTaskId: String? = null,
) {
    /** Versions matching the selected channel; the list row shows all of them. */
    val visibleVersions: List<CoreContentVersion>
        get() = versions.filter { versionChannel.matches(it.versionType) }

    val selectedVersion: CoreContentVersion?
        get() = visibleVersions.firstOrNull { it.versionId == selectedVersionId }
            ?: visibleVersions.firstOrNull()

    fun versionCount(channel: ContentVersionChannel): Int =
        versions.count { channel.matches(it.versionType) }

    val canInstall: Boolean
        get() = selectedProject != null &&
            selectedVersion != null &&
            !isInstalling &&
            !isCheckingDependencies &&
            dependencyPrompt == null
}

/**
 * State for the startup update check.
 *
 * [available] is kept after the prompt is dismissed so Settings → About can
 * still show the release; [visible] is the value the bottom-right prompt uses.
 */
data class UpdateUiState(
    val checking: Boolean = false,
    val available: ReleaseInfo? = null,
    val dismissed: Boolean = false,
    val checked: Boolean = false,
    val failed: Boolean = false,
) {
    val visible: ReleaseInfo?
        get() = available?.takeIf { !dismissed }
}

/** Everything the Compose shell renders. */
data class AppUiState(
    val settings: CoreSettings,
    val theme: ThemeMode,
    /** Interface language; `SYSTEM` follows the host locale. */
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** `auto` or a `#RRGGBB` accent seed. */
    val accent: AccentPreference = AccentPreference.Auto,
    /** Destination opened after a cold start. */
    val initialPage: InitialPage = InitialPage.HOME,
    val destination: AppDestination = AppDestination.HOME,
    val settingsWindow: SettingsUiState = SettingsUiState(),
    val instances: List<Instance> = emptyList(),
    val downloads: List<DownloadRow> = emptyList(),
    /** Full-window queue page opened from Home or the downloads card. */
    val downloadQueueOpen: Boolean = false,
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
    val updates: UpdateUiState = UpdateUiState(),
    val download: DownloadUiState = DownloadUiState(),
    val java: JavaUiState = JavaUiState(),
    val account: AccountUiState = AccountUiState(),
    val content: ContentManagerUiState = ContentManagerUiState(),
    val contentBrowser: ContentBrowserUiState = ContentBrowserUiState(),
    val diag: DiagUiState = DiagUiState(),
    val modpack: ModpackUiState = ModpackUiState(),
    val modpackExport: ExportModpackUiState = ExportModpackUiState(),
    /** Library sub-page shown by [com.fograin.launcher.ui.library.LibraryScreen]. */
    val libraryStage: LibraryStage = LibraryStage.LIST,
    /**
     * Instance backing the detail/settings page. It survives the exit
     * animation after [libraryStage] returns to [LibraryStage.LIST]; the next
     * open overwrites it and deletion clears it.
     */
    val libraryDetailId: String? = null,
    val deviceMemoryBytes: Long = 0,
    val deviceText: String = "",
    val socText: String = "",
    val cpuCount: Int = 4,
) {
    val currentInstance: Instance?
        get() = instances.firstOrNull { it.id == currentInstanceId } ?: instances.firstOrNull()

    val libraryDetailInstance: Instance?
        get() = libraryDetailId?.let { id -> instances.firstOrNull { it.id == id } }

    /** Tasks still working; finished rows stay for history. */
    val activeDownloads: List<DownloadRow>
        get() = downloads.filter { it.state.isActive }

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
