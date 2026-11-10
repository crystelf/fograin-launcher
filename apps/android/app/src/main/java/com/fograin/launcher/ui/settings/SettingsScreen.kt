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
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fograin.launcher.BuildConfig
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.platform.CoreBridge
import com.fograin.launcher.settings.AppearancePreferences
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.components.KeyValueRow
import com.fograin.launcher.ui.components.MilestoneChip

/**
 * Settings: the shared appearance contract (§4.1.1) written through the Rust
 * core, the Android-only dynamic color switch, build info and the milestone
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
    val supportsDynamicColor = AppearancePreferences.supportsDynamicColor()
    val coreReady = CoreBridge.isAvailable

    Column(modifier = Modifier.fillMaxSize()) {
        LargeTopAppBar(
            title = { Text("设置") },
            colors = TopAppBarDefaults.largeTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!coreReady || CoreBridge.initializationError != null) {
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
                                    text = if (coreReady) "核心配置读取失败" else "启动器核心不可用",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = CoreBridge.initializationError
                                        ?: "原生库未加载，实例、下载与启动功能不可用。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }

            item { SettingsGroupTitle("外观") }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "主题",
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
                                    label = { Text(mode.title) },
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
                                    text = "动态取色",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = if (supportsDynamicColor) {
                                        "使用壁纸生成的 Material You 配色（Android 12+）"
                                    } else {
                                        "需要 Android 12 或更高版本"
                                    },
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

            item { SettingsGroupTitle("启动与网络") }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MilestoneRow("语言", "简体中文", "M10")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MilestoneRow("下载源", "自动 · 官方优先", "M2")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MilestoneRow("最大内存", "6 GB", "M6")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MilestoneRow("并发下载", "8", "M2")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MilestoneRow("日志级别", "Info", "M10")
                    }
                }
            }

            item { SettingsGroupTitle("构建") }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyValueRow(label = "应用版本", value = BuildConfig.VERSION_NAME)
                        KeyValueRow(label = "界面", value = "Kotlin + Jetpack Compose")
                        KeyValueRow(label = "核心", value = "Rust · fg-* crates")
                        KeyValueRow(
                            label = "FFI",
                            value = if (coreReady) {
                                "JNI · C ABI v${state.settings.abiVersion}"
                            } else {
                                "JNI 未加载"
                            },
                        )
                        KeyValueRow(label = "平台", value = state.settings.platform)
                        KeyValueRow(
                            label = "Java",
                            value = state.javaSummary,
                            checkmark = (state.appSnapshot?.javaCount ?: 0) > 0,
                        )
                    }
                }
            }

            item { SettingsGroupTitle("关于") }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("雾雨启动器", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Minecraft Java 版启动器。Rust 核心 + 四个原生客户端；Android 端使用 Kotlin + Compose + Material 3。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = "开源与致谢",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "HMCL / HMCLCore（GPL-3.0）与 PCL.Core（Apache-2.0）为下载、Java 扫描与启动实现提供参考；fograin.pen 提供布局与信息架构参考。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Minecraft 是 Mojang Studios 的商标。雾雨启动器与 Mojang、Microsoft 无关联。",
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
