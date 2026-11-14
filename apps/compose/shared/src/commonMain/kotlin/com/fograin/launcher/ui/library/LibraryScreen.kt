package com.fograin.launcher.ui.library

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.LibraryQuery
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.platform.DirectoryPick
import com.fograin.launcher.platform.platformServices
import kotlinx.coroutines.launch
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.EmptyState
import com.fograin.launcher.ui.components.SectionHeader
import com.fograin.launcher.ui.librarySubtitle
import com.fograin.launcher.ui.title
import org.jetbrains.compose.resources.stringResource

/**
 * Instance library: search, loader filters, sort, grouped table and the
 * inspector sheet. Matches the pen library layout on a single column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(model: AppModel, state: AppUiState) {
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDirectoryPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    val nowMillis = remember { InstanceFormat.nowMillis() }
    val backDispatcher = remember { platformServices().backDispatcher }

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

    val closeSearch: () -> Unit = {
        searchExpanded = false
        model.setLibraryQuery("")
        focusManager.clearFocus(force = true)
    }

    backDispatcher.handleBack(enabled = searchExpanded, onBack = closeSearch)
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocusRequester.requestFocus()
    }
    // Desktop menu / Cmd-F: the model asks the page to reveal its search field.
    LaunchedEffect(state.librarySearchRequest) {
        if (state.librarySearchRequest > 0) searchExpanded = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.library_title)) },
                actions = {
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
                                    Res.string.library_close_search
                                } else {
                                    Res.string.library_search
                                },
                            ),
                        )
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
                                text = { Text(stringResource(Res.string.library_new_instance)) },
                                onClick = {
                                    menuExpanded = false
                                    model.openAddInstance()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            if (searchExpanded) {
                OutlinedTextField(
                    value = state.libraryQuery,
                    onValueChange = model::setLibraryQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(searchFocusRequester),
                    placeholder = { Text(stringResource(Res.string.library_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.libraryQuery.isNotEmpty()) {
                            IconButton(onClick = { model.setLibraryQuery("") }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(Res.string.library_clear_search),
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

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        text = state.librarySubtitle(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
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
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
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
                                model = model,
                                instance = instance,
                                isSelected = state.librarySelection == instance.id,
                                nowMillis = nowMillis,
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
