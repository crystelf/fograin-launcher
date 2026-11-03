package com.fograin.launcher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.R
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.model.SampleData
import com.fograin.launcher.ui.components.AccountAvatarButton
import com.fograin.launcher.ui.components.SectionHeader
import java.time.LocalTime

/**
 * M1 home screen: current instance, recent instances, and the primary launch
 * action. All data is static until the FFI lands in M4/M3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenLibrary: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    var selectedInstanceId by rememberSaveable { mutableStateOf(SampleData.currentInstanceId) }
    val current = SampleData.instances.firstOrNull { it.id == selectedInstanceId }
        ?: SampleData.currentInstance
    val recent = remember(current.id) {
        SampleData.recentInstances(limit = 3, excludingId = current.id)
    }
    val nowMillis = remember { System.currentTimeMillis() }

    val greeting = currentGreeting()
    val startMessage = stringResource(R.string.stub_launch)
    val serversMessage = stringResource(R.string.stub_servers)
    val accountMessage = stringResource(R.string.stub_account)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            R.string.home_greeting_format,
                            greeting,
                            SampleData.account.name,
                        ),
                        style = MaterialTheme.typography.headlineLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { onShowMessage(serversMessage) }) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = stringResource(R.string.home_notifications),
                        )
                    }
                    AccountAvatarButton(
                        initial = SampleData.account.initial,
                        label = stringResource(R.string.home_account, SampleData.account.name),
                        onClick = { onShowMessage(accountMessage) },
                    )
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        text = stringResource(
                            R.string.home_last_played,
                            current.name,
                            InstanceFormat.relativeTime(current.lastPlayedEpochMillis, nowMillis),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    CurrentInstanceCard(
                        instance = current,
                        onSelectInstance = { selectedInstanceId = it.id },
                    )
                }
                item {
                    SectionHeader(
                        title = stringResource(R.string.home_recent),
                        action = {
                            TextButton(onClick = onOpenLibrary) {
                                Text(stringResource(R.string.home_see_all))
                            }
                        },
                    )
                }
                item {
                    RecentInstancesCard(
                        instances = recent,
                        nowMillis = nowMillis,
                        onSelectInstance = { selectedInstanceId = it.id },
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { onShowMessage(startMessage) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            text = { Text(stringResource(R.string.home_start_game)) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
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
