package com.fograin.launcher.platform

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Android entry point for the shared platform layer.
 *
 * The activity installs its application context before `createCoreClient()`;
 * every service below reads it lazily so a unit test without a context still
 * gets safe fallbacks instead of a crash.
 */
object AndroidPlatform {
    private const val PREFERENCES_FILE = "fograin.appearance"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var preferences: android.content.SharedPreferences? = null

    /** Must run before the first core call (MainActivity does this in onCreate). */
    fun install(context: Context) {
        appContext = context.applicationContext
        preferences = null
    }

    // ---------------------------------------------------------------------
    // SAF directory picker bridge. MainActivity owns the ActivityResultLauncher
    // (registered at construction time, as the Activity Result API requires) and
    // publishes a `launch` lambda plus a result sink here; the shared picker
    // only sees these two functions.
    // ---------------------------------------------------------------------

    @Volatile
    internal var treePickerLauncher: (() -> Unit)? = null

    @Volatile
    private var pendingPick: ((Uri?) -> Unit)? = null

    fun attachTreePicker(launch: () -> Unit) {
        treePickerLauncher = launch
    }

    fun onTreePicked(uri: Uri?) {
        val sink = pendingPick
        pendingPick = null
        sink?.invoke(uri)
    }

    internal fun setPendingPick(sink: (Uri?) -> Unit) {
        pendingPick = sink
    }

    // ---------------------------------------------------------------------
    // SAF single-file picker (`.mrpack`, `.zip`). The shared layer copies the
    // document into the cache so the Rust core gets a real path.
    // ---------------------------------------------------------------------

    @Volatile
    internal var filePickerLauncher: ((Array<String>) -> Unit)? = null

    @Volatile
    private var pendingFilePick: ((Uri?) -> Unit)? = null

    // ---------------------------------------------------------------------
    // SAF save dialog (`CreateDocument`). The result is a content URI; the
    // shared layer writes the exported bytes through it.
    // ---------------------------------------------------------------------

    @Volatile
    internal var savePickerLauncher: ((String) -> Unit)? = null

    @Volatile
    private var pendingSavePick: ((Uri?) -> Unit)? = null

    fun attachSavePicker(launch: (String) -> Unit) {
        savePickerLauncher = launch
    }

    fun onSavePicked(uri: Uri?) {
        val sink = pendingSavePick
        pendingSavePick = null
        sink?.invoke(uri)
    }

    internal fun setPendingSavePick(sink: (Uri?) -> Unit) {
        pendingSavePick = sink
    }

    fun attachFilePicker(launch: (Array<String>) -> Unit) {
        filePickerLauncher = launch
    }

    fun onFilePicked(uri: Uri?) {
        val sink = pendingFilePick
        pendingFilePick = null
        sink?.invoke(uri)
    }

    internal fun setPendingFilePick(sink: (Uri?) -> Unit) {
        pendingFilePick = sink
    }

    internal fun contextOrNull(): Context? = appContext

    internal fun sharedPreferences(): android.content.SharedPreferences? {
        val context = appContext ?: return null
        return preferences ?: context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .also { preferences = it }
    }

    /** Data folder chosen in settings, applied before the core is created. */
    fun installedDataRootOverride(): String? =
        sharedPreferences()?.getString(KEY_DATA_ROOT, null)?.takeIf { it.isNotBlank() }

    internal fun supportSocModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }
}

actual fun platformServices(): PlatformServices = androidServices

private val androidServices: PlatformServices by lazy {
    PlatformServices(
        platformName = "android",
        nativeLibrary = object : NativeLibrary {
            override fun ensureLoaded() = ensureNativeLibraryLoaded()
        },
        coreFactory = JvmCoreClientFactory,
        deviceInfo = object : DeviceInfoProvider {
            override val totalMemoryBytes: Long
                get() {
                    val context = AndroidPlatform.contextOrNull() ?: return 0
                    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                        ?: return 0
                    val info = ActivityManager.MemoryInfo()
                    manager.getMemoryInfo(info)
                    return info.totalMem
                }

            override val deviceText: String
                get() = listOf(Build.MANUFACTURER, Build.MODEL)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "Android" }

            override val socText: String
                get() = AndroidPlatform.supportSocModel()

            override val cpuCount: Int
                get() = Runtime.getRuntime().availableProcessors()
        },
        clipboard = object : ClipboardService {
            override fun copy(text: String) {
                val context = AndroidPlatform.contextOrNull() ?: return
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return
                manager.setPrimaryClip(ClipData.newPlainText("Fograin", text))
            }
        },
        preferences = object : LocalPreferences {
            override fun supportsDynamicColor(): Boolean = supportsMaterialYou()

            // Android windows are not resizable in the desktop sense.
            override var windowFrame: WindowFrame?
                get() = null
                set(_) = Unit

            override var dataRootOverride: String?
                get() = AndroidPlatform.sharedPreferences()
                    ?.getString(KEY_DATA_ROOT, null)
                    ?.takeIf { it.isNotBlank() }
                set(value) {
                    AndroidPlatform.sharedPreferences()?.edit()?.let { editor ->
                        if (value.isNullOrBlank()) {
                            editor.remove(KEY_DATA_ROOT)
                        } else {
                            editor.putString(KEY_DATA_ROOT, value)
                        }
                        editor.apply()
                    }
                }

            override var settingsSidebarWidth: Float
                get() = AndroidPlatform.sharedPreferences()
                    ?.getFloat(KEY_SETTINGS_SIDEBAR_WIDTH, 0f) ?: 0f
                set(value) {
                    AndroidPlatform.sharedPreferences()?.edit()
                        ?.putFloat(KEY_SETTINGS_SIDEBAR_WIDTH, value)?.apply()
                }
        },
        directories = AndroidDirectoryPicker,
        files = AndroidFilePicker,
        logger = object : AppLogger {
            override fun debug(tag: String, message: String) {
                android.util.Log.d(tag, message)
            }

            override fun warn(tag: String, message: String, error: Throwable?) {
                android.util.Log.w(tag, message, error)
            }

            override fun error(tag: String, message: String, error: Throwable?) {
                android.util.Log.e(tag, message, error)
            }
        },
        appearance = object : SystemAppearance {
            override val supportsDynamicColor: Boolean
                get() = supportsMaterialYou()

            @Composable
            override fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? {
                if (!supportsMaterialYou()) return null
                val context = LocalContext.current
                return if (darkTheme) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            }

            @Composable
            override fun applySystemBars(darkTheme: Boolean) {
                val view = LocalView.current
                if (view.isInEditMode) return
                val context = LocalContext.current
                SideEffect {
                    val window = context.findActivity()?.window ?: return@SideEffect
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            }
        },
        backDispatcher = object : BackDispatcher {
            @Composable
            override fun handleBack(enabled: Boolean, onBack: () -> Unit) {
                BackHandler(enabled = enabled, onBack = onBack)
            }
        },
        updates = JvmUpdateService,
        links = AndroidSystemLinks,
    )
}

private const val KEY_DATA_ROOT = "storage.dataRoot"
private const val KEY_SETTINGS_SIDEBAR_WIDTH = "settings.sidebarWidth"

private fun supportsMaterialYou(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
