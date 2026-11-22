package com.fograin.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fograin.launcher.model.Instance

/**
 * Tile shown next to an instance everywhere in the shell.
 *
 * Instances no longer use generated palette tiles: the loader logo (grass
 * block, anvil, fox, needle, patchwork, OptiFine badge) is the identity.
 */
@Composable
fun InstanceTile(
    instance: Instance,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    LoaderGlyph(
        kind = instance.loader,
        modifier = modifier,
        size = size,
    )
}
