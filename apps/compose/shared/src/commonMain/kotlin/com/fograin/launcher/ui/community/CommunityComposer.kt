package com.fograin.launcher.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.CommunityComposerUiState
import com.fograin.launcher.community.CommunityConfig
import com.fograin.launcher.community.CommunityFailure
import com.fograin.launcher.community.CommunityNote
import com.fograin.launcher.community.CommunityReactionAcceptance
import com.fograin.launcher.community.CommunityUser
import com.fograin.launcher.community.CommunityVisibility
import com.fograin.launcher.community.LocalAttachment
import com.fograin.launcher.community.formatCommunityTimestamp
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_composer_add_media
import com.fograin.launcher.resources.community_composer_add_option
import com.fograin.launcher.resources.community_composer_attachment_limit
import com.fograin.launcher.resources.community_composer_count
import com.fograin.launcher.resources.community_composer_cw
import com.fograin.launcher.resources.community_composer_cw_placeholder
import com.fograin.launcher.resources.community_composer_drive_full
import com.fograin.launcher.resources.community_composer_edit
import com.fograin.launcher.resources.community_composer_empty
import com.fograin.launcher.resources.community_composer_file_too_large
import com.fograin.launcher.resources.community_composer_file_type
import com.fograin.launcher.resources.community_composer_file_unavailable
import com.fograin.launcher.resources.community_composer_hashtags
import com.fograin.launcher.resources.community_composer_hashtags_hint
import com.fograin.launcher.resources.community_composer_inappropriate
import com.fograin.launcher.resources.community_composer_mention
import com.fograin.launcher.resources.community_composer_mention_empty
import com.fograin.launcher.resources.community_composer_mention_loading
import com.fograin.launcher.resources.community_composer_multiple
import com.fograin.launcher.resources.community_composer_placeholder
import com.fograin.launcher.resources.community_composer_poll
import com.fograin.launcher.resources.community_composer_poll_choice
import com.fograin.launcher.resources.community_composer_poll_expiry
import com.fograin.launcher.resources.community_composer_poll_invalid
import com.fograin.launcher.resources.community_composer_poll_choice_too_long
import com.fograin.launcher.resources.community_composer_preview
import com.fograin.launcher.resources.community_composer_preview_empty
import com.fograin.launcher.resources.community_composer_publish
import com.fograin.launcher.resources.community_composer_publishing
import com.fograin.launcher.resources.community_composer_quote_title
import com.fograin.launcher.resources.community_composer_reaction_acceptance
import com.fograin.launcher.resources.community_composer_remove_attachment
import com.fograin.launcher.resources.community_composer_reply_title
import com.fograin.launcher.resources.community_composer_reply_to
import com.fograin.launcher.resources.community_composer_schedule
import com.fograin.launcher.resources.community_composer_scheduled_for
import com.fograin.launcher.resources.community_composer_sensitive
import com.fograin.launcher.resources.community_composer_share_hint
import com.fograin.launcher.resources.community_composer_title
import com.fograin.launcher.resources.community_composer_too_long
import com.fograin.launcher.resources.community_composer_uploading
import com.fograin.launcher.resources.community_composer_visibility
import com.fograin.launcher.resources.community_login_close
import com.fograin.launcher.resources.community_reaction_acceptance_all
import com.fograin.launcher.resources.community_reaction_acceptance_like_only
import com.fograin.launcher.resources.community_reaction_acceptance_like_only_remote
import com.fograin.launcher.resources.community_reaction_acceptance_non_sensitive
import com.fograin.launcher.resources.community_reaction_acceptance_non_sensitive_remote_like
import com.fograin.launcher.resources.community_schedule_cancel
import com.fograin.launcher.resources.community_schedule_past
import com.fograin.launcher.resources.community_visibility_followers
import com.fograin.launcher.resources.community_visibility_home
import com.fograin.launcher.resources.community_visibility_public
import com.fograin.launcher.resources.poll_expiry_1d
import com.fograin.launcher.resources.poll_expiry_1h
import com.fograin.launcher.resources.poll_expiry_3d
import com.fograin.launcher.resources.poll_expiry_6h
import com.fograin.launcher.resources.poll_expiry_7d
import com.fograin.launcher.resources.poll_expiry_never
import com.fograin.launcher.ui.components.DropdownField
import com.fograin.launcher.ui.components.SegmentedTabRail
import org.jetbrains.compose.resources.stringResource

