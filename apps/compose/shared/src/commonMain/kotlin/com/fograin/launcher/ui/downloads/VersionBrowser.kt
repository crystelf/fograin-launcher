package com.fograin.launcher.ui.downloads

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.platform.CoreVersionEntry
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.SkeletonBox
import com.fograin.launcher.ui.components.VersionKindGlyph
import com.fograin.launcher.ui.emptyMessage
import org.jetbrains.compose.resources.stringResource

/** Stable test tag for one row of the game version list. */
fun downloadVersionTag(id: String): String = "download-version-$id"

/**
 * The game tab is a pure version browser: rows open the install page. Version
 * details, loader choice and the instance name all live one page deeper, so
 * the list stays scannable instead of splitting the screen with a preview.
 */
@Composable
internal fun GameDownloads(
    model: AppModel,
    state: AppUiState,
    wide: Boolean,
    listState: LazyListState,
    gridState: LazyGridState,
    onRetryVersions: () -> Unit,
) {
    val download = state.download
    val query = download.query.trim()
    val filtered = remember(download.versions, query) {
        if (query.isEmpty()) {
            download.versions
        } else {
            val needle = query.lowercase()
            download.versions.filter { it.id.lowercase().contains(needle) }
        }
    }
    // The badge belongs to the newest manifest entry, not to the first row of
    // a filtered list.
    val latestId = download.versions.firstOrNull()?.id

    Column(modifier = Modifier.fillMaxSize()) {
        VersionListHeader(
            count = filtered.size,
            loading = download.isLoadingVersions,
        )
        Spacer(Modifier.height(12.dp))

        when {
            download.versionsError != null && download.versions.isEmpty() -> {
                ErrorState(message = download.versionsError, onRetry = onRetryVersions)
            }

            download.versions.isEmpty() && download.isLoadingVersions -> {
                SkeletonVersionList()
            }

            download.versions.isEmpty() -> {
                QuietEmptyState(
                    icon = Icons.Outlined.Inbox,
                    title = download.channel.emptyMessage(),
                    body = stringResource(Res.string.downloads_empty_body),
                )
            }

            filtered.isEmpty() -> {
                QuietEmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(Res.string.downloads_no_match),
                    body = stringResource(Res.string.downloads_search_empty_body),
                    action = {
                        TextButton(onClick = { model.downloads.setQuery("") }) {
                            Text(stringResource(Res.string.downloads_clear_search))
                        }
                    },
                )
            }

            wide -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 380.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItemsIndexed(
                    items = filtered.take(120),
                    key = { _, version -> version.id },
                ) { _, version ->
                    VersionRow(
                        version = version,
                        latest = version.id == latestId,
                        onClick = { model.downloads.openInstallPage(version.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(
                    items = filtered.take(120),
                    key = { _, version -> version.id },
                ) { _, version ->
                    VersionRow(
                        version = version,
                        latest = version.id == latestId,
                        onClick = { model.downloads.openInstallPage(version.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionListHeader(
    count: Int,
    loading: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.downloads_version_list),
            style = MaterialTheme.typography.titleMedium,
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun VersionRow(
    version: CoreVersionEntry,
    latest: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        targetValue = if (hovered) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(160),
        label = "version-container",
    )
    val kindTint = when (version.kind) {
        "release" -> MaterialTheme.colorScheme.primary
        "snapshot" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val arrowTint by animateColorAsState(
        targetValue = if (hovered) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        },
        animationSpec = tween(160),
        label = "version-arrow",
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag(downloadVersionTag(version.id)),
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VersionKindGlyph(kind = version.kind, size = 36.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = version.id,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (latest) {
                        LatestBadge()
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = kindText(version.kind),
                        style = MaterialTheme.typography.labelSmall,
                        color = kindTint,
                    )
                    version.released?.let { released ->
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = released.take(10),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = arrowTint,
            )
        }
    }
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(Res.string.downloads_retry))
            }
        }
    }
}

@Composable
internal fun QuietEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(38.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        action?.invoke()
    }
}

@Composable
private fun SkeletonVersionList() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(6) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SkeletonBox(
                        modifier = Modifier.size(36.dp),
                        shape = RoundedCornerShape(11.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SkeletonBox(
                            modifier = Modifier
                                .width(132.dp)
                                .height(13.dp),
                        )
                        SkeletonBox(
                            modifier = Modifier
                                .width(88.dp)
                                .height(10.dp),
                        )
                    }
                    SkeletonBox(
                        modifier = Modifier
                            .width(56.dp)
                            .height(20.dp),
                        shape = CircleShape,
                    )
                }
            }
        }
    }
}
