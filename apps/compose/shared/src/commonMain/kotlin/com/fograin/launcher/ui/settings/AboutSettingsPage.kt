package com.fograin.launcher.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.Notice
import com.fograin.launcher.app.SettingsSection
import com.fograin.launcher.platform.ReleaseInfo
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.app_icon
import com.fograin.launcher.resources.settings_about_body
import com.fograin.launcher.resources.settings_about_build
import com.fograin.launcher.resources.settings_about_config
import com.fograin.launcher.resources.settings_about_copied
import com.fograin.launcher.resources.settings_about_copy
import com.fograin.launcher.resources.settings_about_data
import com.fograin.launcher.resources.settings_about_license
import com.fograin.launcher.resources.settings_about_license_value
import com.fograin.launcher.resources.settings_about_runtime
import com.fograin.launcher.resources.settings_about_subtitle
import com.fograin.launcher.resources.settings_about_title
import com.fograin.launcher.resources.settings_about_update_title
import com.fograin.launcher.resources.settings_about_version
import com.fograin.launcher.resources.settings_app_version
import com.fograin.launcher.resources.settings_core
import com.fograin.launcher.resources.settings_core_value
import com.fograin.launcher.resources.settings_ffi
import com.fograin.launcher.resources.settings_ffi_missing
import com.fograin.launcher.resources.settings_ffi_value
import com.fograin.launcher.resources.settings_group_about
import com.fograin.launcher.resources.settings_open_folder
import com.fograin.launcher.resources.settings_platform
import com.fograin.launcher.resources.settings_thanks
import com.fograin.launcher.resources.settings_thanks_body
import com.fograin.launcher.resources.settings_trademark
import com.fograin.launcher.resources.settings_ui
import com.fograin.launcher.resources.settings_ui_value
import com.fograin.launcher.resources.update_available_title
import com.fograin.launcher.resources.update_check
import com.fograin.launcher.resources.update_check_failed
import com.fograin.launcher.resources.update_check_hint
import com.fograin.launcher.resources.update_checking
import com.fograin.launcher.resources.update_group
import com.fograin.launcher.resources.update_open
import com.fograin.launcher.resources.update_open_failed
import com.fograin.launcher.resources.update_up_to_date
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

const val SettingsCopyDiagnosticsTag = "settings-copy-diagnostics"
const val SettingsOpenAboutRootTag = "settings-open-about-root"
const val SettingsCheckUpdatesTag = "settings-check-updates"

