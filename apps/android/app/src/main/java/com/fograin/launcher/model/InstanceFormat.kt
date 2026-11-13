package com.fograin.launcher.model

import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToLong
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Human-readable values shared by every Android page and covered by unit tests.
 *
 * The formats match the pen screens and the desktop shell: `8.4 GB`,
 * `2 小时前`, `昨天`, `从未游玩`.
 */
object InstanceFormat {
    private val dateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

    fun byteSize(bytes: Long): String {
        val (value, unit) = when {
            bytes >= 1_000_000_000L -> bytes / 1_000_000_000.0 to "GB"
            bytes >= 1_000_000L -> bytes / 1_000_000.0 to "MB"
            else -> bytes / 1_000.0 to "KB"
        }
        val text = String.format(Locale.US, "%.1f", value)
        val trimmed = if (text.endsWith(".0")) text.dropLast(2) else text
        return "$trimmed $unit"
    }

    fun totalSize(instances: List<Instance>): String = byteSize(LibraryQuery.totalSize(instances))

    fun relativeTime(
        epochMillis: Long?,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
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
            else -> Instant.ofEpochMilli(epochMillis).atZone(zone).format(dateFormatter)
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
}
