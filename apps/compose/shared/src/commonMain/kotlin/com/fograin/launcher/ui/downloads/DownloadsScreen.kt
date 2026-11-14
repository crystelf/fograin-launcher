package com.fograin.launcher.ui.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.DownloadRow
import com.fograin.launcher.app.DownloadUiState
import com.fograin.launcher.model.ContentLoaderFilter
import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.MilestoneChip
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.components.ThinProgressBar
import com.fograin.launcher.ui.downloadSpeedText
import com.fograin.launcher.ui.stateText
import com.fograin.launcher.ui.title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun DownloadsScreen(model: AppModel, state: AppUiState) {
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val settingsPending = stringResource(Res.string.downloads_settings_pending)
    val backDispatcher = remember { platformServices().backDispatcher }
    val category = state.download.category
    val searchAvailable = category == DownloadsCategory.GAME

    val closeSearch: () -> Unit = {
        searchExpanded = false
        model.setDownloadQuery("")
        focusManager.clearFocus(force = true)
    }

    LaunchedEffect(Unit) {
        if (category == DownloadsCategory.GAME) {
            model.loadDownloadVersions()
        }
    }
    LaunchedEffect(category) {
        if (!searchAvailable) {
            searchExpanded = false
            model.setDownloadQuery("")
            focusManager.clearFocus(force = true)
        }
    }
    backDispatcher.handleBack(enabled = searchExpanded, onBack = closeSearch)

    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(Res.string.downloads_title),
            searchPlaceholder = if (searchAvailable) {
                stringResource(Res.string.downloads_search_placeholder)
            } else {
                null
            },
            searchQuery = state.download.query,
            onSearchQueryChange = model::setDownloadQuery,
            searchExpanded = searchExpanded,
            onSearchExpandedChange = { expanded ->
                if (expanded) searchExpanded = true else closeSearch()
            },
            actions = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(Res.string.action_more),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.downloads_settings)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Tune, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                model.showNotice(settingsPending)
                            },
                        )
                    }
                }
            },
        )

        DownloadsBody(model = model, state = state)
    }
}

