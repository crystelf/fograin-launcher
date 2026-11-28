package com.fograin.launcher.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.community.CommunityConfig

private const val UrlTag = "community_url"

private val InlinePattern = Regex(
    """\*\*(.+?)\*\*|~~(.+?)~~|`([^`]+)`|\[([^\]]+)\]\((https?://[^)\s]+)\)|(https?://[^\s]+)|(@[A-Za-z0-9_.-]+(?:@[A-Za-z0-9.-]+)?)|(#[^\s#]+)|(?<!\*)\*([^*\n]+)\*(?!\*)""",
)

/** Renders note text with light Markdown, mentions and hashtags. */
@Suppress("DEPRECATION")
@Composable
fun CommunityRichText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface)
    val annotated = remember(text, linkColor) { communityInlineText(text, linkColor) }
    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = textStyle,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onClick = { offset ->
            annotated.getStringAnnotations(UrlTag, offset, offset)
                .firstOrNull()
                ?.let { runCatching { uriHandler.openUri(it.item) } }
        },
    )
}

private fun communityInlineText(text: String, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val match = InlinePattern.find(text, cursor)
            if (match == null) {
                append(text.substring(cursor))
                break
            }
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            val value = match.value
            when {
                match.groups[1] != null -> withStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold),
                ) { append(match.groups[1]!!.value) }

                match.groups[2] != null -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                ) { append(match.groups[2]!!.value) }

                match.groups[3] != null -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x22808080),
                    ),
                ) { append(match.groups[3]!!.value) }

                match.groups[4] != null -> {
                    val label = match.groups[4]!!.value
                    val url = match.groups[5]!!.value
                    pushStringAnnotation(UrlTag, url)
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) { append(label) }
                    pop()
                }

                match.groups[6] != null -> {
                    val url = match.groups[6]!!.value
                    pushStringAnnotation(UrlTag, url)
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) { append(url) }
                    pop()
                }

                match.groups[7] != null -> {
                    val mention = match.groups[7]!!.value
                    pushStringAnnotation(UrlTag, "${CommunityConfig.BASE_URL}/$mention")
                    withStyle(SpanStyle(color = linkColor)) { append(mention) }
                    pop()
                }

                match.groups[8] != null -> {
                    val tag = match.groups[8]!!.value
                    pushStringAnnotation(
                        UrlTag,
                        "${CommunityConfig.BASE_URL}/tags/${tag.removePrefix("#")}",
                    )
                    withStyle(SpanStyle(color = linkColor)) { append(tag) }
                    pop()
                }

                match.groups[9] != null -> withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic),
                ) { append(match.groups[9]!!.value) }

                else -> append(value)
            }
            cursor = match.range.last + 1
        }
    }

private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Bullets(val items: List<String>) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/** Block-level preview used in the composer's preview tab. */
@Composable
fun CommunityMarkdownPreview(
    text: String,
    modifier: Modifier = Modifier,
    emptyText: String = "",
) {
    val blocks = remember(text) { parseMarkdown(text) }
    if (blocks.isEmpty()) {
        Text(
            text = emptyText,
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = block.text,
                    style = if (block.level == 1) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.SemiBold,
                )

                is MarkdownBlock.Paragraph -> CommunityRichText(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                )

                is MarkdownBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    )
                    CommunityRichText(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MarkdownBlock.Bullets -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    block.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", color = MaterialTheme.colorScheme.primary)
                            CommunityRichText(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MarkdownBlock.Code -> Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                MarkdownBlock.Divider -> HorizontalDivider()
            }
        }
    }
}

private fun parseMarkdown(text: String): List<MarkdownBlock> {
    if (text.isBlank()) return emptyList()
    val lines = text.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
            paragraph.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val code = mutableListOf<String>()
                index += 1
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code += lines[index]
                    index += 1
                }
                blocks += MarkdownBlock.Code(code.joinToString("\n"))
            }

            trimmed.matches(Regex("^#{1,3}\\s+.*")) -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += MarkdownBlock.Heading(level, trimmed.drop(level).trim())
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(trimmed.removePrefix(">").trim())
            }

            trimmed.matches(Regex("^[-*+]\\s+.*")) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val bullet = lines[index].trim()
                    if (bullet.matches(Regex("^[-*+]\\s+.*"))) {
                        items += bullet.drop(2).trim()
                        index += 1
                    } else {
                        break
                    }
                }
                blocks += MarkdownBlock.Bullets(items)
                continue
            }

            trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val bullet = lines[index].trim()
                    if (bullet.matches(Regex("^\\d+\\.\\s+.*"))) {
                        items += bullet.substringAfter('.').trim()
                        index += 1
                    } else {
                        break
                    }
                }
                blocks += MarkdownBlock.Bullets(items)
                continue
            }

            trimmed.matches(Regex("^-{3,}$")) -> {
                flushParagraph()
                blocks += MarkdownBlock.Divider
            }

            trimmed.isEmpty() -> flushParagraph()
            else -> paragraph += trimmed
        }
        index += 1
    }
    flushParagraph()
    return blocks
}
