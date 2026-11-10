package com.fograin.launcher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Compact tonal status pill shown over the hero artwork. */
@Composable
fun StatusPill(
    text: String,
    icon: ImageVector? = null,
    tone: StatusTone = StatusTone.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    val container = when (tone) {
        StatusTone.READY -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        StatusTone.RUNNING -> MaterialTheme.colorScheme.secondaryContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
    }
    val content = when (tone) {
        StatusTone.READY -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.RUNNING -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(imageVector = it, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

enum class StatusTone { READY, WARNING, RUNNING, NEUTRAL }
