package com.fograin.launcher.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * In-app directory browser for `instances.import`.
 *
 * Android's storage sandbox means a SAF tree cannot be handed to the Rust core
 * as a path, so the picker walks the app's own data root through the shared
 * [com.fograin.launcher.platform.DirectoryBrowser]. Desktop uses the same
 * abstraction until FileKit lands in R4.
 */
@Composable
fun DirectoryPickerDialog(
    rootPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val browser = remember { platformServices().directories }
    var currentPath by remember(rootPath) { mutableStateOf(rootPath) }
    val directories = remember(currentPath) {
        if (currentPath.isBlank()) emptyList() else browser.listDirectories(currentPath)
    }
    // Never walk above the launcher data root: on Android the parent chain
    // reaches other apps' directories, and the Rust core only accepts paths
    // below the root anyway.
    val canGoUp = rootPath.isNotBlank() &&
        currentPath.length > rootPath.length &&
        currentPath.startsWith(rootPath)
    val parent = if (canGoUp) browser.parentDirectory(currentPath) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.directory_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.directory_picker_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentPath.ifBlank {
                        stringResource(Res.string.directory_picker_root_missing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (rootPath.isBlank()) {
                    Text(
                        text = stringResource(Res.string.directory_picker_core_missing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                    ) {
                        if (parent != null) {
                            item {
                                DirectoryRow(
                                    name = "..",
                                    icon = {
                                        Icon(
                                            Icons.Outlined.ArrowUpward,
                                            contentDescription = stringResource(
                                                Res.string.directory_picker_up,
                                            ),
                                        )
                                    },
                                    onClick = { currentPath = parent },
                                )
                            }
                        }
                        if (directories.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.directory_picker_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                        }
                        items(items = directories, key = { it.path }) { directory ->
                            DirectoryRow(
                                name = directory.name,
                                icon = { Icon(Icons.Filled.Folder, null) },
                                onClick = { currentPath = directory.path },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSelect(currentPath) },
                enabled = rootPath.isNotBlank(),
            ) {
                Text(stringResource(Res.string.directory_picker_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@Composable
private fun DirectoryRow(
    name: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.size(24.dp)) { icon() }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
