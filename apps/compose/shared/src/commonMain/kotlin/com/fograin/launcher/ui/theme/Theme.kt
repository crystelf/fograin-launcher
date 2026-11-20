package com.fograin.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.fograin.launcher.generated.FograinTokens
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.settings.AccentPreference
import com.fograin.launcher.settings.ThemeMode

/**
 * Fograin's Material 3 theme.
 *
 * Every colour, type size, spacing step and radius comes from
 * `design/tokens.json` through the generated `FograinTokens`. Material You is
 * layered on top only where the platform reports support and the user enables
 * dynamic colour.
 */
private val FograinDarkScheme: ColorSchemeTokens = ColorSchemeTokens(
    surface = FograinTokens.Dark.surface,
    surfaceLow = FograinTokens.Dark.surfaceLow,
    surfaceContainer = FograinTokens.Dark.surfaceContainer,
    surfaceHigh = FograinTokens.Dark.surfaceHigh,
    surfaceHighest = FograinTokens.Dark.surfaceHighest,
    onSurface = FograinTokens.Dark.onSurface,
    onSurfaceVariant = FograinTokens.Dark.onSurfaceVariant,
    outline = FograinTokens.Dark.outline,
    outlineVariant = FograinTokens.Dark.outlineVariant,
    primary = FograinTokens.Dark.primary,
    onPrimary = FograinTokens.Dark.onPrimary,
    primaryContainer = FograinTokens.Dark.primaryContainer,
    onPrimaryContainer = FograinTokens.Dark.onPrimaryContainer,
    secondary = FograinTokens.Dark.secondary,
    onSecondary = FograinTokens.Dark.onSecondary,
    secondaryContainer = FograinTokens.Dark.secondaryContainer,
    onSecondaryContainer = FograinTokens.Dark.onSecondaryContainer,
    tertiary = FograinTokens.Dark.tertiary,
    onTertiary = FograinTokens.Dark.onTertiary,
    tertiaryContainer = FograinTokens.Dark.tertiaryContainer,
    onTertiaryContainer = FograinTokens.Dark.onTertiaryContainer,
    error = FograinTokens.Dark.error,
    inverseSurface = FograinTokens.Dark.inverseSurface,
    inverseOnSurface = FograinTokens.Dark.inverseOnSurface,
    inversePrimary = FograinTokens.Dark.inversePrimary,
    scrim = FograinTokens.Dark.scrim,
)

private val FograinLightScheme: ColorSchemeTokens = ColorSchemeTokens(
    surface = FograinTokens.Light.surface,
    surfaceLow = FograinTokens.Light.surfaceLow,
    surfaceContainer = FograinTokens.Light.surfaceContainer,
    surfaceHigh = FograinTokens.Light.surfaceHigh,
    surfaceHighest = FograinTokens.Light.surfaceHighest,
    onSurface = FograinTokens.Light.onSurface,
    onSurfaceVariant = FograinTokens.Light.onSurfaceVariant,
    outline = FograinTokens.Light.outline,
    outlineVariant = FograinTokens.Light.outlineVariant,
    primary = FograinTokens.Light.primary,
    onPrimary = FograinTokens.Light.onPrimary,
    primaryContainer = FograinTokens.Light.primaryContainer,
    onPrimaryContainer = FograinTokens.Light.onPrimaryContainer,
    secondary = FograinTokens.Light.secondary,
    onSecondary = FograinTokens.Light.onSecondary,
    secondaryContainer = FograinTokens.Light.secondaryContainer,
    onSecondaryContainer = FograinTokens.Light.onSecondaryContainer,
    tertiary = FograinTokens.Light.tertiary,
    onTertiary = FograinTokens.Light.onTertiary,
    tertiaryContainer = FograinTokens.Light.tertiaryContainer,
    onTertiaryContainer = FograinTokens.Light.onTertiaryContainer,
    error = FograinTokens.Light.error,
    inverseSurface = FograinTokens.Light.inverseSurface,
    inverseOnSurface = FograinTokens.Light.inverseOnSurface,
    inversePrimary = FograinTokens.Light.inversePrimary,
    scrim = FograinTokens.Light.scrim,
)

