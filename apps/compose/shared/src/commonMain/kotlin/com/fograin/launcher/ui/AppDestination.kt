package com.fograin.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.ui.graphics.vector.ImageVector
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.nav_community
import com.fograin.launcher.resources.nav_downloads
import com.fograin.launcher.resources.nav_home
import com.fograin.launcher.resources.nav_library
import org.jetbrains.compose.resources.StringResource

/**
 * Top-level destinations of the shell. Settings is deliberately not a tab:
 * it opens from the home header gear (and the desktop rail shortcut).
 */
enum class AppDestination(
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME(Icons.Outlined.Home, Icons.Filled.Home),
    LIBRARY(Icons.Outlined.Apps, Icons.Filled.Apps),
    DOWNLOADS(Icons.Outlined.FileDownload, Icons.Filled.FileDownload),
    COMMUNITY(Icons.Outlined.Forum, Icons.Filled.Forum),
}

val AppDestination.label: StringResource
    get() = when (this) {
        AppDestination.HOME -> Res.string.nav_home
        AppDestination.LIBRARY -> Res.string.nav_library
        AppDestination.DOWNLOADS -> Res.string.nav_downloads
        AppDestination.COMMUNITY -> Res.string.nav_community
    }
