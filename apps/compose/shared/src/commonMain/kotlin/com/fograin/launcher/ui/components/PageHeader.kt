package com.fograin.launcher.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.search_action
import com.fograin.launcher.resources.search_clear
import com.fograin.launcher.resources.search_close
import org.jetbrains.compose.resources.stringResource

/**
 * The single page header used by every destination.
 *
 * One row: the page title on the left, the search box and page actions on the
 * right, all vertically centred. The old `LargeTopAppBar` stacked the title
 * under the action icons and reserved a second line for a subtitle; this
 * compact header gives the content roughly 56–72 dp more room on a phone.
 *
 * The search field is persistent on wide windows and collapses to an icon on
 * compact ones. When it expands on compact, the title fades out and the field
 * slides into the same row, so the header height never changes.
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    searchPlaceholder: String? = null,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    searchExpanded: Boolean = false,
    onSearchExpandedChange: (Boolean) -> Unit = {},
    focusRequest: Long = 0,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        val compact = maxWidth < 640.dp
        val wideSearchWidth = if (maxWidth >= 980.dp) 300.dp else 236.dp
        val searchMode = compact && searchExpanded && searchPlaceholder != null

        // Compact search expands inside the title slot; wide windows keep the
        // field visible and only need the shortcut focus request.
        LaunchedEffect(searchMode) {
            if (searchMode) focusRequester.requestFocus()
        }
        LaunchedEffect(focusRequest) {
            if (focusRequest > 0 && searchPlaceholder != null) focusRequester.requestFocus()
        }

        AnimatedContent(
            targetState = searchMode,
            transitionSpec = {
                (fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 8 }) togetherWith
                    (fadeOut(tween(90)) + slideOutHorizontally(tween(160)) { -it / 8 })
            },
            label = "page-header-mode",
        ) { searching ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (searching) {
                    PageSearchField(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        placeholder = searchPlaceholder.orEmpty(),
                        focusRequester = focusRequester,
                        modifier = Modifier.weight(1f),
                    )
                    HeaderIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.search_close),
                        onClick = {
                            onSearchExpandedChange(false)
                            focusManager.clearFocus(force = true)
                        },
                    )
                } else {
                    Text(
                        text = title,
                        style = if (compact) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.headlineMedium
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    if (searchPlaceholder != null) {
                        if (compact) {
                            HeaderIconButton(
                                icon = Icons.Outlined.Search,
                                contentDescription = stringResource(Res.string.search_action),
                                onClick = { onSearchExpandedChange(true) },
                            )
                        } else {
                            PageSearchField(
                                query = searchQuery,
                                onQueryChange = onSearchQueryChange,
                                placeholder = searchPlaceholder,
                                focusRequester = focusRequester,
                                modifier = Modifier.width(wideSearchWidth),
                            )
                        }
                    }
                    actions()
                }
            }
        }
    }
}

/** Icon button with the shared 40 dp header touch target. */
@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 40 dp pill search field. Uses `BasicTextField` instead of
 * `OutlinedTextField` so the header keeps a single 64 dp row.
 */
@Composable
fun PageSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val container by animateColorAsState(
        targetValue = if (focused) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(160),
        label = "search-container",
    )
    val border by animateColorAsState(
        targetValue = if (focused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(160),
        label = "search-border",
    )
    val clearAlpha by animateFloatAsState(
        targetValue = if (query.isNotEmpty()) 1f else 0f,
        animationSpec = tween(120),
        label = "search-clear-alpha",
    )

    Surface(
        modifier = modifier.height(40.dp),
        shape = CircleShape,
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            focusManager.clearFocus(force = true)
                            true
                        } else {
                            false
                        }
                    },
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .then(
                            if (focusRequester != null) {
                                Modifier.focusRequester(focusRequester)
                            } else {
                                Modifier
                            },
                        ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { focusManager.clearFocus(force = true) },
                    ),
                )
            }
            Box(
                modifier = Modifier.width(34.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = { onQueryChange("") },
                    enabled = query.isNotEmpty(),
                    modifier = Modifier
                        .size(34.dp)
                        .alpha(clearAlpha),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.search_clear),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
