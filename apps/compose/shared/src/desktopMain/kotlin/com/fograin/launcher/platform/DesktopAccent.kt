package com.fograin.launcher.platform

import androidx.compose.ui.graphics.Color
import java.util.concurrent.TimeUnit

/**
 * Best-effort read of the desktop accent colour.
 *
 * macOS exposes it through `AppleAccentColor`, Windows through the DWM
 * colorization value and GNOME 47+ through `org.gnome.desktop.interface
 * accent-color`. Every command is bounded to 700 ms and any failure returns
 * `null`, which lets the theme fall back to the Fograin palette. The value is
 * cached for the process lifetime: the launcher only re-reads it on restart.
 */
internal object DesktopAccent {
    private val cached: Color? by lazy { detect() }

    fun color(): Color? = cached

    private fun detect(): Color? = when {
        isMac() -> macAccent()
        isWindows() -> windowsAccent()
        else -> gnomeAccent()
    }

    private fun isMac(): Boolean =
        System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

    private fun macAccent(): Color? {
        val raw = runCommand(listOf("defaults", "read", "-g", "AppleAccentColor"))
            ?.trim()
            ?.trim('"')
            ?: return MAC_BLUE
        val index = raw.toIntOrNull() ?: return MAC_BLUE
        return when (index) {
            -1 -> Color(0xFF8E8E93)
            0 -> Color(0xFFFF453A)
            1 -> Color(0xFFFF9F0A)
            2 -> Color(0xFFFFD60A)
            3 -> Color(0xFF30D158)
            4 -> MAC_BLUE
            5 -> Color(0xFFBF5AF2)
            6 -> Color(0xFFFF375F)
            else -> MAC_BLUE
        }
    }

    private fun windowsAccent(): Color? {
        val output = runCommand(
            listOf(
                "reg",
                "query",
                "HKCU\\Software\\Microsoft\\Windows\\DWM",
                "/v",
                "ColorizationColor",
            ),
        ) ?: return WINDOWS_BLUE
        val token = output.split(Regex("\\s+"))
            .firstOrNull { it.startsWith("0x", ignoreCase = true) }
            ?: return WINDOWS_BLUE
        val value = token.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
            ?: return WINDOWS_BLUE
        val red = ((value shr 16) and 0xFF).toInt()
        val green = ((value shr 8) and 0xFF).toInt()
        val blue = (value and 0xFF).toInt()
        // A fully transparent colorization value means "automatic": keep the
        // documented Windows accent instead of painting black.
        if (red == 0 && green == 0 && blue == 0) return WINDOWS_BLUE
        return Color(red, green, blue)
    }

    private fun gnomeAccent(): Color? {
        val raw = runCommand(
            listOf("gsettings", "get", "org.gnome.desktop.interface", "accent-color"),
        )?.trim()?.trim('\'')?.trim('"')
        return when (raw?.lowercase()) {
            "blue" -> Color(0xFF3584E4)
            "teal" -> Color(0xFF2190A4)
            "green" -> Color(0xFF3A944A)
            "yellow" -> Color(0xFFC88800)
            "orange" -> Color(0xFFED5B00)
            "red" -> Color(0xFFE62D42)
            "pink" -> Color(0xFFD56199)
            "purple" -> Color(0xFF9141AC)
            "slate" -> Color(0xFF6F8396)
            else -> GNOME_BLUE
        }
    }

    private fun runCommand(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(700, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
    }.getOrNull()

    private val MAC_BLUE = Color(0xFF0A84FF)
    private val WINDOWS_BLUE = Color(0xFF60CDFF)
    private val GNOME_BLUE = Color(0xFF3584E4)
}
