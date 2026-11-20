package com.fograin.launcher.platform

import com.fograin.launcher.settings.AppLanguage
import java.util.Locale

private val systemLocale: Locale = Locale.getDefault()

actual fun currentSystemLanguageTag(): String = runCatching {
    systemLocale.toLanguageTag()
}.getOrDefault("")

actual fun applyAppLanguage(language: AppLanguage) {
    val tag = language.localeTag ?: systemLocale.toLanguageTag()
    runCatching { Locale.setDefault(Locale.forLanguageTag(tag)) }
}
