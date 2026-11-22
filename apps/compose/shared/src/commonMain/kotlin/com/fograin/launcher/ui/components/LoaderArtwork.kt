package com.fograin.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.model.LoaderKind
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.loader_fabric
import com.fograin.launcher.resources.loader_forge
import com.fograin.launcher.resources.loader_neoforge
import com.fograin.launcher.resources.loader_optifine
import com.fograin.launcher.resources.loader_quilt
import com.fograin.launcher.resources.loader_vanilla
import com.fograin.launcher.resources.version_old
import com.fograin.launcher.resources.version_snapshot
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Official loader artwork, used as the square instance icon everywhere outside
 * the home hero.
 *
 * Sources: Modrinth's loader catalog (grass block, Forge anvil, NeoForge fox,
 * Fabric needle, Quilt patchwork) and OptiFine's own badge. Every PNG has a
 * transparent background, so the tile blends into the current surface instead
 * of sitting on a generated color plate.
 */
internal fun LoaderKind.artwork(): DrawableResource = when (this) {
    LoaderKind.VANILLA -> Res.drawable.loader_vanilla
    LoaderKind.FABRIC -> Res.drawable.loader_fabric
    LoaderKind.QUILT -> Res.drawable.loader_quilt
    LoaderKind.FORGE -> Res.drawable.loader_forge
    LoaderKind.NEOFORGE -> Res.drawable.loader_neoforge
    LoaderKind.OPTIFINE -> Res.drawable.loader_optifine
}

internal fun GameLoaderKind.artwork(): DrawableResource = when (this) {
    GameLoaderKind.VANILLA -> Res.drawable.loader_vanilla
    GameLoaderKind.FABRIC -> Res.drawable.loader_fabric
    GameLoaderKind.QUILT -> Res.drawable.loader_quilt
    GameLoaderKind.FORGE -> Res.drawable.loader_forge
    GameLoaderKind.NEOFORGE -> Res.drawable.loader_neoforge
    GameLoaderKind.OPTIFINE -> Res.drawable.loader_optifine
}

/**
 * Square loader icon with a transparent background. The image itself carries
 * the official artwork; no letter, plate, tint or hand-drawn fallback is added.
 */
@Composable
fun LoaderGlyph(
    kind: LoaderKind,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Image(
        painter = painterResource(kind.artwork()),
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun LoaderGlyph(
    kind: GameLoaderKind,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Image(
        painter = painterResource(kind.artwork()),
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

/**
 * Icon for a game version channel. Releases get the grass block, snapshots the
 * command block, and pre-1.13 builds a pickaxe. Square and transparent, like
 * the loader icons.
 */
@Composable
fun VersionKindGlyph(
    kind: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val artwork = when (kind) {
        "release" -> Res.drawable.loader_vanilla
        "snapshot", "april_fools" -> Res.drawable.version_snapshot
        else -> Res.drawable.version_old
    }
    Image(
        painter = painterResource(artwork),
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
