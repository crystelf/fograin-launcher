@file:OptIn(ExperimentalTime::class)

package com.fograin.launcher.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.HomeFilter
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.TaskState
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.AccountAvatarButton
import com.fograin.launcher.ui.components.InstanceTile
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.components.SectionHeader
import com.fograin.launcher.ui.components.StatusPill
import com.fograin.launcher.ui.components.StatusTone
import com.fograin.launcher.ui.components.VersionBackdrop
import com.fograin.launcher.ui.components.ThinProgressBar
import com.fograin.launcher.ui.downloadSpeedText
import com.fograin.launcher.ui.metaText
import com.fograin.launcher.ui.relativeTimeText
import com.fograin.launcher.ui.title
import com.fograin.launcher.ui.titleText
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** Test tag for the top-bar account entry. */
const val AccountAvatarTag: String = "account-avatar"
const val HomeSettingsTag: String = "home-settings"
const val HomeListTag: String = "home-list"
const val HomeTaskQueueTag: String = "home-task-queue"
const val HomeOverflowTag: String = "home-overflow"
const val HomeImportModpackTag: String = "home-import-modpack"
const val HomeDownloadQueueTag: String = "home-download-queue"

fun homeDownloadPauseTag(taskId: String): String = "home-download-pause-$taskId"

fun homeDownloadResumeTag(taskId: String): String = "home-download-resume-$taskId"

fun homeDownloadStopTag(taskId: String): String = "home-download-stop-$taskId"

