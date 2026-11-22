@file:OptIn(ExperimentalTime::class)

package com.fograin.launcher.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class InstanceFormatTest {
    private val day = 24L * 60L * 60L * 1_000L
    private val hour = 60L * 60L * 1_000L
    private val now = Instant.parse("2026-11-01T00:00:00Z").toEpochMilliseconds()

    @Test
    fun byteSizeUsesDecimalGigabytes() {
        assertEquals("3.1 GB", InstanceFormat.byteSize(3_100_000_000))
        assertEquals("410 MB", InstanceFormat.byteSize(410_000_000))
        assertEquals("1.5 MB", InstanceFormat.byteSize(1_500_000))
    }

    @Test
    fun relativeTimeReadsLikeThePen() {
        assertEquals("从未游玩", InstanceFormat.relativeTime(null, now, TimeZone.UTC))
        assertEquals("刚刚", InstanceFormat.relativeTime(now - 30_000, now, TimeZone.UTC))
        assertEquals("30 分钟前", InstanceFormat.relativeTime(now - 30 * 60_000L, now, TimeZone.UTC))
        assertEquals("2 小时前", InstanceFormat.relativeTime(now - 2 * hour, now, TimeZone.UTC))
        assertEquals("昨天", InstanceFormat.relativeTime(now - 30 * hour, now, TimeZone.UTC))
        assertEquals("3 天前", InstanceFormat.relativeTime(now - 3 * day, now, TimeZone.UTC))
        assertEquals("上周", InstanceFormat.relativeTime(now - 10 * day, now, TimeZone.UTC))
        assertEquals(
            "10月12日",
            InstanceFormat.relativeTime(now - 20 * day, now, TimeZone.UTC),
        )
    }

    @Test
    fun sanitizedInstanceIDKeepsSafeCharacters() {
        assertEquals("fog-forest", InstanceFormat.sanitizedInstanceID("fog-forest"))
        assertEquals("1.21.4", InstanceFormat.sanitizedInstanceID("1.21.4"))
        assertEquals("", InstanceFormat.sanitizedInstanceID("雾林生存"))
        assertEquals("abc", InstanceFormat.sanitizedInstanceID("..abc.."))
    }

    @Test
    fun taskTitleReadsLikeTheQueue() {
        assertEquals("安装 1.21.4", InstanceFormat.taskTitle("install:1.21.4"))
        assertEquals("Java 21", InstanceFormat.taskTitle("java:21"))
        assertEquals("install", InstanceFormat.taskTitle("install"))
    }

    @Test
    fun byteRateUsesTheSameUnitsAsByteSize() {
        assertEquals("—", InstanceFormat.byteRate(0.0))
        assertEquals("12.3 MB", InstanceFormat.byteRate(12_300_000.0))
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
        )
        assertEquals("1.21.4 · Fabric · 42 模组", instance.meta)
        assertEquals("1.21.4 · Fabric 0.16.9", instance.versionLine)
    }

    @Test
    fun contentTasksReadAsInstallNotRawGraphNames() {
        assertEquals("安装 Sodium", InstanceFormat.taskTitle("content:Sodium"))
    }
}