const val CommunityComposerTag: String = "community-composer"
const val CommunityComposerFieldTag: String = "community-composer-field"
const val CommunityComposerPublishTag: String = "community-composer-publish"
const val CommunityComposerAddMediaTag: String = "community-composer-add-media"
const val CommunityComposerPreviewTag: String = "community-composer-preview"
const val CommunityComposerPollTag: String = "community-composer-poll"
const val CommunityComposerPollEditorTag: String = "community-composer-poll-editor"
const val CommunityComposerHashtagTag: String = "community-composer-hashtag"
const val CommunityComposerHashtagFieldTag: String = "community-composer-hashtag-field"
const val CommunityComposerScheduleTag: String = "community-composer-schedule"
const val CommunityComposerScheduleButtonTag: String = "community-composer-schedule-button"
const val CommunityComposerMentionTag: String = "community-composer-mention"
const val CommunityComposerCwTag: String = "community-composer-cw"
const val CommunityComposerCwFieldTag: String = "community-composer-cw-field"

@Composable
fun CommunityComposerSheet(model: AppModel, state: AppUiState) {
    val composer = state.community.composer
    if (!composer.open) return
    val maxLength = state.community.meta?.maxNoteTextLength
        ?: CommunityConfig.MAX_NOTE_LENGTH_FALLBACK
    val addMediaLabel = stringResource(Res.string.community_composer_add_media)
    val scheduleAvailable =
        (state.community.meta?.policies?.scheduledNoteLimit ?: 0) > 0
    var scheduleDialogOpen by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {
            if (!composer.isPosting) model.community.closeComposer()
        },
        properties = DialogProperties(
            dismissOnBackPress = !composer.isPosting,
            dismissOnClickOutside = !composer.isPosting,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .heightIn(max = 840.dp)
                    .testTag(CommunityComposerTag),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ComposerHeader(model = model, composer = composer)
                    SegmentedTabRail(
                        options = listOf(false, true),
                        selected = composer.preview,
                        onSelect = { preview ->
                            if (preview != composer.preview) model.community.togglePreview()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        label = { preview ->
                            stringResource(
                                if (preview) {
                                    Res.string.community_composer_preview
                                } else {
                                    Res.string.community_composer_edit
                                },
                            )
                        },
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        composer.replyTo?.let { reply ->
                            ComposerTargetCard(
                                label = stringResource(Res.string.community_composer_reply_to, reply.user.handle),
                                note = reply,
                            )
                        }
                        composer.quotedNote?.let { quoted ->
                            ComposerTargetCard(
                                label = stringResource(Res.string.community_composer_quote_title),
                                note = quoted,
                            )
                        }

                        if (composer.preview) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 220.dp)
                                    .testTag(CommunityComposerPreviewTag),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                CommunityMarkdownPreview(
                                    text = composer.publishText,
                                    modifier = Modifier.padding(14.dp),
                                    emptyText = stringResource(Res.string.community_composer_preview_empty),
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = TextFieldValue(
                                    text = composer.text,
                                    selection = androidx.compose.ui.text.TextRange(
                                        composer.selection.coerceIn(0, composer.text.length),
                                    ),
                                ),
                                onValueChange = { value ->
                                    if (!composer.isPosting) {
                                        model.community.setComposerText(
                                            text = value.text,
                                            selection = value.selection.start,
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 170.dp, max = 300.dp)
                                    .testTag(CommunityComposerFieldTag),
                                placeholder = {
                                    Text(stringResource(Res.string.community_composer_placeholder))
                                },
                                minLines = 5,
                                maxLines = 10,
                                enabled = !composer.isPosting,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                keyboardActions = KeyboardActions(),
                            )
                            if (composer.mentionQuery != null) {
                                MentionSuggestions(model = model, composer = composer)
                            }
                        }

                        if (composer.hashtagsEnabled) {
                            OutlinedTextField(
                                value = composer.hashtags,
                                onValueChange = model.community::setHashtags,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(CommunityComposerHashtagFieldTag),
                                label = { Text(stringResource(Res.string.community_composer_hashtags)) },
                                supportingText = {
                                    Text(stringResource(Res.string.community_composer_hashtags_hint))
                                },
                                singleLine = true,
                                enabled = !composer.isPosting,
                                shape = RoundedCornerShape(14.dp),
                            )
                        }

                        if (composer.cwEnabled) {
                            OutlinedTextField(
                                value = composer.cw,
                                onValueChange = model.community::setCw,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(CommunityComposerCwFieldTag),
                                singleLine = true,
                                label = {
                                    Text(stringResource(Res.string.community_composer_cw_placeholder))
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.VisibilityOff, contentDescription = null)
                                },
                                enabled = !composer.isPosting,
                                shape = RoundedCornerShape(14.dp),
                            )
                        }

                        if (composer.pollEnabled) {
                            PollEditor(model = model, composer = composer)
                        }

                        if (composer.attachments.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(
                                    items = composer.attachments,
                                    key = { _, attachment -> attachment.path },
                                ) { index, attachment ->
                                    AttachmentChip(
                                        attachment = attachment,
                                        enabled = !composer.isPosting,
                                        onRemove = { model.community.removeAttachment(index) },
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(Res.string.community_composer_sensitive),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = composer.sensitiveAttachments,
                                    onCheckedChange = model.community::setSensitiveAttachments,
                                    enabled = !composer.isPosting,
                                )
                            }
                        }

                        composer.scheduledAtEpochMillis?.let { scheduledAt ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(CommunityComposerScheduleTag),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.CalendarMonth,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = stringResource(
                                            Res.string.community_composer_scheduled_for,
                                            formatCommunityTimestamp(scheduledAt),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = model.community::clearSchedule) {
                                        Text(stringResource(Res.string.community_schedule_cancel))
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = stringResource(Res.string.community_composer_visibility),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                DropdownField(
                                    value = communityVisibilityLabel(composer.visibility),
                                    options = CommunityVisibility.entries.map { visibility ->
                                        communityVisibilityLabel(visibility) to {
                                            model.community.setVisibility(visibility)
                                        }
                                    },
                                    enabled = !composer.isPosting,
                                    fillWidth = true,
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = stringResource(Res.string.community_composer_reaction_acceptance),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                DropdownField(
                                    value = reactionAcceptanceLabel(composer.reactionAcceptance),
                                    options = CommunityReactionAcceptance.entries.map { value ->
                                        reactionAcceptanceLabel(value) to {
                                            model.community.setReactionAcceptance(value)
                                        }
                                    },
                                    enabled = !composer.isPosting,
                                    fillWidth = true,
                                )
                            }
                        }

                        composer.error?.let { failure ->
                            ComposerError(failure = failure)
                        }
                    }
                    ComposerFooter(
                        model = model,
                        composer = composer,
                        maxLength = maxLength,
                        addMediaLabel = addMediaLabel,
                        scheduleAvailable = scheduleAvailable,
                        onSchedule = { scheduleDialogOpen = true },
                    )
                }
            }
        }
    }

    // Secondary dialogs are declared after the composer so they stack above it
    // on platforms where dialog z-order follows composition order.
    if (scheduleDialogOpen) {
        CommunityScheduleDialog(
            initialEpochMillis = composer.scheduledAtEpochMillis,
            onDismiss = { scheduleDialogOpen = false },
            onConfirm = {
                model.community.setScheduledAt(it)
                scheduleDialogOpen = false
            },
        )
    }
    CommunityMentionDialog(model = model, state = state)
}

