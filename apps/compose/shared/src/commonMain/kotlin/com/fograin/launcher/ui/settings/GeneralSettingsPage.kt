package com.fograin.launcher.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.SettingsSection
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.java_auto_download
import com.fograin.launcher.resources.java_auto_download_body
import com.fograin.launcher.resources.settings_auto_complete
import com.fograin.launcher.resources.settings_auto_complete_desc
import com.fograin.launcher.resources.settings_fullscreen
import com.fograin.launcher.resources.settings_fullscreen_desc
import com.fograin.launcher.resources.settings_general_subtitle
import com.fograin.launcher.resources.settings_group_game
import com.fograin.launcher.resources.settings_group_java
import com.fograin.launcher.resources.settings_group_startup
import com.fograin.launcher.resources.settings_initial_page
import com.fograin.launcher.resources.settings_initial_page_desc
import com.fograin.launcher.resources.settings_java_count
import com.fograin.launcher.resources.settings_java_open
import com.fograin.launcher.resources.settings_jvm_args
import com.fograin.launcher.resources.settings_jvm_args_desc
import com.fograin.launcher.resources.settings_jvm_args_hint
import com.fograin.launcher.resources.settings_memory
import com.fograin.launcher.resources.settings_memory_auto
import com.fograin.launcher.resources.settings_memory_auto_desc
import com.fograin.launcher.resources.settings_memory_desc
import com.fograin.launcher.resources.settings_memory_slider_desc
import com.fograin.launcher.resources.settings_memory_value
import com.fograin.launcher.resources.settings_resolution
import com.fograin.launcher.resources.settings_resolution_default
import com.fograin.launcher.resources.settings_resolution_desc
import com.fograin.launcher.resources.settings_resolution_value
import com.fograin.launcher.resources.settings_show_log
import com.fograin.launcher.resources.settings_show_log_desc
import com.fograin.launcher.resources.summary_memory_auto
import com.fograin.launcher.settings.InitialPage
import com.fograin.launcher.ui.deviceMemoryText
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

const val SettingsInitialPageRowTag = "settings-initial-page-row"
const val SettingsMemoryAutoTag = "settings-memory-auto"
const val SettingsMemorySliderTag = "settings-memory-slider"
const val SettingsJvmArgsTag = "settings-jvm-args"
const val SettingsFullscreenTag = "settings-fullscreen"
const val SettingsShowLogTag = "settings-show-log"
const val SettingsAutoCompleteTag = "settings-auto-complete"
const val SettingsJavaAutoDownloadTag = "settings-java-auto-download"
const val SettingsJavaOpenTag = "settings-java-open"

/**
 * General: the shell's initial page and the `[game]` defaults inherited by
 * every instance that does not override them, plus the Java shortcut.
 */
