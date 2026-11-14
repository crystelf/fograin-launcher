package com.fograin.launcher.ui.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.CoreVersionEntry
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.SkeletonBox
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun VersionDetailPane(
    model: AppModel,
    state: AppUiState,
    version: CoreVersionEntry?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        if (version == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyDetail()
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = version,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentKey = { it.id },
                    transitionSpec = {
                        (fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.99f)) togetherWith
                            fadeOut(tween(120))
                    },
                    label = "version-detail",
                ) { current ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        VersionDetailSummary(
                            model = model,
                            state = state,
                            version = current,
                            showArtwork = true,
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                InstallFooter(
                    model = model,
                    state = state,
                    version = version,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun VersionDetailCard(
    model: AppModel,
    state: AppUiState,
    version: CoreVersionEntry?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        if (version == null) {
            EmptyDetail(modifier = Modifier.padding(24.dp))
        } else {
            Box(modifier = Modifier.padding(16.dp)) {
                AnimatedContent(
                    targetState = version,
                    contentKey = { it.id },
                    transitionSpec = {
                        (fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.99f)) togetherWith
                            fadeOut(tween(120))
                    },
                    label = "version-detail-card",
                ) { current ->
                    VersionDetailContent(model = model, state = state, version = current)
                }
            }
        }
    }
}

@Composable
private fun VersionDetailContent(
    model: AppModel,
    state: AppUiState,
    version: CoreVersionEntry,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        VersionDetailSummary(model = model, state = state, version = version)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        InstallFooter(model = model, state = state, version = version)
    }
}

@Composable
private fun VersionDetailSummary(
    model: AppModel,
    state: AppUiState,
    version: CoreVersionEntry,
    showArtwork: Boolean = false,
) {
    val plan = state.download.plan
    val planError = state.download.planError

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        RoundedCornerShape(17.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = versionIcon(version.kind),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = version.id,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    VersionKindBadge(version.kind)
                }
                Text(
                    text = version.released?.let { released ->
                        stringResource(Res.string.downloads_released_on, released.take(10))
                    } ?: kindText(version.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val phase = when {
            plan != null -> "ready"
            planError != null -> "error"
            else -> "loading"
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "plan-phase",
        ) { currentPhase ->
            when (currentPhase) {
                "ready" -> {
                    val readyPlan = plan
                    if (readyPlan != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatTile(
                                icon = Icons.Outlined.Code,
                                label = stringResource(Res.string.downloads_java_label),
                                value = readyPlan.javaMajor.toString(),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                icon = Icons.Outlined.Folder,
                                label = stringResource(Res.string.downloads_files),
                                value = stringResource(
                                    Res.string.downloads_file_count,
                                    readyPlan.files,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                icon = Icons.Outlined.Storage,
                                label = stringResource(Res.string.downloads_size),
                                value = InstanceFormat.byteSize(readyPlan.totalBytes),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                "error" -> ErrorState(
                    message = stringResource(
                        Res.string.downloads_plan_error,
                        planError.orEmpty(),
                    ),
                    onRetry = model::refreshDownloadPlan,
                )

                else -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(3) {
                        SkeletonBox(
                            modifier = Modifier
                                .weight(1f)
                                .height(76.dp),
                            shape = RoundedCornerShape(14.dp),
                        )
                    }
                }
            }
        }

        if (showArtwork) {
            VersionArtwork(version = version)
        }
    }
}

@Composable
private fun VersionArtwork(version: CoreVersionEntry) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.30f),
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ),
            ),
    ) {
        Text(
            text = version.id,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_edition),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = version.id,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append(kindText(version.kind))
                    version.released?.let { append(" · ${it.take(10)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstallFooter(
    model: AppModel,
    state: AppUiState,
    version: CoreVersionEntry,
    modifier: Modifier = Modifier,
) {
    val installRunning = state.download.isGameDownloadRunning

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = { model.openInstallSheet(version.id) },
            enabled = !installRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            AnimatedContent(
                targetState = installRunning,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                label = "install-button",
            ) { running ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(stringResource(Res.string.downloads_install_running))
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(Res.string.action_install))
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.downloads_install_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VersionKindBadge(kind: String) {
    val container = when (kind) {
        "release" -> MaterialTheme.colorScheme.primaryContainer
        "snapshot" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (kind) {
        "release" -> MaterialTheme.colorScheme.onPrimaryContainer
        "snapshot" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            text = kindText(kind),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun LatestBadge() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = stringResource(Res.string.downloads_latest),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyDetail(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(18.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(Res.string.downloads_select_version_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(Res.string.downloads_select_version_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
