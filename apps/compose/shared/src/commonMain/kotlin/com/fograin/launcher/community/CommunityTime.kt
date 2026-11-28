package com.fograin.launcher.community

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Local `yyyy-MM-dd HH:mm` label for scheduled notes, drafts and poll expiry. */
fun formatCommunityTimestamp(epochMillis: Long): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return buildString {
        append(dateTime.year)
        append('-')
        append((dateTime.month.ordinal + 1).toString().padStart(2, '0'))
        append('-')
        append(dateTime.day.toString().padStart(2, '0'))
        append(' ')
        append(dateTime.hour.toString().padStart(2, '0'))
        append(':')
        append(dateTime.minute.toString().padStart(2, '0'))
    }
}
