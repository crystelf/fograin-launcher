package com.fograin.launcher.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.model.Instance
import com.fograin.launcher.ui.metaText
import com.fograin.launcher.ui.relativeTimeText
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.InstanceTile
import org.jetbrains.compose.resources.stringResource

/** Stable tag for a library row, used by the navigation tests. */
fun libraryRowTag(instanceId: String): String = "library-row-$instanceId"

/**
 * One row of the grouped instance library table.
 *
 * The row is a pure navigation affordance now: the trailing chevron replaces
 * the old overflow menu and clicking anywhere enters the instance detail page,
 * where all management actions live.
 */
@Composable
fun LibraryRow(
    instance: Instance,
    isSelected: Boolean,
    nowMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(180),
        label = "library-row-background",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(libraryRowTag(instance.id))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InstanceTile(instance = instance, size = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = instance.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (instance.isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(Res.string.home_favorite_badge),
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                text = "${instance.metaText()} · ${InstanceFormat.byteSize(instance.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = relativeTimeText(instance.lastPlayedEpochMillis, nowMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
