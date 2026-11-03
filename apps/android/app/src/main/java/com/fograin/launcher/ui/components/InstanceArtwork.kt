package com.fograin.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fograin.launcher.generated.FograinTokens
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.LoaderKind

/** Tile color for an instance palette, straight from `design/tokens.json`. */
fun Instance.InstancePalette.toColor(): Color = when (this) {
    Instance.InstancePalette.FOREST -> FograinTokens.Palette.forest
    Instance.InstancePalette.COPPER -> FograinTokens.Palette.copper
    Instance.InstancePalette.SLATE -> FograinTokens.Palette.slate
    Instance.InstancePalette.VIOLET -> FograinTokens.Palette.violet
    Instance.InstancePalette.CLAY -> FograinTokens.Palette.clay
    Instance.InstancePalette.LAGOON -> FograinTokens.Palette.lagoon
    Instance.InstancePalette.SAND -> FograinTokens.Palette.sand
    Instance.InstancePalette.MOSS -> FograinTokens.Palette.moss
    Instance.InstancePalette.ASH -> FograinTokens.Palette.ash
}

/** Material icon used by the generated instance tile. */
fun LoaderKind.icon(): ImageVector = when (this) {
    LoaderKind.VANILLA -> Icons.Filled.Terrain
    LoaderKind.OPTIFINE -> Icons.Filled.Star
    LoaderKind.FABRIC -> Icons.Filled.Extension
    LoaderKind.QUILT -> Icons.Filled.Apps
    LoaderKind.FORGE -> Icons.Filled.Build
    LoaderKind.NEOFORGE -> Icons.Filled.LocalFireDepartment
}

/**
 * Generated artwork for an instance: a rounded tile with the loader glyph.
 *
 * The pen uses per-instance key art; the M1 shell only ships the Fograin hero
 * image, so every row gets this token-colored tile instead.
 */
@Composable
fun InstanceTile(
    instance: Instance,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                color = instance.palette.toColor(),
                shape = RoundedCornerShape(size * 0.27f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = instance.loader.icon(),
            contentDescription = null,
            modifier = Modifier.size(size * 0.42f),
            tint = Color.White.copy(alpha = 0.92f),
        )
    }
}

/** Small dot marking an instance whose content has updates. */
@Composable
fun UpdateDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
    )
}
