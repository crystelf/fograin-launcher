package com.fograin.launcher.ui.community

import kotlin.test.Test
import kotlin.test.assertEquals

class CommunityVideoTimeTest {
    @Test
    fun formatsUnderAnHourWithoutAnHourField() {
        assertEquals("0:00", formatVideoTime(0))
        assertEquals("0:01", formatVideoTime(1_000))
        assertEquals("0:59", formatVideoTime(59_999))
        assertEquals("1:00", formatVideoTime(60_000))
        assertEquals("12:34", formatVideoTime(754_000))
        assertEquals("59:59", formatVideoTime(3_599_999))
    }

    @Test
    fun formatsHoursWithTwoDigitMinutesAndSeconds() {
        assertEquals("1:00:00", formatVideoTime(3_600_000))
        assertEquals("2:05:09", formatVideoTime(7_509_000))
    }

    @Test
    fun clampsUnknownDurationsToZero() {
        assertEquals("0:00", formatVideoTime(-1))
        assertEquals("0:00", formatVideoTime(Long.MIN_VALUE))
    }
}
