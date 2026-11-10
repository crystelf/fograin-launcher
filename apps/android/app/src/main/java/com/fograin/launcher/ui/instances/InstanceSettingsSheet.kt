package com.fograin.launcher.ui.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.model.Instance
import com.fograin.launcher.platform.CoreBridge
import com.fograin.launcher.platform.CoreInstanceSettings
import com.fograin.launcher.ui.components.DropdownField
import com.fograin.launcher.ui.components.InstanceTile
import com.fograin.launcher.ui.components.MilestoneChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Instance detail · 游戏设置 (M4 scope): 启动/内存/显示/启动行为, the M8/M9 content
 * placeholders and maintenance actions. The sheet mirrors the macOS instance
 * settings form and writes every change through `instances.settings.set`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceSettingsSheet(
    model: AppModel,
    instance: Instance,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loaded by remember(instance.id) { mutableStateOf<CoreInstanceSettings?>(null) }
    var loadError by remember(instance.id) { mutableStateOf<String?>(null) }
    var memoryGb by remember(instance.id) { mutableStateOf(0) }
    var jvmArgs by remember(instance.id) { mutableStateOf("") }
    var fullscreen by remember(instance.id) { mutableStateOf(false) }
    var resolution by remember(instance.id) { mutableStateOf<List<Int>?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(instance.id) {
        try {
            val payload = CoreBridge.rpcAsync(
                "instances.settings.get",
                JSONObject().put("id", instance.id),
            )
            val parsed = CoreInstanceSettings.from(payload)
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
    }

    // Debounce JVM arguments: macOS persists on every keystroke, which would
    // mean one RPC per character on Android. 600 ms after typing stops.
    LaunchedEffect(jvmArgs, loaded) {
        val current = loaded ?: return@LaunchedEffect
        val saved = current.overrides.jvmArgs.orEmpty()
        if (jvmArgs == saved) return@LaunchedEffect
        delay(600)
        val value = jvmArgs.ifBlank { null }
        model.configureInstance(instance.id, "jvmArgs", value)
        loaded = current.copy(
            overrides = current.overrides.copy(jvmArgs = value),
        )
    }

    ModalBottomSheet(
        onDismissRequest = model::closeInstanceSettings,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InstanceTile(instance = instance, size = 34.dp)
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = instance.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "游戏设置 · ${instance.versionLine}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = model::closeInstanceSettings) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (loadError != null) {
                Text(
                    text = "读取设置失败：$loadError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SettingsSection(title = "启动") {
                SettingRow(label = "内存") {
                    DropdownField(
                        value = if (memoryGb == 0) "自动" else "$memoryGb GB",
                        options = listOf(0, 2, 4, 6, 8, 12, 16).map { option ->
                            val label = if (option == 0) "自动" else "$option GB"
                            label to {
                                memoryGb = option
                                val value = if (option == 0) null else option * 1024
                                model.configureInstance(instance.id, "memory", value)
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
                        text = "JVM 参数",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = jvmArgs,
                        onValueChange = { jvmArgs = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("追加到启动命令的自定义参数") },
                        singleLine = true,
                    )
                }
                SettingRow(label = "Java 运行时") {
                    Text(
                        text = loaded?.overrides?.java ?: "自动发现",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SettingRow(label = "启动时全屏") {
                    Switch(
                        checked = fullscreen,
                        onCheckedChange = { checked ->
                            fullscreen = checked
                            model.configureInstance(instance.id, "fullscreen", checked)
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

            SettingsSection(title = "显示") {
                SettingRow(label = "窗口分辨率") {
                    DropdownField(
                        value = resolution?.let { "${it[0]} × ${it[1]}" } ?: "跟随游戏默认",
                        options = listOf(
                            null to "跟随游戏默认",
                            listOf(1280, 720) to "1280 × 720",
                            listOf(1600, 900) to "1600 × 900",
                            listOf(1920, 1080) to "1920 × 1080",
                            listOf(2560, 1440) to "2560 × 1440",
                        ).map { (value, label) ->
                            label to {
                                resolution = value
                                model.configureInstance(instance.id, "resolution", value)
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

            SettingsSection(title = "内容") {
                ContentMilestoneRow("模组管理", "M8")
                ContentMilestoneRow("资源包管理", "M8")
                ContentMilestoneRow("光影包管理", "M8")
                ContentMilestoneRow("世界管理", "M9")
                ContentMilestoneRow("原理图管理", "M9")
                ContentMilestoneRow("更新整合包", "M9")
                ContentMilestoneRow("日志与崩溃报告", "M9")
                ContentMilestoneRow("导出与备份", "M9")
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingsSection(title = "维护") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.copyInstanceDirectoryPath(instance) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("复制实例目录路径")
                    }
                    OutlinedButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("删除实例…")
                    }
                }
                Text(
                    text = "目录路径会复制到剪贴板，可在文件管理器中粘贴打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除实例？") },
            text = {
                Text("将删除“${instance.name}”的实例记录与游戏目录。正在运行的游戏无法删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        model.deleteInstance(instance)
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
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
private fun ContentMilestoneRow(title: String, milestone: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        MilestoneChip(milestone)
    }
}
