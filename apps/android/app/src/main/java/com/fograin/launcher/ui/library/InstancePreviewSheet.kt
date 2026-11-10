package com.fograin.launcher.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.R
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.ui.components.InstanceTile
import com.fograin.launcher.ui.components.KeyValueRow
import com.fograin.launcher.ui.components.MilestoneChip
import com.fograin.launcher.ui.components.StatusPill
import com.fograin.launcher.ui.components.StatusTone

/** Inspector sheet shown when a library row is tapped (the macOS preview pane). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancePreviewSheet(
    model: AppModel,
    state: AppUiState,
    instance: Instance,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = model::closePreview,
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.hero_forest),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.10f),
                                1.0f to Color.Black.copy(alpha = 0.78f),
                            ),
                        ),
                )
                StatusPill(
                    text = if (instance.isInstalled) "已就绪" else "未安装",
                    tone = if (instance.isInstalled) StatusTone.READY else StatusTone.WARNING,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InstanceTile(instance = instance, size = 44.dp)
                    Column {
                        Text(
                            text = instance.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = instance.versionLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { model.startGame(instance) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (instance.isInstalled) "开始游戏" else "补全资源并启动")
                }
                FilledTonalIconButton(onClick = { model.copyInstanceDirectoryPath(instance) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制实例目录路径")
                }
                FilledTonalIconButton(onClick = { model.manageInstance(instance) }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "管理实例")
                }
                FilledTonalIconButton(onClick = { model.toggleFavorite(instance) }) {
                    Icon(Icons.Filled.Star, contentDescription = "收藏")
                }
            }

            if (!instance.isInstalled) {
                Text(
                    text = "游戏资源尚未安装完成。开始游戏会先补全资源，完成后可在游戏设置里调整内存与显示选项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyValueRow(label = "模组", value = instance.modCount?.toString() ?: "未安装")
                KeyValueRow(label = "Java", value = "自动发现")
                KeyValueRow(
                    label = "内存",
                    value = (state.gameConfig?.memoryMb ?: 0).let { memory ->
                        if (memory == 0) "自动" else "${memory / 1024} GB"
                    },
                )
                KeyValueRow(label = "占用空间", value = InstanceFormat.byteSize(instance.sizeBytes))
                KeyValueRow(label = "游玩时长", value = instance.playTimeText)
                KeyValueRow(
                    label = "上次游玩",
                    value = InstanceFormat.relativeTime(instance.lastPlayedEpochMillis),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("内容管理", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    MilestoneChip("M8 / M9")
                }
                Text(
                    text = "模组、资源包、光影、世界与备份在后续里程碑接入；当前实例已可下载与启动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
