package com.fograin.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.home_fallback
import com.fograin.launcher.ui.artwork.rememberVersionPoster
import org.jetbrains.compose.resources.painterResource

/**
 * Full-bleed artwork for the home hero: the official Minecraft poster for the
 * instance's base version, with the bundled landscape as the immediate
 * backdrop.
 *
 * This is the only place that uses a non-square instance visual. Everything
 * else uses the square, transparent loader icons from [LoaderGlyph].
 *
 * The fallback is always drawn underneath, so the hero is never empty while
 * the patch-note feed loads, when the version predates the feed, or when the
 * device is offline. The poster fades in on top when Coil has it.
 */
@Composable
fun VersionBackdrop(
    minecraftVersion: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Image(
            painter = painterResource(Res.drawable.home_fallback),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        val poster = rememberVersionPoster(minecraftVersion)
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
            // Square 540 px patch art is upscaled on a wide hero; a whisper of
            // white keeps the darkest Pale Garden-style posters readable.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.10f)),
            )
        }
    }
}
