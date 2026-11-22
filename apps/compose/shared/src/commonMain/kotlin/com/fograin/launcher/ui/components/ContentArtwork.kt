package com.fograin.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter

/**
 * Mod / resource-pack / shader-pack artwork.
 *
 * The image comes from the project's Modrinth or CurseForge listing, which is
 * the avatar users already recognize from the website. A category glyph is
 * shown while the request runs (or when a manually imported file has no
 * project metadata), so the tile never falls back to a letter.
 */
@Composable
fun ContentArtwork(
    iconUrl: String?,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val shape = RoundedCornerShape(size * 0.30f)
    val painter = rememberAsyncImagePainter(model = iconUrl)
    val state by painter.state.collectAsState()
    val loaded = state is AsyncImagePainter.State.Success
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Icon(
                imageVector = fallback,
                contentDescription = null,
                modifier = Modifier.size(size * 0.44f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        } else {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
