package com.fograin.launcher.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.ContentDependencyPrompt
import com.fograin.launcher.model.ContentVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.LoaderKind
import com.fograin.launcher.platform.CoreContentInstallItem
import com.fograin.launcher.platform.CoreContentProject
import com.fograin.launcher.platform.CoreContentVersion
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.DropdownField
import com.fograin.launcher.ui.title
import org.jetbrains.compose.resources.stringResource

const val ContentInstallTag: String = "content-install"
const val ContentVersionChannelTag: String = "content-version-channel"
const val ContentDependencyDialogTag: String = "content-dependency-dialog"
const val ContentDependencyAutoTag: String = "content-dependency-auto"
const val ContentDependencySkipTag: String = "content-dependency-skip"

/** Stable test tag for one version row in the project sheet. */
fun contentVersionTag(versionId: String): String = "content-version-$versionId"

/** Stable test tag for one missing prerequisite in the dependency dialog. */
fun contentDependencyItemTag(projectId: String): String = "content-dependency-item-$projectId"

/**
 * Project detail sheet: pick a compatible version and start a content install.
 * Installation itself becomes a `content:<project>` task, so the sheet closes
 * as soon as the core returns a task id.
 *
 * Release / beta / alpha versions are filtered by the 版本 dropdown (defaults
 * to 正式版) and each row carries a colored type dot. Mods with required
 * prerequisites open a confirmation dialog that lists the missing ones before
 * anything is downloaded.
 */