@Composable
private fun ComposerHeader(model: AppModel, composer: CommunityComposerUiState) {
    val title = when {
        composer.replyTo != null -> stringResource(Res.string.community_composer_reply_title)
        composer.quotedNote != null -> stringResource(Res.string.community_composer_quote_title)
        else -> stringResource(Res.string.community_composer_title)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(Res.string.community_composer_share_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { if (!composer.isPosting) model.community.closeComposer() },
            enabled = !composer.isPosting,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(Res.string.community_login_close),
            )
        }
    }
}

@Composable
private fun ComposerFooter(
    model: AppModel,
    composer: CommunityComposerUiState,
    maxLength: Int,
    addMediaLabel: String,
    scheduleAvailable: Boolean,
    onSchedule: () -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (composer.isPosting) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = stringResource(
                Res.string.community_composer_count,
                composer.publishText.length,
                maxLength,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (composer.publishText.length > maxLength) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ComposerTool(
                    icon = Icons.Outlined.AddPhotoAlternate,
                    description = addMediaLabel,
                    enabled = !composer.isPosting &&
                        composer.attachments.size < CommunityConfig.MAX_ATTACHMENTS,
                    onClick = { model.community.pickAttachment(addMediaLabel) },
                    tag = CommunityComposerAddMediaTag,
                )
                ComposerTool(
                    icon = Icons.Outlined.Poll,
                    description = stringResource(Res.string.community_composer_poll),
                    active = composer.pollEnabled,
                    enabled = !composer.isPosting,
                    onClick = model.community::togglePoll,
                    tag = CommunityComposerPollTag,
                )
                ComposerTool(
                    icon = Icons.Outlined.VisibilityOff,
                    description = stringResource(Res.string.community_composer_cw),
                    active = composer.cwEnabled,
                    enabled = !composer.isPosting,
                    onClick = { model.community.setCwEnabled(!composer.cwEnabled) },
                    tag = CommunityComposerCwTag,
                )
                ComposerTool(
                    icon = Icons.Outlined.Tag,
                    description = stringResource(Res.string.community_composer_hashtags),
                    active = composer.hashtagsEnabled,
                    enabled = !composer.isPosting,
                    onClick = model.community::toggleHashtags,
                    tag = CommunityComposerHashtagTag,
                )
                ComposerTool(
                    icon = Icons.Outlined.AlternateEmail,
                    description = stringResource(Res.string.community_composer_mention),
                    enabled = !composer.isPosting,
                    onClick = model.community::openMentionPicker,
                    tag = CommunityComposerMentionTag,
                )
                if (scheduleAvailable) {
                    ComposerTool(
                        icon = Icons.Outlined.CalendarMonth,
                        description = stringResource(Res.string.community_composer_schedule),
                        active = composer.scheduledAtEpochMillis != null,
                        enabled = !composer.isPosting,
                        onClick = onSchedule,
                        tag = CommunityComposerScheduleButtonTag,
                    )
                }
            }
            Button(
                onClick = model.community::publish,
                enabled = composer.hasPayload &&
                    !composer.isPosting &&
                    composer.publishText.length <= maxLength &&
                    (!composer.pollEnabled || composer.pollChoicesValid),
                modifier = Modifier.testTag(CommunityComposerPublishTag),
                shape = RoundedCornerShape(50),
            ) {
                if (composer.isPosting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = composer.uploadProgress?.let { progress ->
                            stringResource(Res.string.community_composer_uploading, progress)
                        } ?: stringResource(Res.string.community_composer_publishing),
                    )
                } else {
                    Text(
                        text = if (composer.scheduledAtEpochMillis != null) {
                            stringResource(Res.string.community_composer_schedule)
                        } else {
                            stringResource(Res.string.community_composer_publish)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String? = null,
    active: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
            )
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ComposerTargetCard(label: String, note: CommunityNote) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = note.user.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            note.text?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PollEditor(model: AppModel, composer: CommunityComposerUiState) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CommunityComposerPollEditorTag),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Poll,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(Res.string.community_composer_poll),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = model.community::togglePoll) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            composer.pollChoices.forEachIndexed { index, choice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = choice,
                        onValueChange = { model.community.setPollChoice(index, it) },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                stringResource(
                                    Res.string.community_composer_poll_choice,
                                    index + 1,
                                ),
                            )
                        },
                        singleLine = true,
                        enabled = !composer.isPosting,
                        shape = RoundedCornerShape(12.dp),
                    )
                    IconButton(
                        onClick = { model.community.removePollChoice(index) },
                        enabled = !composer.isPosting && composer.pollChoices.size > 2,
                    ) {
                        Icon(
                            Icons.Outlined.RemoveCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (composer.pollChoices.size < 10) {
                TextButton(
                    onClick = model.community::addPollChoice,
                    enabled = !composer.isPosting,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(Res.string.community_composer_add_option))
                }
            }
            if (composer.pollChoices.any { it.isNotBlank() } && !composer.pollChoicesValid) {
                Text(
                    text = stringResource(Res.string.community_composer_poll_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.community_composer_multiple),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = composer.pollMultiple,
                    onCheckedChange = model.community::setPollMultiple,
                    enabled = !composer.isPosting,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.community_composer_poll_expiry),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                DropdownField(
                    value = pollExpiryLabel(composer.pollExpiresInHours),
                    options = listOf(
                        pollExpiryLabel(null) to { model.community.setPollExpiresInHours(null) },
                        pollExpiryLabel(1) to { model.community.setPollExpiresInHours(1) },
                        pollExpiryLabel(6) to { model.community.setPollExpiresInHours(6) },
                        pollExpiryLabel(24) to { model.community.setPollExpiresInHours(24) },
                        pollExpiryLabel(72) to { model.community.setPollExpiresInHours(72) },
                        pollExpiryLabel(168) to { model.community.setPollExpiresInHours(168) },
                    ),
                    enabled = !composer.isPosting,
                )
            }
        }
    }
}

