package com.fograin.launcher.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class InstanceFormatTest {
    private val day = 24L * 60L * 60L * 1_000L
    private val hour = 60L * 60L * 1_000L

    @Test
    fun byteSizeUsesDecimalGigabytes() {
        assertEquals("3.1 GB", InstanceFormat.byteSize(3_100_000_000))
        assertEquals("410 MB", InstanceFormat.byteSize(410_000_000))
        assertEquals("1.5 MB", InstanceFormat.byteSize(1_500_000))
    }

    @Test
    fun relativeTimeReadsLikeThePen() {
        val now = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        assertEquals("从未游玩", InstanceFormat.relativeTime(null, now))
        assertEquals("刚刚", InstanceFormat.relativeTime(now - 30_000, now))
        assertEquals("30 分钟前", InstanceFormat.relativeTime(now - 30 * 60_000L, now))
        assertEquals("2 小时前", InstanceFormat.relativeTime(now - 2 * hour, now))
        assertEquals("昨天", InstanceFormat.relativeTime(now - 30 * hour, now))
        assertEquals("3 天前", InstanceFormat.relativeTime(now - 3 * day, now))
        assertEquals("上周", InstanceFormat.relativeTime(now - 10 * day, now))
        assertEquals(
            "10月12日",
            InstanceFormat.relativeTime(now - 20 * day, now, ZoneId.of("UTC")),
        )
    }

    @Test
    fun playTimeSplitsHoursAndMinutes() {
        assertEquals("—", InstanceFormat.playTime(null))
        assertEquals("—", InstanceFormat.playTime(0))
        assertEquals("45 分钟", InstanceFormat.playTime(45))
        assertEquals("2 小时", InstanceFormat.playTime(120))
        assertEquals("1 小时 30 分钟", InstanceFormat.playTime(90))
    }

    @Test
    fun instanceMetaMatchesTheLibraryLine() {
        val instance = Instance(
            id = "fog",
            name = "雾林生存",
            loader = LoaderKind.FABRIC,
            loaderVersion = "0.16.9",
            minecraftVersion = "1.21.4",
            modCount = 42,
            sizeBytes = 0,
            lastPlayedEpochMillis = null,
            palette = Instance.InstancePalette.FOREST,
        )
        assertEquals("1.21.4 · Fabric · 42 模组", instance.meta)
        assertEquals("1.21.4 · Fabric 0.16.9", instance.versionLine)
    }
}
