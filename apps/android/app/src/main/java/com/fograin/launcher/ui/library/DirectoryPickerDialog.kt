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
import java.io.File

/**
 * In-app directory browser for `instances.import`.
 *
 * Android's storage sandbox means a SAF tree cannot be handed to the Rust core
 * as a path, so the picker walks the app's own data root. That directory is
 * reachable over USB, which is where a `.minecraft` folder can be placed
 * without copying it into launcher-managed storage.
 */
@Composable
fun DirectoryPickerDialog(
    rootPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var currentPath by remember(rootPath) { mutableStateOf(rootPath) }
    val directories = remember(currentPath) {
        runCatching {
            File(currentPath).listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }.getOrDefault(emptyList())
    }
    val atRoot = currentPath == rootPath
    val canGoUp = !atRoot && currentPath.startsWith(rootPath)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 .minecraft 目录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "选择一个现有的 .minecraft 目录。文件保留在原处，只登记为实例游戏目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentPath.ifBlank { "数据目录尚未就绪" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (rootPath.isBlank()) {
                    Text(
                        text = "启动器核心尚未初始化，无法浏览目录。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                    ) {
                        if (canGoUp) {
                            item {
                                DirectoryRow(
                                    name = "..",
                                    icon = { Icon(Icons.Outlined.ArrowUpward, null) },
                                    onClick = {
                                        currentPath = File(currentPath).parentFile?.absolutePath
                                            ?: rootPath
                                    },
                                )
                            }
                        }
                        if (directories.isEmpty()) {
                            item {
                                Text(
                                    text = "这个目录下没有子目录。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                        }
                        items(items = directories, key = { it.absolutePath }) { directory ->
                            DirectoryRow(
                                name = directory.name,
                                icon = { Icon(Icons.Filled.Folder, null) },
                                onClick = { currentPath = directory.absolutePath },
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
                Text("选择此目录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