/**
 * Home tab: the current instance hero, the instance list and the task queue.
 * Keeps the initial screen focused on starting a game instead of repeating
 * settings that already live under Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppModel, state: AppUiState) {
    val profile = state.homeProfile
    val nowMillis = remember { Clock.System.now().toEpochMilliseconds() }
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(
                Res.string.home_greeting_format,
                currentGreeting(),
                profile.name,
            ),
            actions = {
                IconButton(
                    onClick = model::openSettings,
                    modifier = Modifier.testTag(HomeSettingsTag),
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(Res.string.settings_title),
                    )
                }
                AccountAvatarButton(
                    label = stringResource(Res.string.home_account_format, profile.name),
                    onClick = model.account::open,
                    avatarUrl = profile.avatarUrl,
                    isCommunity = profile.isCommunity,
                    modifier = Modifier.testTag(AccountAvatarTag),
                )
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag(HomeOverflowTag),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(Res.string.action_more),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.download_queue_open)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.FileDownload, contentDescription = null)
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
                            modifier = Modifier.testTag(HomeDownloadQueueTag),
                            onClick = {
                                menuOpen = false
                                model.downloads.openQueue()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.modpack_import)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Inventory2, contentDescription = null)
                            },
                            modifier = Modifier.testTag(HomeImportModpackTag),
                            onClick = {
                                menuOpen = false
                                model.modpack.startImport()
                            },
                        )
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(HomeListTag),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AnimatedContent(
                    targetState = state.currentInstance,
                    transitionSpec = {
                        fadeIn(tween(240)) togetherWith fadeOut(tween(140))
                    },
                    contentKey = { it?.id },
                    label = "home-hero",
                ) { current ->
                    if (current != null) {
                        HeroCard(model = model, state = state, instance = current)
                    } else {
                        EmptyHomeHero(onAddInstance = model.library::openAddInstance)
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(Res.string.home_instances))
            }
            item {
                HomeFilterRow(
                    selected = state.homeFilter,
                    onSelect = model::setHomeFilter,
                )
            }

            val homeInstances = state.homeInstances()
            if (homeInstances.isEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            if (state.instances.isEmpty()) {
                                Res.string.home_empty_all
                            } else {
                                Res.string.home_empty_filter
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(items = homeInstances, key = { it.id }) { instance ->
                    HomeInstanceRow(
                        model = model,
                        instance = instance,
                        isActive = state.currentInstance?.id == instance.id,
                        nowMillis = nowMillis,
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            item {
                AnimatedVisibility(
                    visible = state.activeDownloads.isNotEmpty(),
                    enter = expandVertically(tween(220)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                ) {
                    TaskQueueCard(model = model, state = state)
                }
            }
        }
    }
}

@Composable
private fun HeroCard(model: AppModel, state: AppUiState, instance: Instance) {
    var switchExpanded by remember { mutableStateOf(false) }
    val downloading = model.downloads.isDownloading(instance)
    // A healthy instance needs no badge; the pill is reserved for states the
    // user has to act on or wait for.
    val status = when {
        state.sessionRunning -> stringResource(Res.string.home_status_running)
        state.isSessionLoading -> stringResource(Res.string.home_status_launching)
        downloading -> stringResource(Res.string.home_status_downloading)
        !instance.isInstalled -> stringResource(Res.string.home_status_not_installed)
        else -> null
    }
    val tone = when {
        state.sessionRunning -> StatusTone.RUNNING
        state.isSessionLoading || downloading -> StatusTone.WARNING
        !instance.isInstalled -> StatusTone.WARNING
        else -> StatusTone.READY
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.height(300.dp)) {
            VersionBackdrop(
                minecraftVersion = instance.minecraftVersion,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.16f),
                            0.34f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
            )
            AnimatedContent(
                targetState = status to tone,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "hero-status",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) { (statusText, statusTone) ->
                if (statusText != null) {
                    StatusPill(text = statusText, tone = statusTone)
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InstanceTile(instance = instance, size = 48.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = instance.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = instance.metaText(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        IconButton(onClick = { switchExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.SwapHoriz,
                                contentDescription = stringResource(Res.string.home_switch_instance),
                                tint = Color.White,
                            )
                        }
                        DropdownMenu(
                            expanded = switchExpanded,
                            onDismissRequest = { switchExpanded = false },
                        ) {
                            state.instances.forEach { candidate ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(candidate.name)
                                            Text(
                                                text = candidate.metaText(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        if (candidate.id == instance.id) {
                                            Icon(Icons.Filled.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        switchExpanded = false
                                        model.selectHomeInstance(candidate)
                                    },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    AnimatedContent(
                        targetState = state.sessionRunning,
                        transitionSpec = {
                            fadeIn(tween(190)) togetherWith fadeOut(tween(120))
                        },
                        label = "hero-primary-action",
                    ) { running ->
                        if (running) {
                            Button(onClick = model.launch::stop) {
                                Icon(Icons.Filled.Stop, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(Res.string.action_stop_game))
                            }
                        } else {
                            Button(
                                onClick = { model.launch.start(instance) },
                                enabled = !state.isSessionLoading,
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    stringResource(
                                        if (state.isSessionLoading) {
                                            Res.string.action_launching
                                        } else {
                                            Res.string.action_start_game
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { model.manageInstance(instance) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = Color.White,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(Res.string.action_game_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHomeHero(onAddInstance: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.home_no_instance_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(Res.string.home_no_instance_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAddInstance) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(Res.string.action_add_instance))
            }
        }
    }
}

@Composable
private fun HomeFilterRow(selected: HomeFilter, onSelect: (HomeFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.title()) },
                leadingIcon = if (filter == selected) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
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
private fun HomeInstanceRow(
    model: AppModel,
    instance: Instance,
    isActive: Boolean,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { model.selectHomeInstance(instance) }
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InstanceTile(instance = instance, size = 46.dp)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = instance.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (instance.isFavorite) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = stringResource(Res.string.home_favorite_badge),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    text = instance.homeDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = relativeTimeText(instance.lastPlayedEpochMillis, nowMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
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
                        text = { Text(stringResource(Res.string.action_start_game)) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            model.launch.start(instance)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.home_menu_open_detail)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            model.openInstance(instance)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_open_instance_dir)) },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            model.instanceSettings.openDirectory(instance)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (instance.isFavorite) {
                                        Res.string.home_menu_unfavorite
                                    } else {
                                        Res.string.home_menu_favorite
                                    },
                                ),
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            model.library.toggleFavorite(instance)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskQueueCard(model: AppModel, state: AppUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HomeTaskQueueTag),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 12.dp),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.download_queue_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = downloadSpeedText(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = model.downloads::openQueue) {
                    Text(stringResource(Res.string.download_queue_view))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            state.activeDownloads.forEach { task ->
                HomeDownloadRow(model = model, task = task)
            }
        }
    }
}

@Composable
private fun HomeDownloadRow(model: AppModel, task: com.fograin.launcher.app.DownloadRow) {
    val paused = task.state == TaskState.PAUSED
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.titleText(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (paused) {
                    stringResource(Res.string.status_paused)
                } else {
                    "${(task.fraction * 100).toInt()}%"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (paused) {
                IconButton(
                    onClick = { model.downloads.resume(task.id) },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag(homeDownloadResumeTag(task.id)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(Res.string.download_queue_resume),
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = { model.downloads.pause(task.id) },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag(homeDownloadPauseTag(task.id)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = stringResource(Res.string.download_queue_pause),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(
                onClick = { model.downloads.cancel(task.id) },
                modifier = Modifier
                    .size(32.dp)
                    .testTag(homeDownloadStopTag(task.id)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = stringResource(Res.string.download_queue_stop),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        ThinProgressBar(progress = task.fraction)
    }
}

@Composable
private fun currentGreeting(): String {
    val hour = remember {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour
    }
    val greeting = when (hour) {
        in 5..10 -> Res.string.greeting_morning
        in 11..12 -> Res.string.greeting_noon
        in 13..17 -> Res.string.greeting_afternoon
        else -> Res.string.greeting_evening
    }
    return stringResource(greeting)
}
