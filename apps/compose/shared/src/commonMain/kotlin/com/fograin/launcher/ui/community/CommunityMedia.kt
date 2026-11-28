package com.fograin.launcher.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.fograin.launcher.community.CommunityFile
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_attachments
import com.fograin.launcher.resources.community_image
import com.fograin.launcher.resources.community_next_media
import com.fograin.launcher.resources.community_previous_media
import com.fograin.launcher.resources.community_sensitive
import com.fograin.launcher.resources.community_show_media
import com.fograin.launcher.resources.community_video
import org.jetbrains.compose.resources.stringResource

const val CommunityMediaViewerTag: String = "community-media-viewer"
const val CommunityMediaSaveTag: String = "community-media-save"

fun communityMediaTag(id: String): String = "community-media-$id"

/**
 * Feed media.
 *
 * Images open the full-screen viewer immediately. Videos play in the note
 * itself after the play affordance is tapped; nothing navigates or opens a
 * dialog. The player replaces its own tile, so the card layout does not grow.
 * A single tap opens the window-level fullscreen player and a double tap
 * toggles playback. The video surface is a Compose Media Player surface on
 * every platform, so the same floating chrome is composed above it.
 */
@Composable
fun CommunityMediaGrid(
    noteId: String,
    files: List<CommunityFile>,
    savingIds: Set<String>,
    onSave: (CommunityFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    val revealed = remember(files) { mutableStateOf(emptySet<String>()) }
    var playingVideoId by remember(files) { mutableStateOf<String?>(null) }
    var imageViewerId by remember(files) { mutableStateOf<String?>(null) }
    var expanded by remember(files) { mutableStateOf(false) }
    val shown = files.take(4)
    val extraFiles = files.drop(4)
    val extra = extraFiles.size
    val imageFiles = files.filterNot { it.isVideo }

    fun openFile(file: CommunityFile) {
        if (file.isVideo) {
            imageViewerId = null
            playingVideoId = file.id
        } else {
            playingVideoId = null
            imageViewerId = file.id
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when (shown.size) {
            1 -> MediaSlot(
                noteId = noteId,
                file = shown[0],
                revealed = shown[0].id in revealed.value,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                playingVideoId = playingVideoId,
                onReveal = { revealed.value = revealed.value + shown[0].id },
                onOpen = { openFile(shown[0]) },
                onVideoClose = { playingVideoId = null },
            )

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.take(2).forEachIndexed { index, file ->
                        MediaSlot(
                            noteId = noteId,
                            file = file,
                            revealed = file.id in revealed.value,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                            playingVideoId = playingVideoId,
                            badge = if (extra > 0 && index == 1) "+$extra" else null,
                            onReveal = { revealed.value = revealed.value + file.id },
                            onOpen = { openFile(file) },
                            onVideoClose = { playingVideoId = null },
                        )
                    }
                }
                if (shown.size > 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        shown.drop(2).forEachIndexed { index, file ->
                            MediaSlot(
                                noteId = noteId,
                                file = file,
                                revealed = file.id in revealed.value,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(if (shown.size == 3) 2f else 1f),
                                playingVideoId = playingVideoId,
                                onReveal = { revealed.value = revealed.value + file.id },
                                onOpen = { openFile(file) },
                                onVideoClose = { playingVideoId = null },
                            )
                        }
                    }
                }
            }
        }

        if (extra > 0 && !expanded) {
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(Res.string.community_attachments, extraFiles.size))
            }
        }
        if (expanded) {
            extraFiles.chunked(2).forEach { rowFiles ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    rowFiles.forEach { file ->
                        MediaSlot(
                            noteId = noteId,
                            file = file,
                            revealed = file.id in revealed.value,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                            playingVideoId = playingVideoId,
                            onReveal = { revealed.value = revealed.value + file.id },
                            onOpen = { openFile(file) },
                            onVideoClose = { playingVideoId = null },
                        )
                    }
                    if (rowFiles.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

    }

    imageViewerId?.let { id ->
        val index = imageFiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            CommunityMediaViewer(
                files = imageFiles,
                initialIndex = index,
                savingIds = savingIds,
                onSave = onSave,
                onDismiss = { imageViewerId = null },
            )
        }
    }
}

@Composable
private fun MediaSlot(
    noteId: String,
    file: CommunityFile,
    revealed: Boolean,
    modifier: Modifier,
    playingVideoId: String?,
    badge: String? = null,
    onReveal: () -> Unit,
    onOpen: () -> Unit,
    onVideoClose: () -> Unit,
) {
    if (file.isVideo && playingVideoId == file.id) {
        InlineVideoPlayer(
            playbackKey = "$noteId:${file.id}",
            file = file,
            onClose = onVideoClose,
            modifier = modifier,
        )
    } else {
        MediaTile(
            file = file,
            revealed = revealed,
            modifier = modifier,
            badge = badge,
            onReveal = onReveal,
            onOpen = onOpen,
        )
    }
}

@Composable
private fun MediaTile(
    file: CommunityFile,
    revealed: Boolean,
    modifier: Modifier,
    badge: String? = null,
    onReveal: () -> Unit,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .testTag(communityMediaTag(file.id))
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ),
            )
            .clickable {
                if (file.isSensitive && !revealed) onReveal() else onOpen()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (file.isSensitive && !revealed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.community_sensitive),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(Res.string.community_show_media),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            val url = (file.thumbnailUrl ?: file.url).takeIf { it.startsWith("https://") }
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (file.isVideo) Icons.Outlined.Movie else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (file.isVideo) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.10f),
                                1f to Color.Black.copy(alpha = 0.38f),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(Res.string.community_video),
                        tint = Color.White,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }

            badge?.let {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = Color.Black.copy(alpha = 0.62f),
                    contentColor = Color.White,
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun CommunityMediaViewer(
    files: List<CommunityFile>,
    initialIndex: Int,
    savingIds: Set<String>,
    onSave: (CommunityFile) -> Unit,
    onDismiss: () -> Unit,
) {
    if (files.isEmpty()) return
    var index by remember(initialIndex) {
        mutableStateOf(initialIndex.coerceIn(files.indices))
    }
    val file = files[index]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag(CommunityMediaViewerTag),
        ) {
            CommunityZoomableImage(
                url = file.url,
                contentDescription = stringResource(Res.string.community_image),
                modifier = Modifier.fillMaxSize(),
                resetKey = file.id,
                onDismiss = onDismiss,
            )
            MediaViewerTopBar(
                title = "${index + 1}/${files.size} · ${file.name}",
                isSaving = file.id in savingIds,
                onSave = { onSave(file) },
                onClose = onDismiss,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            if (files.size > 1) {
                ViewerArrow(
                    previous = true,
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = { index = (index - 1 + files.size) % files.size },
                )
                ViewerArrow(
                    previous = false,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onClick = { index = (index + 1) % files.size },
                )
            }
        }
    }
}

@Composable
private fun ViewerArrow(
    previous: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.padding(10.dp),
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.42f),
        contentColor = Color.White,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = if (previous) {
                    Icons.AutoMirrored.Outlined.ArrowBack
                } else {
                    Icons.AutoMirrored.Outlined.ArrowForward
                },
                contentDescription = stringResource(
                    if (previous) {
                        Res.string.community_previous_media
                    } else {
                        Res.string.community_next_media
                    },
                ),
            )
        }
    }
}
