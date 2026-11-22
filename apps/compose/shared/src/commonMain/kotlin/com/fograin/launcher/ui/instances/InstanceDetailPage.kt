package com.fograin.launcher.ui.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.model.Instance
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.content.ContentManager
import org.jetbrains.compose.resources.stringResource

const val InstanceDetailTag: String = "instance-detail"
const val InstanceQuickStartTag: String = "instance-quick-start"
const val InstanceFavoriteTag: String = "instance-favorite"
const val InstanceOverflowTag: String = "instance-overflow"

/**
 * Instance management page: the page header owns the launch action and the
 * inline content manager fills the rest.
 *
 * The header carries the instance name, search, 开始游戏, 收藏 and the overflow
 * menu (游戏设置 / 复制目录 / 删除). There is deliberately no second instance
 * identity row: the name appears once, at the top. On compact widths the launch
 * action collapses to a 40 dp icon button so the name keeps enough room.
 */
@Composable
fun InstanceDetailPage(
    model: AppModel,
    state: AppUiState,
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
) {
    val current = state.libraryDetailInstance
    var retained by remember { mutableStateOf(current) }
    LaunchedEffect(current) {
        if (current != null) retained = current
    }
    val instance = current ?: retained ?: return

    var menuExpanded by remember(instance.id) { mutableStateOf(false) }
    var showDeleteConfirm by remember(instance.id) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (state.content.query.isNotEmpty()) onSearchExpandedChange(true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(InstanceDetailTag),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactHeader = maxWidth < 640.dp
            PageHeader(
                title = instance.name,
                onBack = model::closeInstanceDetail,
                searchPlaceholder = stringResource(Res.string.instance_detail_search_placeholder),
                searchQuery = state.content.query,
                onSearchQueryChange = model.content::setQuery,
                searchExpanded = searchExpanded,
                onSearchExpandedChange = onSearchExpandedChange,
                actions = {
                    LaunchAction(
                        model = model,
                        state = state,
                        instance = instance,
                        compact = compactHeader,
                    )
                    FavoriteAction(model = model, instance = instance)
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag(InstanceOverflowTag),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(Res.string.action_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.action_game_settings)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Tune, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    model.manageInstance(instance)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.action_open_instance_dir)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    model.instanceSettings.openDirectory(instance)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.library_menu_delete)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Delete, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteConfirm = true
                                },
                            )
                        }
                    }
                },
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ContentManager(
                model = model,
                state = state,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(Res.string.instance_settings_delete_title)) },
            text = {
                Text(stringResource(Res.string.instance_settings_delete_body, instance.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        model.library.deleteInstance(instance)
                    },
                ) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

/** Play/stop in the header; a labelled button on wide windows, icon on phones. */
@Composable
private fun LaunchAction(
    model: AppModel,
    state: AppUiState,
    instance: Instance,
    compact: Boolean,
) {
    val running = isRunning(state, instance)
    val label = stringResource(
        when {
            running -> Res.string.action_stop_game
            instance.isInstalled -> Res.string.action_start_game
            else -> Res.string.instance_detail_complete_and_play
        },
    )
    val onClick = {
        if (running) model.launch.stop() else model.launch.start(instance)
    }
    if (compact) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier
                .size(40.dp)
                .testTag(InstanceQuickStartTag),
        ) {
            Icon(
                imageVector = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
            )
        }
    } else {
        Button(
            onClick = onClick,
            modifier = Modifier
                .height(40.dp)
                .testTag(InstanceQuickStartTag),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Icon(
                imageVector = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Favourite toggle, kept next to the launch action as the second primary verb. */
@Composable
private fun FavoriteAction(model: AppModel, instance: Instance) {
    IconButton(
        onClick = { model.library.toggleFavorite(instance) },
        modifier = Modifier
            .size(40.dp)
            .testTag(InstanceFavoriteTag),
    ) {
        Icon(
            imageVector = if (instance.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
            contentDescription = stringResource(
                if (instance.isFavorite) {
                    Res.string.library_menu_unfavorite
                } else {
                    Res.string.library_menu_favorite
                },
            ),
            modifier = Modifier.size(21.dp),
            tint = if (instance.isFavorite) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun isRunning(state: AppUiState, instance: Instance): Boolean =
    state.sessionRunning && state.currentInstanceId == instance.id
