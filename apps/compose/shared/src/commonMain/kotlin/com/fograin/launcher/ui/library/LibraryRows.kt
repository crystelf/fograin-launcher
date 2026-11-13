package com.fograin.launcher.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.InstanceTile
import org.jetbrains.compose.resources.stringResource

/** One row of the grouped instance library table. */
@Composable
fun LibraryRow(
    model: AppModel,
    instance: Instance,
    isSelected: Boolean,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val background = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .clickable { model.showPreview(instance) }
            .padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InstanceTile(instance = instance, size = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = instance.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (instance.isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(Res.string.home_favorite_badge),
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                text = "${instance.meta} · ${InstanceFormat.byteSize(instance.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = InstanceFormat.relativeTime(instance.lastPlayedEpochMillis, nowMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(Res.string.action_more),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.library_menu_start)) },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        model.startGame(instance)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.library_menu_manage)) },
                    leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        model.manageInstance(instance)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_copy_instance_dir)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        model.copyInstanceDirectoryPath(instance)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (instance.isFavorite) {
                                    Res.string.library_menu_unfavorite
                                } else {
                                    Res.string.library_menu_favorite
                                },
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        model.toggleFavorite(instance)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.library_menu_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        model.manageInstance(instance)
                    },
                )
            }
        }
    }
}
