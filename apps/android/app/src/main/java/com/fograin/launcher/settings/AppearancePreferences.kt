package com.fograin.launcher.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/** `appearance.theme` in the shared `launcher.toml` (owned by the Rust core). */
enum class ThemeMode(val wireValue: String, val title: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromWire(value: String?): ThemeMode =
            entries.firstOrNull { it.wireValue == value } ?: SYSTEM
    }
}

/**
 * Local preferences the core does not own yet.
 *
 * `appearance.theme` moved to `launcher.toml` in M4 and is read/written through
 * [com.fograin.launcher.platform.CoreBridge]. Material You dynamic color is an
 * Android-only presentation choice, so it stays in SharedPreferences until the
 * settings surface lands in M10.
 */
class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Material You colors are opt-in so the Fograin palette stays the default. */
    var dynamicColor: Boolean
        get() = preferences.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) {
            preferences.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        }

    companion object {
        private const val FILE_NAME = "fograin.appearance"
        private const val KEY_DYNAMIC_COLOR = "appearance.dynamicColor"

        /** Material You dynamic color exists on Android 12 and newer. */
        fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /** Resolves the concrete theme for the starting window and first frame. */
        fun resolveDark(context: Context, themeMode: ThemeMode): Boolean = when (themeMode) {
            ThemeMode.SYSTEM ->
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES

            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    }
}
