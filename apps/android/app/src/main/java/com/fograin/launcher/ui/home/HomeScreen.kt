package com.fograin.launcher.ui.home

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.R
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.HomeFilter
import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.SampleData
import com.fograin.launcher.ui.components.AccountAvatarButton
import com.fograin.launcher.ui.components.InstanceTile
import com.fograin.launcher.ui.components.KeyValueRow
import com.fograin.launcher.ui.components.SectionHeader
import com.fograin.launcher.ui.components.StatusPill
import com.fograin.launcher.ui.components.StatusTone
import com.fograin.launcher.ui.components.ThinProgressBar
import java.time.LocalTime

/**
 * Home tab: the current instance hero, the instance list, the task queue and
 * the runtime/news column. Mirrors the macOS home page and keeps every action
 * on native Material 3 controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppModel, state: AppUiState) {
    val account = SampleData.account
    val nowMillis = remember { System.currentTimeMillis() }

    Column(modifier = Modifier.fillMaxSize()) {
        LargeTopAppBar(
            title = {
                Column {
                    Text(
                        text = stringResource(
                            R.string.home_greeting_format,
                            currentGreeting(),
                            account.name,
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.homeSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                AccountAvatarButton(
                    initial = account.initial,
                    label = stringResource(R.string.home_account, account.name),
                    onClick = { model.showNotice("账户页将在 M7 接入") },
                )
            },
            colors = TopAppBarDefaults.largeTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                val current = state.currentInstance
                if (current != null) {
                    HeroCard(model = model, state = state, instance = current)
                } else {
                    EmptyHomeHero(onAddInstance = model::openAddInstance)
                }
            }

            item {
                SectionHeader(title = "实例")
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
                        text = if (state.instances.isEmpty()) {
                            "还没有实例。添加一个版本后就能在这里启动。"
                        } else {
                            "这个筛选条件下没有实例。"
                        },
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
                    )
                }
            }

            item { TaskQueueCard(state = state) }
            item { RuntimeCard(state = state) }
            item { NewsCard(model = model) }
        }
    }
}

@Composable
private fun HeroCard(model: AppModel, state: AppUiState, instance: Instance) {
    var switchExpanded by remember { mutableStateOf(false) }
    val downloading = model.isDownloading(instance)
    val status = when {
        state.sessionRunning -> "运行中"
        state.isSessionLoading -> "启动中…"
        downloading -> "下载中"
        !instance.isInstalled -> "未安装"
        else -> "已就绪"
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
        Box(modifier = Modifier.height(248.dp)) {
            Image(
                painter = painterResource(R.drawable.hero_forest),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.10f),
                            0.40f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.86f),
                        ),
                    ),
            )
            StatusPill(
                text = status,
                tone = tone,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
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
                            text = instance.meta,
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
                                contentDescription = stringResource(R.string.home_switch_instance),
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
                                                text = candidate.meta,
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
                    if (state.sessionRunning) {
                        Button(onClick = model::stopGame) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("停止游戏")
                        }
                    } else {
                        Button(
                            onClick = { model.startGame(instance) },
                            enabled = !state.isSessionLoading,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(if (state.isSessionLoading) "启动中…" else "开始游戏")
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
                        Text("游戏设置")
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
            Text("还没有实例", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "通过下载页选择 Minecraft 版本并安装，几分钟后就能开始游戏。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAddInstance) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("添加实例")
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
                label = { Text(filter.title) },
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
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
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
            InstanceTile(instance = instance, size = 40.dp)
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
                            contentDescription = "已收藏",
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
                text = InstanceFormat.relativeTime(instance.lastPlayedEpochMillis, nowMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.library_more),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("开始游戏") },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            model.startGame(instance)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("在实例库中显示") },
                        leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            model.openInstance(instance)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("复制实例目录路径") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            model.copyInstanceDirectoryPath(instance)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (instance.isFavorite) "取消收藏" else "收藏") },
                        leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            model.toggleFavorite(instance)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskQueueCard(state: AppUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text("任务队列", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    text = state.downloadsSpeedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.activeDownloads.isEmpty()) {
                Text(
                    text = "没有进行中的任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.activeDownloads.forEach { task ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = task.stateDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ThinProgressBar(progress = task.fraction)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeCard(state: AppUiState) {
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
            Text("运行环境", style = MaterialTheme.typography.titleSmall)
            KeyValueRow(
                label = "Java",
                value = state.javaSummary,
                checkmark = (state.appSnapshot?.javaCount ?: 0) > 0,
            )
            KeyValueRow(label = "内存分配", value = state.memorySummary, checkmark = true)
            KeyValueRow(
                label = "设备",
                value = listOf(state.deviceText, state.socText)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                checkmark = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "Java 运行时自动下载将在 M6 接入；当前优先使用系统中已发现的运行时。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NewsCard(model: AppModel) {
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
            Text("版本动态", style = MaterialTheme.typography.titleSmall)
            SampleData.news.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { model.installVersion(item.versionId) }) {
                        Text("安装")
                    }
                }
            }
        }
    }
}

@Composable
private fun currentGreeting(): String {
    val hour = remember { LocalTime.now().hour }
    val greetingRes = when (hour) {
        in 5..10 -> R.string.greeting_morning
        in 11..12 -> R.string.greeting_noon
        in 13..17 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    }
    return stringResource(greetingRes)
}
