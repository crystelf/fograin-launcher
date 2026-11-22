package com.fograin.launcher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.platform.ReleaseInfo
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.app_icon
import com.fograin.launcher.resources.update_available_body
import com.fograin.launcher.resources.update_available_title
import com.fograin.launcher.resources.update_later
import com.fograin.launcher.resources.update_open
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

const val UpdatePromptTag: String = "update-prompt"
const val UpdatePromptDismissTag: String = "update-prompt-dismiss"

/**
 * Bottom-right release notice shown after the startup check finds a newer tag.
 *
 * The whole card opens the release page; the close affordance only hides the
 * card for this session, and Settings → About keeps the update details.
 */
@Composable
fun UpdatePrompt(
    update: ReleaseInfo?,
    currentVersion: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the last release while the exit animation runs; the model clears
    // `update` as soon as the user dismisses the card.
    var lastRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    LaunchedEffect(update) {
        if (update != null) lastRelease = update
    }
    val release = update ?: lastRelease

    AnimatedVisibility(
        visible = update != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        if (release != null) {
            Surface(
                onClick = onOpen,
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .testTag(UpdatePromptTag),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 3.dp,
                shadowElevation = 12.dp,
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = 14.dp,
                        top = 14.dp,
                        bottom = 14.dp,
                        end = 6.dp,
                    ),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        painter = painterResource(Res.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                Res.string.update_available_title,
                                release.version,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = stringResource(
                                Res.string.update_available_body,
                                currentVersion,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(Res.string.update_open),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag(UpdatePromptDismissTag),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(Res.string.update_later),
                            modifier = Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}