package com.fograin.launcher.ui.content

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.ContentManagerTab
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.CoreContentUpdate
import com.fograin.launcher.platform.CoreDatapack
import com.fograin.launcher.platform.CoreLocalContent
import com.fograin.launcher.platform.CoreWorld
import com.fograin.launcher.platform.CoreWorldBackup
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.SegmentedTabRail
import com.fograin.launcher.ui.components.fograinTabTransition
import com.fograin.launcher.ui.emptyTitle
import com.fograin.launcher.ui.relativeTimeText
import com.fograin.launcher.ui.title
import org.jetbrains.compose.resources.stringResource

const val ContentManagerTag: String = "content-manager"
const val ContentCheckUpdatesTag: String = "content-check-updates"

fun contentTabTag(tab: ContentManagerTab): String = "content-tab-${tab.name.lowercase()}"

fun contentItemTag(fileName: String): String = "content-item-$fileName"

fun contentUpdateTag(fileName: String): String = "content-update-$fileName"

/**
 * Inline instance content manager used by the instance detail page.
 *
 * It mirrors the downloads page: a segmented category rail with counts, one
 * compact toolbar, then an animated list pane. The same rail component and
 * [fograinTabTransition] are used on both pages so switching categories feels
 * identical whether the user is browsing online content or managing an
 * installed instance.
 */
