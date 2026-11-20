package com.fograin.launcher.settings

import com.fograin.launcher.platform.currentSystemLanguageTag

/**
 * UI languages shipped by the launcher.
 *
 * The wire values are also the `launcher.toml` values written by the Rust
 * core. [SYSTEM] resolves the host locale at composition time; every other
 * value pins one Compose resource qualifier set.
 */
enum class AppLanguage(
    val wireValue: String,
    val languageCode: String?,
    val regionCode: String?,
) {
    SYSTEM("system", null, null),
    SIMPLIFIED_CHINESE("zh-Hans", "zh", "CN"),
    TRADITIONAL_CHINESE("zh-Hant", "zh", "TW"),
    ENGLISH("en", "en", null),
    JAPANESE("ja", "ja", null),
    ;

    /** Concrete language used by resource lookup and the language picker. */
    fun resolve(systemTag: String = currentSystemLanguageTag()): AppLanguage =
        if (this == SYSTEM) fromSystemTag(systemTag) else this

    /** BCP-47 tag applied to the platform locale; `null` means "follow system". */
    val localeTag: String?
        get() = when (this) {
            SYSTEM -> null
            // Region tags, not script tags: Compose resources ships
            // `values-zh-rTW` and the base bundle is Simplified Chinese.
            SIMPLIFIED_CHINESE -> "zh-CN"
            TRADITIONAL_CHINESE -> "zh-TW"
            ENGLISH -> "en"
            JAPANESE -> "ja"
        }

    companion object {
        fun fromWire(value: String?): AppLanguage =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: SYSTEM

        /** Maps a BCP-47 tag such as `zh-Hant-TW` or `ja-JP` to a shipped UI language. */
        fun fromSystemTag(tag: String): AppLanguage {
            val normalized = tag.trim().replace('_', '-').lowercase()
            return when {
                normalized.startsWith("zh") -> {
                    val traditional = normalized.contains("hant") ||
                        normalized.contains("-tw") ||
                        normalized.contains("-hk") ||
                        normalized.contains("-mo")
                    if (traditional) TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
                }

                normalized.startsWith("ja") -> JAPANESE
                normalized.startsWith("en") -> ENGLISH
                // The base resource bundle is Simplified Chinese; an unknown
                // system language gets the international English copy.
                normalized.isEmpty() -> SIMPLIFIED_CHINESE
                else -> ENGLISH
            }
        }
    }
}

/** `[general] initial_page` — the destination shown after a cold start. */
enum class InitialPage(val wireValue: String) {
    HOME("home"),
    LIBRARY("library"),
    DOWNLOADS("downloads"),
    ;

    companion object {
        fun fromWire(value: String?): InitialPage =
            entries.firstOrNull { it.wireValue == value } ?: HOME
    }
}

/**
 * The accent stored in `launcher.toml`: `auto` or a `#RRGGBB` seed.
 *
 * Kept as a tiny value type instead of a sealed class so the Rust wire string
 * round-trips without extra mapping.
 */
data class AccentPreference(val value: String) {
    val isAuto: Boolean
        get() = value.equals("auto", ignoreCase = true)

    val hex: String?
        get() = if (isAuto) null else value

    companion object {
        val Auto = AccentPreference("auto")

        fun fromWire(value: String?): AccentPreference {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.equals("auto", ignoreCase = true)) return Auto
            val hex = trimmed.removePrefix("#").uppercase()
            val valid = hex.length == 6 && hex.all { it in '0'..'9' || it in 'A'..'F' }
            return if (valid) AccentPreference("#$hex") else Auto
        }

        fun custom(hex: String): AccentPreference = fromWire(hex)
    }
}
