package com.fograin.launcher.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.DownloadRow
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.TaskState
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.EmptyState
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.components.ThinProgressBar
import com.fograin.launcher.ui.downloadSpeedText
import com.fograin.launcher.ui.stateText
import com.fograin.launcher.ui.titleText
import org.jetbrains.compose.resources.stringResource

const val DownloadQueueTag: String = "download-queue"

fun downloadQueueRowTag(taskId: String): String = "download-queue-row-$taskId"

fun downloadQueuePauseTag(taskId: String): String = "download-queue-pause-$taskId"

fun downloadQueueResumeTag(taskId: String): String = "download-queue-resume-$taskId"

fun downloadQueueStopTag(taskId: String): String = "download-queue-stop-$taskId"

/**
 * Full-height download queue page.
 *
 * It follows the same shell as the instance detail page: the shared page
 * header, the navigation rail/bottom bar, and a flat content list with thin
 * dividers. The previous framed overview card and bordered task cards were
 * removed so the queue reads as one continuous page instead of a dashboard of
 * boxes.
 */
@Composable
fun DownloadQueueScreen(model: AppModel, state: AppUiState) {
    val active = state.downloads.filter { it.state.isActive }
    val finished = state.downloads
        .filter { !it.state.isActive }
        .takeLast(8)
        .reversed()
    val runningCount = active.count { it.state == TaskState.RUNNING }
    val pausedCount = active.count { it.state == TaskState.PAUSED }

    Column(modifier = Modifier.fillMaxSize().testTag(DownloadQueueTag)) {
        PageHeader(
            title = stringResource(Res.string.download_queue_title),
            onBack = model.downloads::closeQueue,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            if (active.isNotEmpty()) {
                QueueSummary(
                    runningCount = runningCount,
                    pausedCount = pausedCount,
                    speedText = downloadSpeedText(state),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                if (active.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.FileDownload,
                            title = stringResource(Res.string.download_queue_empty_title),
                            body = stringResource(Res.string.download_queue_empty_body),
                        )
                    }
                } else {
                    item {
                        QueueSectionHeader(
                            title = stringResource(Res.string.download_queue_active),
                            count = active.size,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(items = active, key = { it.id }) { row ->
                        QueueTaskRow(
                            row = row,
                            onPause = { model.downloads.pause(row.id) },
                            onResume = { model.downloads.resume(row.id) },
                            onStop = { model.downloads.cancel(row.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                if (finished.isNotEmpty()) {
                    item {
                        QueueSectionHeader(
                            title = stringResource(Res.string.download_queue_history),
                            count = finished.size,
                            modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
                        )
                    }
                    items(items = finished, key = { "history-${it.id}" }) { row ->
                        FinishedTaskRow(row = row)
                    }
                }
            }
        }
    }
}

/**
 * One-line status row, deliberately unframed. It mirrors the compact toolbar
 * row used by the instance content manager.
 */
@Composable
private fun QueueSummary(
    runningCount: Int,
    pausedCount: Int,
    speedText: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                Res.string.download_queue_summary,
                runningCount,
                pausedCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(Res.string.download_queue_speed, speedText),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** Compact section title with the row count on the right. */
@Composable
private fun QueueSectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QueueTaskRow(
    row: DownloadRow,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paused = row.state == TaskState.PAUSED
    val statusColor = if (paused) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val percent = "${(row.fraction * 100).toInt().coerceIn(0, 100)}%"
    val meta = if (paused) {
        "${stringResource(Res.string.status_paused)} · $percent"
    } else {
        stringResource(
            Res.string.download_queue_speed_value,
            InstanceFormat.byteRate(row.speed),
        ) + " · $percent"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(downloadQueueRowTag(row.id)),
    ) {
        Row(
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = row.titleText(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (paused) {
                QueueAction(
                    icon = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(Res.string.download_queue_resume),
                    onClick = onResume,
                    modifier = Modifier.testTag(downloadQueueResumeTag(row.id)),
                )
            } else {
                QueueAction(
                    icon = Icons.Filled.Pause,
                    contentDescription = stringResource(Res.string.download_queue_pause),
                    onClick = onPause,
                    modifier = Modifier.testTag(downloadQueuePauseTag(row.id)),
                )
            }
            QueueAction(
                icon = Icons.Filled.Stop,
                contentDescription = stringResource(Res.string.download_queue_stop),
                onClick = onStop,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(downloadQueueStopTag(row.id)),
            )
        }

        ThinProgressBar(progress = row.fraction)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun QueueAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
    }
}

@Composable
private fun FinishedTaskRow(row: DownloadRow) {
    val tone = when (row.state) {
        TaskState.SUCCEEDED -> MaterialTheme.colorScheme.primary
        TaskState.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (row.state) {
        TaskState.FAILED -> Icons.Outlined.ErrorOutline
        TaskState.CANCELLED -> Icons.Outlined.Cancel
        else -> Icons.Outlined.CheckCircle
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = tone,
            )
            Text(
                text = row.titleText(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.stateText(),
                style = MaterialTheme.typography.labelSmall,
                color = tone,
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}
