@file:OptIn(ExperimentalTime::class)

package com.fograin.launcher.ui

import androidx.compose.runtime.Composable
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.ContentManagerTab
import com.fograin.launcher.app.DownloadRow
import com.fograin.launcher.app.HomeFilter
import com.fograin.launcher.model.ContentLoaderFilter
import com.fograin.launcher.model.ContentVersionChannel
import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.LoaderKind
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
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
}

@Composable
fun ContentVersionChannel.title(): String = stringResource(
    when (this) {
        ContentVersionChannel.RELEASE -> Res.string.content_version_release
        ContentVersionChannel.BETA -> Res.string.content_version_beta
        ContentVersionChannel.ALPHA -> Res.string.content_version_alpha
        ContentVersionChannel.ALL -> Res.string.content_version_all
    },
)

@Composable
fun ContentManagerTab.title(): String = when (this) {
    ContentManagerTab.MODS -> stringResource(Res.string.content_tab_mods)
    ContentManagerTab.RESOURCE_PACKS -> stringResource(Res.string.content_tab_resourcepacks)
    ContentManagerTab.SHADER_PACKS -> stringResource(Res.string.content_tab_shaderpacks)
    ContentManagerTab.WORLDS -> stringResource(Res.string.content_tab_worlds)
    ContentManagerTab.DATAPACKS -> stringResource(Res.string.content_tab_datapacks)
}

@Composable
fun ContentManagerTab.emptyTitle(): String = when (this) {
    ContentManagerTab.MODS -> stringResource(Res.string.content_empty_mods)
    ContentManagerTab.RESOURCE_PACKS -> stringResource(Res.string.content_empty_resourcepacks)
    ContentManagerTab.SHADER_PACKS -> stringResource(Res.string.content_empty_shaderpacks)
    ContentManagerTab.WORLDS -> stringResource(Res.string.content_empty_worlds)
    ContentManagerTab.DATAPACKS -> stringResource(Res.string.datapack_empty_title)
}

@Composable
fun ThemeMode.title(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> Res.string.settings_theme_system
        ThemeMode.LIGHT -> Res.string.settings_theme_light
        ThemeMode.DARK -> Res.string.settings_theme_dark
    },
)

/** Localized loader name for instance summaries. */
@Composable
fun LoaderKind.titleText(): String = when (this) {
    LoaderKind.VANILLA -> stringResource(Res.string.library_scope_vanilla)
    else -> title
}

/** Install-page loader name; only Vanilla differs from the wire label. */
@Composable
fun GameLoaderKind.titleText(): String = when (this) {
    GameLoaderKind.VANILLA -> stringResource(Res.string.library_scope_vanilla)
    else -> title
}

/** Install-page one-line description in the current language. */
@Composable
fun GameLoaderKind.subtitleText(): String = stringResource(
    when (this) {
        GameLoaderKind.VANILLA -> Res.string.loader_vanilla_subtitle
        GameLoaderKind.FABRIC -> Res.string.loader_fabric_subtitle
        GameLoaderKind.QUILT -> Res.string.loader_quilt_subtitle
        GameLoaderKind.FORGE -> Res.string.loader_forge_subtitle
        GameLoaderKind.NEOFORGE -> Res.string.loader_neoforge_subtitle
        GameLoaderKind.OPTIFINE -> Res.string.loader_optifine_subtitle
    },
)

/** `1.21.4 · Fabric · 42 模组` in the current language. */
@Composable
fun Instance.metaText(): String {
    val loaderLabel = loader.titleText()
    val parts = buildList {
        add(minecraftVersion)
        add(loaderLabel)
        modCount?.let { add(stringResource(Res.string.instance_meta_mods, it)) }
    }
    return parts.joinToString(" · ")
}

/** `1.21.4 · Fabric 0.16.9`, or just the Minecraft version for vanilla. */
@Composable
fun Instance.versionLineText(): String {
    if (loaderVersion.isBlank()) return minecraftVersion
    return "$minecraftVersion · ${loader.titleText()} $loaderVersion"
}

/** Relative "last played" label in the current language. */
@Composable
fun relativeTimeText(
    epochMillis: Long?,
    nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (epochMillis == null) return stringResource(Res.string.time_never_played)
    val delta = nowMillis - epochMillis
    if (delta < 60_000L) return stringResource(Res.string.time_just_now)
    val minutes = delta / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 60L -> stringResource(Res.string.time_minutes_ago, minutes.toInt())
        hours < 24L -> stringResource(Res.string.time_hours_ago, hours.toInt())
        days == 1L -> stringResource(Res.string.time_yesterday)
        days < 7L -> stringResource(Res.string.time_days_ago, days.toInt())
        days < 14L -> stringResource(Res.string.time_last_week)
        else -> {
            val date = Instant.fromEpochMilliseconds(epochMillis)
                .toLocalDateTime(timeZone)
                .date
            stringResource(Res.string.time_month_day, date.month.number, date.day)
        }
    }
}

/** Download counter with a language-appropriate compact unit. */
@Composable
fun downloadsCountText(count: Long): String = when {
    count >= 100_000_000L -> {
        val tenths = count * 10 / 100_000_000L
        stringResource(
            Res.string.content_downloads_ten_millions,
            "${tenths / 10}.${tenths % 10}",
        )
    }

    count >= 10_000L -> stringResource(
        Res.string.content_downloads_thousands,
        count / 1_000L,
    )

    else -> stringResource(Res.string.content_downloads_exact, count)
}

/** Localized task title built from the raw core graph name. */
@Composable
fun DownloadRow.titleText(): String {
    val name = graphName.ifBlank { title }
    return when {
        name.startsWith("install:") ->
            stringResource(Res.string.task_install, name.removePrefix("install:"))

        name.startsWith("java:") ->
            stringResource(Res.string.task_java, name.removePrefix("java:"))

        name.startsWith("content:") ->
            stringResource(Res.string.task_content_install, name.removePrefix("content:"))

        name.startsWith("整合包下载:") ->
            stringResource(Res.string.task_modpack_download, name.removePrefix("整合包下载:"))

        name.startsWith("整合包:") ->
            stringResource(Res.string.task_modpack_import, name.removePrefix("整合包:"))

        else -> title
    }
}

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
