package com.fograin.launcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.SettingsSection
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.DirectoryPick
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.action_cancel
import com.fograin.launcher.resources.settings_config_file
import com.fograin.launcher.resources.settings_copied
import com.fograin.launcher.resources.settings_copy_path
import com.fograin.launcher.resources.settings_data_root
import com.fograin.launcher.resources.settings_data_root_change
import com.fograin.launcher.resources.settings_data_root_desc
import com.fograin.launcher.resources.settings_data_root_dialog_body
import com.fograin.launcher.resources.settings_data_root_dialog_confirm
import com.fograin.launcher.resources.settings_data_root_dialog_title
import com.fograin.launcher.resources.settings_data_root_pending
import com.fograin.launcher.resources.settings_data_root_pick_title
import com.fograin.launcher.resources.settings_data_root_reset
import com.fograin.launcher.resources.settings_disk_free
import com.fograin.launcher.resources.settings_disk_free_value
import com.fograin.launcher.resources.settings_group_storage_data
import com.fograin.launcher.resources.settings_group_disk
import com.fograin.launcher.resources.settings_open_folder
import com.fograin.launcher.resources.settings_storage_android_locked
import com.fograin.launcher.resources.settings_storage_subtitle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

const val SettingsDataRootChangeTag = "settings-data-root-change"
const val SettingsDataRootResetTag = "settings-data-root-reset"
const val SettingsOpenDataRootTag = "settings-open-data-root"
const val SettingsCopyDataRootTag = "settings-copy-data-root"

/** Storage: data folder, disk space and the config file location. */
@Composable
internal fun StorageSettingsPage(
    model: AppModel,
    state: AppUiState,
    showTitle: Boolean,
) {
    val platform = remember { platformServices() }
    val scope = rememberCoroutineScope()
    val currentRoot = state.settings.dataRoot
    val pendingRoot = state.settingsWindow.dataRootOverride
    var candidate by remember { mutableStateOf<String?>(null) }
    val copied = stringResource(Res.string.settings_copied)
    val pickTitle = stringResource(Res.string.settings_data_root_pick_title)
    val canChangeRoot = platform.platformName != "android"

    SettingsPage(
        title = SettingsSection.STORAGE.title(),
        subtitle = stringResource(Res.string.settings_storage_subtitle),
        showTitle = showTitle,
    ) {
        item { SettingsGroupHeader(stringResource(Res.string.settings_group_storage_data)) }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
                Text(
                    text = stringResource(Res.string.settings_data_root),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(Res.string.settings_data_root_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = currentRoot,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("settings-data-root-value"),
                    )
                }
                if (pendingRoot != null) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.settings_data_root_pending),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = pendingRoot,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = { model.setDataRootOverride(null) },
                                modifier = Modifier.testTag(SettingsDataRootResetTag),
                            ) {
                                Text(stringResource(Res.string.settings_data_root_reset))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canChangeRoot) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val picked = platform.directories.pick(
                                        pickTitle,
                                        currentRoot,
                                    )
                                    if (picked is DirectoryPick.Picked) {
                                        candidate = picked.path
                                    }
                                }
                            },
                            modifier = Modifier.testTag(SettingsDataRootChangeTag),
                        ) {
                            Text(stringResource(Res.string.settings_data_root_change))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (!platform.links.revealDirectory(currentRoot)) {
                                platform.clipboard.copy(currentRoot)
                                model.showNotice(copied)
                            }
                        },
                        modifier = Modifier.testTag(SettingsOpenDataRootTag),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.size(7.dp))
                        Text(stringResource(Res.string.settings_open_folder))
                    }
                    TextButton(
                        onClick = {
                            platform.clipboard.copy(currentRoot)
                            model.showNotice(copied)
                        },
                        modifier = Modifier.testTag(SettingsCopyDataRootTag),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(Res.string.settings_copy_path))
                    }
                }
                if (!canChangeRoot) {
                    SettingsNote(text = stringResource(Res.string.settings_storage_android_locked))
                }
            }
            SettingsDivider()
        }
        item {
            SettingsValueRow(
                label = stringResource(Res.string.settings_config_file),
                value = state.settings.configPath,
                mono = true,
            )
            SettingsDivider()
        }

        item { SettingsGroupHeader(stringResource(Res.string.settings_group_disk)) }
        item {
            SettingsValueRow(
                label = stringResource(Res.string.settings_disk_free),
                value = stringResource(
                    Res.string.settings_disk_free_value,
                    InstanceFormat.byteSize(state.appSnapshot?.diskFreeBytes ?: 0L),
                ),
            )
            SettingsDivider()
        }
    }

    candidate?.let { path ->
        AlertDialog(
            onDismissRequest = { candidate = null },
            title = { Text(stringResource(Res.string.settings_data_root_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    Text(stringResource(Res.string.settings_data_root_dialog_body))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.setDataRootOverride(path)
                        candidate = null
                    },
                ) {
                    Text(stringResource(Res.string.settings_data_root_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { candidate = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}
