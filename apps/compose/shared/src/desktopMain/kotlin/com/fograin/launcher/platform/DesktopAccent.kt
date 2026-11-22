package com.fograin.launcher.platform

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort read of the desktop accent colour.
 *
 * macOS exposes it through `AppleAccentColor`, Windows through the DWM
 * colorization value and GNOME 47+ through `org.gnome.desktop.interface
 * accent-color`. Every command is bounded to 700 ms and any failure returns
 * `null`, which lets the theme fall back to the Fograin palette.
 *
 * The probe runs on a daemon thread, not on the Compose/EDT thread: the theme
 * paints its fallback palette immediately and recomposes when [colorState]
 * receives the value. This removes a subprocess wait from the startup path
 * without forcing the launcher to start with the wrong accent.
 */
internal object DesktopAccent {
    private val detected = mutableStateOf<Color?>(null)
    private val started = AtomicBoolean(false)

    /** Compose-observable accent; `null` until the probe finishes. */
    val colorState: State<Color?>
        get() {
            start()
            return detected
        }

    fun color(): Color? = colorState.value

    /** Starts the probe without waiting for it. Idempotent. */
    fun prefetch() {
        start()
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) return
        val probe = Thread(
            {
                // Run the (bounded) subprocess outside the snapshot, then
                // publish the result in a short mutable snapshot so the next
                // recomposition sees it without holding a snapshot open.
                val color = detect()
                Snapshot.withMutableSnapshot {
                    detected.value = color
                }
            },
            "fograin-desktop-accent",
        )
        probe.isDaemon = true
        probe.start()
    }

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
        // Read the DWM value in-process via JNA. Spawning `reg.exe` from the
        // GUI launcher flashed a console window on every start; the registry
        // read is a few microseconds and cannot surprise the user.
        val value = runCatching {
            com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(
                com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\DWM",
                "ColorizationColor",
            )
        }.getOrNull() ?: return WINDOWS_BLUE

        return colorizationColor(value) ?: WINDOWS_BLUE
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

    /**
     * Extracts the RGB part of DWM's `ColorizationColor` DWORD.
     *
     * The value is AARRGGBB. A zero RGB triplet means "automatic colorization";
     * callers substitute the documented Windows accent instead of black.
     */
    internal fun colorizationColor(value: Int): Color? {
        val argb = value.toLong() and 0xFFFF_FFFFL
        val red = ((argb shr 16) and 0xFF).toInt()
        val green = ((argb shr 8) and 0xFF).toInt()
        val blue = (argb and 0xFF).toInt()
        if (red == 0 && green == 0 && blue == 0) return null
        return Color(red, green, blue)
    }

    private val MAC_BLUE = Color(0xFF0A84FF)
    private val WINDOWS_BLUE = Color(0xFF60CDFF)
    private val GNOME_BLUE = Color(0xFF3584E4)
}
