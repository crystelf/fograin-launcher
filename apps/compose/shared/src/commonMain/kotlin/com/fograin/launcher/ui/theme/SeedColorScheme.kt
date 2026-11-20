package com.fograin.launcher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Builds a complete Material 3 scheme from one seed colour.
 *
 * Material's HCT pipeline is not available in common code, so this uses OKLCH:
 * a perceptually uniform space where holding hue/chroma and changing lightness
 * gives the tonal steps the M3 roles expect. Out-of-gamut tones reduce chroma
 * instead of clipping, which keeps custom accent colours from turning muddy.
 */
fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val base = seed.toOklch()
    val hue = base.hue
    // A very saturated seed would make large containers shout; cap the chroma
    // the roles below are allowed to borrow from it.
    val accentChroma = base.chroma.coerceIn(0.04, 0.16)
    val neutralChroma = minOf(base.chroma * 0.12, 0.018)
    val secondaryChroma = accentChroma * 0.55

    fun accent(tone: Double, chroma: Double = accentChroma): Color =
        oklch(tone, chroma, hue)

    fun neutral(tone: Double): Color = oklch(tone, neutralChroma, hue)

    val primary = accent(if (dark) 0.80 else 0.46)
    val onPrimary = accent(if (dark) 0.22 else 0.99)
    val primaryContainer = accent(if (dark) 0.33 else 0.91)
    val onPrimaryContainer = accent(if (dark) 0.93 else 0.20)
    val inversePrimary = accent(if (dark) 0.46 else 0.80)

    val secondary = accent(if (dark) 0.79 else 0.47, secondaryChroma)
    val onSecondary = accent(if (dark) 0.22 else 0.99, secondaryChroma)
    val secondaryContainer = accent(if (dark) 0.31 else 0.91, secondaryChroma)
    val onSecondaryContainer = accent(if (dark) 0.92 else 0.21, secondaryChroma)

    val tertiary = oklch(if (dark) 0.81 else 0.46, 0.09, hue + 55.0)
    val onTertiary = oklch(if (dark) 0.22 else 0.99, 0.05, hue + 55.0)
    val tertiaryContainer = oklch(if (dark) 0.32 else 0.91, 0.06, hue + 55.0)
    val onTertiaryContainer = oklch(if (dark) 0.92 else 0.21, 0.05, hue + 55.0)

    val error = if (dark) oklch(0.78, 0.14, 25.0) else oklch(0.50, 0.17, 25.0)
    val onError = if (dark) oklch(0.22, 0.06, 25.0) else oklch(0.99, 0.01, 25.0)
    val errorContainer = if (dark) oklch(0.33, 0.11, 25.0) else oklch(0.91, 0.06, 25.0)
    val onErrorContainer = if (dark) oklch(0.93, 0.04, 25.0) else oklch(0.21, 0.08, 25.0)

    val surface = neutral(if (dark) 0.135 else 0.985)
    val surfaceLow = neutral(if (dark) 0.165 else 0.965)
    val surfaceContainer = neutral(if (dark) 0.195 else 0.945)
    val surfaceHigh = neutral(if (dark) 0.225 else 0.925)
    val surfaceHighest = neutral(if (dark) 0.265 else 0.900)
    val onSurface = neutral(if (dark) 0.925 else 0.175)
    val onSurfaceVariant = neutral(if (dark) 0.780 else 0.385)
    val outline = neutral(if (dark) 0.610 else 0.545)
    val outlineVariant = neutral(if (dark) 0.325 else 0.815)
    val inverseSurface = neutral(if (dark) 0.925 else 0.265)
    val inverseOnSurface = neutral(if (dark) 0.225 else 0.945)
    val scrim = Color.Black

    return if (dark) {
        darkColorScheme(
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
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
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
    } else {
        lightColorScheme(
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
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
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
    }
}

/** Parses `#RRGGBB` (with or without `#`) and returns `null` for invalid input. */
fun parseHexColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6) return null
    val rgb = hex.toLongOrNull(16) ?: return null
    return Color(
        red = ((rgb shr 16) and 0xFF).toInt(),
        green = ((rgb shr 8) and 0xFF).toInt(),
        blue = (rgb and 0xFF).toInt(),
    )
}

/** Upper-case `#RRGGBB` form used by `launcher.toml`. */
fun Color.toHexString(): String {
    fun channel(value: Float): String =
        (value.coerceIn(0f, 1f) * 255f).toInt().toString(16).padStart(2, '0')
    return "#${channel(red)}${channel(green)}${channel(blue)}".uppercase()
}

private data class Oklch(val lightness: Double, val chroma: Double, val hue: Double)