@Composable
internal fun GeneralSettingsPage(
    model: AppModel,
    state: AppUiState,
    showTitle: Boolean,
) {
    val config = state.gameConfig
    val memoryMb = config?.memoryMb ?: 0
    val autoMemory = memoryMb == 0
    val deviceGb = max(2, (state.deviceMemoryBytes / 1_073_741_824.0).roundToInt())
    val defaultGb = min(max(2, deviceGb / 4), 8)
    val configuredGb = if (memoryMb == 0) null else max(1, memoryMb / 1024)
    // Keep the configured value inside the slider even when it exceeds half of
    // the current machine's physical memory (moved config, VM, or swap).
    val maxGb = max(configuredGb ?: 0, min(max(4, deviceGb / 2), 32))
    var memoryGb by remember(memoryMb) {
        mutableStateOf(configuredGb ?: defaultGb)
    }
    val resolution = config?.resolution
    val resolutionLabel = if (resolution == null || resolution.size != 2) {
        stringResource(Res.string.settings_resolution_default)
    } else {
        stringResource(
            Res.string.settings_resolution_value,
            resolution[0],
            resolution[1],
        )
    }
    val java = state.java
    val javaCount = state.appSnapshot?.javaCount ?: java.runtimes.size

    SettingsPage(
        title = SettingsSection.GENERAL.title(),
        subtitle = stringResource(Res.string.settings_general_subtitle),
        showTitle = showTitle,
    ) {
        item { SettingsGroupHeader(stringResource(Res.string.settings_group_startup)) }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_initial_page),
                subtitle = stringResource(Res.string.settings_initial_page_desc),
                value = state.initialPage.title(),
                options = InitialPage.entries.map { page ->
                    page.title() to { model.settings.setInitialPage(page) }
                },
                testTag = SettingsInitialPageRowTag,
            )
            SettingsDivider()
        }

        item { SettingsGroupHeader(stringResource(Res.string.settings_group_game)) }
        item {
            SettingsToggleRow(
                title = stringResource(Res.string.settings_memory_auto),
                subtitle = stringResource(Res.string.settings_memory_auto_desc),
                checked = autoMemory,
                testTag = SettingsMemoryAutoTag,
                onCheckedChange = { auto ->
                    if (auto) {
                        model.settings.setMemoryMb(0)
                    } else {
                        memoryGb = defaultGb
                        model.settings.setMemoryMb(defaultGb * 1024)
                    }
                },
            )
            if (!autoMemory) {
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_memory),
                    subtitle = stringResource(Res.string.settings_memory_desc) + " " +
                        stringResource(Res.string.settings_memory_slider_desc),
                    valueText = stringResource(Res.string.settings_memory_value, memoryGb),
                    value = memoryGb.toFloat(),
                    range = 1f..maxGb.toFloat(),
                    enabled = true,
                    testTag = SettingsMemorySliderTag,
                    onValueChange = { memoryGb = it.roundToInt().coerceIn(1, maxGb) },
                    onCommit = { value ->
                        val gb = value.roundToInt().coerceIn(1, maxGb)
                        memoryGb = gb
                        model.settings.setMemoryMb(gb * 1024)
                    },
                )
            } else {
                SettingsNote(
                    text = stringResource(
                        Res.string.summary_memory_auto,
                        deviceMemoryText(state.deviceMemoryBytes),
                    ),
                )
            }
            SettingsDivider()
        }
        item {
            SettingsTextRow(
                title = stringResource(Res.string.settings_jvm_args),
                subtitle = stringResource(Res.string.settings_jvm_args_desc),
                value = config?.jvmArgs.orEmpty(),
                placeholder = stringResource(Res.string.settings_jvm_args_hint),
                testTag = SettingsJvmArgsTag,
                onCommit = model.settings::setJvmArgs,
            )
            SettingsDivider()
        }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_resolution),
                subtitle = stringResource(Res.string.settings_resolution_desc),
                value = resolutionLabel,
                options = buildList {
                    add(stringResource(Res.string.settings_resolution_default) to {
                        model.setGlobalResolution(null, null)
                    })
                    listOf(1280 to 720, 1600 to 900, 1920 to 1080, 2560 to 1440, 3840 to 2160)
                        .forEach { (width, height) ->
                            add(
                                stringResource(Res.string.settings_resolution_value, width, height) to {
                                    model.setGlobalResolution(width, height)
                                },
                            )
                        }
                },
            )
            SettingsDivider()
        }
        item {
            SettingsToggleRow(
                title = stringResource(Res.string.settings_fullscreen),
                subtitle = stringResource(Res.string.settings_fullscreen_desc),
                checked = config?.fullscreen == true,
                testTag = SettingsFullscreenTag,
                onCheckedChange = model.settings::setFullscreen,
            )
            SettingsDivider()
        }
        item {
            SettingsToggleRow(
                title = stringResource(Res.string.settings_show_log),
                subtitle = stringResource(Res.string.settings_show_log_desc),
                checked = config?.showLog == true,
                testTag = SettingsShowLogTag,
                onCheckedChange = model.settings::setShowLog,
            )
            SettingsDivider()
        }
        item {
            SettingsToggleRow(
                title = stringResource(Res.string.settings_auto_complete),
                subtitle = stringResource(Res.string.settings_auto_complete_desc),
                checked = config?.autoCompleteResources != false,
                testTag = SettingsAutoCompleteTag,
                onCheckedChange = model::setGlobalAutoCompleteResources,
            )
            SettingsDivider()
        }

        item { SettingsGroupHeader(stringResource(Res.string.settings_group_java)) }
        item {
            SettingsToggleRow(
                title = stringResource(Res.string.java_auto_download),
                subtitle = stringResource(Res.string.java_auto_download_body),
                checked = java.autoDownload,
                testTag = SettingsJavaAutoDownloadTag,
                onCheckedChange = model.java::setAutoDownload,
            )
            SettingsDivider()
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_java_open),
                subtitle = stringResource(
                    Res.string.settings_java_count,
                    javaCount,
                    java.managedCount,
                ),
                modifier = Modifier.testTag(SettingsJavaOpenTag),
                onClick = { model.settings.push(SettingsSection.JAVA) },
                trailing = {
                    SettingsChevron()
                },
            )
            SettingsDivider()
        }
    }
}

/** Shared chevron for rows that navigate. */
@Composable
internal fun SettingsChevron() {
    Icon(
        imageVector = Icons.Outlined.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}
