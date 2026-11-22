package com.fograin.launcher.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Public
import androidx.compose.ui.graphics.vector.ImageVector
import com.fograin.launcher.model.DownloadsCategory

/** Category glyph shown while a project icon loads, or when a file has none. */
internal fun DownloadsCategory.fallbackIcon(): ImageVector = when (this) {
    DownloadsCategory.MOD -> Icons.Outlined.Extension
    DownloadsCategory.RESOURCEPACK -> Icons.Outlined.Image
    DownloadsCategory.SHADERPACK -> Icons.Outlined.MovieFilter
    DownloadsCategory.MODPACK -> Icons.Outlined.Inventory2
    DownloadsCategory.DATAPACK -> Icons.Outlined.FolderZip
    DownloadsCategory.GAME -> Icons.Outlined.Inventory2
}

/** Same mapping for an installed file's `kind` wire value. */
internal fun contentFallbackIcon(kind: String): ImageVector = when (kind.lowercase()) {
    "resourcepack", "resource_pack" -> Icons.Outlined.Image
    "shaderpack", "shader_pack" -> Icons.Outlined.MovieFilter
    "world" -> Icons.Outlined.Public
    "datapack" -> Icons.Outlined.FolderZip
    "modpack" -> Icons.Outlined.Inventory2
    else -> Icons.Outlined.Extension
}
