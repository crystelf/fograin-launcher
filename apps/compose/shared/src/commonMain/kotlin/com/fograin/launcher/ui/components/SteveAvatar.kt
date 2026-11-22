package com.fograin.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.avatar_steve
import org.jetbrains.compose.resources.painterResource

/**
 * Steve's head, used for every local (offline) account.
 *
 * Offline profiles have no skin server and no reliable skin data, so the
 * launcher deliberately shows one familiar face instead of a generated letter
 * avatar. [selected] adds a primary ring without changing the artwork.
 */
@Composable
fun SteveAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val ring = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF3E2F26))
            .border(if (selected) 2.dp else 1.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.avatar_steve),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
