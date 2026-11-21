package com.fograin.launcher.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.LibraryStage
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.LibraryQuery
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.platform.DirectoryPick
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.EmptyState
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.components.SectionHeader
import com.fograin.launcher.ui.components.fograinPageTransition
import com.fograin.launcher.ui.diagnostics.DiagnosticsPage
import com.fograin.launcher.ui.instances.InstanceDetailPage
import com.fograin.launcher.ui.instances.InstanceSettingsPage
import com.fograin.launcher.ui.modpack.ModpackExportDialog
import com.fograin.launcher.ui.modpack.ModpackExportPage
import com.fograin.launcher.ui.title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Instance library destination: the grouped list, the instance detail page and
 * the per-instance settings page.
 *
 * The three pages share one `AnimatedContent` stage, exactly like the downloads
 * destination shares its list / install / loader pages. Clicking a row never
 * opens an inspector sheet any more; it enters [InstanceDetailPage], where the
 * content manager lives inline.
 */
@Composable
fun LibraryScreen(model: AppModel, state: AppUiState) {
    var listSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var detailSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val backDispatcher = remember { platformServices().backDispatcher }
    val listState = rememberLazyListState()
    val stage = state.libraryStage

    val closeListSearch: () -> Unit = {
        listSearchExpanded = false
        model.setLibraryQuery("")
        focusManager.clearFocus(force = true)
    }
    val closeDetailSearch: () -> Unit = {
        detailSearchExpanded = false
        model.setContentQuery("")
        focusManager.clearFocus(force = true)
    }

    backDispatcher.handleBack(
        enabled = (stage == LibraryStage.LIST && listSearchExpanded) ||
            (stage == LibraryStage.DETAIL && detailSearchExpanded),
    ) {
        if (stage == LibraryStage.LIST) closeListSearch() else closeDetailSearch()
    }

    // Desktop menu / Cmd-F: reveal the list search field.
    LaunchedEffect(state.librarySearchRequest) {
        if (state.librarySearchRequest > 0 && stage == LibraryStage.LIST) {
            listSearchExpanded = true
        }
    }
    // Reopen the content search if a query survived a recomposition; collapse
    // it when returning to the list.
    LaunchedEffect(stage) {
        when (stage) {
            LibraryStage.DETAIL -> detailSearchExpanded = state.content.query.isNotEmpty()
            LibraryStage.LIST -> detailSearchExpanded = false
            LibraryStage.SETTINGS, LibraryStage.EXPORT, LibraryStage.DIAGNOSTICS -> Unit
        }
    }
    // A deleted or externally removed instance must not leave a blank page.
    LaunchedEffect(stage, state.libraryDetailInstance?.id, state.isInitialLoading) {
        if (stage != LibraryStage.LIST &&
            state.libraryDetailInstance == null &&
            !state.isInitialLoading
        ) {
            model.closeInstanceDetail()
        }
    }

    AnimatedContent(
        targetState = stage,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            fograinPageTransition(forward = targetState.ordinal > initialState.ordinal)
        },
        label = "library-stage",
    ) { current ->
        when (current) {
            LibraryStage.LIST -> LibraryListPage(
                model = model,
                state = state,
                listState = listState,
                searchExpanded = listSearchExpanded,
                onSearchExpandedChange = { expanded ->
                    if (expanded) listSearchExpanded = true else closeListSearch()
                },
                onOpenInstance = { instance ->
                    closeListSearch()
                    model.openInstanceDetail(instance)
                },
            )

            LibraryStage.DETAIL -> InstanceDetailPage(
                model = model,
                state = state,
                searchExpanded = detailSearchExpanded,
                onSearchExpandedChange = { expanded ->
                    if (expanded) detailSearchExpanded = true else closeDetailSearch()
                },
            )

            LibraryStage.SETTINGS -> InstanceSettingsRoute(model = model, state = state)

            LibraryStage.EXPORT -> ModpackExportRoute(model = model, state = state)

            LibraryStage.DIAGNOSTICS -> DiagnosticsPage(model = model, state = state)
        }
    }
}

