package com.fograin.launcher.settings

import androidx.compose.ui.graphics.Color
import com.fograin.launcher.ui.theme.parseHexColor
import com.fograin.launcher.ui.components.windowHasRoundedCorners
import com.fograin.launcher.ui.theme.seedColorScheme
import com.fograin.launcher.ui.theme.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsTest {

    @Test
    fun systemTagsMapToTheFourShippedLanguages() {
        assertEquals(
            AppLanguage.SIMPLIFIED_CHINESE,
            AppLanguage.fromSystemTag("zh-CN"),
        )
        assertEquals(
            AppLanguage.SIMPLIFIED_CHINESE,
            AppLanguage.fromSystemTag("zh_Hans_CN"),
        )
        assertEquals(
            AppLanguage.TRADITIONAL_CHINESE,
            AppLanguage.fromSystemTag("zh-Hant-TW"),
        )
        assertEquals(
            AppLanguage.TRADITIONAL_CHINESE,
            AppLanguage.fromSystemTag("zh-HK"),
        )
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromSystemTag("ja-JP"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromSystemTag("en-US"))
        // Unsupported system languages fall back to English copy, not the
        // Simplified Chinese base bundle.
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromSystemTag("fr-FR"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromSystemTag(""))
    }

    @Test
    fun wireValuesRoundTrip() {
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromWire("zh-Hant"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromWire("zh-Hans"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromWire("klingon"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromWire(null))

        assertEquals("zh-CN", AppLanguage.SIMPLIFIED_CHINESE.localeTag)
        assertEquals("zh-TW", AppLanguage.TRADITIONAL_CHINESE.localeTag)
        assertNull(AppLanguage.SYSTEM.localeTag)
    }

    @Test
    fun accentNormalizesToAutoOrUppercaseHex() {
        assertTrue(AccentPreference.fromWire("auto").isAuto)
        assertTrue(AccentPreference.fromWire("AUTO").isAuto)
        assertTrue(AccentPreference.fromWire(null).isAuto)
        assertEquals("#3F8F6B", AccentPreference.fromWire("#3f8f6b").value)
        assertEquals("#3F8F6B", AccentPreference.fromWire("3f8f6b").value)
        assertTrue(AccentPreference.fromWire("not-a-colour").isAuto)
    }

    @Test
    fun hexColorsRoundTrip() {
        val colour = parseHexColor("#5EC8A0")
        assertEquals("#5EC8A0", colour?.toHexString())
        assertNull(parseHexColor("5EC8A"))
        assertNull(parseHexColor("zzzzzz"))
    }

    @Test
    fun onlyRoundedWindowPlatformsRoundTheContentPane() {
        assertTrue(windowHasRoundedCorners("macos"))
        assertTrue(windowHasRoundedCorners("win11"))
        assertTrue(windowHasRoundedCorners("gnome"))
        // Windows 10/7 and fullscreen Android have square window corners.
        assertTrue(!windowHasRoundedCorners("win10"))
        assertTrue(!windowHasRoundedCorners("win7"))
        assertTrue(!windowHasRoundedCorners("android"))
    }

    @Test
    fun seededSchemesProduceContrastingMaterialRoles() {
        val seed = Color(0xFF336699)
        val dark = seedColorScheme(seed, dark = true)
        val light = seedColorScheme(seed, dark = false)

        assertNotEquals(dark.primary, light.primary)
        assertNotEquals(dark.surface, dark.primary)
        assertNotEquals(light.surface, light.primary)
        // The accent should keep the seed hue family; blue seed must not turn
        // the primary red.
        assertTrue(dark.primary.blue > dark.primary.red, "dark primary lost the seed hue")
        assertTrue(light.primary.blue > light.primary.red, "light primary lost the seed hue")
    }
}
