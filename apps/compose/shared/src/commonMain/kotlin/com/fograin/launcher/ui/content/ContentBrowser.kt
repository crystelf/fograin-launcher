package com.fograin.launcher.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.ContentSourceOption
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.platform.CoreContentProject
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.DropdownField
import com.fograin.launcher.ui.downloadsCountText
import com.fograin.launcher.ui.titleText
import org.jetbrains.compose.resources.stringResource

/** Stable test tag for one content project card. */
fun contentProjectTag(project: CoreContentProject): String =
    "content-project-${project.source}-${project.projectId}"

/**
 * Downloads → 模组 / 资源包 / 光影: project search across Modrinth and
 * CurseForge (MCIM) for the selected instance's Minecraft version and loader.
 */
@Composable
fun ContentBrowser(
    model: AppModel,
    state: AppUiState,
    category: DownloadsCategory,
    wide: Boolean,
) {
    val browser = state.contentBrowser
    val instances = state.instances
    val target = instances.firstOrNull { it.id == browser.targetInstanceId }
    val sourceName = stringResource(
        when (browser.source) {
            ContentSourceOption.MODRINTH -> Res.string.downloads_content_source_modrinth
            ContentSourceOption.CURSEFORGE -> Res.string.downloads_content_source_curseforge
        },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_content_source),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ContentSourceOption.entries.forEach { option ->
                FilterChip(
                    selected = option == browser.source,
                    onClick = { model.contentBrowser.setSource(option) },
                    label = {
                        Text(
                            stringResource(
                                when (option) {
                                    ContentSourceOption.MODRINTH ->
                                        Res.string.downloads_content_source_modrinth

                                    ContentSourceOption.CURSEFORGE ->
                                        Res.string.downloads_content_source_curseforge
                                },
                            ),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (category.worldScoped) {
            val selectedWorld = browser.worlds.firstOrNull { it.name == browser.targetWorld }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.datapack_world),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownField(
                    value = selectedWorld?.let { it.levelName ?: it.name }
                        ?: stringResource(Res.string.datapack_no_world_title),
                    options = browser.worlds.map { world ->
                        (world.levelName ?: world.name) to { model.contentBrowser.selectWorld(world.name) }
                    },
                    enabled = browser.worlds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                if (browser.isLoadingWorlds) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            if (!browser.isLoadingWorlds && browser.worlds.isEmpty()) {
                Text(
                    text = stringResource(Res.string.downloads_content_world_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!category.installsToNewInstance && instances.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.downloads_content_browse_without_instance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        when {
            browser.isLoading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            browser.error != null -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                        Text(
                            text = browser.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { model.contentBrowser.openCategory(category) }) {
                            Text(stringResource(Res.string.downloads_retry))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$sourceName · ${stringResource(Res.string.downloads_content_results, browser.projects.size)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!category.installsToNewInstance && target != null) {
                val selectedWorld = browser.worlds.firstOrNull {
                    it.name == browser.targetWorld
                }
                Text(
                    text = if (category.worldScoped) {
                        stringResource(
                            Res.string.downloads_content_installs_to_world,
                            selectedWorld?.let { it.levelName ?: it.name }
                                ?: browser.targetWorld.ifBlank {
                                    stringResource(Res.string.datapack_world)
                                },
                        )
                    } else {
                        "${target.minecraftVersion} · ${target.loader.titleText()}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                browser.isLoading && browser.projects.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                browser.projects.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Public,
                    title = stringResource(Res.string.downloads_content_empty),
                    body = stringResource(Res.string.downloads_content_empty_body),
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(if (wide) 2 else 1),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(browser.projects, key = { "${it.source}-${it.projectId}" }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { model.contentBrowser.openProject(project) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: CoreContentProject, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(contentProjectTag(project)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(14.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = project.title.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = project.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = sourceLabel(project.source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = buildString {
                        append(downloadsCountText(project.downloads))
                        project.categories.take(3).takeIf { it.isNotEmpty() }?.let { categories ->
                            append(" · ")
                            append(categories.joinToString(" / "))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (project.description.isNotBlank()) {
                    Text(
                        text = project.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun sourceLabel(source: String): String = when (source.lowercase()) {
    "curseforge" -> stringResource(Res.string.downloads_content_source_curseforge)
    else -> stringResource(Res.string.downloads_content_source_modrinth)
}
