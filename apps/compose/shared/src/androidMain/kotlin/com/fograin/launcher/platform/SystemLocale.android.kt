package com.fograin.launcher.platform

import android.content.res.Configuration
import android.os.LocaleList
import com.fograin.launcher.settings.AppLanguage
import java.util.Locale

private val systemLocale: Locale = Locale.getDefault()

actual fun currentSystemLanguageTag(): String = runCatching {
    systemLocale.toLanguageTag()
}.getOrDefault("")

actual fun applyAppLanguage(language: AppLanguage) {
    val tag = language.localeTag ?: systemLocale.toLanguageTag()
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    runCatching { LocaleList.setDefault(LocaleList(locale)) }

    // Compose and the Android resource system both read the Activity's
    // configuration; update it in place so the current activity does not have
    // to be recreated when the language changes.
    val context = AndroidPlatform.contextOrNull() ?: return
    runCatching {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }
}
