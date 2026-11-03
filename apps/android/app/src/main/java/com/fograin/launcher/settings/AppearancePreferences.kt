package com.fograin.launcher.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/** `appearance.theme` in the shared `launcher.toml`. */
enum class ThemeMode(val title: String, val description: String) {
    SYSTEM("跟随系统", "随系统深色模式自动切换"),
    LIGHT("浅色", "始终使用浅色外观"),
    DARK("深色", "始终使用深色外观"),
}

/**
 * Local stand-in for the Rust core's launch settings.
 *
 * `docs/design.md` §4.1.1 makes `launcher.toml` the source of truth. Until
 * `settings_get`/`settings_set` are exposed through `fg-ffi`, Android persists
 * the same two keys locally so the first frame can be themed without waiting
 * for a bridge. M4 moves this behind the FFI without changing the UI.
 */
class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == preferences.getString(KEY_THEME, null) }
            ?: ThemeMode.SYSTEM
        set(value) {
            preferences.edit().putString(KEY_THEME, value.name).apply()
        }

    /** Material You colors are opt-in so the Fograin palette stays the default. */
    var dynamicColor: Boolean
        get() = preferences.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) {
            preferences.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        }

    /** Resolves the concrete theme for the starting window and the first frame. */
    fun isDark(context: Context): Boolean = when (themeMode) {
        ThemeMode.SYSTEM ->
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    companion object {
        private const val FILE_NAME = "fograin.appearance"
        private const val KEY_THEME = "appearance.theme"
        private const val KEY_DYNAMIC_COLOR = "appearance.dynamicColor"

        /** Material You dynamic color exists on Android 12 and newer. */
        fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
