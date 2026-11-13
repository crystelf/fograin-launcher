package com.fograin.launcher.ui

import androidx.compose.runtime.Composable
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.DownloadRow
import com.fograin.launcher.app.HomeFilter
import com.fograin.launcher.model.ContentLoaderFilter
import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.LibraryGroup
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.resources.*
import com.fograin.launcher.settings.ThemeMode
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

/**
 * Resource-backed presentation helpers shared by the four pages. Keeping the
 * Chinese copy in `composeResources` means the screens themselves contain no
 * user-facing literals; M10 can add locales without touching layout code.
 */

@Composable
fun HomeFilter.title(): String = stringResource(
    when (this) {
        HomeFilter.RECENT -> Res.string.home_filter_recent
        HomeFilter.FAVORITES -> Res.string.home_filter_favorites
        HomeFilter.ALL -> Res.string.home_filter_all
    },
)

@Composable
fun LibraryScope.title(): String = stringResource(
    when (this) {
        LibraryScope.ALL -> Res.string.library_scope_all
        LibraryScope.FAVORITES -> Res.string.library_scope_favorites
        LibraryScope.FABRIC -> Res.string.library_scope_fabric
        LibraryScope.FORGE -> Res.string.library_scope_forge
        LibraryScope.NEOFORGE -> Res.string.library_scope_neoforge
        LibraryScope.VANILLA -> Res.string.library_scope_vanilla
    },
)

@Composable
fun LibrarySort.title(): String = stringResource(
    when (this) {
        LibrarySort.RECENT -> Res.string.library_sort_recent
        LibrarySort.NAME -> Res.string.library_sort_name
        LibrarySort.SIZE -> Res.string.library_sort_size
    },
)

@Composable
fun LibraryGroup.title(): String = stringResource(
    when (this) {
        LibraryGroup.FABRIC -> Res.string.library_scope_fabric
        LibraryGroup.FORGE -> Res.string.library_scope_forge
        LibraryGroup.NEOFORGE -> Res.string.library_scope_neoforge
        LibraryGroup.VANILLA -> Res.string.library_scope_vanilla
    },
)

@Composable
fun DownloadsCategory.title(): String = stringResource(
    when (this) {
        DownloadsCategory.GAME -> Res.string.downloads_category_game
        DownloadsCategory.MOD -> Res.string.downloads_category_mod
        DownloadsCategory.MODPACK -> Res.string.downloads_category_modpack
        DownloadsCategory.RESOURCEPACK -> Res.string.downloads_category_resourcepack
        DownloadsCategory.SHADERPACK -> Res.string.downloads_category_shaderpack
        DownloadsCategory.DATAPACK -> Res.string.downloads_category_datapack
    },
)

@Composable
fun DownloadVersionChannel.title(): String = stringResource(
    when (this) {
        DownloadVersionChannel.RELEASE -> Res.string.downloads_channel_release
        DownloadVersionChannel.SNAPSHOT -> Res.string.downloads_channel_snapshot
        DownloadVersionChannel.APRIL_FOOLS -> Res.string.downloads_channel_april_fools
        DownloadVersionChannel.OLD -> Res.string.downloads_channel_old
    },
)

@Composable
fun DownloadVersionChannel.emptyMessage(): String = stringResource(
    when (this) {
        DownloadVersionChannel.RELEASE -> Res.string.downloads_channel_empty_release
        DownloadVersionChannel.SNAPSHOT -> Res.string.downloads_channel_empty_snapshot
        DownloadVersionChannel.APRIL_FOOLS -> Res.string.downloads_channel_empty_april_fools
        DownloadVersionChannel.OLD -> Res.string.downloads_channel_empty_old
    },
)

@Composable
fun ContentLoaderFilter.title(): String = when (this) {
    ContentLoaderFilter.ALL -> stringResource(Res.string.downloads_loader_all)
    ContentLoaderFilter.FORGE -> "Forge"
    ContentLoaderFilter.NEOFORGE -> "NeoForge"
    ContentLoaderFilter.FABRIC -> "Fabric"
    ContentLoaderFilter.QUILT -> "Quilt"
    ContentLoaderFilter.OPTIFINE -> "OptiFine"
}

@Composable
fun ThemeMode.title(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> Res.string.settings_theme_system
        ThemeMode.LIGHT -> Res.string.settings_theme_light
        ThemeMode.DARK -> Res.string.settings_theme_dark
    },
)

@Composable
fun DownloadRow.stateText(): String = when (state) {
    "succeeded" -> stringResource(Res.string.status_succeeded)
    "failed" -> stringResource(Res.string.status_failed)
    "paused" -> stringResource(Res.string.status_paused)
    "cancelled" -> stringResource(Res.string.status_cancelled)
    else -> "${(fraction * 100).toInt()}%"
}

@Composable
fun downloadSpeedText(state: AppUiState): String {
    val total = state.activeDownloads.sumOf { it.speed }
    if (total <= 0) {
        return if (state.activeDownloads.isEmpty()) {
            stringResource(Res.string.home_speed_idle)
        } else {
            "—"
        }
    }
    return InstanceFormat.byteRate(total) + "/s"
}

/** `未知`, `16 GB` — one value used by both memory summaries. */
@Composable
fun deviceMemoryText(bytes: Long): String {
    if (bytes <= 0) return stringResource(Res.string.device_unknown)
    val gigabytes = bytes / 1_073_741_824.0
    return stringResource(Res.string.degree_gb, maxOf(1, (gigabytes + 0.5).roundToLong()).toInt())
}

@Composable
fun AppUiState.memorySummary(): String {
    val memoryMb = gameConfig?.memoryMb ?: 0
    val physical = deviceMemoryText(deviceMemoryBytes)
    return if (memoryMb == 0) {
        stringResource(Res.string.summary_memory_auto, physical)
    } else {
        stringResource(Res.string.summary_memory_explicit, maxOf(1, memoryMb / 1024), physical)
    }
}

@Composable
fun AppUiState.javaSummary(): String {
    val count = appSnapshot?.javaCount ?: return stringResource(Res.string.summary_java_scanning)
    return if (count == 0) {
        stringResource(Res.string.summary_java_none)
    } else {
        stringResource(Res.string.summary_java_found, count)
    }
}

@Composable
fun AppUiState.homeSubtitle(): String {
    val lastPlayed = instances.mapNotNull { it.lastPlayedEpochMillis }.maxOrNull()
    return stringResource(
        Res.string.summary_instances_count,
        instances.size,
        InstanceFormat.relativeTime(lastPlayed),
    )
}

@Composable
fun AppUiState.librarySubtitle(): String = stringResource(
    Res.string.summary_library,
    instances.size,
    InstanceFormat.totalSize(instances),
)
