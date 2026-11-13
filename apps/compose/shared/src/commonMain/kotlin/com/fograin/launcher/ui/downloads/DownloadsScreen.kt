package com.fograin.launcher.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.DownloadRow
import com.fograin.launcher.model.ContentLoaderFilter
import com.fograin.launcher.model.DownloadVersionChannel
import com.fograin.launcher.model.DownloadsCategory
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.CoreVersionEntry
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.MilestoneChip
import com.fograin.launcher.ui.components.ThinProgressBar
import com.fograin.launcher.ui.downloadSpeedText
import com.fograin.launcher.ui.emptyMessage
import com.fograin.launcher.ui.stateText
import com.fograin.launcher.ui.title
import org.jetbrains.compose.resources.stringResource

/**
 * 下载页 following the HMCL/PCL flow: category tabs, the top-bar version
 * channel / loader dropdown, a library-style search icon and the reserved
 * 下载设置 menu, the version list with a plan preview, and the install sheet
 * for loader / version / name. Memory and display options stay in 游戏设置.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(model: AppModel, state: AppUiState) {
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var channelExpanded by remember { mutableStateOf(false) }
    var loaderExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    val settingsPending = stringResource(Res.string.downloads_settings_pending)
    val backDispatcher = remember { platformServices().backDispatcher }
    val category = state.download.category
    val searchAvailable = category == DownloadsCategory.GAME
    val selectedLoader = ContentLoaderFilter.entries
        .firstOrNull { it.name == state.download.contentLoader }
        ?: ContentLoaderFilter.ALL

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
        if (!searchAvailable && searchExpanded) {
            closeSearch()
        }
    }
    backDispatcher.handleBack(enabled = searchExpanded, onBack = closeSearch)
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocusRequester.requestFocus()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LargeTopAppBar(
            title = {
                Column {
                    Text(stringResource(Res.string.downloads_title))
                    Text(
                        text = stringResource(Res.string.downloads_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                when (category) {
                    DownloadsCategory.GAME -> DownloadTopBarDropdown(
                        text = state.download.channel.title(),
                        expanded = channelExpanded,
                        onExpandedChange = { channelExpanded = it },
                        options = DownloadVersionChannel.entries.map { channel ->
                            channel.title() to { model.setDownloadChannel(channel) }
                        },
                    )

                    DownloadsCategory.MOD, DownloadsCategory.MODPACK -> DownloadTopBarDropdown(
                        text = selectedLoader.title(),
                        expanded = loaderExpanded,
                        onExpandedChange = { loaderExpanded = it },
                        options = ContentLoaderFilter.entries.map { loader ->
                            loader.title() to { model.setDownloadContentLoader(loader) }
                        },
                    )

                    else -> Unit
                }

                if (searchAvailable) {
                    IconButton(
                        onClick = {
                            if (searchExpanded) closeSearch() else searchExpanded = true
                        },
                    ) {
                        Icon(
                            imageVector = if (searchExpanded) {
                                Icons.Filled.Close
                            } else {
                                Icons.Outlined.Search
                            },
                            contentDescription = stringResource(
                                if (searchExpanded) {
                                    Res.string.downloads_close_search
                                } else {
                                    Res.string.downloads_search
                                },
                            ),
                        )
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        if (searchAvailable && searchExpanded) {
            OutlinedTextField(
                value = state.download.query,
                onValueChange = model::setDownloadQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(searchFocusRequester),
                placeholder = { Text(stringResource(Res.string.downloads_search_placeholder)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.download.query.isNotEmpty()) {
                        IconButton(onClick = { model.setDownloadQuery("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(Res.string.downloads_clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus(force = true) },
                ),
            )
        }

        PrimaryScrollableTabRow(
            selectedTabIndex = category.ordinal,
            edgePadding = 16.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            divider = {},
        ) {
            DownloadsCategory.entries.forEach { item ->
                Tab(
                    selected = item == category,
                    onClick = { model.setDownloadCategory(item) },
                    text = { Text(item.title()) },
                )
            }
        }

        when (category) {
            DownloadsCategory.GAME -> GameDownloads(model = model, state = state)
            else -> ContentComingSoon(state = state)
        }
    }
}

/**
 * Compact native dropdown in the downloads top bar: the version channel on the
 * game tab, the loader filter on the mod / modpack tabs.
 */
