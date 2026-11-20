package com.fograin.launcher.ui.locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.fograin.launcher.platform.applyAppLanguage
import com.fograin.launcher.platform.currentSystemLanguageTag
import com.fograin.launcher.settings.AppLanguage

/**
 * Applies the user's language to every `stringResource` below this point and
 * recreates that subtree when the language changes.
 *
 * Compose Multiplatform 1.12 keeps the custom resource-environment API
 * internal, so the supported switch point is the platform locale: we set it
 * once per language value and re-key the content, which makes every
 * `stringResource` read the new bundle. [AppLanguage.SYSTEM] restores the
 * locale captured at process start.
 */
@Composable
fun AppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    val systemTag = remember { currentSystemLanguageTag() }
    val resolved = remember(language, systemTag) { language.resolve(systemTag) }
    // Apply the concrete language, not SYSTEM: an unsupported host locale
    // maps to English, and setting the raw host tag would fall back to the
    // Simplified Chinese base bundle instead.
    remember(resolved) { applyAppLanguage(resolved) }
    key(resolved) { content() }
}