private fun Color.toOklch(): Oklch {
    fun linear(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    val r = linear(red)
    val g = linear(green)
    val b = linear(blue)

    val l = 0.412_221_470_8 * r + 0.536_332_536_3 * g + 0.051_445_992_9 * b
    val m = 0.211_903_498_2 * r + 0.680_699_545_1 * g + 0.107_396_956_6 * b
    val s = 0.088_302_461_9 * r + 0.281_718_837_6 * g + 0.629_978_700_5 * b

    val lRoot = cbrt(l)
    val mRoot = cbrt(m)
    val sRoot = cbrt(s)

    val lightness = 0.210_454_255_3 * lRoot + 0.793_617_785_0 * mRoot - 0.004_072_046_8 * sRoot
    val a = 1.977_998_495_1 * lRoot - 2.428_592_205_0 * mRoot + 0.450_593_709_9 * sRoot
    val bb = 0.025_904_037_1 * lRoot + 0.782_771_766_2 * mRoot - 0.808_675_766_0 * sRoot

    return Oklch(
        lightness = lightness,
        chroma = hypot(a, bb),
        hue = atan2(bb, a) * 180.0 / kotlin.math.PI,
    )
}

private fun oklch(lightness: Double, chroma: Double, hue: Double): Color {
    // Reduce chroma until the sRGB cube contains the colour. Twelve bisection
    // steps are well below one 8-bit step for every practical seed.
    var low = 0.0
    var high = chroma.coerceAtLeast(0.0)
    var best = oklchToLinear(lightness, 0.0, hue)
    repeat(14) {
        val mid = (low + high) / 2.0
        val candidate = oklchToLinear(lightness, mid, hue)
        if (candidate.isInGamut()) {
            best = candidate
            low = mid
        } else {
            high = mid
        }
    }
    return best.toSrgbColor()
}

private data class LinearRgb(val r: Double, val g: Double, val b: Double) {
    fun isInGamut(): Boolean =
        r in -0.000_1..1.000_1 && g in -0.000_1..1.000_1 && b in -0.000_1..1.000_1

    fun toSrgbColor(): Color {
        fun encode(channel: Double): Float {
            val value = channel.coerceIn(0.0, 1.0)
            val srgb = if (value <= 0.003_130_8) 12.92 * value else 1.055 * value.pow(1.0 / 2.4) - 0.055
            return srgb.toFloat()
        }
        return Color(encode(r), encode(g), encode(b))
    }
}

private fun oklchToLinear(lightness: Double, chroma: Double, hue: Double): LinearRgb {
    val radians = hue * kotlin.math.PI / 180.0
    val a = chroma * kotlin.math.cos(radians)
    val b = chroma * kotlin.math.sin(radians)

    val lRoot = lightness + 0.396_337_777_4 * a + 0.215_803_757_3 * b
    val mRoot = lightness - 0.105_561_345_8 * a - 0.063_854_172_8 * b
    val sRoot = lightness - 0.089_484_177_5 * a - 1.291_485_548_0 * b

    val l = lRoot * lRoot * lRoot
    val m = mRoot * mRoot * mRoot
    val s = sRoot * sRoot * sRoot

    return LinearRgb(
        r = 4.076_741_662_1 * l - 3.307_711_591_3 * m + 0.230_969_929_2 * s,
        g = -1.268_437_510_9 * l + 2.609_757_401_1 * m - 0.341_319_397_1 * s,
        b = -0.004_196_086_3 * l - 0.703_418_614_7 * m + 1.707_614_701_0 * s,
    )
}

/**
 * Presets offered on the appearance page. Values are stable wire strings so a
 * future palette can add colours without migrating existing configs.
 */
val AccentPresets: List<Color> = listOf(
    Color(0xFF5EC8A0), // Fograin green
    Color(0xFF6FA8FF), // rain blue
    Color(0xFFB48CFF), // iris
    Color(0xFFF0A85E), // amber
    Color(0xFFFF8FA3), // sakura
    Color(0xFF8FD14F), // moss
    Color(0xFFE6C860), // sand
    Color(0xFF9BA6B2), // graphite
)

/** True when two seeds are close enough to disable a selected swatch. */
internal fun Color.isNear(other: Color): Boolean =
    abs(red - other.red) < 0.01f && abs(green - other.green) < 0.01f && abs(blue - other.blue) < 0.01f

/** Guards against a malformed accent hex ever reaching the UI. */
internal fun sanitizeAccent(accent: String): Color? =
    if (accent.equals("auto", ignoreCase = true)) null else parseHexColor(accent)

/** Fallback used when a custom accent string is not parseable. */
const val DefaultAccentHex: String = "#5EC8A0"

internal fun chromaOf(color: Color): Double = color.toOklch().chroma

internal fun distanceTo(seed: Color, color: Color): Double {
    val left = seed.toOklch()
    val right = color.toOklch()
    return sqrt((left.lightness - right.lightness).pow(2) + (left.chroma - right.chroma).pow(2))
}
