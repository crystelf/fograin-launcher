@file:OptIn(ExperimentalTime::class)

package com.fograin.launcher.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.community.CommunityNote
import com.fograin.launcher.community.CommunityPoll
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_cw
import com.fograin.launcher.resources.community_delete_note_body
import com.fograin.launcher.resources.community_delete_note_title
import com.fograin.launcher.resources.community_hide_content
import com.fograin.launcher.resources.community_note_menu
import com.fograin.launcher.resources.community_poll_ended
import com.fograin.launcher.resources.community_poll_multiple
import com.fograin.launcher.resources.community_poll_vote
import com.fograin.launcher.resources.community_quote
import com.fograin.launcher.resources.community_reaction
import com.fograin.launcher.resources.community_remove_note
import com.fograin.launcher.resources.community_remove_note_admin
import com.fograin.launcher.resources.action_cancel
import com.fograin.launcher.resources.community_renote
import com.fograin.launcher.resources.community_renote_action
import com.fograin.launcher.resources.community_renoted
import com.fograin.launcher.resources.community_reply
import com.fograin.launcher.resources.community_reply_to
import com.fograin.launcher.resources.community_report
import com.fograin.launcher.resources.community_report_comment
import com.fograin.launcher.resources.community_report_note_body
import com.fograin.launcher.resources.community_report_note_title
import com.fograin.launcher.resources.community_report_send
import com.fograin.launcher.resources.community_share
import com.fograin.launcher.resources.community_show_content
import com.fograin.launcher.resources.community_votes
import com.fograin.launcher.ui.relativeTimeText
import coil3.compose.AsyncImage
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

fun communityNoteTag(id: String): String = "community-note-$id"

fun communityReplyTag(id: String): String = "community-reply-$id"

fun communityMoreTag(id: String): String = "community-more-$id"

fun communityReactionTag(id: String): String = "community-reaction-$id"

fun communityShareTag(id: String): String = "community-share-$id"

fun communityReportTag(id: String): String = "community-report-$id"

fun communityDeleteTag(id: String): String = "community-delete-$id"

@Composable
fun CommunityNoteCard(
    model: AppModel,
    state: AppUiState,
    note: CommunityNote,
    detail: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val now = remember { Clock.System.now().toEpochMilliseconds() }
    var revealed by remember(note.id, note.cw) { mutableStateOf(false) }
    val clickableModifier = if (detail) {
        Modifier
    } else {
        Modifier.clickable { model.community.openNote(note) }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(communityNoteTag(note.id))
            .then(clickableModifier),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NoteHeader(model = model, note = note, now = now)

            if (note.reply != null) {
                Text(
                    text = stringResource(
                        Res.string.community_reply_to,
                        note.reply.user.handle,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        model.community.openNote(note.reply)
                    },
                )
            }

            if (note.renote != null) {
                Text(
                    text = "${stringResource(Res.string.community_renoted)} ${note.renote.user.handle}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QuotedNote(model = model, note = note.renote)
            }

            val hiddenByWarning = note.cw != null && !revealed
            if (hiddenByWarning) {
                ContentWarning(
                    cw = note.cw.orEmpty(),
                    onReveal = { revealed = true },
                )
            } else {
                if (note.cw != null && revealed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${stringResource(Res.string.community_cw)}: ${note.cw}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { revealed = false }) {
                            Text(stringResource(Res.string.community_hide_content))
                        }
                    }
                }
                if (!note.text.isNullOrBlank()) {
                    CommunityRichText(
                        text = note.text,
                        maxLines = if (detail) Int.MAX_VALUE else 12,
                    )
                }
                if (note.files.isNotEmpty()) {
                    CommunityMediaGrid(
                        noteId = note.id,
                        files = note.files,
                        savingIds = state.community.savingMediaIds,
                        onSave = model.community::saveMedia,
                    )
                }
                note.poll?.let { poll ->
                    CommunityPollView(
                        note = note,
                        poll = poll,
                        state = state,
                        onVote = { choice -> model.community.votePoll(note, choice) },
                    )
                }
            }

            NoteActions(model = model, state = state, note = note)
        }
    }
}

