package com.fograin.launcher.ui.downloads

import androidx.compose.runtime.Composable
import com.fograin.launcher.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun kindText(kind: String): String = when (kind) {
    "release" -> stringResource(Res.string.downloads_kind_release)
    "snapshot" -> stringResource(Res.string.downloads_kind_snapshot)
    "old" -> stringResource(Res.string.downloads_kind_old)
    "april_fools" -> stringResource(Res.string.downloads_kind_april_fools)
    else -> kind
}
