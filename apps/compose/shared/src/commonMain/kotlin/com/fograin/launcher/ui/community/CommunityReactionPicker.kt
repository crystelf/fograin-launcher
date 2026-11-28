package com.fograin.launcher.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import com.fograin.launcher.community.CommunityNote
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_emoji_activity
import com.fograin.launcher.resources.community_emoji_animals
import com.fograin.launcher.resources.community_emoji_category
import com.fograin.launcher.resources.community_emoji_custom
import com.fograin.launcher.resources.community_emoji_food
import com.fograin.launcher.resources.community_emoji_gestures
import com.fograin.launcher.resources.community_emoji_hearts
import com.fograin.launcher.resources.community_emoji_recent
import com.fograin.launcher.resources.community_emoji_smileys
import com.fograin.launcher.resources.community_emoji_symbols
import com.fograin.launcher.resources.community_reaction
import com.fograin.launcher.resources.community_reaction_picker_title
import org.jetbrains.compose.resources.stringResource

const val CommunityReactionPickerTag: String = "community-reaction-picker"

private val CommonReactions = listOf("👍", "❤️", "😂", "🎉", "👀", "🔥", "😮", "🙏")

private val SmileyReactions = listOf(
    "😀", "😄", "😁", "😊", "🙂", "😉", "😍", "🥰",
    "😘", "😜", "🤪", "🤔", "🤨", "😐", "😴", "😭",
    "😤", "😡", "🥺", "😳", "🤯", "😱", "🤗", "🤫",
)

private val GestureReactions = listOf(
    "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "👏",
    "🙌", "🤝", "🙏", "💪", "👀", "🫶", "👋", "🤙",
)

private val HeartReactions = listOf(
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
    "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘",
)

private val AnimalReactions = listOf(
    "🐱", "🐶", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁",
    "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🦄", "🐙",
)

private val FoodReactions = listOf(
    "🍎", "🍊", "🍋", "🍉", "🍇", "🍓", "🍒", "🍑",
    "🍕", "🍔", "🍟", "🍣", "🍜", "🍰", "🍩", "☕",
)

private val ActivityReactions = listOf(
    "🎉", "🎊", "✨", "🎁", "🏆", "⚽", "🎮", "🎵",
    "🎬", "📚", "💡", "🚀", "🌙", "☀️", "🌈", "⭐",
)

private val SymbolReactions = listOf(
    "✅", "❌", "⚠️", "❓", "❗", "💯", "🔥", "💫",
    "💥", "💤", "💬", "🔔", "🔒", "🔑", "📌", "♻️",
)

@Composable
fun CommunityReactionPicker(
    note: CommunityNote,
    recent: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val custom = note.reactionEmojis.entries
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .map { CustomReaction(name = it.key, url = it.value) }
    val categories = listOf(
        EmojiCategory(stringResource(Res.string.community_emoji_smileys), SmileyReactions),
        EmojiCategory(stringResource(Res.string.community_emoji_gestures), GestureReactions),
        EmojiCategory(stringResource(Res.string.community_emoji_hearts), HeartReactions),
        EmojiCategory(stringResource(Res.string.community_emoji_animals), AnimalReactions),
        EmojiCategory(stringResource(Res.string.community_emoji_food), FoodReactions),
        EmojiCategory(stringResource(Res.string.community_emoji_activity), ActivityReactions),
        EmojiCategory(stringResource(Res.string.community_emoji_symbols), SymbolReactions),
    )
    var selectedIndex by remember { mutableIntStateOf(0) }
    val recentList = (recent + CommonReactions).distinct().take(16)
    val selectedEmojis = categories[selectedIndex.coerceIn(categories.indices)].emojis
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        ReactionPopupPositionProvider(
            margin = with(density) { 8.dp.roundToPx() },
            gap = with(density) { 6.dp.roundToPx() },
        )
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnClickOutside = true,
        ),
    ) {
        BoxWithConstraints {
            val popupWidth = if (maxWidth > 396.dp) 380.dp else maxWidth - 16.dp
            Surface(
                modifier = Modifier
                    .width(popupWidth)
                    .heightIn(max = 540.dp)
                    .testTag(CommunityReactionPickerTag),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 12.dp,
            ) {
            Column(
                modifier = Modifier.padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.community_reaction_picker_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(Res.string.community_reaction),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }

                Text(
                    text = stringResource(Res.string.community_emoji_recent),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(recentList, key = { it }) { emoji ->
                        EmojiButton(
                            emoji = emoji,
                            selected = emoji == note.myReaction,
                            onClick = { onSelect(emoji) },
                        )
                    }
                }

                Text(
                    text = stringResource(Res.string.community_emoji_category),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(categories.size) { index ->
                        CategoryChip(
                            label = categories[index].label,
                            selected = selectedIndex == index,
                            onClick = { selectedIndex = index },
                        )
                    }
                    if (custom.isNotEmpty()) {
                        item {
                            CategoryChip(
                                label = stringResource(Res.string.community_emoji_custom),
                                selected = selectedIndex == categories.size,
                                onClick = { selectedIndex = categories.size },
                            )
                        }
                    }
                }

                if (selectedIndex < categories.size) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(42.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(248.dp)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(selectedEmojis, key = { it }) { emoji ->
                            EmojiButton(
                                emoji = emoji,
                                selected = emoji == note.myReaction,
                                onClick = { onSelect(emoji) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(52.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(248.dp)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(custom, key = { it.name }) { reaction ->
                            CustomEmojiButton(
                                reaction = reaction,
                                selected = note.myReaction == ":${reaction.name}:",
                                onClick = { onSelect(":${reaction.name}:") },
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

private class ReactionPopupPositionProvider(
    private val margin: Int,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - popupContentSize.height - gap
        val y = when {
            below <= maxY -> below
            above >= margin -> above
            else -> below.coerceIn(margin, maxY)
        }
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = anchorBounds.left.coerceIn(margin, maxX)
        return IntOffset(x, y)
    }
}

private data class EmojiCategory(val label: String, val emojis: List<String>)

private data class CustomReaction(val name: String, val url: String)

@Composable
private fun EmojiButton(
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = emoji, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CustomEmojiButton(
    reaction: CustomReaction,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = reaction.url,
                contentDescription = reaction.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(6.dp)
                    .size(36.dp),
            )
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