@Composable
private fun NoteHeader(model: AppModel, note: CommunityNote, now: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CommunityAvatar(
            user = note.user,
            size = 40.dp,
            modifier = Modifier.clickable { model.community.openUserProfile(note.user) },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { model.community.openUserProfile(note.user) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = note.user.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.user.isBot) {
                    Text(
                        text = "BOT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val createdAtMillis = runCatching {
                Instant.parse(note.createdAt).toEpochMilliseconds()
            }.getOrNull()
            val timeText = if (createdAtMillis != null) {
                relativeTimeText(epochMillis = createdAtMillis, nowMillis = now)
            } else {
                ""
            }
            Text(
                text = if (timeText.isBlank()) {
                    note.user.handle
                } else {
                    "${note.user.handle} · $timeText"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ContentWarning(cw: String, onReveal: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReveal),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${stringResource(Res.string.community_cw)}: $cw",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.community_show_content),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun QuotedNote(model: AppModel, note: CommunityNote) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { model.community.openNote(note) },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "${note.user.displayName} ${note.user.handle}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!note.text.isNullOrBlank()) {
                CommunityRichText(
                    text = note.text,
                    maxLines = 4,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (note.files.isNotEmpty()) {
                Text(
                    text = note.files.first().name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommunityPollView(
    note: CommunityNote,
    poll: CommunityPoll,
    state: AppUiState,
    onVote: (Int) -> Unit,
) {
    val now = Clock.System.now().toEpochMilliseconds()
    val expiresAt = poll.expiresAt?.let { value ->
        runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
    }
    val expired = expiresAt != null && expiresAt <= now
    val voted = poll.choices.any { it.isVoted }
    val canVote = state.community.isLoggedIn &&
        !expired &&
        note.id !in state.community.votingPollNoteIds &&
        if (poll.multiple) true else !voted
    val total = poll.choices.sumOf { it.votes }.coerceAtLeast(1)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.community_poll_vote),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (poll.multiple) {
                    Text(
                        text = stringResource(Res.string.community_poll_multiple),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expired) {
                    Text(
                        text = stringResource(Res.string.community_poll_ended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            poll.choices.forEachIndexed { index, choice ->
                val fraction = choice.votes.toFloat() / total
                val choiceCanVote = canVote && (!poll.multiple || !choice.isVoted)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable(enabled = choiceCanVote) { onVote(index) },
                ) {
                    Box(modifier = Modifier.matchParentSize()) {
                        if (fraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    ),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = choice.text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (choice.isVoted) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(Res.string.community_votes, choice.votes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (note.id in state.community.votingPollNoteIds) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteActions(model: AppModel, state: AppUiState, note: CommunityNote) {
    var reactionPicker by remember(note.id) { mutableStateOf(false) }
    var moreMenu by remember(note.id) { mutableStateOf(false) }
    var renoteMenu by remember(note.id) { mutableStateOf(false) }
    var deleteConfirm by remember(note.id) { mutableStateOf(false) }
    var reportOpen by remember(note.id) { mutableStateOf(false) }
    val isLoggedIn = state.community.isLoggedIn
    val me = state.community.account?.user
    val canDelete = isLoggedIn && (note.user.id == me?.id || me?.canModerate == true)
    val isDeleting = note.id in state.community.deletingNoteIds

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (note.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                note.reactions.entries
                    .sortedByDescending { it.value }
                    .take(8)
                    .forEach { (reaction, count) ->
                        val selected = note.myReaction == reaction
                        Surface(
                            modifier = if (isLoggedIn) {
                                Modifier.clickable {
                                    model.community.toggleReaction(note, reaction)
                                }
                            } else {
                                Modifier
                            },
                            shape = RoundedCornerShape(50),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ) {
                            val customName = reaction.removeSurrounding(":")
                            val customUrl = note.reactionEmojis[customName]
                                ?: note.reactionEmojis[reaction]
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                if (customUrl != null) {
                                    AsyncImage(
                                        model = customUrl,
                                        contentDescription = customName,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.size(17.dp),
                                    )
                                } else {
                                    Text(
                                        text = reaction,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Text(
                                    text = count.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (isLoggedIn) {
                NoteAction(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    label = stringResource(Res.string.community_reply),
                    count = note.repliesCount,
                    onClick = { model.community.openComposer(note) },
                    modifier = Modifier.testTag(communityReplyTag(note.id)),
                )
                Box {
                    NoteAction(
                        icon = Icons.Outlined.Repeat,
                        label = stringResource(Res.string.community_renote_action),
                        count = note.renoteCount,
                        onClick = { renoteMenu = true },
                    )
                    DropdownMenu(
                        expanded = renoteMenu,
                        onDismissRequest = { renoteMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.community_renote)) },
                            onClick = {
                                renoteMenu = false
                                model.community.renote(note)
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Repeat, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.community_quote)) },
                            onClick = {
                                renoteMenu = false
                                model.community.quoteNote(note)
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Share, contentDescription = null)
                            },
                        )
                    }
                }
                Box {
                    NoteAction(
                        icon = Icons.Outlined.AddReaction,
                        label = stringResource(Res.string.community_reaction),
                        onClick = { reactionPicker = true },
                        modifier = Modifier.testTag(communityReactionTag(note.id)),
                    )
                    if (reactionPicker) {
                        CommunityReactionPicker(
                            note = note,
                            recent = state.community.recentReactions,
                            onSelect = { reaction ->
                                reactionPicker = false
                                model.community.toggleReaction(note, reaction)
                            },
                            onDismiss = { reactionPicker = false },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                NoteAction(
                    icon = Icons.Outlined.MoreHoriz,
                    label = stringResource(Res.string.community_note_menu),
                    onClick = { moreMenu = true },
                    modifier = Modifier.testTag(communityMoreTag(note.id)),
                )
                DropdownMenu(
                    expanded = moreMenu,
                    onDismissRequest = { moreMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.community_share)) },
                        onClick = {
                            moreMenu = false
                            model.community.shareNote(note)
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                        },
                        modifier = Modifier.testTag(communityShareTag(note.id)),
                    )
                    if (isLoggedIn && note.user.id != state.community.account?.user?.id) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.community_report)) },
                            onClick = {
                                moreMenu = false
                                reportOpen = true
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Flag, contentDescription = null)
                            },
                            modifier = Modifier.testTag(communityReportTag(note.id)),
                        )
                    }
                    if (canDelete) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (note.user.id == me.id) {
                                            Res.string.community_remove_note
                                        } else {
                                            Res.string.community_remove_note_admin
                                        },
                                    ),
                                )
                            },
                            onClick = {
                                moreMenu = false
                                deleteConfirm = true
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                            },
                            enabled = !isDeleting,
                            modifier = Modifier.testTag(communityDeleteTag(note.id)),
                        )
                    }
                }
            }
        }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text(stringResource(Res.string.community_delete_note_title)) },
            text = { Text(stringResource(Res.string.community_delete_note_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirm = false
                        model.community.deleteNote(note)
                    },
                ) {
                    Text(stringResource(Res.string.community_remove_note))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    if (reportOpen) {
        ReportNoteDialog(
            onDismiss = { reportOpen = false },
            onSend = { comment ->
                reportOpen = false
                model.community.reportNote(note, comment)
            },
        )
    }
}

@Composable
private fun NoteAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    count: Int = 0,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportNoteDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.community_report_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.community_report_note_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(2048) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    placeholder = { Text(stringResource(Res.string.community_report_comment)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(comment) },
                enabled = comment.isNotBlank(),
            ) {
                Text(stringResource(Res.string.community_report_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
