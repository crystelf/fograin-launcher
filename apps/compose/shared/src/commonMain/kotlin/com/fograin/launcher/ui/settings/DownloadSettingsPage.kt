package com.fograin.launcher.ui.settings

import androidx.compose.runtime.Composable
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.SettingsSection
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.settings_concurrency
import com.fograin.launcher.resources.settings_concurrency_auto
import com.fograin.launcher.resources.settings_concurrency_auto_value
import com.fograin.launcher.resources.settings_concurrency_desc
import com.fograin.launcher.resources.settings_download_source_desc
import com.fograin.launcher.resources.settings_download_source_title
import com.fograin.launcher.resources.settings_download_subtitle
import com.fograin.launcher.resources.settings_group_engine
import com.fograin.launcher.resources.settings_group_source
import com.fograin.launcher.resources.settings_per_host
import com.fograin.launcher.resources.settings_per_host_desc
import com.fograin.launcher.resources.settings_per_host_value
import com.fograin.launcher.resources.settings_rate_limit
import com.fograin.launcher.resources.settings_rate_limit_desc
import com.fograin.launcher.resources.settings_rate_limit_off
import com.fograin.launcher.resources.settings_rate_limit_value
import com.fograin.launcher.resources.settings_restart_engine
import com.fograin.launcher.resources.settings_retries
import com.fograin.launcher.resources.settings_retries_desc
import com.fograin.launcher.resources.settings_retries_value
import com.fograin.launcher.resources.settings_segment_threshold
import com.fograin.launcher.resources.settings_segment_threshold_desc
import com.fograin.launcher.resources.settings_segments
import com.fograin.launcher.resources.settings_segments_desc
import com.fograin.launcher.resources.settings_segments_off
import com.fograin.launcher.resources.settings_segments_value
import com.fograin.launcher.resources.settings_source_auto
import com.fograin.launcher.resources.settings_source_mirror
import com.fograin.launcher.resources.settings_source_official
import com.fograin.launcher.resources.settings_threshold_mb
import com.fograin.launcher.resources.settings_threshold_off
import com.fograin.launcher.resources.settings_version_source_desc
import com.fograin.launcher.resources.settings_version_source_title
import org.jetbrains.compose.resources.stringResource

const val SettingsDownloadSourceTag = "settings-download-source"
const val SettingsVersionSourceTag = "settings-version-source"
const val SettingsConcurrencyTag = "settings-concurrency"
const val SettingsSegmentsTag = "settings-segments"
const val SettingsSegmentThresholdTag = "settings-segment-threshold"
const val SettingsRetriesTag = "settings-retries"
const val SettingsPerHostTag = "settings-per-host"
const val SettingsRateLimitTag = "settings-rate-limit"

