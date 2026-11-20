package com.fograin.launcher.ui.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.ui.titleText
import com.fograin.launcher.platform.CoreLoaderVersion
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.SkeletonBox
import org.jetbrains.compose.resources.stringResource

/** Stable test tag for one loader build row. */
fun loaderVersionTag(kind: GameLoaderKind, version: String): String =
    "loader-version-${kind.wireName}-$version"

/**
 * Third page of the download flow: the loader build list for the pinned
 * Minecraft version. Choosing a row stores the build and returns to the
 * install page, much like the game version list opens the install page.
 */
@Composable
internal fun LoaderVersionsPage(
    model: AppModel,
    state: AppUiState,
    modifier: Modifier = Modifier,
) {
    val download = state.download
    val loader = download.installLoader
    val minecraft = download.installVersionId.orEmpty()
    val versions = download.loaderVersions

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 820.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoaderIconTile(kind = loader, size = 44, iconSize = 22)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = loader.titleText(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (!download.isLoadingLoaderVersions && versions.isNotEmpty()) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ) {
                                Text(
                                    text = stringResource(
                                        Res.string.install_loader_count,
                                        versions.size,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(
                                        horizontal = 8.dp,
                                        vertical = 2.dp,
                                    ),
                                )
                            }
                        }
                        if (download.isLoadingLoaderVersions) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            when {
                download.loaderVersionsError != null && versions.isEmpty() -> {
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        ErrorState(
                            message = download.loaderVersionsError,
                            onRetry = model::retryLoaderVersions,
                        )
                    }
                }

                versions.isEmpty() && download.isLoadingLoaderVersions -> {
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        SkeletonLoaderVersions()
                    }
                }

                versions.isEmpty() -> {
                    QuietEmptyState(
                        icon = Icons.Outlined.Inbox,
                        title = stringResource(Res.string.install_loader_empty_title),
                        body = stringResource(Res.string.install_loader_empty_body, minecraft),
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(
                        items = versions,
                        key = { _, version -> version.version },
                    ) { _, version ->
                        LoaderVersionRowCard(
                            loader = loader,
                            version = version,
                            selected = version.version == download.installLoaderVersion,
                            onClick = { model.selectInstallLoaderVersion(version.version) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoaderVersionRowCard(
    loader: GameLoaderKind,
    version: CoreLoaderVersion,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(160),
        label = "loader-version-container",
    )
    val border by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        },
        animationSpec = tween(160),
        label = "loader-version-border",
    )
    val badgeColor = if (version.stable) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag(loaderVersionTag(loader, version.version)),
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, border),
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoaderIconTile(kind = loader, size = 36, iconSize = 18)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = version.version,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Surface(
                        shape = CircleShape,
                        color = badgeColor.copy(alpha = 0.14f),
                        contentColor = badgeColor,
                    ) {
                        Text(
                            text = stringResource(
                                if (version.stable) {
                                    Res.string.install_loader_stable
                                } else {
                                    Res.string.install_loader_preview
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                version.label?.takeIf { it.isNotBlank() }?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                version.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = stringResource(Res.string.install_loader_note_forge, note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    (fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.7f)) togetherWith
                        (fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.7f))
                },
                label = "loader-version-selected",
            ) { checked ->
                if (checked) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SkeletonLoaderVersions() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(5) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SkeletonBox(
                        modifier = Modifier.size(36.dp),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SkeletonBox(
                            modifier = Modifier
                                .width(110.dp)
                                .height(13.dp),
                        )
                        SkeletonBox(
                            modifier = Modifier
                                .width(72.dp)
                                .height(10.dp),
                        )
                    }
                    SkeletonBox(
                        modifier = Modifier
                            .width(48.dp)
                            .height(18.dp),
                        shape = CircleShape,
                    )
                }
            }
        }
    }
}
