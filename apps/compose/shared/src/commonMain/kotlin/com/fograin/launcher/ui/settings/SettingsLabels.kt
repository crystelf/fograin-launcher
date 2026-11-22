package com.fograin.launcher.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.fograin.launcher.app.SettingsFailure
import com.fograin.launcher.app.SettingsSection
import com.fograin.launcher.platform.currentSystemLanguageTag
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.settings_error_config
import com.fograin.launcher.resources.settings_error_core
import com.fograin.launcher.resources.settings_error_network
import com.fograin.launcher.resources.settings_initial_downloads
import com.fograin.launcher.resources.settings_initial_home
import com.fograin.launcher.resources.settings_initial_library
import com.fograin.launcher.resources.settings_language_en
import com.fograin.launcher.resources.settings_language_ja
import com.fograin.launcher.resources.settings_language_system
import com.fograin.launcher.resources.settings_language_system_value
import com.fograin.launcher.resources.settings_language_zh_hans
import com.fograin.launcher.resources.settings_language_zh_hant
import com.fograin.launcher.resources.settings_nav_about
import com.fograin.launcher.resources.settings_nav_appearance
import com.fograin.launcher.resources.settings_nav_download
import com.fograin.launcher.resources.settings_nav_general
import com.fograin.launcher.resources.settings_nav_java
import com.fograin.launcher.resources.settings_nav_network
import com.fograin.launcher.resources.settings_nav_storage
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.settings.InitialPage
import org.jetbrains.compose.resources.stringResource

/** Sidebar icon for each settings category. */
internal val SettingsSection.icon: ImageVector
    get() = when (this) {
        SettingsSection.APPEARANCE -> Icons.Outlined.Palette
        SettingsSection.GENERAL -> Icons.Outlined.Tune
        SettingsSection.JAVA -> Icons.Outlined.Coffee
        SettingsSection.DOWNLOAD -> Icons.Outlined.Download
        SettingsSection.NETWORK -> Icons.Outlined.Wifi
        SettingsSection.STORAGE -> Icons.Outlined.FolderOpen
        SettingsSection.ABOUT -> Icons.Outlined.Info
    }

/** Sidebar and page title. */
@Composable
fun SettingsSection.title(): String = stringResource(
    when (this) {
        SettingsSection.APPEARANCE -> Res.string.settings_nav_appearance
        SettingsSection.GENERAL -> Res.string.settings_nav_general
        SettingsSection.JAVA -> Res.string.settings_nav_java
        SettingsSection.DOWNLOAD -> Res.string.settings_nav_download
        SettingsSection.NETWORK -> Res.string.settings_nav_network
        SettingsSection.STORAGE -> Res.string.settings_nav_storage
        SettingsSection.ABOUT -> Res.string.settings_nav_about
    },
)

/** Native name of a language option; the language picker never translates it. */
@Composable
fun AppLanguage.nativeName(): String = stringResource(
    when (this) {
        AppLanguage.SYSTEM -> Res.string.settings_language_system
        AppLanguage.SIMPLIFIED_CHINESE -> Res.string.settings_language_zh_hans
        AppLanguage.TRADITIONAL_CHINESE -> Res.string.settings_language_zh_hant
        AppLanguage.ENGLISH -> Res.string.settings_language_en
        AppLanguage.JAPANESE -> Res.string.settings_language_ja
    },
)

/**
 * "跟随系统（简体中文）" for the system option, the concrete language name
 * otherwise. The concrete name uses the app's own resource bundle, so an
 * English UI still shows 日本語 for Japanese.
 */
@Composable
fun AppLanguage.pickerLabel(): String {
    val resolved = resolve(currentSystemLanguageTag())
    return if (this == AppLanguage.SYSTEM) {
        stringResource(Res.string.settings_language_system_value, resolved.nativeName())
    } else {
        nativeName()
    }
}

/** Localizes a settings failure without leaking the technical key. */
@Composable
fun settingsFailureText(failure: SettingsFailure): String {
    val key = failure.messageKey
    val base = when {
        key.startsWith("error.network") || key == "error.content.request" ->
            stringResource(Res.string.settings_error_network)

        key.startsWith("error.config") || key.startsWith("error.theme") ||
            key.startsWith("error.settings") || key.startsWith("error.data_root") ->
            stringResource(Res.string.settings_error_config)

        else -> stringResource(Res.string.settings_error_core)
    }
    val detail = failure.detail?.trim()?.takeIf { it.isNotEmpty() }?.take(240)
    return if (detail == null) base else "$base $detail"
}

@Composable
fun InitialPage.title(): String = stringResource(
    when (this) {
        InitialPage.HOME -> Res.string.settings_initial_home
        InitialPage.LIBRARY -> Res.string.settings_initial_library
        InitialPage.DOWNLOADS -> Res.string.settings_initial_downloads
    },
)
