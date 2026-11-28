package com.fograin.launcher.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.action_cancel
import com.fograin.launcher.resources.community_schedule_confirm
import com.fograin.launcher.resources.community_schedule_past
import com.fograin.launcher.resources.community_schedule_time
import com.fograin.launcher.resources.community_schedule_title
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

const val CommunityScheduleDialogTag: String = "community-schedule-dialog"
const val CommunityScheduleConfirmTag: String = "community-schedule-confirm"

/**
 * Date + time picker for Misskey's server-side scheduled notes.
 *
 * A scheduled note is a draft with `isActuallyScheduled`; the launcher cannot
 * simply pass a future date to `notes/create`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScheduleDialog(
    initialEpochMillis: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val initial = remember(initialEpochMillis) {
        initialEpochMillis?.let { Instant.fromEpochMilliseconds(it) }
            ?: (Clock.System.now() + 1.hours)
    }
    val initialLocal = remember(initial) { initial.toLocalDateTime(zone) }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initialLocal.date
            .atStartOfDayIn(TimeZone.UTC)
            .toEpochMilliseconds(),
    )
    val timeState = rememberTimePickerState(
        initialHour = initialLocal.hour,
        initialMinute = initialLocal.minute,
        is24Hour = true,
    )
    var error by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(12.dp)
                .heightIn(max = 760.dp)
                .testTag(CommunityScheduleDialogTag),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(Res.string.community_schedule_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(Res.string.community_schedule_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DatePicker(
                    state = dateState,
                    showModeToggle = false,
                    title = null,
                    headline = null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TimeInput(state = timeState)
                }
                if (error) {
                    Text(
                        text = stringResource(Res.string.community_schedule_past),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            val selectedMillis = dateState.selectedDateMillis ?: return@TextButton
                            val date = Instant.fromEpochMilliseconds(selectedMillis)
                                .toLocalDateTime(TimeZone.UTC)
                                .date
                            val epoch = date
                                .atTime(timeState.hour, timeState.minute)
                                .toInstant(zone)
                                .toEpochMilliseconds()
                            if (epoch <= Clock.System.now().toEpochMilliseconds()) {
                                error = true
                            } else {
                                error = false
                                onConfirm(epoch)
                            }
                        },
                        modifier = Modifier.testTag(CommunityScheduleConfirmTag),
                    ) {
                        Text(stringResource(Res.string.community_schedule_confirm))
                    }
                }
            }
        }
    }
}
