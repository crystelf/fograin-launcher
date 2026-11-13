package com.fograin.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.nav_downloads
import com.fograin.launcher.resources.nav_home
import com.fograin.launcher.resources.nav_library
import com.fograin.launcher.resources.nav_settings
import org.jetbrains.compose.resources.StringResource

/** Top-level destinations of the shell, matching the pen's bottom navigation. */
enum class AppDestination(
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME(Icons.Outlined.Home, Icons.Filled.Home),
    LIBRARY(Icons.Outlined.Apps, Icons.Filled.Apps),
    DOWNLOADS(Icons.Outlined.FileDownload, Icons.Filled.FileDownload),
    SETTINGS(Icons.Outlined.Settings, Icons.Filled.Settings),
}

val AppDestination.label: StringResource
    get() = when (this) {
        AppDestination.HOME -> Res.string.nav_home
        AppDestination.LIBRARY -> Res.string.nav_library
        AppDestination.DOWNLOADS -> Res.string.nav_downloads
        AppDestination.SETTINGS -> Res.string.nav_settings
    }
