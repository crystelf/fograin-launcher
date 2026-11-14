package com.fograin.launcher.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
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

            override var dynamicColor: Boolean
                get() = node.getBoolean(KEY_DYNAMIC_COLOR, false)
                set(value) {
                    node.putBoolean(KEY_DYNAMIC_COLOR, value)
                }

            override fun supportsDynamicColor(): Boolean = false

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
        },
        directories = DesktopDirectoryPicker,
        logger = JvmLogger,
        appearance = object : SystemAppearance {
            override val supportsDynamicColor: Boolean = false

            @Composable
            override fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

            @Composable
            override fun applySystemBars(darkTheme: Boolean) = Unit
        },
        backDispatcher = DesktopBackDispatcher,
    )
}

private const val KEY_DYNAMIC_COLOR = "appearance.dynamicColor"
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
        os.contains("win") -> "win11"
        else -> "gnome"
    }
}

private fun totalPhysicalMemory(): Long = runCatching {
    val bean = ManagementFactory.getOperatingSystemMXBean()
    (bean as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize
}.getOrNull()?.takeIf { it > 0 } ?: Runtime.getRuntime().maxMemory()
