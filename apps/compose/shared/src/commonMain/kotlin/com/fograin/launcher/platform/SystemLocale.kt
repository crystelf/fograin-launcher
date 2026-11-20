package com.fograin.launcher.platform

import com.fograin.launcher.settings.AppLanguage

/**
 * The host's preferred BCP-47 language tag, captured once at process start so
 * an in-app language choice can later return to "follow system".
 */
expect fun currentSystemLanguageTag(): String

/**
 * Applies [language] to the process locale before the next resource lookup.
 *
 * Compose resources resolves `stringResource` against the platform locale, and
 * the public API does not expose a custom resource environment yet. Setting the
 * process locale is therefore the supported switch point; callers re-key the
 * subtree so every string is read again with the new locale.
 */
expect fun applyAppLanguage(language: AppLanguage)