/** About: build identity, paths and acknowledgements. */
@Composable
internal fun AboutSettingsPage(
    model: AppModel,
    state: AppUiState,
    showTitle: Boolean,
) {
    val platform = remember { platformServices() }
    val copied = stringResource(Res.string.settings_about_copied)
    val uiValue = stringResource(Res.string.settings_ui_value)
    val appVersion = state.settings.appVersion
    val platformName = state.settings.platform
    val abiVersion = state.settings.abiVersion
    val coreReady = model.isCoreAvailable
    val updates = state.updates
    val updateStatus = when {
        updates.checking -> stringResource(Res.string.update_checking)
        updates.available != null ->
            stringResource(Res.string.update_available_title, updates.available.version)

        updates.failed -> stringResource(Res.string.update_check_failed)
        updates.checked -> stringResource(Res.string.update_up_to_date)
        else -> stringResource(Res.string.update_check_hint)
    }
    val updateOpenFailed = stringResource(
        Res.string.update_open_failed,
        updates.available?.url.orEmpty(),
    )
    val labels = AboutLabels(
        version = stringResource(Res.string.settings_about_version),
        ui = stringResource(Res.string.settings_ui),
        core = stringResource(Res.string.settings_core),
        ffi = stringResource(Res.string.settings_ffi),
        platform = stringResource(Res.string.settings_platform),
        data = stringResource(Res.string.settings_about_data),
        config = stringResource(Res.string.settings_about_config),
    )

    SettingsPage(
        title = SettingsSection.ABOUT.title(),
        subtitle = stringResource(Res.string.settings_about_subtitle),
        showTitle = showTitle,
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Image(
                        painter = painterResource(Res.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(Res.string.settings_about_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(Res.string.settings_about_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingsDivider()
        }
        item { SettingsGroupHeader(stringResource(Res.string.settings_about_build)) }
        item {
            SettingsValueRow(
                label = stringResource(Res.string.settings_app_version),
                value = appVersion,
                testTag = "settings-about-version",
            )
            SettingsDivider()
            SettingsValueRow(label = labels.ui, value = stringResource(Res.string.settings_ui_value))
            SettingsDivider()
            SettingsValueRow(label = labels.core, value = stringResource(Res.string.settings_core_value))
            SettingsDivider()
            SettingsValueRow(
                label = labels.ffi,
                value = if (coreReady) {
                    stringResource(Res.string.settings_ffi_value, abiVersion)
                } else {
                    stringResource(Res.string.settings_ffi_missing)
                },
            )
            SettingsDivider()
            SettingsValueRow(label = labels.platform, value = platformName)
            SettingsDivider()
            SettingsValueRow(
                label = stringResource(Res.string.settings_about_license),
                value = stringResource(Res.string.settings_about_license_value),
            )
            SettingsDivider()
        }

        item { SettingsGroupHeader(stringResource(Res.string.update_group)) }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_about_update_title),
                subtitle = updateStatus,
                trailing = {
                    if (updates.available != null) {
                        TextButton(onClick = { openUpdate(model, updates.available, updateOpenFailed) }) {
                            Text(stringResource(Res.string.update_open))
                        }
                    } else {
                        TextButton(
                            onClick = { model.checkForUpdates() },
                            enabled = !updates.checking,
                            modifier = Modifier.testTag(SettingsCheckUpdatesTag),
                        ) {
                            if (updates.checking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(stringResource(Res.string.update_check))
                            }
                        }
                    }
                },
            )
            SettingsDivider()
        }

        item { SettingsGroupHeader(stringResource(Res.string.settings_about_runtime)) }
        item {
            SettingsValueRow(
                label = labels.data,
                value = state.settings.dataRoot,
                mono = true,
            )
            SettingsDivider()
            SettingsValueRow(
                label = labels.config,
                value = state.settings.configPath,
                mono = true,
            )
            SettingsDivider()
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (!platform.links.revealDirectory(state.settings.dataRoot)) {
                            platform.clipboard.copy(state.settings.dataRoot)
                            model.showNotice(copied)
                        }
                    },
                    modifier = Modifier.testTag(SettingsOpenAboutRootTag),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(stringResource(Res.string.settings_open_folder))
                }
                OutlinedButton(
                    onClick = {
                        val report = buildString {
                            appendLine("Fograin Launcher $appVersion")
                            appendLine("${labels.ui}: $uiValue")
                            appendLine("${labels.core}: Rust")
                            appendLine("${labels.ffi}: $abiVersion")
                            appendLine("${labels.platform}: $platformName")
                            appendLine("${labels.data}: ${state.settings.dataRoot}")
                            appendLine("${labels.config}: ${state.settings.configPath}")
                        }
                        platform.clipboard.copy(report)
                        model.showNotice(copied)
                    },
                    modifier = Modifier.testTag(SettingsCopyDiagnosticsTag),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(Res.string.settings_about_copy))
                }
            }
        }

        item { SettingsGroupHeader(stringResource(Res.string.settings_group_about)) }
        item {
            Text(
                text = stringResource(Res.string.settings_thanks),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )
            Text(
                text = stringResource(Res.string.settings_thanks_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Text(
                text = stringResource(Res.string.settings_trademark),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
    }
}

private fun openUpdate(
    model: AppModel,
    update: ReleaseInfo?,
    failureMessage: String,
) {
    if (!model.openUpdate(update)) {
        model.showNotice(failureMessage, Notice.Tone.WARNING)
    }
}

private data class AboutLabels(
    val version: String,
    val ui: String,
    val core: String,
    val ffi: String,
    val platform: String,
    val data: String,
    val config: String,
)