@Composable
fun ContentProjectSheet(
    model: AppModel,
    state: AppUiState,
    project: CoreContentProject,
) {
    val browser = state.contentBrowser
    val target = state.instances.firstOrNull { it.id == browser.targetInstanceId }
    val sourceLabel = stringResource(
        when (project.source.lowercase()) {
            "curseforge" -> Res.string.downloads_content_source_curseforge
            else -> Res.string.downloads_content_source_modrinth
        },
    )
    val requiredDependencies = browser.selectedVersion
        ?.dependencies
        ?.count { it.kind == "required" }
        ?: 0
    val dependenciesText = if (requiredDependencies > 0) {
        stringResource(Res.string.downloads_content_dependencies, requiredDependencies)
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = project.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(sourceLabel)
                append(" · ")
                append(compactCount(project.downloads))
                append(" 下载")
                project.categories.take(4).takeIf { it.isNotEmpty() }?.let { categories ->
                    append(" · ")
                    append(categories.joinToString(" / "))
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (project.description.isNotBlank()) {
            Text(
                text = project.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val category = state.download.category
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_content_target),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (category.installsToNewInstance) {
                Text(
                    text = stringResource(Res.string.downloads_content_target_new_instance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            } else {
                DropdownField(
                    value = target?.name ?: stringResource(Res.string.downloads_content_target_empty),
                    options = state.instances.map { instance ->
                        instance.name to { model.setContentTargetInstance(instance.id) }
                    },
                    enabled = state.instances.isNotEmpty(),
                    fillWidth = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(Res.string.downloads_content_version_filter),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownField(
                value = browser.versionChannel.title(),
                options = ContentVersionChannel.entries.map { channel ->
                    val count = browser.versionCount(channel)
                    val label = if (channel == ContentVersionChannel.ALL || count > 0) {
                        "${channel.title()} ($count)"
                    } else {
                        channel.title()
                    }
                    label to { model.setContentVersionChannel(channel) }
                },
                enabled = browser.versions.isNotEmpty(),
                fillWidth = true,
                testTag = ContentVersionChannelTag,
                modifier = Modifier.weight(0.85f),
            )
        }
        if (category.worldScoped) {
            val selectedWorld = browser.worlds.firstOrNull { it.name == browser.targetWorld }
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        (world.levelName ?: world.name) to {
                            model.selectContentWorld(world.name)
                        }
                    },
                    enabled = browser.worlds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.downloads_content_versions),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (browser.versions.isNotEmpty()) {
                Text(
                    text = stringResource(
                        Res.string.downloads_content_versions_count,
                        browser.visibleVersions.size,
                        browser.versions.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (browser.isLoadingVersions) {
                Spacer(Modifier.size(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        when {
            browser.versionsError != null -> Text(
                text = browser.versionsError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            !browser.isLoadingVersions && browser.versions.isEmpty() -> Text(
                text = stringResource(Res.string.downloads_content_versions_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            browser.visibleVersions.isEmpty() -> Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.downloads_content_versions_channel_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { model.setContentVersionChannel(ContentVersionChannel.ALL) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(Res.string.downloads_content_versions_show_all))
                }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(browser.visibleVersions, key = { it.versionId }) { version ->
                    VersionRow(
                        version = version,
                        selected = version.versionId == browser.selectedVersion?.versionId,
                        onSelect = { model.selectContentVersion(version.versionId) },
                    )
                }
            }
        }

        val selected = browser.selectedVersion
        if (selected != null) {
            Text(
                text = buildString {
                    selected.file?.let { file ->
                        append(file.fileName)
                        append(" · ")
                        append(InstanceFormat.byteSize(file.size))
                    }
                    dependenciesText?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val loaderMissing = category == DownloadsCategory.MOD &&
            target?.loader == LoaderKind.VANILLA
        val targetReady = when {
            category.installsToNewInstance -> true
            category.worldScoped -> target != null && browser.targetWorld.isNotBlank()
            else -> target != null
        }
        val actionLabel = when (category) {
            DownloadsCategory.MODPACK -> Res.string.downloads_content_install_modpack
            DownloadsCategory.DATAPACK -> Res.string.downloads_content_install_datapack
            else -> Res.string.downloads_content_install
        }
        Button(
            onClick = model::installSelectedContent,
            enabled = browser.canInstall && targetReady && !loaderMissing,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ContentInstallTag),
        ) {
            if (browser.isInstalling || browser.isCheckingDependencies) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = when {
                    browser.isCheckingDependencies ->
                        stringResource(Res.string.downloads_content_dependency_checking)

                    browser.isInstalling ->
                        stringResource(Res.string.downloads_content_install_running)

                    else -> stringResource(actionLabel)
                },
            )
        }
    }

    browser.dependencyPrompt?.let { prompt ->
        DependencyDialog(
            prompt = prompt,
            onAutoDownload = { model.confirmContentDependencyPrompt(true) },
            onSkip = { model.confirmContentDependencyPrompt(false) },
            onDismiss = model::dismissContentDependencyPrompt,
        )
    }
}

@Composable
private fun DependencyDialog(
    prompt: ContentDependencyPrompt,
    onAutoDownload: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ContentDependencyDialogTag),
        title = { Text(stringResource(Res.string.downloads_content_dependency_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(
                        if (prompt.items.isEmpty()) {
                            Res.string.downloads_content_dependency_warning_body
                        } else {
                            Res.string.downloads_content_dependency_body
                        },
                        prompt.projectName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    prompt.items.take(5).forEach { item ->
                        DependencyRow(item = item)
                    }
                }
                if (prompt.items.size > 5) {
                    Text(
                        text = stringResource(
                            Res.string.downloads_content_dependency_more,
                            prompt.items.size - 5,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                prompt.warnings.take(3).forEach { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(Res.string.downloads_content_dependency_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAutoDownload,
                modifier = Modifier.testTag(ContentDependencyAutoTag),
            ) {
                Text(stringResource(Res.string.downloads_content_dependency_auto))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.testTag(ContentDependencySkipTag),
            ) {
                Text(stringResource(Res.string.downloads_content_dependency_skip))
            }
        },
    )
}

@Composable
private fun DependencyRow(item: CoreContentInstallItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(contentDependencyItemTag(item.projectId)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.tertiary, CircleShape),
        )
        Text(
            text = item.projectName.ifBlank { item.projectId },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = buildString {
                append(item.versionNumber.ifBlank { item.versionId })
                if (item.size > 0) {
                    append(" · ")
                    append(InstanceFormat.byteSize(item.size))
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun VersionRow(
    version: CoreContentVersion,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(contentVersionTag(version.versionId))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = version.name.ifBlank { version.versionNumber },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        version.gameVersions.take(3).takeIf { it.isNotEmpty() }?.let {
                            append(it.joinToString(", "))
                        }
                        version.loaders.take(3).takeIf { it.isNotEmpty() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it.joinToString(", "))
                        }
                        version.file?.let {
                            if (isNotEmpty()) append(" · ")
                            append(InstanceFormat.byteSize(it.size))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            VersionTypeBadge(version.versionType)
        }
    }
}

/** Colored dot + label so 正式版 / 测试版 / 内测版 are distinguishable at a glance. */
@Composable
private fun VersionTypeBadge(versionType: String) {
    val normalized = versionType.lowercase()
    val label = stringResource(
        when (normalized) {
            "beta" -> Res.string.content_version_beta
            "alpha" -> Res.string.content_version_alpha
            else -> Res.string.content_version_release
        },
    )
    val tint: Color = when (normalized) {
        "beta" -> MaterialTheme.colorScheme.tertiary
        "alpha" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(tint, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

private fun compactCount(value: Long): String = when {
    value >= 100_000_000L -> {
        val tenths = value * 10 / 100_000_000L
        "${tenths / 10}.${tenths % 10} 亿"
    }

    value >= 10_000L -> "${value / 1_000L} 千"
    else -> value.toString()
}