@Composable
private fun MentionSuggestions(model: AppModel, composer: CommunityComposerUiState) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            when {
                composer.mentionsLoading -> Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Res.string.community_composer_mention_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                composer.mentionSuggestions.isEmpty() -> Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(Res.string.community_composer_mention_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                else -> composer.mentionSuggestions.forEach { user ->
                    MentionRow(
                        user = user,
                        onClick = { model.community.selectMention(user) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MentionRow(user: CommunityUser, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CommunityAvatar(user = user, size = 28.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = user.handle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: LocalAttachment,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(96.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box {
            if (attachment.isVideo) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = attachment.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = localMediaUri(attachment.path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                    contentColor = MaterialTheme.colorScheme.surface,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.community_composer_remove_attachment),
                        modifier = Modifier
                            .padding(3.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerError(failure: CommunityFailure) {
    val message = when (failure.message) {
        "empty-post" -> stringResource(Res.string.community_composer_empty)
        "too-long" -> stringResource(Res.string.community_composer_too_long)
        "attachment-limit" -> stringResource(
            Res.string.community_composer_attachment_limit,
            CommunityConfig.MAX_ATTACHMENTS,
        )
        "file-picker-unavailable" -> stringResource(Res.string.community_composer_file_unavailable)
        "poll-invalid" -> stringResource(Res.string.community_composer_poll_invalid)
        "poll-choice-too-long" -> stringResource(Res.string.community_composer_poll_choice_too_long)
        "schedule-past" -> stringResource(Res.string.community_schedule_past)
        "MAX_FILE_SIZE_EXCEEDED" -> stringResource(Res.string.community_composer_file_too_large)
        "UNALLOWED_FILE_TYPE" -> stringResource(Res.string.community_composer_file_type)
        "NO_FREE_SPACE" -> stringResource(Res.string.community_composer_drive_full)
        "INAPPROPRIATE" -> stringResource(Res.string.community_composer_inappropriate)
        else -> communityFailureText(failure)
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
fun communityVisibilityLabel(visibility: CommunityVisibility): String = stringResource(
    when (visibility) {
        CommunityVisibility.PUBLIC -> Res.string.community_visibility_public
        CommunityVisibility.HOME -> Res.string.community_visibility_home
        CommunityVisibility.FOLLOWERS -> Res.string.community_visibility_followers
    },
)

@Composable
fun reactionAcceptanceLabel(value: CommunityReactionAcceptance): String = stringResource(
    when (value) {
        CommunityReactionAcceptance.ALL -> Res.string.community_reaction_acceptance_all
        CommunityReactionAcceptance.LIKE_ONLY -> Res.string.community_reaction_acceptance_like_only
        CommunityReactionAcceptance.LIKE_ONLY_FOR_REMOTE ->
            Res.string.community_reaction_acceptance_like_only_remote
        CommunityReactionAcceptance.NON_SENSITIVE_ONLY ->
            Res.string.community_reaction_acceptance_non_sensitive
        CommunityReactionAcceptance.NON_SENSITIVE_ONLY_FOR_LOCAL_LIKE_ONLY_FOR_REMOTE ->
            Res.string.community_reaction_acceptance_non_sensitive_remote_like
    },
)

@Composable
private fun pollExpiryLabel(hours: Int?): String = stringResource(
    when (hours) {
        null -> Res.string.poll_expiry_never
        1 -> Res.string.poll_expiry_1h
        6 -> Res.string.poll_expiry_6h
        24 -> Res.string.poll_expiry_1d
        72 -> Res.string.poll_expiry_3d
        168 -> Res.string.poll_expiry_7d
        else -> Res.string.poll_expiry_1d
    },
)
