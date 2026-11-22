package com.fograin.launcher.ui.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.DownloadRow
import com.fograin.launcher.app.DownloadStage
import com.fograin.launcher.model.ContentLoaderFilter
import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.Instance
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.components.SegmentedTabRail
import com.fograin.launcher.ui.components.ThinProgressBar
import com.fograin.launcher.ui.components.fograinPageTransition
import com.fograin.launcher.ui.components.fograinTabTransition
import com.fograin.launcher.ui.content.ContentBrowser
import com.fograin.launcher.ui.downloadSpeedText
import com.fograin.launcher.ui.stateText
import com.fograin.launcher.ui.title
import com.fograin.launcher.ui.titleText
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * 下载 destination. The version list is the first page; a row opens the
 * pinned install page and the loader-version page opens on top of that. All
 * three pages share this header and the bottom navigation.
 */
@Composable
fun DownloadsScreen(model: AppModel, state: AppUiState) {
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val settingsPending = stringResource(Res.string.downloads_settings_pending)
    val backDispatcher = remember { platformServices().backDispatcher }
    val category = state.download.category
    val stage = state.download.stage
    val searchAvailable = category.isGame || category.isContent
    // Hoisted out of the stage switch so returning from the install page keeps
    // the list scrolled where the user left it.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val selectedLoader = ContentLoaderFilter.entries
        .firstOrNull { it.name == state.download.contentLoader }
        ?: ContentLoaderFilter.ALL
    var loaderExpanded by remember { mutableStateOf(false) }

    val closeSearch: () -> Unit = {
        searchExpanded = false
        model.downloads.setQuery("")
        focusManager.clearFocus(force = true)
    }

    LaunchedEffect(Unit) {
        // A query can outlive the screen (the model owns it), so reopen the
        // compact search field instead of showing a silently filtered list.
        if (state.download.query.isNotEmpty()) searchExpanded = true
        if (category == DownloadsCategory.GAME) {
            model.downloads.loadVersions()
        }
    }
    LaunchedEffect(category) {
        if (!searchAvailable) {
            searchExpanded = false
            model.downloads.setQuery("")
            focusManager.clearFocus(force = true)
        }
    }
    backDispatcher.handleBack(
        enabled = searchExpanded && stage == DownloadStage.BROWSER,
        onBack = closeSearch,
    )

    AnimatedContent(
        targetState = stage,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            fograinPageTransition(forward = targetState.ordinal > initialState.ordinal)
        },
        label = "download-stage",
    ) { current ->
        when (current) {
            DownloadStage.BROWSER -> Column(modifier = Modifier.fillMaxSize()) {
                PageHeader(
                    title = stringResource(Res.string.downloads_title),
                    searchPlaceholder = when {
                        category == DownloadsCategory.GAME ->
                            stringResource(Res.string.downloads_search_placeholder)

                        category.isContent -> stringResource(
                            when (category) {
                                DownloadsCategory.MOD -> Res.string.downloads_search_mod
                                DownloadsCategory.MODPACK -> Res.string.downloads_search_modpack
                                DownloadsCategory.RESOURCEPACK ->
                                    Res.string.downloads_search_resourcepack

                                DownloadsCategory.SHADERPACK ->
                                    Res.string.downloads_search_shaderpack

                                DownloadsCategory.DATAPACK -> Res.string.downloads_search_datapack
                                DownloadsCategory.GAME -> Res.string.downloads_search_placeholder
                            },
                        )

                        else -> null
                    },
                    searchQuery = state.download.query,
                    onSearchQueryChange = model.downloads::setQuery,
                    searchExpanded = searchExpanded,
                    onSearchExpandedChange = { expanded ->
                        if (expanded) searchExpanded = true else closeSearch()
                    },
                    leadingActions = {
                        when {
                            category == DownloadsCategory.GAME -> {
                                ChannelFilterChip(
                                    channel = state.download.channel,
                                    onSelect = model.downloads::setChannel,
                                )
                            }

                            // Modpacks install into a new instance, so the
                            // "新实例" chip is redundant; the loader filter
                            // takes its place in the header.
                            category.installsToNewInstance -> FilterMenuChip(
                                label = selectedLoader.title(),
                                expanded = loaderExpanded,
                                onExpandedChange = { loaderExpanded = it },
                                options = ContentLoaderFilter.entries.map { loader ->
                                    MenuOption(
                                        label = loader.title(),
                                        selected = loader == selectedLoader,
                                        onClick = {
                                            model.downloads.setContentLoader(loader)
                                        },
                                    )
                                },
                            )

                            category.isContent -> {
                                ContentTargetChip(
                                    instances = state.instances,
                                    selectedId = state.contentBrowser.targetInstanceId,
                                    onSelect = model.contentBrowser::setTargetInstance,
                                )
                            }
                        }
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
                                    text = { Text(stringResource(Res.string.download_queue_open)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.FileDownload,
                                            contentDescription = null,
                                        )
                                    },
                                    trailingIcon = {
                                        if (state.activeDownloadCount > 0) {
                                            Text(
                                                text = state.activeDownloadCount.toString(),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                    modifier = Modifier.testTag(DownloadsQueueMenuTag),
                                    onClick = {
                                        menuExpanded = false
                                        model.downloads.openQueue()
                                    },
                                )
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

                DownloadsBody(
                    model = model,
                    state = state,
                    listState = listState,
                    gridState = gridState,
                )
            }

            DownloadStage.INSTALL -> Column(modifier = Modifier.fillMaxSize()) {
                PageHeader(
                    title = stringResource(Res.string.install_title),
                    onBack = model.downloads::closeInstallPage,
                )
                GameInstallPage(model = model, state = state)
            }

            DownloadStage.LOADER_VERSIONS -> Column(modifier = Modifier.fillMaxSize()) {
                PageHeader(
                    title = stringResource(
                        Res.string.install_loader_versions,
                        state.download.installLoader.title,
                    ),
                    onBack = model.downloads::closeLoaderVersionPicker,
                )
                LoaderVersionsPage(model = model, state = state)
            }
        }
    }
}

/** Stable test tag for one downloads category tab. */
fun downloadsCategoryTag(category: DownloadsCategory): String =
    "downloads-category-${category.name.lowercase()}"

const val DownloadsQueueCardTag: String = "downloads-queue-card"
const val DownloadsQueueMenuTag: String = "downloads-queue-menu"

/** Tag for the target-instance selector that sits in the downloads header. */
const val ContentTargetTag: String = "content-target-menu"

fun contentTargetOptionTag(instanceId: String): String = "content-target-option-$instanceId"

/**
 * Target instance selector for the content categories. It lives in the page
 * header on the same row as the search field so the content body starts with
 * the source filter instead of duplicating the install target.
 */
@Composable
private fun ContentTargetChip(
    instances: List<Instance>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = instances.firstOrNull { it.id == selectedId }
    val targetLabel = stringResource(Res.string.downloads_content_target)
    val instanceLabel = selected?.name
        ?: stringResource(Res.string.downloads_content_target_empty)
    val label = "$targetLabel：$instanceLabel"
    Box {
        AssistChip(
            onClick = { if (instances.isNotEmpty()) expanded = true },
            enabled = instances.isNotEmpty(),
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
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
            modifier = Modifier
                .testTag(ContentTargetTag)
                .semantics { contentDescription = label },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            instances.forEach { instance ->
                DropdownMenuItem(
                    text = { Text(instance.name) },
                    leadingIcon = {
                        if (instance.id == selectedId) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.width(24.dp))
                        }
                    },
                    modifier = Modifier.testTag(contentTargetOptionTag(instance.id)),
                    onClick = {
                        expanded = false
                        onSelect(instance.id)
                    },
                )
            }
        }
    }
}

/**
 * Channel filter (正式版 / 快照 / …) that sits immediately left of the search
 * affordance in the downloads header.
 */
@Composable
private fun ChannelFilterChip(
    channel: DownloadVersionChannel,
    onSelect: (DownloadVersionChannel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = channel.title()
    val semanticsLabel = stringResource(Res.string.downloads_channel_label)
    Box {
        AssistChip(
            onClick = { expanded = true },
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
            modifier = Modifier.semantics {
                contentDescription = "$semanticsLabel：$label"
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DownloadVersionChannel.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title()) },
                    leadingIcon = {
                        if (option == channel) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.width(24.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadsBody(
    model: AppModel,
    state: AppUiState,
    listState: LazyListState,
    gridState: LazyGridState,
) {
    val download = state.download
    val category = download.category

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 880.dp
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(if (wide) 14.dp else 8.dp))

            DownloadToolbar(
                category = category,
                onCategoryChange = model.downloads::setCategory,
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
                        onOpen = model.downloads::openQueue,
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
                    transitionSpec = { fograinTabTransition() },
                    label = "download-category",
                ) { current ->
                    when (current) {
                        DownloadsCategory.GAME -> GameDownloads(
                            model = model,
                            state = state,
                            wide = wide,
                            listState = listState,
                            gridState = gridState,
                            onRetryVersions = { scope.launch { model.downloads.reloadVersions() } },
                        )

                        else -> ContentBrowser(
                            model = model,
                            state = state,
                            category = current,
                            wide = wide,
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
    onCategoryChange: (DownloadsCategory) -> Unit,
) {
    SegmentedTabRail(
        options = DownloadsCategory.entries,
        selected = category,
        onSelect = onCategoryChange,
        modifier = Modifier.fillMaxWidth(),
        tabModifier = { option -> Modifier.testTag(downloadsCategoryTag(option)) },
        label = { option -> option.title() },
    )
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
private fun ActiveDownloadsCard(
    rows: List<DownloadRow>,
    speedText: String,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DownloadsQueueCardTag),
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
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = stringResource(Res.string.download_queue_open),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            rows.take(3).forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.titleText(),
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