private data class ColorSchemeTokens(
    val surface: Color,
    val surfaceLow: Color,
    val surfaceContainer: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
    val scrim: Color,
)

private fun ColorSchemeTokens.toDarkColorScheme() = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = surface,
    onBackground = onSurface,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceContainer,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    error = error,
    outline = outline,
    outlineVariant = outlineVariant,
    scrim = scrim,
    surfaceBright = surfaceHighest,
    surfaceDim = surface,
    surfaceContainerLowest = surface,
    surfaceContainerLow = surfaceLow,
    surfaceContainer = surfaceContainer,
    surfaceContainerHigh = surfaceHigh,
    surfaceContainerHighest = surfaceHighest,
)

private fun ColorSchemeTokens.toLightColorScheme() = lightColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = surface,
    onBackground = onSurface,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceContainer,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    error = error,
    outline = outline,
    outlineVariant = outlineVariant,
    scrim = scrim,
    surfaceBright = surfaceHighest,
    surfaceDim = surface,
    surfaceContainerLowest = surface,
    surfaceContainerLow = surfaceLow,
    surfaceContainer = surfaceContainer,
    surfaceContainerHigh = surfaceHigh,
    surfaceContainerHighest = surfaceHighest,
)

private val DarkColorScheme = FograinDarkScheme.toDarkColorScheme()
private val LightColorScheme = FograinLightScheme.toLightColorScheme()

private val FograinTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = FograinTokens.Type.hero,
        fontWeight = FontWeight.Normal,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontSize = FograinTokens.Type.page,
        fontWeight = FontWeight.Normal,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontSize = FograinTokens.Type.title,
        fontWeight = FontWeight.Medium,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontSize = FograinTokens.Type.title,
        fontWeight = FontWeight.Medium,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontSize = FograinTokens.Type.heading,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontSize = FograinTokens.Type.subhead,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = FograinTokens.Type.subhead,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = FograinTokens.Type.body,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = FograinTokens.Type.small,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontSize = FograinTokens.Type.subhead,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontSize = FograinTokens.Type.body,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontSize = FograinTokens.Type.caption,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
    ),
)

private val FograinShapes = Shapes(
    extraSmall = RoundedCornerShape(FograinTokens.Radius.control),
    small = RoundedCornerShape(FograinTokens.Radius.control),
    medium = RoundedCornerShape(FograinTokens.Radius.card),
    large = RoundedCornerShape(FograinTokens.Radius.pill),
    extraLarge = RoundedCornerShape(FograinTokens.Radius.pill),
)

/**
 * Applies the Fograin theme.
 *
 * [accent] is the string stored in `launcher.toml`: `auto` follows the system
 * accent (Material You on Android, the desktop accent colour elsewhere), while
 * a `#RRGGBB` value seeds a full Material 3 scheme. When the platform has no
 * accent source the Fograin palette is used, so `auto` always has a defined
 * fallback.
 */
@Composable
fun FograinTheme(
    themeMode: ThemeMode,
    accent: String = "auto",
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val appearance = remember { platformServices().appearance }
    val preference = remember(accent) { AccentPreference.fromWire(accent) }
    val customScheme = remember(preference, darkTheme) {
        preference.hex?.let { hex ->
            parseHexColor(hex)?.let { seed -> seedColorScheme(seed, darkTheme) }
        }
    }
    val systemScheme = if (preference.isAuto) {
        appearance.dynamicColorScheme(darkTheme)
    } else {
        null
    }
    val colorScheme: ColorScheme = customScheme
        ?: systemScheme
        ?: if (darkTheme) DarkColorScheme else LightColorScheme

    // Re-apply the status/navigation bar icon contrast when the in-app theme
    // changes; `enableEdgeToEdge` captures a fixed detect-dark value.
    appearance.applySystemBars(darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FograinTypography,
        shapes = FograinShapes,
        content = content,
    )
}
