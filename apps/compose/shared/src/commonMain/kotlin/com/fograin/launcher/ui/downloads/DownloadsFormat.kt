package com.fograin.launcher.ui.downloads

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Science
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.fograin.launcher.resources.*
import org.jetbrains.compose.resources.stringResource

internal fun versionIcon(kind: String): ImageVector = when (kind) {
    "release" -> Icons.Outlined.NewReleases
    "snapshot" -> Icons.Outlined.Science
    else -> Icons.Outlined.History
}

@Composable
internal fun kindText(kind: String): String = when (kind) {
    "release" -> stringResource(Res.string.downloads_kind_release)
    "snapshot" -> stringResource(Res.string.downloads_kind_snapshot)
    "old" -> stringResource(Res.string.downloads_kind_old)
    "april_fools" -> stringResource(Res.string.downloads_kind_april_fools)
    else -> kind
}
