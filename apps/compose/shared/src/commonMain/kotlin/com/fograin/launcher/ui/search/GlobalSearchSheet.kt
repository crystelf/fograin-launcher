package com.fograin.launcher.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.model.Instance
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.components.InstanceTile
import com.fograin.launcher.ui.label
import org.jetbrains.compose.resources.stringResource

const val GlobalSearchScreenTag: String = "global-search-screen"
const val GlobalSearchFieldTag: String = "global-search-field"

fun globalSearchCommandTag(id: String): String = "global-search-command-$id"

/**
 * Command-palette style search opened from the rail search button or
 * Cmd/Ctrl+F. It covers the launcher's global nouns — instances, pages and
 * maintenance actions — instead of filtering only the library list.
 */
@Composable
fun GlobalSearchSheet(model: AppModel, state: AppUiState) {
    val focusRequester = remember { FocusRequester() }
    val query = state.globalSearchQuery.trim()
    val javaLabel = stringResource(Res.string.global_search_java)
    val addLabel = stringResource(Res.string.global_search_add_instance)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val closeAnd: (() -> Unit) -> Unit = { action ->
        model.closeGlobalSearch()
        action()
    }

    val commands = buildList {
        AppDestination.entries.forEach { destination ->
            val label = stringResource(destination.label)
            add(
                SearchCommand(
                    id = destination.name.lowercase(),
                    title = label,
                    subtitle = "前往$label",
                    icon = destination.icon,
                    action = { closeAnd { model.navigate(destination) } },
                ),
            )
        }
        add(
            SearchCommand(
                id = "java",
                title = javaLabel,
                subtitle = stringResource(Res.string.global_search_java_body),
                icon = Icons.Outlined.Memory,
                action = { closeAnd { model.openJavaSettings() } },
            ),
        )
        add(
            SearchCommand(
                id = "add-instance",
                title = addLabel,
                subtitle = stringResource(Res.string.global_search_add_instance_body),
                icon = Icons.Outlined.Add,
                action = { closeAnd { model.openAddInstance() } },
            ),
        )
    }.filter { command ->
        query.isEmpty() ||
            command.title.contains(query, ignoreCase = true) ||
            command.subtitle.contains(query, ignoreCase = true)
    }

    val instances = state.instances
        .filter { instance ->
            query.isEmpty() ||
                listOf(instance.name, instance.minecraftVersion, instance.loader.title, instance.loaderVersion)
                    .any { it.contains(query, ignoreCase = true) }
        }
        .take(if (query.isEmpty()) 4 else 8)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.global_search_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = model::closeGlobalSearch) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.action_close),
                )
            }
        }
        OutlinedTextField(
            value = state.globalSearchQuery,
            onValueChange = model::setGlobalSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(GlobalSearchFieldTag)
                .focusRequester(focusRequester),
            singleLine = true,
            placeholder = { Text(stringResource(Res.string.global_search_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.globalSearchQuery.isNotEmpty()) {
                    IconButton(onClick = { model.setGlobalSearchQuery("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(Res.string.search_clear),
                        )
                    }
                }
            },
            shape = RoundedCornerShape(16.dp),
        )

        if (query.isEmpty()) {
            Text(
                text = stringResource(Res.string.global_search_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(GlobalSearchScreenTag)
                .heightIn(max = 460.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (instances.isNotEmpty()) {
                item { SearchSection(if (query.isEmpty()) Res.string.global_search_recent else Res.string.global_search_instances) }
                items(instances, key = { it.id }) { instance ->
                    SearchInstanceRow(instance) {
                        closeAnd { model.openInstance(instance) }
                    }
                }
            }

            if (commands.isNotEmpty()) {
                item { SearchSection(Res.string.global_search_actions) }
                items(commands, key = { it.id }) { command ->
                    SearchCommandRow(command)
                }
            }

            if (query.isNotEmpty() && instances.isEmpty() && commands.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.global_search_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private data class SearchCommand(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val action: () -> Unit,
)

@Composable
private fun SearchSection(title: org.jetbrains.compose.resources.StringResource) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun SearchInstanceRow(instance: Instance, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InstanceTile(instance = instance, size = 38.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = instance.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = instance.versionLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchCommandRow(command: SearchCommand) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(globalSearchCommandTag(command.id))
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = command.action)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Icon(
                imageVector = command.icon,
                contentDescription = null,
                modifier = Modifier.padding(9.dp).size(19.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = command.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = command.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
