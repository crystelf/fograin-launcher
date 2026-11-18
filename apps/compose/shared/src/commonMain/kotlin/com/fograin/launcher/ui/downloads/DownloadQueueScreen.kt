package com.fograin.launcher.ui.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.DownloadRow
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.EmptyState
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.components.SectionHeader
import com.fograin.launcher.ui.components.ThinProgressBar
import com.fograin.launcher.ui.downloadSpeedText
import com.fograin.launcher.ui.stateText
import org.jetbrains.compose.resources.stringResource

const val DownloadQueueTag: String = "download-queue"

fun downloadQueueRowTag(taskId: String): String = "download-queue-row-$taskId"

fun downloadQueuePauseTag(taskId: String): String = "download-queue-pause-$taskId"

fun downloadQueueResumeTag(taskId: String): String = "download-queue-resume-$taskId"

fun downloadQueueStopTag(taskId: String): String = "download-queue-stop-$taskId"

/**
 * Full-window download queue.
 *
 * It is deliberately not a fifth destination: the page opens over the shell
 * from Home or the downloads card, hides the navigation bar, and returns to
 * wherever the user came from. The list is split into live tasks (running or
 * paused) and a short recent history so stopping a task still leaves a trace.
 */
@Composable
fun DownloadQueueScreen(model: AppModel, state: AppUiState) {
    val active = state.downloads.filter { it.state == "running" || it.state == "paused" }
    val finished = state.downloads
        .filter { it.state != "running" && it.state != "paused" }
        .takeLast(8)
        .reversed()
    val runningCount = active.count { it.state == "running" }
    val pausedCount = active.count { it.state == "paused" }

    Column(modifier = Modifier.fillMaxSize().testTag(DownloadQueueTag)) {
        PageHeader(
            title = stringResource(Res.string.download_queue_title),
            onBack = model::closeDownloadQueue,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    QueueOverview(
                        runningCount = runningCount,
                        pausedCount = pausedCount,
                        speedText = downloadSpeedText(state),
                    )
                }

                item {
                    SectionHeader(
                        title = stringResource(Res.string.download_queue_active),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (active.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            EmptyState(
                                icon = Icons.Outlined.FileDownload,
                                title = stringResource(Res.string.download_queue_empty_title),
                                body = stringResource(Res.string.download_queue_empty_body),
                            )
                        }
                    }
                } else {
                    items(items = active, key = { it.id }) { row ->
                        QueueTaskCard(
                            row = row,
                            onPause = { model.pauseDownload(row.id) },
                            onResume = { model.resumeDownload(row.id) },
                            onStop = { model.cancelDownload(row.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                if (finished.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(Res.string.download_queue_history),
                            modifier = Modifier.padding(top = 8.dp),
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

@Composable
private fun QueueOverview(
    runningCount: Int,
    pausedCount: Int,
    speedText: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(Res.string.download_queue_active),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        Res.string.download_queue_summary,
                        runningCount,
                        pausedCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(Res.string.download_queue_speed, speedText),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun QueueTaskCard(
    row: DownloadRow,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paused = row.state == "paused"
    val statusColor = if (paused) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(downloadQueueRowTag(row.id)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (paused) {
                            stringResource(Res.string.status_paused)
                        } else {
                            stringResource(
                                Res.string.download_queue_speed_value,
                                InstanceFormat.byteRate(row.speed),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        Res.string.download_queue_percent,
                        "${(row.fraction * 100).toInt().coerceIn(0, 100)}%",
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            ThinProgressBar(
                progress = row.fraction,
                modifier = Modifier.padding(end = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (paused) {
                    TextButton(
                        onClick = onResume,
                        modifier = Modifier.testTag(downloadQueueResumeTag(row.id)),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(5.dp))
                        Text(stringResource(Res.string.download_queue_resume))
                    }
                } else {
                    TextButton(
                        onClick = onPause,
                        modifier = Modifier.testTag(downloadQueuePauseTag(row.id)),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(5.dp))
                        Text(stringResource(Res.string.download_queue_pause))
                    }
                }
                TextButton(
                    onClick = onStop,
                    modifier = Modifier.testTag(downloadQueueStopTag(row.id)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(5.dp))
                    Text(
                        text = stringResource(Res.string.download_queue_stop),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun FinishedTaskRow(row: DownloadRow) {
    val tone = when (row.state) {
        "succeeded" -> MaterialTheme.colorScheme.primary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = if (row.state == "failed") Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tone,
            )
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.stateText(),
                style = MaterialTheme.typography.labelMedium,
                color = tone,
            )
        }
    }
}