@Composable
fun ContentManager(
    model: AppModel,
    state: AppUiState,
    modifier: Modifier = Modifier,
) {
    val manager = state.content
    // Dialogs belong to one instance; opening another instance must not leave
    // a confirmation pointing at the previous instance's files.
    var deleteContent by remember(manager.instanceId) { mutableStateOf<CoreLocalContent?>(null) }
    var deleteWorld by remember(manager.instanceId) { mutableStateOf<CoreWorld?>(null) }
    var deleteBackup by remember(manager.instanceId) { mutableStateOf<CoreWorldBackup?>(null) }
    var restoreBackup by remember(manager.instanceId) { mutableStateOf<CoreWorldBackup?>(null) }
    var restoreName by remember(manager.instanceId) { mutableStateOf("") }
    var deleteDatapack by remember(manager.instanceId) { mutableStateOf<CoreDatapack?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ContentManagerTag),
    ) {
        SegmentedTabRail(
            options = ContentManagerTab.entries,
            selected = manager.tab,
            onSelect = model.content::setTab,
            modifier = Modifier.fillMaxWidth(),
            tabModifier = { tab -> Modifier.testTag(contentTabTag(tab)) },
            label = { tab -> "${tab.title()} ${manager.countFor(tab)}" },
        )

        Spacer(Modifier.height(10.dp))

        // Worlds have no update check, so the whole content toolbar collapses
        // and the list gets the row back; the worlds header carries refresh.
        AnimatedVisibility(
            visible = manager.tab == ContentManagerTab.MODS ||
                manager.tab == ContentManagerTab.RESOURCE_PACKS ||
                manager.tab == ContentManagerTab.SHADER_PACKS,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(100)),
        ) {
            ContentToolbar(model = model, state = state)
        }

        AnimatedVisibility(
            visible = manager.isLoading,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(100)),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        AnimatedVisibility(
            visible = manager.error != null,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(100)),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    Text(
                        text = manager.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        AnimatedContent(
            targetState = manager.tab,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            transitionSpec = { fograinTabTransition() },
            label = "content-tab",
        ) { tab ->
            when (tab) {
                ContentManagerTab.WORLDS -> WorldsPane(
                    model = model,
                    state = state,
                    onDeleteWorld = { deleteWorld = it },
                    onDeleteBackup = { deleteBackup = it },
                    onRestore = { backup ->
                        restoreBackup = backup
                        restoreName = "${backup.name.removeSuffix(".zip")}-restored"
                    },
                )

                ContentManagerTab.DATAPACKS -> DatapacksPane(
                    model = model,
                    state = state,
                    onDelete = { deleteDatapack = it },
                )

                else -> LocalContentPane(
                    model = model,
                    state = state,
                    tab = tab,
                    onDelete = { deleteContent = it },
                )
            }
        }
    }

    deleteContent?.let { item ->
        ConfirmDialog(
            title = stringResource(Res.string.content_delete_content_title, item.title),
            body = stringResource(Res.string.content_delete_content_body),
            confirm = stringResource(Res.string.action_delete),
            onConfirm = {
                model.content.delete(item)
                deleteContent = null
            },
            onDismiss = { deleteContent = null },
        )
    }
    deleteWorld?.let { world ->
        ConfirmDialog(
            title = stringResource(Res.string.content_delete_world_title, world.name),
            body = stringResource(Res.string.content_delete_world_body),
            confirm = stringResource(Res.string.action_delete),
            onConfirm = {
                model.content.deleteWorld(world.name)
                deleteWorld = null
            },
            onDismiss = { deleteWorld = null },
        )
    }
    deleteBackup?.let { backup ->
        ConfirmDialog(
            title = stringResource(
                Res.string.content_backup_delete_title,
                backup.name.removeSuffix(".zip"),
            ),
            body = stringResource(Res.string.content_backup_delete_body),
            confirm = stringResource(Res.string.action_delete),
            onConfirm = {
                model.content.deleteBackup(backup)
                deleteBackup = null
            },
            onDismiss = { deleteBackup = null },
        )
    }
    restoreBackup?.let { backup ->
        AlertDialog(
            onDismissRequest = { restoreBackup = null },
            title = { Text(stringResource(Res.string.content_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(Res.string.content_restore_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = restoreName,
                        onValueChange = { restoreName = it },
                        label = { Text(stringResource(Res.string.content_restore_name)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.restoreWorldBackup(backup, restoreName)
                        restoreBackup = null
                    },
                    enabled = restoreName.isNotBlank(),
                ) {
                    Text(stringResource(Res.string.content_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreBackup = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    deleteDatapack?.let { item ->
        ConfirmDialog(
            title = stringResource(Res.string.datapack_delete_title, item.title),
            body = stringResource(Res.string.datapack_delete_body),
            confirm = stringResource(Res.string.action_delete),
            onConfirm = {
                model.deleteDatapack(item)
                deleteDatapack = null
            },
            onDismiss = { deleteDatapack = null },
        )
    }
}

@Composable
private fun ContentToolbar(model: AppModel, state: AppUiState) {
    val manager = state.content
    val status = when {
        manager.updateUnknown > 0 && manager.updates.isEmpty() ->
            stringResource(Res.string.content_unknown_sources, manager.updateUnknown)

        manager.updates.isNotEmpty() ->
            stringResource(Res.string.content_updates_count, manager.updates.size)

        else -> ""
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = model.content::checkUpdates,
            enabled = !manager.isCheckingUpdates && !manager.isLoading,
            modifier = Modifier.testTag(ContentCheckUpdatesTag),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            if (manager.isCheckingUpdates) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Outlined.SystemUpdateAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(stringResource(Res.string.content_check_updates))
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            onClick = model.content::refresh,
            enabled = !manager.isLoading,
        ) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = stringResource(Res.string.content_refresh),
            )
        }
    }
}

@Composable
private fun LocalContentPane(
    model: AppModel,
    state: AppUiState,
    tab: ContentManagerTab,
    onDelete: (CoreLocalContent) -> Unit,
) {
    val manager = state.content
    val items = manager.visibleItemsFor(tab)
    val searching = manager.query.isNotBlank()
    if (items.isEmpty() && !manager.isLoading) {
        val title = if (searching) {
            stringResource(Res.string.content_search_empty_title)
        } else {
            tab.emptyTitle()
        }
        EmptyContent(
            icon = tab.emptyIcon(),
            title = title,
            body = stringResource(
                if (searching) {
                    Res.string.content_search_empty_body
                } else {
                    Res.string.content_empty_body
                },
            ),
            action = if (searching) {
                null
            } else {
                {
                    TextButton(onClick = { model.browseContentFor(tab) }) {
                        Text(stringResource(Res.string.content_browse_downloads))
                    }
                }
            },
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.fileName }) { item ->
            LocalContentRow(
                item = item,
                update = manager.updateFor(item.fileName),
                busy = manager.busyFile == item.fileName,
                onToggle = { model.content.toggle(item) },
                onDelete = { onDelete(item) },
                onUpdate = { update -> model.content.installUpdate(update) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun LocalContentRow(
    item: CoreLocalContent,
    update: CoreContentUpdate?,
    busy: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (CoreContentUpdate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = buildList {
        item.version?.takeIf { it.isNotBlank() }?.let { add(it) }
        item.loader?.takeIf { it.isNotBlank() }?.let { add(loaderLabel(it)) }
        item.packFormat?.let { add(stringResource(Res.string.content_pack_format, it)) }
        add(InstanceFormat.byteSize(item.sizeBytes))
    }.joinToString(" · ")

    val container by animateColorAsState(
        targetValue = if (item.enabled) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(180),
        label = "content-row-background",
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        modifier = modifier
            .fillMaxWidth()
            .testTag(contentItemTag(item.fileName)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Switch(checked = item.enabled, onCheckedChange = { onToggle() })
                }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(Res.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (update != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            Res.string.content_update_available,
                            update.latestVersion,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onUpdate(update) },
                        enabled = !busy,
                        modifier = Modifier.testTag(contentUpdateTag(item.fileName)),
                    ) {
                        Text(stringResource(Res.string.content_update_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldsPane(
    model: AppModel,
    state: AppUiState,
    onDeleteWorld: (CoreWorld) -> Unit,
    onDeleteBackup: (CoreWorldBackup) -> Unit,
    onRestore: (CoreWorldBackup) -> Unit,
) {
    val manager = state.content
    val worlds = manager.visibleWorlds()
    val backups = manager.visibleBackups()
    val searching = manager.query.isNotBlank()

    if (worlds.isEmpty() && backups.isEmpty() && !manager.isLoading) {
        EmptyContent(
            icon = Icons.Outlined.Public,
            title = if (searching) {
                stringResource(Res.string.content_search_empty_title)
            } else {
                stringResource(Res.string.content_empty_worlds)
            },
            body = stringResource(
                if (searching) {
                    Res.string.content_search_empty_body
                } else {
                    Res.string.content_empty_body
                },
            ),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(Res.string.content_tab_worlds),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${worlds.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = model.content::refresh,
                    enabled = !manager.isLoading,
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(Res.string.content_refresh),
                    )
                }
            }
        }
        if (worlds.isEmpty() && searching) {
            item {
                Text(
                    text = stringResource(Res.string.content_search_empty_worlds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(worlds, key = { "world-${it.name}" }) { world ->
            WorldRow(
                world = world,
                busy = manager.busyWorld == world.name,
                onBackup = { model.content.backupWorld(world.name) },
                onDelete = { onDeleteWorld(world) },
                modifier = Modifier.animateItem(),
            )
        }
        item {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.content_backups),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${backups.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(Res.string.content_backup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (backups.isEmpty()) {
            item {
                Text(
                    text = stringResource(
                        if (searching) {
                            Res.string.content_search_empty_title
                        } else {
                            Res.string.content_no_backups
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(backups, key = { "backup-${it.name}" }) { backup ->
            BackupRow(
                backup = backup,
                onRestore = { onRestore(backup) },
                onDelete = { onDeleteBackup(backup) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun DatapacksPane(
    model: AppModel,
    state: AppUiState,
    onDelete: (CoreDatapack) -> Unit,
) {
    val manager = state.content
    val worlds = manager.worlds
    if (worlds.isEmpty()) {
        EmptyContent(
            icon = Icons.Outlined.FolderZip,
            title = stringResource(Res.string.datapack_no_world_title),
            body = stringResource(Res.string.datapack_no_world_body),
        )
        return
    }
    val selected = worlds.firstOrNull { it.name == manager.datapackWorld } ?: worlds.first()
    val items = manager.visibleDatapacks()
    val searching = manager.query.isNotBlank()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SegmentedTabRail(
                options = worlds,
                selected = selected,
                onSelect = { world -> model.content.selectDatapackWorld(world.name) },
                modifier = Modifier.weight(1f),
                label = { world -> world.levelName ?: world.name },
            )
            IconButton(onClick = { model.content.importFromFile() }) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(Res.string.datapack_import),
                )
            }
            IconButton(
                onClick = model.content::refreshDatapacks,
                enabled = !manager.datapacksLoading,
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(Res.string.content_refresh),
                )
            }
        }
        Text(
            text = stringResource(Res.string.datapack_hint_short),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (manager.datapacksLoading) {
            LinearProgressIndicator(modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp))
        }

        if (items.isEmpty() && !manager.datapacksLoading) {
            EmptyContent(
                icon = Icons.Outlined.FolderZip,
                title = if (searching) {
                    stringResource(Res.string.datapack_search_empty)
                } else {
                    stringResource(Res.string.datapack_empty_title)
                },
                body = stringResource(Res.string.datapack_empty_body),
                action = if (searching) {
                    null
                } else {
                    {
                        TextButton(onClick = { model.content.importFromFile() }) {
                            Text(stringResource(Res.string.datapack_import))
                        }
                    }
                },
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.fileName }) { item ->
                DatapackRow(
                    item = item,
                    onToggle = { model.toggleDatapack(item) },
                    onDelete = { onDelete(item) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun DatapackRow(
    item: CoreDatapack,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (item.enabled) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(180),
        label = "datapack-row-background",
    )
    val formatLabel = item.packFormat?.let {
        stringResource(Res.string.datapack_format, it)
    }
    val meta = buildList {
        formatLabel?.let(::add)
        add(InstanceFormat.byteSize(item.sizeBytes))
    }.joinToString(" · ")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = item.enabled, onCheckedChange = { onToggle() })
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(Res.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WorldRow(
    world: CoreWorld,
    busy: Boolean,
    onBackup: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = world.levelName?.takeIf { it.isNotBlank() } ?: world.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val modified = world.modifiedMs?.let { millis ->
                    stringResource(
                        Res.string.content_world_modified,
                        relativeTimeText(millis),
                    )
                }
                Text(
                    text = listOfNotNull(
                        world.version,
                        gameModeLabel(world.gameMode),
                        InstanceFormat.byteSize(world.sizeBytes),
                        modified,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            IconButton(onClick = onBackup, enabled = !busy) {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = stringResource(Res.string.content_backup),
                )
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BackupRow(
    backup: CoreWorldBackup,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.name.removeSuffix(".zip"),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildList {
                        add(InstanceFormat.byteSize(backup.sizeBytes))
                        backup.modifiedMs?.let {
                            add(InstanceFormat.relativeTime(it))
                        }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRestore) {
                Icon(Icons.Outlined.Restore, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(Res.string.content_restore))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyContent(
    icon: ImageVector,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

private fun ContentManagerTab.emptyIcon(): ImageVector = when (this) {
    ContentManagerTab.MODS -> Icons.Outlined.Extension
    ContentManagerTab.RESOURCE_PACKS -> Icons.Outlined.Image
    ContentManagerTab.SHADER_PACKS -> Icons.Outlined.MovieFilter
    ContentManagerTab.WORLDS -> Icons.Outlined.Public
    ContentManagerTab.DATAPACKS -> Icons.Outlined.FolderZip
}

@Composable
private fun gameModeLabel(mode: String?): String? = when (mode?.lowercase()) {
    "survival" -> stringResource(Res.string.world_mode_survival)
    "creative" -> stringResource(Res.string.world_mode_creative)
    "adventure" -> stringResource(Res.string.world_mode_adventure)
    "spectator" -> stringResource(Res.string.world_mode_spectator)
    else -> null
}

private fun loaderLabel(loader: String): String = when (loader.lowercase()) {
    "fabric" -> "Fabric"
    "quilt" -> "Quilt"
    "forge" -> "Forge"
    "neoforge" -> "NeoForge"
    else -> loader
}