/** Download engine: mirror preference, concurrency, segmented download and caps. */
@Composable
internal fun DownloadSettingsPage(
    model: AppModel,
    state: AppUiState,
    showTitle: Boolean,
) {
    val download = state.settingsWindow.download
    val autoConcurrency = (state.cpuCount * 4).coerceIn(4, 64)

    SettingsPage(
        title = SettingsSection.DOWNLOAD.title(),
        subtitle = stringResource(Res.string.settings_download_subtitle),
        showTitle = showTitle,
    ) {
        item { SettingsGroupHeader(stringResource(Res.string.settings_group_source)) }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_download_source_title),
                subtitle = stringResource(Res.string.settings_download_source_desc),
                value = download.source.sourceLabel(),
                options = downloadSourceOptions { value ->
                    model.updateDownloadSettings { it.copy(source = value) }
                },
                testTag = SettingsDownloadSourceTag,
            )
            SettingsDivider()
        }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_version_source_title),
                subtitle = stringResource(Res.string.settings_version_source_desc),
                value = download.versionSource.sourceLabel(),
                options = downloadSourceOptions { value ->
                    model.updateDownloadSettings { it.copy(versionSource = value) }
                },
                testTag = SettingsVersionSourceTag,
            )
            SettingsDivider()
        }

        item { SettingsGroupHeader(stringResource(Res.string.settings_group_engine)) }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_concurrency),
                subtitle = stringResource(Res.string.settings_concurrency_desc),
                value = if (download.concurrency == 0) {
                    stringResource(Res.string.settings_concurrency_auto_value, autoConcurrency)
                } else {
                    download.concurrency.toString()
                },
                options = buildList {
                    add(stringResource(Res.string.settings_concurrency_auto) to {
                        model.updateDownloadSettings { it.copy(concurrency = 0) }
                    })
                    listOf(4, 8, 16, 24, 32, 64).forEach { count ->
                        add(count.toString() to {
                            model.updateDownloadSettings { it.copy(concurrency = count) }
                        })
                    }
                },
                testTag = SettingsConcurrencyTag,
            )
            SettingsDivider()
        }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_segments),
                subtitle = stringResource(Res.string.settings_segments_desc),
                value = if (download.segments <= 1) {
                    stringResource(Res.string.settings_segments_off)
                } else {
                    stringResource(Res.string.settings_segments_value, download.segments)
                },
                options = buildList {
                    add(stringResource(Res.string.settings_segments_off) to {
                        model.updateDownloadSettings { it.copy(segments = 1) }
                    })
                    listOf(2, 4, 8, 16).forEach { count ->
                        add(stringResource(Res.string.settings_segments_value, count) to {
                            model.updateDownloadSettings { it.copy(segments = count) }
                        })
                    }
                },
                testTag = SettingsSegmentsTag,
            )
            SettingsDivider()
        }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_segment_threshold),
                subtitle = stringResource(Res.string.settings_segment_threshold_desc),
                value = if (download.segmentThresholdMb == 0) {
                    stringResource(Res.string.settings_threshold_off)
                } else {
                    stringResource(Res.string.settings_threshold_mb, download.segmentThresholdMb)
                },
                options = buildList {
                    add(stringResource(Res.string.settings_threshold_off) to {
                        model.updateDownloadSettings { it.copy(segmentThresholdMb = 0) }
                    })
                    listOf(8, 16, 32, 64, 128).forEach { size ->
                        add(stringResource(Res.string.settings_threshold_mb, size) to {
                            model.updateDownloadSettings { it.copy(segmentThresholdMb = size) }
                        })
                    }
                },
                testTag = SettingsSegmentThresholdTag,
            )
            SettingsDivider()
        }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_per_host),
                subtitle = stringResource(Res.string.settings_per_host_desc),
                value = stringResource(Res.string.settings_per_host_value, download.perHost),
                options = listOf(4, 8, 16, 32, 64).map { count ->
                    stringResource(Res.string.settings_per_host_value, count) to {
                        model.updateDownloadSettings { it.copy(perHost = count) }
                    }
                },
                testTag = SettingsPerHostTag,
            )
            SettingsDivider()
        }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_retries),
                subtitle = stringResource(Res.string.settings_retries_desc),
                value = stringResource(Res.string.settings_retries_value, download.retries),
                options = listOf(1, 2, 3, 5, 10).map { count ->
                    stringResource(Res.string.settings_retries_value, count) to {
                        model.updateDownloadSettings { it.copy(retries = count) }
                    }
                },
                testTag = SettingsRetriesTag,
            )
            SettingsDivider()
        }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_rate_limit),
                subtitle = stringResource(Res.string.settings_rate_limit_desc),
                value = if (download.rateLimitKib == 0) {
                    stringResource(Res.string.settings_rate_limit_off)
                } else {
                    stringResource(
                        Res.string.settings_rate_limit_value,
                        InstanceFormat.byteRate(download.rateLimitKib * 1024.0),
                    )
                },
                options = buildList {
                    add(stringResource(Res.string.settings_rate_limit_off) to {
                        model.updateDownloadSettings { it.copy(rateLimitKib = 0) }
                    })
                    listOf(1024, 4096, 8192, 16384).forEach { kib ->
                        add(
                            stringResource(
                                Res.string.settings_rate_limit_value,
                                InstanceFormat.byteRate(kib * 1024.0),
                            ) to {
                                model.updateDownloadSettings { it.copy(rateLimitKib = kib) }
                            },
                        )
                    }
                },
                testTag = SettingsRateLimitTag,
            )
            SettingsDivider()
        }
        item {
            SettingsNote(text = stringResource(Res.string.settings_restart_engine))
        }
    }
}

@Composable
private fun String.sourceLabel(): String = stringResource(
    when (this) {
        "official" -> Res.string.settings_source_official
        "mirror" -> Res.string.settings_source_mirror
        else -> Res.string.settings_source_auto
    },
)

@Composable
private fun downloadSourceOptions(onPick: (String) -> Unit): List<Pair<String, () -> Unit>> = listOf(
    stringResource(Res.string.settings_source_auto) to { onPick("auto") },
    stringResource(Res.string.settings_source_official) to { onPick("official") },
    stringResource(Res.string.settings_source_mirror) to { onPick("mirror") },
)
