package com.fograin.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.SettingsSection
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.settings_accent_auto
import com.fograin.launcher.resources.settings_accent_auto_desc
import com.fograin.launcher.resources.settings_accent_custom
import com.fograin.launcher.resources.settings_accent_desc
import com.fograin.launcher.resources.settings_accent_hex
import com.fograin.launcher.resources.settings_accent_hex_hint
import com.fograin.launcher.resources.settings_accent_invalid
import com.fograin.launcher.resources.settings_accent_title
import com.fograin.launcher.resources.settings_appearance_subtitle
import com.fograin.launcher.resources.settings_nav_appearance
import com.fograin.launcher.resources.settings_language
import com.fograin.launcher.resources.settings_language_desc
import com.fograin.launcher.resources.settings_theme
import com.fograin.launcher.resources.settings_theme_desc
import com.fograin.launcher.settings.AccentPreference
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.theme.AccentPresets
import com.fograin.launcher.ui.theme.DefaultAccentHex
import com.fograin.launcher.ui.theme.parseHexColor
import com.fograin.launcher.ui.theme.toHexString
import com.fograin.launcher.ui.title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

const val SettingsLanguageRowTag = "settings-language-row"
const val SettingsThemeRowTag = "settings-theme-row"
const val SettingsAccentAutoTag = "settings-accent-auto"
const val SettingsAccentHexTag = "settings-accent-hex"
fun settingsAccentSwatchTag(hex: String): String = "settings-accent-swatch-$hex"

/**
 * Appearance: language, light/dark mode and the accent seed.
 *
 * "自动取色" maps to `accent = auto`, which uses the OS accent (Material You
 * on Android) and falls back to the Fograin palette. Turning it off exposes a
 * swatch row plus a hex field; a valid hex is applied after a short debounce
 * so the whole window visibly previews the seed while typing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSettingsPage(
    model: AppModel,
    state: AppUiState,
    showTitle: Boolean,
) {
    val language = state.language
    val accent = state.accent
    var lastCustom by remember {
        mutableStateOf(accent.hex ?: DefaultAccentHex)
    }
    LaunchedEffect(accent) {
        if (!accent.isAuto) lastCustom = accent.hex ?: DefaultAccentHex
    }
    var hex by remember(lastCustom) { mutableStateOf(lastCustom.trimStart('#')) }
    val parsedHex = parseHexColor(hex)
    val customEnabled = !accent.isAuto

    // Apply a valid hex once the user stops typing. `hex` is kept without the
    // leading # so the field is shorter; the stored value adds it back.
    LaunchedEffect(hex, customEnabled) {
        if (!customEnabled) return@LaunchedEffect
        val parsed = parseHexColor(hex) ?: return@LaunchedEffect
        if (parsed.toHexString() == accent.hex) return@LaunchedEffect
        delay(450)
        lastCustom = parsed.toHexString()
        model.setAccent(AccentPreference.custom(parsed.toHexString()))
    }

    SettingsPage(
        title = SettingsSection.APPEARANCE.title(),
        subtitle = stringResource(Res.string.settings_appearance_subtitle),
        showTitle = showTitle,
    ) {
        item {
            SettingsGroupHeader(stringResource(Res.string.settings_nav_appearance))
        }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_language),
                subtitle = stringResource(Res.string.settings_language_desc),
                value = language.pickerLabel(),
                options = AppLanguage.entries.map { option ->
                    option.nativeName() to { model.setLanguage(option) }
                },
                testTag = SettingsLanguageRowTag,
            )
            SettingsDivider()
        }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_theme),
                subtitle = stringResource(Res.string.settings_theme_desc),
                value = state.theme.title(),
                options = ThemeMode.entries.map { mode ->
                    mode.title() to { model.setTheme(mode) }
                },
                testTag = SettingsThemeRowTag,
            )
            SettingsDivider()
        }

        item {
            SettingsGroupHeader(stringResource(Res.string.settings_accent_title))
        }
        item {
            SettingsToggleRow(
                title = stringResource(Res.string.settings_accent_auto),
                subtitle = stringResource(Res.string.settings_accent_auto_desc),
                checked = accent.isAuto,
                testTag = SettingsAccentAutoTag,
                onCheckedChange = { auto ->
                    model.setAccent(
                        if (auto) {
                            AccentPreference.Auto
                        } else {
                            AccentPreference.custom(lastCustom)
                        },
                    )
                },
            )
            SettingsDivider()
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.settings_accent_custom),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(Res.string.settings_accent_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Live preview of the colour the theme is currently using.
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            ),
                    )
                }
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AccentPresets.forEach { preset ->
                        val presetHex = preset.toHexString()
                        val selected = customEnabled && accent.hex == presetHex
                        AccentSwatch(
                            color = preset,
                            selected = selected,
                            // Picking a swatch is also a shortcut for leaving
                            // automatic mode, so it stays tappable either way.
                            enabled = true,
                            modifier = Modifier.testTag(settingsAccentSwatchTag(presetHex)),
                            onClick = {
                                lastCustom = presetHex
                                hex = presetHex.trimStart('#')
                                model.setAccent(AccentPreference.custom(presetHex))
                            },
                        )
                    }
                }
                if (customEnabled) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { input ->
                            hex = input.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                                .take(6)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SettingsAccentHexTag),
                        singleLine = true,
                        label = { Text(stringResource(Res.string.settings_accent_hex)) },
                        prefix = { Text("#") },
                        placeholder = { Text(stringResource(Res.string.settings_accent_hex_hint)) },
                        isError = hex.length == 6 && parsedHex == null,
                        supportingText = if (hex.length == 6 && parsedHex == null) {
                            { Text(stringResource(Res.string.settings_accent_invalid)) }
                        } else {
                            null
                        },
                    )
                }
            }
            SettingsDivider()
        }
    }
}

@Composable
private fun AccentSwatch(
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (enabled) color else color.copy(alpha = 0.35f))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) ring else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = if (color.luminance() > 0.55f) Color.Black else Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