@Composable
private fun DownloadTopBarDropdown(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<Pair<String, () -> Unit>>,
) {
    Box {
        TextButton(
            onClick = { onExpandedChange(true) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            options.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onExpandedChange(false)
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun GameDownloads(model: AppModel, state: AppUiState) {
    val download = state.download
    val filteredVersions = remember(download.versions, download.query) {
        val query = download.query.trim().lowercase()
        if (query.isEmpty()) {
            download.versions
        } else {
            download.versions.filter { it.id.lowercase().contains(query) }
        }
    }
    val selected = download.versions.firstOrNull { it.id == download.selectedVersionId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            if (selected != null) {
                VersionDetailCard(model = model, state = state, version = selected)
            } else {
                Text(
                    text = stringResource(Res.string.downloads_pick_version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }

        if (state.activeDownloads.isNotEmpty()) {
            item {
                QueueCard(
                    rows = state.activeDownloads,
                    speedText = downloadSpeedText(state),
                )
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.downloads_version_list),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                if (download.isLoadingVersions) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        when {
            download.versionsError != null && download.versions.isEmpty() -> item {
                Text(
                    text = download.versionsError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            download.versions.isEmpty() && download.isLoadingVersions -> item {
                Text(
                    text = stringResource(Res.string.downloads_versions_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            download.versions.isEmpty() -> item {
                Text(
                    text = download.channel.emptyMessage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            filteredVersions.isEmpty() -> item {
                Text(
                    text = stringResource(Res.string.downloads_no_match),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> items(items = filteredVersions.take(80), key = { it.id }) { version ->
                VersionRow(
                    version = version,
                    isSelected = version.id == download.selectedVersionId,
                    onClick = { model.selectDownloadVersion(version.id) },
                )
            }
        }

        item {
            Text(
                text = stringResource(Res.string.downloads_install_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun VersionDetailCard(
    model: AppModel,
    state: AppUiState,
    version: CoreVersionEntry,
) {
    val plan = state.download.plan
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = version.id,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(8.dp))
                MilestoneChip(kindText(version.kind))
            }
            when {
                plan != null -> {
                    DetailLine(
                        stringResource(Res.string.downloads_need_java),
                        stringResource(Res.string.downloads_java_major, plan.javaMajor),
                    )
                    DetailLine(
                        stringResource(Res.string.downloads_files),
                        stringResource(Res.string.downloads_file_count, plan.files),
                    )
                    DetailLine(
                        stringResource(Res.string.downloads_size),
                        InstanceFormat.byteSize(plan.totalBytes),
                    )
                }

                state.download.planError != null -> {
                    Text(
                        text = stringResource(
                            Res.string.downloads_plan_error,
                            state.download.planError,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(Res.string.downloads_plan_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            version.released?.let { released ->
                DetailLine(
                    stringResource(Res.string.downloads_released),
                    released.take(10),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { model.openInstallSheet(version.id) },
                    enabled = !state.download.isGameDownloadRunning,
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(Res.string.action_install))
                }
                if (state.download.isGameDownloadRunning) {
                    Text(
                        text = stringResource(Res.string.downloads_install_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VersionRow(
    version: CoreVersionEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.ViewInAr,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = version.id,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        version.released?.let {
            Text(
                text = it.take(10),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun QueueCard(rows: List<DownloadRow>, speedText: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(Res.string.downloads_task_queue),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = speedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rows.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = row.stateText(),
                            style = MaterialTheme.typography.bodySmall,
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
private fun ContentComingSoon(state: AppUiState) {
    val loader = ContentLoaderFilter.entries
        .firstOrNull { it.name == state.download.contentLoader }
        ?: ContentLoaderFilter.ALL
    ComingSoonCategory(category = state.download.category, loader = loader)
}

@Composable
private fun ComingSoonCategory(
    category: DownloadsCategory,
    loader: ContentLoaderFilter = ContentLoaderFilter.ALL,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FileDownload,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(category.title(), style = MaterialTheme.typography.titleMedium)
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
            )
            MilestoneChip(
                stringResource(
                    Res.string.downloads_milestone_chip,
                    category.milestone
                        ?: stringResource(Res.string.downloads_milestone_later),
                ),
            )
        }
    }
}

@Composable
private fun kindText(kind: String): String = when (kind) {
    "release" -> stringResource(Res.string.downloads_kind_release)
    "snapshot" -> stringResource(Res.string.downloads_kind_snapshot)
    else -> kind
}
