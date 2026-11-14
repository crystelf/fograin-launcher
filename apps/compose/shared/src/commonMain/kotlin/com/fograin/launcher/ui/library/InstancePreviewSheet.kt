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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.InstanceTile
import com.fograin.launcher.ui.components.KeyValueRow
import com.fograin.launcher.ui.components.MilestoneChip
import com.fograin.launcher.ui.components.StatusPill
import com.fograin.launcher.ui.components.StatusTone
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Inspector sheet opened from a library row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancePreviewSheet(
    model: AppModel,
    state: AppUiState,
    instance: Instance,
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
                .height(160.dp)
                .clip(RoundedCornerShape(18.dp)),
        ) {
            Image(
                painter = painterResource(Res.drawable.hero_forest),
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
                text = stringResource(
                    if (instance.isInstalled) {
                        Res.string.home_status_ready
                    } else {
                        Res.string.home_status_not_installed
                    },
                ),
                tone = if (instance.isInstalled) StatusTone.READY else StatusTone.WARNING,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            )
            FilledTonalIconButton(
                onClick = model::closePreview,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.action_close),
                    modifier = Modifier.size(18.dp),
                )
            }
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
                Text(
                    stringResource(
                        if (instance.isInstalled) {
                            Res.string.preview_play
                        } else {
                            Res.string.preview_complete_and_play
                        },
                    ),
                )
            }
            FilledTonalIconButton(onClick = { model.copyInstanceDirectoryPath(instance) }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(Res.string.preview_copy_dir),
                )
            }
            FilledTonalIconButton(onClick = { model.manageInstance(instance) }) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = stringResource(Res.string.preview_manage),
                )
            }
            FilledTonalIconButton(onClick = { model.toggleFavorite(instance) }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(Res.string.preview_favorite),
                )
            }
        }

        if (!instance.isInstalled) {
            Text(
                text = stringResource(Res.string.preview_incomplete),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyValueRow(
                label = stringResource(Res.string.preview_mods),
                value = instance.modCount?.toString()
                    ?: stringResource(Res.string.preview_mods_not_installed),
            )
            KeyValueRow(
                label = stringResource(Res.string.preview_java),
                value = stringResource(Res.string.preview_java_auto),
            )
            KeyValueRow(
                label = stringResource(Res.string.preview_memory),
                value = (state.gameConfig?.memoryMb ?: 0).let { memory ->
                    if (memory == 0) {
                        stringResource(Res.string.preview_memory_auto)
                    } else {
                        stringResource(Res.string.degree_gb, memory / 1024)
                    }
                },
            )
            KeyValueRow(
                label = stringResource(Res.string.preview_size),
                value = InstanceFormat.byteSize(instance.sizeBytes),
            )
            KeyValueRow(
                label = stringResource(Res.string.preview_playtime),
                value = instance.playTimeText,
            )
            KeyValueRow(
                label = stringResource(Res.string.preview_last_played),
                value = InstanceFormat.relativeTime(instance.lastPlayedEpochMillis),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.preview_content),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                MilestoneChip(stringResource(Res.string.preview_content_milestone))
            }
            Text(
                text = stringResource(Res.string.preview_content_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
