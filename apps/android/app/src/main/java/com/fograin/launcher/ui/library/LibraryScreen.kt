package com.fograin.launcher.ui.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fograin.launcher.R
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.LibraryQuery
import com.fograin.launcher.model.LibraryScope
import com.fograin.launcher.model.LibrarySort
import com.fograin.launcher.model.SampleData
import com.fograin.launcher.ui.components.AccountAvatarButton
import com.fograin.launcher.ui.components.EmptyState
import com.fograin.launcher.ui.components.SectionHeader

/**
 * M1 instance library: native filter chips, search field, sort menu and a
 * LazyColumn of instance cards backed by static fixtures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onShowMessage: (String) -> Unit) {
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var scopeName by rememberSaveable { mutableStateOf(LibraryScope.ALL.name) }
    var sortName by rememberSaveable { mutableStateOf(LibrarySort.LAST_PLAYED.name) }

    val scope = LibraryScope.entries.firstOrNull { it.name == scopeName } ?: LibraryScope.ALL
    val sort = LibrarySort.entries.firstOrNull { it.name == sortName } ?: LibrarySort.LAST_PLAYED
    val nowMillis = remember { System.currentTimeMillis() }
    val visible = remember(scope, sort, query, nowMillis) {
        LibraryQuery.select(SampleData.instances, scope, sort, query, nowMillis)
    }

    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    val closeSearch: () -> Unit = {
        searchExpanded = false
        query = ""
        focusManager.clearFocus(force = true)
    }

    BackHandler(enabled = searchExpanded, onBack = closeSearch)
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocusRequester.requestFocus()
    }

    val createMessage = stringResource(R.string.stub_create_instance)
    val launchMessage = stringResource(R.string.stub_launch)
    val accountMessage = stringResource(R.string.stub_account)
    val settingsMessage = stringResource(R.string.stub_instance_settings)
    val folderMessage = stringResource(R.string.stub_open_folder)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
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
                                    R.string.library_close_search
                                } else {
                                    R.string.library_search
                                },
                            ),
                        )
                    }
                    AccountAvatarButton(
                        initial = SampleData.account.initial,
                        label = stringResource(
                            R.string.home_account,
                            SampleData.account.name,
                        ),
                        onClick = { onShowMessage(accountMessage) },
                    )
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            if (searchExpanded) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(searchFocusRequester),
                    placeholder = { Text(stringResource(R.string.library_search)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.library_clear_search),
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
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (visible.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(
                                R.string.library_summary,
                                visible.size,
                                InstanceFormat.totalSize(visible),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    LibraryFilterRow(selected = scope, onSelect = { scopeName = it.name })
                }

                if (visible.isEmpty()) {
                    item {
                        val searching = query.isNotBlank()
                        EmptyState(
                            icon = if (searching) Icons.Filled.SearchOff else Icons.Outlined.Inbox,
                            title = stringResource(
                                if (searching) {
                                    R.string.library_empty_search_title
                                } else {
                                    R.string.library_empty_scope_title
                                },
                            ),
                            body = stringResource(
                                if (searching) {
                                    R.string.library_empty_search_body
                                } else {
                                    R.string.library_empty_scope_body
                                },
                            ),
                        )
                    }
                } else {
                    item {
                        SectionHeader(
                            title = scope.title,
                            action = {
                                SortMenu(selected = sort, onSelect = { sortName = it.name })
                            },
                        )
                    }
                    items(items = visible, key = { it.id }) { instance ->
                        InstanceCard(
                            instance = instance,
                            onLaunch = { onShowMessage(launchMessage) },
                            onInstanceSettings = { onShowMessage(settingsMessage) },
                            onOpenFolder = { onShowMessage(folderMessage) },
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { onShowMessage(createMessage) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.library_create),
            )
        }
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
                label = { Text(scope.chipTitle) },
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
            Text(
                text = selected.title,
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.library_sort),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LibrarySort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.title) },
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
