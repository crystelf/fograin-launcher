@file:OptIn(ExperimentalTime::class)

package com.fograin.launcher.model

import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Human-readable values shared by every page and covered by common unit tests.
 *
 * The formats match the pen screens: `8.4 GB`,
 * `2 小时前`, `昨天`, `从未游玩`. Implemented with kotlinx-datetime so the code
 * compiles in `commonMain` without `java.time`.
 */
object InstanceFormat {
    /** Current wall-clock time; screens capture it once per composition. */
    fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    fun byteSize(bytes: Long): String {
        val (value, unit) = when {
            bytes >= 1_000_000_000L -> bytes / 1_000_000_000.0 to "GB"
            bytes >= 1_000_000L -> bytes / 1_000_000.0 to "MB"
            else -> bytes / 1_000.0 to "KB"
        }
        return "${oneDecimal(value)} $unit"
    }

    fun totalSize(instances: List<Instance>): String = byteSize(LibraryQuery.totalSize(instances))

    fun relativeTime(
        epochMillis: Long?,
        nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        if (epochMillis == null) return "从未游玩"

        val delta = nowMillis - epochMillis
        if (delta < 60_000L) return "刚刚"

        val minutes = delta / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 60L -> "$minutes 分钟前"
            hours < 24L -> "$hours 小时前"
            days == 1L -> "昨天"
            days < 7L -> "$days 天前"
            days < 14L -> "上周"
            else -> {
                val date = Instant.fromEpochMilliseconds(epochMillis)
                    .toLocalDateTime(timeZone)
                    .date
                "${date.month.number}月${date.day}日"
            }
        }
    }

    /** Turns a display name into a filesystem-safe instance id. */
    fun sanitizedInstanceID(raw: String): String {
        val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_."
        return raw.filter { it in allowed }.trim('.')
    }

    /** Task graph name shown in the queue rows (`install:1.21.4` → 安装 1.21.4). */
    fun taskTitle(graphName: String): String {
        if (graphName.startsWith("install:")) {
            return "安装 ${graphName.removePrefix("install:")}"
        }
        return graphName
    }

    /** Speed text like `12.3 MB`; accepts a bytes-per-second double. */
    fun byteRate(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0) return "—"
        return byteSize(max(1L, bytesPerSecond.roundToLong()))
    }

    fun playTime(minutes: Int?): String {
        if (minutes == null || minutes <= 0) return "—"
        if (minutes < 60) return "$minutes 分钟"

        val hours = minutes / 60
        val remainder = minutes % 60
        return if (remainder == 0) "$hours 小时" else "$hours 小时 $remainder 分钟"
    }

    /** `3.1`, `410`, `0.5` — one decimal, trailing `.0` removed. */
    private fun oneDecimal(value: Double): String {
        val tenths = (value * 10).roundToLong()
        val whole = tenths / 10
        val fraction = tenths % 10
        return if (fraction == 0L) whole.toString() else "$whole.$fraction"
    }
}
