package com.fograin.launcher.ui

import androidx.annotation.StringRes
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
import com.fograin.launcher.R

/** Top-level destinations of the M1 shell, matching the pen's bottom navigation. */
enum class AppDestination(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME(R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    LIBRARY(R.string.nav_library, Icons.Outlined.Apps, Icons.Filled.Apps),
    DOWNLOADS(R.string.nav_downloads, Icons.Outlined.FileDownload, Icons.Filled.FileDownload),
    SETTINGS(R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}
