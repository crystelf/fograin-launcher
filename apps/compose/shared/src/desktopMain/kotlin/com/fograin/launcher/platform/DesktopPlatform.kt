package com.fograin.launcher.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.lang.management.ManagementFactory
import java.util.prefs.Preferences

/**
 * Desktop implementation of the shared platform layer.
 *
 * Window state, menus and FileKit land in R4; for now the shell only needs the
 * JNI loader, clipboard, logging, a preferences node and the data-root path
 * the Rust core resolves itself.
 */
actual fun platformServices(): PlatformServices = desktopServices

private val desktopServices: PlatformServices by lazy {
    PlatformServices(
        platformName = desktopPlatformName(),
        nativeLibrary = object : NativeLibrary {
            override fun ensureLoaded() = ensureNativeLibraryLoaded()
        },
        coreFactory = JvmCoreClientFactory,
        deviceInfo = object : DeviceInfoProvider {
            override val totalMemoryBytes: Long get() = totalPhysicalMemory()

            override val deviceText: String get() = desktopDeviceName()

            override val socText: String
                get() = System.getProperty("os.arch")

            override val cpuCount: Int
                get() = Runtime.getRuntime().availableProcessors()
        },
        clipboard = object : ClipboardService {
            override fun copy(text: String) {
                runCatching {
                    val selection = StringSelection(text)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                }
            }
        },
        preferences = object : LocalPreferences {
            private val node: Preferences by lazy {
                Preferences.userRoot().node("com/fograin/launcher")
            }

            override fun supportsDynamicColor(): Boolean = DesktopAccent.color() != null

            override var windowFrame: WindowFrame?
                get() = node.getInt(KEY_WINDOW_WIDTH, 0).takeIf { it > 0 }?.let { width ->
                    WindowFrame(
                        x = node.getInt(KEY_WINDOW_X, 0),
                        y = node.getInt(KEY_WINDOW_Y, 0),
                        width = width,
                        height = node.getInt(KEY_WINDOW_HEIGHT, 0),
                        maximized = node.getBoolean(KEY_WINDOW_MAXIMIZED, false),
                    )
                }
                set(value) {
                    if (value == null) {
                        node.remove(KEY_WINDOW_WIDTH)
                        node.remove(KEY_WINDOW_HEIGHT)
                        node.remove(KEY_WINDOW_X)
                        node.remove(KEY_WINDOW_Y)
                        node.remove(KEY_WINDOW_MAXIMIZED)
                        return
                    }
                    node.putInt(KEY_WINDOW_X, value.x)
                    node.putInt(KEY_WINDOW_Y, value.y)
                    node.putInt(KEY_WINDOW_WIDTH, value.width)
                    node.putInt(KEY_WINDOW_HEIGHT, value.height)
                    node.putBoolean(KEY_WINDOW_MAXIMIZED, value.maximized)
                }

            override var dataRootOverride: String?
                get() = node.get(KEY_DATA_ROOT, null)?.takeIf { it.isNotBlank() }
                set(value) {
                    if (value.isNullOrBlank()) {
                        node.remove(KEY_DATA_ROOT)
                    } else {
                        node.put(KEY_DATA_ROOT, value)
                    }
                }

            override var settingsSidebarWidth: Float
                get() = node.getFloat(KEY_SETTINGS_SIDEBAR_WIDTH, 0f)
                set(value) {
                    node.putFloat(KEY_SETTINGS_SIDEBAR_WIDTH, value)
                }
        },
        directories = DesktopDirectoryPicker,
        files = DesktopFilePicker,
        logger = JvmLogger,
        appearance = object : SystemAppearance {
            override val supportsDynamicColor: Boolean
                get() = DesktopAccent.color() != null

            @Composable
            override fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? {
                // Higher-contrast seeded scheme from the OS accent. `remember`
                // keeps the palette stable while the appearance page offers
                // the other options.
                val seed = remember { DesktopAccent.color() } ?: return null
                return remember(seed, darkTheme) {
                    com.fograin.launcher.ui.theme.seedColorScheme(seed, darkTheme)
                }
            }

            @Composable
            override fun applySystemBars(darkTheme: Boolean) = Unit
        },
        backDispatcher = DesktopBackDispatcher,
        links = DesktopSystemLinks,
    )
}

private const val KEY_DATA_ROOT = "storage.dataRoot"
private const val KEY_SETTINGS_SIDEBAR_WIDTH = "settings.sidebarWidth"
private const val KEY_WINDOW_X = "window.x"
private const val KEY_WINDOW_Y = "window.y"
private const val KEY_WINDOW_WIDTH = "window.width"
private const val KEY_WINDOW_HEIGHT = "window.height"
private const val KEY_WINDOW_MAXIMIZED = "window.maximized"

private fun desktopDeviceName(): String {
    val os = System.getProperty("os.name").orEmpty()
    val version = System.getProperty("os.version").orEmpty()
    val name = when {
        os.contains("mac", ignoreCase = true) -> "macOS"
        os.contains("win", ignoreCase = true) -> "Windows"
        else -> "Linux"
    }
    return if (version.isBlank()) name else "$name $version"
}

private fun desktopPlatformName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") -> "macos"
        os.contains("win") -> windowsPlatformName()
        else -> "gnome"
    }
}

/**
 * Windows 11 (build 22000+) draws rounded native window corners; Windows 10
 * and 7 do not. The shared `windowContentShape` uses this to decide whether
 * the content pane gets rounded left corners or stays rectangular.
 */
private fun windowsPlatformName(): String {
    if (System.getProperty("os.name").orEmpty().contains("11")) return "win11"
    val build = runCatching {
        val process = ProcessBuilder(
            "reg",
            "query",
            "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",
            "/v",
            "CurrentBuildNumber",
        )
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(700, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            null
        } else {
            process.inputStream.bufferedReader().use { it.readText() }
                .trim()
                .split(Regex("\\s+"))
                .lastOrNull()
                ?.toIntOrNull()
        }
    }.getOrNull()
    return if (build != null && build >= 22_000) "win11" else "win10"
}

private fun totalPhysicalMemory(): Long = runCatching {
    val bean = ManagementFactory.getOperatingSystemMXBean()
    (bean as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize
}.getOrNull()?.takeIf { it > 0 } ?: Runtime.getRuntime().maxMemory()
