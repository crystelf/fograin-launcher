package com.fograin.launcher.ui.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.community.CommunityUser
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_composer_mention_empty
import com.fograin.launcher.resources.community_composer_mention_loading
import com.fograin.launcher.resources.community_mention_body
import com.fograin.launcher.resources.community_mention_placeholder
import com.fograin.launcher.resources.community_mention_title
import org.jetbrains.compose.resources.stringResource

const val CommunityMentionDialogTag: String = "community-mention-dialog"
const val CommunityMentionFieldTag: String = "community-mention-field"

@Composable
fun CommunityMentionDialog(model: AppModel, state: AppUiState) {
    val composer = state.community.composer
    if (!composer.mentionPickerOpen) return
    Dialog(
        onDismissRequest = model.community::closeMentionPicker,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(12.dp)
                .heightIn(max = 560.dp)
                .testTag(CommunityMentionDialogTag),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.community_mention_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(Res.string.community_mention_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = model.community::closeMentionPicker) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
                OutlinedTextField(
                    value = composer.mentionPickerQuery,
                    onValueChange = model.community::setMentionPickerQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CommunityMentionFieldTag),
                    placeholder = { Text(stringResource(Res.string.community_mention_placeholder)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                )
                when {
                    composer.mentionPickerLoading -> Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(Res.string.community_composer_mention_loading),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    composer.mentionPickerQuery.isNotBlank() &&
                        composer.mentionPickerResults.isEmpty() -> Text(
                        text = stringResource(Res.string.community_composer_mention_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )

                    composer.mentionPickerResults.isNotEmpty() -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                    ) {
                        items(composer.mentionPickerResults, key = { it.id }) { user ->
                            MentionUserRow(
                                user = user,
                                onClick = { model.community.selectPickerMention(user) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MentionUserRow(user: CommunityUser, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CommunityAvatar(user = user, size = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = user.handle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "@",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