@Composable
private fun LibraryListPage(
    model: AppModel,
    state: AppUiState,
    listState: LazyListState,
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onOpenInstance: (Instance) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDirectoryPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nowMillis = remember { InstanceFormat.nowMillis() }

    val sections = remember(
        state.instances,
        state.libraryScope,
        state.librarySort,
        state.libraryQuery,
    ) {
        LibraryQuery.sections(
            instances = state.instances,
            scope = state.libraryScope,
            sort = state.librarySort,
            query = state.libraryQuery,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(
                title = stringResource(Res.string.library_title),
                searchPlaceholder = stringResource(Res.string.library_search_placeholder),
                searchQuery = state.libraryQuery,
                onSearchQueryChange = model::setLibraryQuery,
                searchExpanded = searchExpanded,
                onSearchExpandedChange = onSearchExpandedChange,
                focusRequest = state.librarySearchRequest,
                actions = {
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
                                text = { Text(stringResource(Res.string.library_import_directory)) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        // The native picker (JFileChooser / SAF) is the
                                        // primary path; the in-app browser is the fallback
                                        // for trees the platform cannot map to a path.
                                        when (val pick = model.pickImportDirectory()) {
                                            is DirectoryPick.Picked ->
                                                model.importMinecraftDirectory(pick.path)

                                            DirectoryPick.Cancelled -> Unit
                                            is DirectoryPick.Unavailable -> showDirectoryPicker = true
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.modpack_import)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Inventory2, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    model.startModpackImport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.library_new_instance)) },
                                onClick = {
                                    menuExpanded = false
                                    model.openAddInstance()
                                },
                            )
                        }
                    }
                },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    LibraryFilterRow(
                        selected = state.libraryScope,
                        onSelect = model::setLibraryScope,
                    )
                }

                if (sections.isEmpty()) {
                    item {
                        val searching = state.libraryQuery.isNotBlank()
                        EmptyState(
                            icon = if (searching) Icons.Filled.SearchOff else Icons.Outlined.Inbox,
                            title = stringResource(
                                if (searching) {
                                    Res.string.library_empty_search_title
                                } else {
                                    Res.string.library_empty_title
                                },
                            ),
                            body = stringResource(
                                if (searching) {
                                    Res.string.library_empty_search_body
                                } else {
                                    Res.string.library_empty_body
                                },
                            ),
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { model.setLibraryQuery("") }) {
                                Text(stringResource(Res.string.library_clear_search))
                            }
                            TextButton(onClick = model::openAddInstance) {
                                Text(stringResource(Res.string.action_add_instance))
                            }
                        }
                    }
                } else {
                    sections.forEach { section ->
                        item(key = "header-${section.group.name}") {
                            SectionHeader(
                                title = stringResource(
                                    Res.string.library_group_header,
                                    section.group.title(),
                                    section.instances.size,
                                ),
                                modifier = Modifier
                                    .animateItem()
                                    .padding(top = 12.dp, bottom = 4.dp),
                                action = {
                                    SortMenu(
                                        selected = state.librarySort,
                                        onSelect = model::setLibrarySort,
                                    )
                                },
                            )
                        }
                        items(items = section.instances, key = { it.id }) { instance ->
                            LibraryRow(
                                instance = instance,
                                isSelected = state.librarySelection == instance.id,
                                nowMillis = nowMillis,
                                onClick = { onOpenInstance(instance) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = model::openAddInstance,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(Res.string.library_new_instance),
            )
        }
    }

    if (showDirectoryPicker) {
        DirectoryPickerDialog(
            rootPath = state.settings.dataRoot,
            onDismiss = { showDirectoryPicker = false },
            onSelect = { directory ->
                showDirectoryPicker = false
                model.importMinecraftDirectory(directory)
            },
        )
    }
}

/** Header + two-step export wizard for the library's export stage. */
@Composable
private fun ModpackExportRoute(model: AppModel, state: AppUiState) {
    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(Res.string.modpack_export_page_title),
            onBack = model::backModpackExportStep,
        )
        ModpackExportPage(
            model = model,
            state = state,
            modifier = Modifier.weight(1f),
        )
    }
    state.modpackExport.result?.let { result ->
        ModpackExportDialog(
            result = result,
            onCopy = model::copyModpackExportPath,
            onDismiss = model::dismissModpackExportResult,
        )
    }
}

/** Header + settings body for the third library stage. */
@Composable
private fun InstanceSettingsRoute(model: AppModel, state: AppUiState) {
    val current = state.libraryDetailInstance
    var retained by remember { mutableStateOf(current) }
    LaunchedEffect(current) {
        if (current != null) retained = current
    }
    val instance = current ?: retained ?: return
    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(Res.string.action_game_settings),
            onBack = model::closeInstanceSettings,
        )
        InstanceSettingsPage(
            model = model,
            instance = instance,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LibraryFilterRow(
    selected: LibraryScope,
    onSelect: (LibraryScope) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryScope.entries.forEach { scope ->
            FilterChip(
                selected = scope == selected,
                onClick = { onSelect(scope) },
                label = { Text(scope.title()) },
                leadingIcon = if (scope == selected) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun SortMenu(
    selected: LibrarySort,
    onSelect: (LibrarySort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selected.title(), style = MaterialTheme.typography.labelLarge)
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(Res.string.library_sort),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.title()) },
                    leadingIcon = {
                        if (sort == selected) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(sort)
                    },
                )
            }
        }
    }
}
