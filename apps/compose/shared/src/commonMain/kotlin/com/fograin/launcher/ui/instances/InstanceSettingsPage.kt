package com.fograin.launcher.ui.instances

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.ContentManagerTab
import com.fograin.launcher.model.Instance
import com.fograin.launcher.platform.CoreInstanceSettings
import com.fograin.launcher.platform.CoreJavaRuntime
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.DropdownField
import com.fograin.launcher.ui.components.InstanceTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.jetbrains.compose.resources.stringResource

const val InstanceSettingsPageTag: String = "instance-settings-page"

/**
 * Instance detail · 游戏设置 (M4 scope): 启动/内存/显示/启动行为, the M8 content
 * shortcuts and maintenance actions. Every change is written through
 * `instances.settings.set`.
 *
 * This used to be a bottom sheet; it now lives on a library sub-page so the
 * management surface never mixes full pages and modal sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceSettingsPage(
    model: AppModel,
    instance: Instance,
    modifier: Modifier = Modifier,
) {
    var loaded by remember(instance.id) { mutableStateOf<CoreInstanceSettings?>(null) }
    var loadError by remember(instance.id) { mutableStateOf<String?>(null) }
    var memoryGb by remember(instance.id) { mutableStateOf(0) }
    var jvmArgs by remember(instance.id) { mutableStateOf("") }
    var fullscreen by remember(instance.id) { mutableStateOf(false) }
    var resolution by remember(instance.id) { mutableStateOf<List<Int>?>(null) }
    var javaRuntimes by remember(instance.id) { mutableStateOf<List<CoreJavaRuntime>>(emptyList()) }
    var showDeleteConfirm by remember(instance.id) { mutableStateOf(false) }

    LaunchedEffect(instance.id) {
        try {
            val parsed = model.loadInstanceSettings(instance.id)
            loaded = parsed
            memoryGb = (parsed.overrides.memoryMb ?: 0) / 1024
            jvmArgs = parsed.overrides.jvmArgs.orEmpty()
            fullscreen = parsed.overrides.fullscreen ?: false
            resolution = parsed.overrides.resolution
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            loadError = model.errorMessage(error)
            model.showError("无法读取实例设置", error)
        }

        // The Java picker must include launcher-managed runtimes; a failure here
        // only hides the extra choices, it never blocks the rest of the page.
        runCatching { model.loadJavaRuntimes() }
            .onSuccess { javaRuntimes = it }
    }

    // Debounce JVM arguments: one RPC per character would be wasteful, so save
    // 600 ms after typing stops.
    LaunchedEffect(jvmArgs, loaded) {
        val current = loaded ?: return@LaunchedEffect
        val saved = current.overrides.jvmArgs.orEmpty()
        if (jvmArgs == saved) return@LaunchedEffect
        delay(600)
        val value = jvmArgs.ifBlank { null }
        model.configureInstance(instance.id, "jvmArgs", value?.let(::JsonPrimitive))
        loaded = current.copy(
            overrides = current.overrides.copy(jvmArgs = value),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(InstanceSettingsPageTag),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 820.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InstanceSettingsSummary(instance)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (loadError != null) {
                Text(
                    text = stringResource(
                        Res.string.instance_settings_load_failed,
                        loadError.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SettingsSection(title = stringResource(Res.string.instance_settings_launch)) {
                SettingRow(label = stringResource(Res.string.instance_settings_memory)) {
                    DropdownField(
                        value = if (memoryGb == 0) {
                            stringResource(Res.string.instance_settings_memory_auto)
                        } else {
                            stringResource(Res.string.instance_settings_memory_value, memoryGb)
                        },
                        options = listOf(0, 2, 4, 6, 8, 12, 16).map { option ->
                            val label = if (option == 0) {
                                stringResource(Res.string.instance_settings_memory_auto)
                            } else {
                                stringResource(Res.string.instance_settings_memory_value, option)
                            }
                            label to {
                                memoryGb = option
                                val value = if (option == 0) null else option * 1024
                                model.configureInstance(
                                    instance.id,
                                    "memory",
                                    value?.let(::JsonPrimitive),
                                )
                                loaded?.let { current ->
                                    loaded = current.copy(
                                        overrides = current.overrides.copy(memoryMb = value),
                                    )
                                }
                            }
                        },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(Res.string.instance_settings_jvm_args),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = jvmArgs,
                        onValueChange = { jvmArgs = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(stringResource(Res.string.instance_settings_jvm_args_placeholder))
                        },
                        singleLine = true,
                    )
                }
                SettingRow(label = stringResource(Res.string.instance_settings_java_runtime)) {
                    val override = loaded?.overrides?.java
                    val autoLabel = stringResource(Res.string.instance_settings_java_auto)
                    val currentLabel = override
                        ?.let { path ->
                            javaRuntimes.firstOrNull { it.home == path }?.let { runtime ->
                                "Java ${runtime.version} · ${runtime.arch}"
                            } ?: path.substringAfterLast('/').ifBlank { path }
                        }
                        ?: autoLabel
                    DropdownField(
                        value = currentLabel,
                        options = buildList {
                            add(autoLabel to {
                                model.configureInstance(instance.id, "java", null)
                                loaded?.let { current ->
                                    loaded = current.copy(
                                        overrides = current.overrides.copy(java = null),
                                    )
                                }
                            })
                            javaRuntimes.forEach { runtime ->
                                add(
                                    "Java ${runtime.version} · ${runtime.arch}" to {
                                        model.configureInstance(
                                            instance.id,
                                            "java",
                                            JsonPrimitive(runtime.home),
                                        )
                                        loaded?.let { current ->
                                            loaded = current.copy(
                                                overrides = current.overrides.copy(
                                                    java = runtime.home,
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
                SettingRow(label = stringResource(Res.string.instance_settings_fullscreen)) {
                    Switch(
                        checked = fullscreen,
                        onCheckedChange = { checked ->
                            fullscreen = checked
                            model.configureInstance(
                                instance.id,
                                "fullscreen",
                                JsonPrimitive(checked),
                            )
                            loaded?.let { current ->
                                loaded = current.copy(
                                    overrides = current.overrides.copy(fullscreen = checked),
                                )
                            }
                        },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingsSection(title = stringResource(Res.string.instance_settings_display)) {
                SettingRow(label = stringResource(Res.string.instance_settings_resolution)) {
                    DropdownField(
                        value = resolution?.let {
                            stringResource(
                                Res.string.instance_settings_resolution_value,
                                it[0],
                                it[1],
                            )
                        } ?: stringResource(Res.string.instance_settings_resolution_default),
                        options = listOf(
                            null to stringResource(Res.string.instance_settings_resolution_default),
                            listOf(1280, 720) to stringResource(
                                Res.string.instance_settings_resolution_value,
                                1280,
                                720,
                            ),
                            listOf(1600, 900) to stringResource(
                                Res.string.instance_settings_resolution_value,
                                1600,
                                900,
                            ),
                            listOf(1920, 1080) to stringResource(
                                Res.string.instance_settings_resolution_value,
                                1920,
                                1080,
                            ),
                            listOf(2560, 1440) to stringResource(
                                Res.string.instance_settings_resolution_value,
                                2560,
                                1440,
                            ),
                        ).map { (value, label) ->
                            label to {
                                resolution = value
                                val json = value?.let { size ->
                                    buildJsonArray { size.forEach { add(JsonPrimitive(it)) } }
                                }
                                model.configureInstance(instance.id, "resolution", json)
                                loaded?.let { current ->
                                    loaded = current.copy(
                                        overrides = current.overrides.copy(resolution = value),
                                    )
                                }
                            }
                        },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingsSection(title = stringResource(Res.string.instance_settings_content)) {
                ContentManageRow(
                    label = stringResource(Res.string.instance_settings_mods),
                    onClick = { model.showInstanceContent(ContentManagerTab.MODS) },
                )
                ContentManageRow(
                    label = stringResource(Res.string.instance_settings_resourcepacks),
                    onClick = { model.showInstanceContent(ContentManagerTab.RESOURCE_PACKS) },
                )
                ContentManageRow(
                    label = stringResource(Res.string.instance_settings_shaderpacks),
                    onClick = { model.showInstanceContent(ContentManagerTab.SHADER_PACKS) },
                )
                ContentManageRow(
                    label = stringResource(Res.string.instance_settings_worlds),
                    onClick = { model.showInstanceContent(ContentManagerTab.WORLDS) },
                )
                ContentManageRow(
                    label = stringResource(Res.string.content_tab_datapacks),
                    onClick = { model.showInstanceContent(ContentManagerTab.DATAPACKS) },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingsSection(title = stringResource(Res.string.instance_settings_more)) {
                ContentManageRow(
                    label = stringResource(Res.string.instance_settings_logs),
                    onClick = { model.openDiagnostics(instance) },
                )
                ContentManageRow(
                    label = stringResource(Res.string.modpack_export),
                    onClick = { model.exportModpack(instance) },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingsSection(title = stringResource(Res.string.instance_settings_maintenance)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.copyInstanceDirectoryPath(instance) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(Res.string.action_copy_instance_dir))
                    }
                    OutlinedButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(Res.string.library_menu_delete))
                    }
                }
                Text(
                    text = stringResource(Res.string.instance_settings_copy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(Res.string.instance_settings_delete_title)) },
            text = {
                Text(stringResource(Res.string.instance_settings_delete_body, instance.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        model.deleteInstance(instance)
                    },
                ) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun InstanceSettingsSummary(instance: Instance) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InstanceTile(instance = instance, size = 42.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = instance.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = instance.versionLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun SettingRow(
    label: String,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        content()
    }
}

@Composable
private fun ContentManageRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
