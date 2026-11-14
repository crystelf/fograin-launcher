package com.fograin.launcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.*
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.components.KeyValueRow
import com.fograin.launcher.ui.components.MilestoneChip
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.javaSummary
import com.fograin.launcher.ui.title
import org.jetbrains.compose.resources.stringResource

/**
 * Settings: the shared appearance contract (§4.1.1) written through the Rust
 * core, the platform-only dynamic colour switch, build info and the milestone
 * rows for settings that later milestones own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    model: AppModel,
    state: AppUiState,
    dynamicColorEnabled: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    val preferences = remember { platformServices().preferences }
    val supportsDynamicColor = preferences.supportsDynamicColor()
    val coreReady = model.isCoreAvailable
    val initializationError = model.initializationError

    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(title = stringResource(Res.string.settings_title))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!coreReady || initializationError != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Column {
                                Text(
                                    text = stringResource(
                                        if (coreReady) {
                                            Res.string.settings_core_config_failed
                                        } else {
                                            Res.string.settings_core_unavailable
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = initializationError
                                        ?: stringResource(Res.string.settings_core_unavailable_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }

            item { SettingsGroupTitle(stringResource(Res.string.settings_appearance)) }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(Res.string.settings_theme),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            ThemeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = state.theme == mode,
                                    onClick = { model.setTheme(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = ThemeMode.entries.size,
                                    ),
                                    label = { Text(mode.title()) },
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.settings_dynamic_color),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(
                                        if (supportsDynamicColor) {
                                            Res.string.settings_dynamic_color_supported
                                        } else {
                                            Res.string.settings_dynamic_color_unsupported
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = dynamicColorEnabled,
                                onCheckedChange = onDynamicColorChange,
                                enabled = supportsDynamicColor,
                            )
                        }
                    }
                }
            }

            item { SettingsGroupTitle(stringResource(Res.string.settings_launch_network)) }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MilestoneRow(
                            stringResource(Res.string.settings_language),
                            stringResource(Res.string.settings_language_value),
                            stringResource(Res.string.settings_milestone_m10),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MilestoneRow(
                            stringResource(Res.string.settings_download_source),
                            stringResource(Res.string.settings_download_source_value),
                            stringResource(Res.string.settings_milestone_m2),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MilestoneRow(
                            stringResource(Res.string.settings_max_memory),
                            stringResource(Res.string.settings_max_memory_value),
                            stringResource(Res.string.settings_milestone_m6),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MilestoneRow(
                            stringResource(Res.string.settings_concurrency),
                            stringResource(Res.string.settings_concurrency_value),
                            stringResource(Res.string.settings_milestone_m2),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MilestoneRow(
                            stringResource(Res.string.settings_log_level),
                            stringResource(Res.string.settings_log_level_value),
                            stringResource(Res.string.settings_milestone_m10),
                        )
                    }
                }
            }

            item { SettingsGroupTitle(stringResource(Res.string.settings_build)) }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyValueRow(
                            label = stringResource(Res.string.settings_app_version),
                            value = state.settings.appVersion,
                        )
                        KeyValueRow(
                            label = stringResource(Res.string.settings_ui),
                            value = stringResource(Res.string.settings_ui_value),
                        )
                        KeyValueRow(
                            label = stringResource(Res.string.settings_core),
                            value = stringResource(Res.string.settings_core_value),
                        )
                        KeyValueRow(
                            label = stringResource(Res.string.settings_ffi),
                            value = if (coreReady) {
                                stringResource(Res.string.settings_ffi_value, state.settings.abiVersion)
                            } else {
                                stringResource(Res.string.settings_ffi_missing)
                            },
                        )
                        KeyValueRow(
                            label = stringResource(Res.string.settings_platform),
                            value = state.settings.platform,
                        )
                        KeyValueRow(
                            label = stringResource(Res.string.settings_java),
                            value = state.javaSummary(),
                            checkmark = (state.appSnapshot?.javaCount ?: 0) > 0,
                        )
                    }
                }
            }

            item { SettingsGroupTitle(stringResource(Res.string.settings_about)) }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(Res.string.settings_about_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(Res.string.settings_about_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = stringResource(Res.string.settings_thanks),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(Res.string.settings_thanks_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(Res.string.settings_trademark),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun MilestoneRow(title: String, value: String, milestone: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(4.dp))
        MilestoneChip(milestone)
    }
}