@Composable
private fun DownloadsBody(model: AppModel, state: AppUiState) {
    val download = state.download
    val category = download.category

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 880.dp
        val scope = rememberCoroutineScope()
        val selectedLoader = ContentLoaderFilter.entries
            .firstOrNull { it.name == download.contentLoader }
            ?: ContentLoaderFilter.ALL

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(if (wide) 14.dp else 8.dp))

            DownloadToolbar(
                category = category,
                download = download,
                selectedLoader = selectedLoader,
                onCategoryChange = model::setDownloadCategory,
                onChannelChange = model::setDownloadChannel,
                onLoaderChange = model::setDownloadContentLoader,
            )

            Spacer(Modifier.height(14.dp))

            AnimatedVisibility(
                visible = state.activeDownloads.isNotEmpty(),
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                Column {
                    ActiveDownloadsCard(
                        rows = state.activeDownloads,
                        speedText = downloadSpeedText(state),
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AnimatedContent(
                    targetState = category,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(tween(240), initialScale = 0.985f)) togetherWith
                            fadeOut(tween(130))
                    },
                    label = "download-category",
                ) { current ->
                    when (current) {
                        DownloadsCategory.GAME -> GameDownloads(
                            model = model,
                            state = state,
                            wide = wide,
                            onRetryVersions = { scope.launch { model.reloadDownloadVersions() } },
                        )

                        else -> ComingSoonCategory(
                            category = current,
                            loader = selectedLoader,
                            onBrowseGame = { model.setDownloadCategory(DownloadsCategory.GAME) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadToolbar(
    category: DownloadsCategory,
    download: com.fograin.launcher.app.DownloadUiState,
    selectedLoader: ContentLoaderFilter,
    onCategoryChange: (DownloadsCategory) -> Unit,
    onChannelChange: (DownloadVersionChannel) -> Unit,
    onLoaderChange: (ContentLoaderFilter) -> Unit,
) {
    var channelExpanded by remember { mutableStateOf(false) }
    var loaderExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CategoryRail(
            selected = category,
            onSelect = onCategoryChange,
            modifier = Modifier.weight(1f),
        )

        when (category) {
            DownloadsCategory.GAME -> FilterMenuChip(
                label = download.channel.title(),
                expanded = channelExpanded,
                onExpandedChange = { channelExpanded = it },
                options = DownloadVersionChannel.entries.map { channel ->
                    MenuOption(
                        label = channel.title(),
                        selected = channel == download.channel,
                        onClick = { onChannelChange(channel) },
                    )
                },
            )

            DownloadsCategory.MOD, DownloadsCategory.MODPACK -> FilterMenuChip(
                label = selectedLoader.title(),
                expanded = loaderExpanded,
                onExpandedChange = { loaderExpanded = it },
                options = ContentLoaderFilter.entries.map { loader ->
                    MenuOption(
                        label = loader.title(),
                        selected = loader == selectedLoader,
                        onClick = { onLoaderChange(loader) },
                    )
                },
            )

            else -> Unit
        }
    }
}

/** Pill-style category rail with an animated selected background. */
@Composable
private fun CategoryRail(
    selected: DownloadsCategory,
    onSelect: (DownloadsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadsCategory.entries.forEach { category ->
                val isSelected = category == selected
                val container by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(180),
                    label = "category-container",
                )
                val content by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(180),
                    label = "category-content",
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelect(category) },
                        )
                        .background(container)
                        .animateContentSize(tween(160))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    AnimatedVisibility(
                        visible = isSelected,
                        enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.5f),
                        exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.5f),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = content,
                        )
                    }
                    Text(
                        text = category.title(),
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private data class MenuOption(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun FilterMenuChip(
    label: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<MenuOption>,
) {
    Box {
        AssistChip(
            onClick = { onExpandedChange(true) },
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            shape = CircleShape,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                labelColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            ),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = {
                        if (option.selected) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.width(24.dp))
                        }
                    },
                    onClick = {
                        onExpandedChange(false)
                        option.onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun ActiveDownloadsCard(rows: List<DownloadRow>, speedText: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.downloads_in_progress),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(Res.string.downloads_task_count, rows.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = speedText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            rows.take(3).forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = row.stateText(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ThinProgressBar(progress = row.fraction)
                }
            }
        }
    }
}


@Composable
private fun ComingSoonCategory(
    category: DownloadsCategory,
    loader: ContentLoaderFilter,
    onBrowseGame: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            RoundedCornerShape(22.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = category.title(),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = when (category) {
                        DownloadsCategory.MOD -> if (loader != ContentLoaderFilter.ALL) {
                            stringResource(Res.string.downloads_mod_body_filtered, loader.title())
                        } else {
                            stringResource(Res.string.downloads_mod_body)
                        }

                        DownloadsCategory.MODPACK -> if (loader != ContentLoaderFilter.ALL) {
                            stringResource(Res.string.downloads_modpack_body_filtered, loader.title())
                        } else {
                            stringResource(Res.string.downloads_modpack_body)
                        }

                        DownloadsCategory.RESOURCEPACK ->
                            stringResource(Res.string.downloads_resourcepack_body)

                        DownloadsCategory.SHADERPACK ->
                            stringResource(Res.string.downloads_shaderpack_body)

                        DownloadsCategory.DATAPACK ->
                            stringResource(Res.string.downloads_datapack_body)

                        DownloadsCategory.GAME -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MilestoneChip(
                        stringResource(
                            Res.string.downloads_milestone_chip,
                            category.milestone
                                ?: stringResource(Res.string.downloads_milestone_later),
                        ),
                    )
                    TextButton(onClick = onBrowseGame) {
                        Text(stringResource(Res.string.downloads_browse_game))
                    }
                }
            }
        }
    }
}
