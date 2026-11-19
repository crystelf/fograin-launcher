package com.fograin.launcher.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Process-wide values the platform shell installs before the first core call.
 *
 * Android sets [dataRoot] to its app-private directory; desktop leaves it null
 * so the Rust core resolves `$HOME` (or `FOGRAIN_HOME`). Tests point it at a
 * temporary directory.
 */
object AppRuntime {
    var dataRoot: String? = null
}

/**
 * Saved desktop window geometry. Android has no movable window, so its
 * `LocalPreferences.windowFrame` stays `null`.
 */
data class WindowFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean = false,
)

// ---------------------------------------------------------------------------
// The platform interfaces the shared UI depends on. `commonMain` never
// references Android or `java.*` types directly; every capability below has an
// `androidMain` and a `desktopMain` implementation.
// ---------------------------------------------------------------------------

/** 1. Loads `libfg_ffi_jni` for the current platform. Must be idempotent. */
interface NativeLibrary {
    fun ensureLoaded()
}

/** 2. Creates the process-wide JNI-backed [CoreClient]. */
interface CoreClientFactory {
    fun create(): CoreClient
}

/** 3. Host information the shell can show without waiting for `app.snapshot`. */
interface DeviceInfoProvider {
    val totalMemoryBytes: Long
    val deviceText: String
    val socText: String
}

/** 4. Plain-text clipboard access (instance paths, diagnostics). */
interface ClipboardService {
    fun copy(text: String)
}

/**
 * 5. Preferences the core does not own yet. Material You dynamic color is an
 * Android-only presentation choice, so it stays local until the settings
 * surface lands in M10.
 */
interface LocalPreferences {
    var dynamicColor: Boolean
    fun supportsDynamicColor(): Boolean

    /** Desktop window geometry; `null` on Android and until the user moves a window. */
    var windowFrame: WindowFrame?
}

/** A directory row returned by [DirectoryBrowser]. Paths stay absolute strings. */
data class DirectoryEntry(val name: String, val path: String)

/**
 * 6. Directory access for `instances.import`: a native picker plus the listing
 * used by the in-app fallback browser.
 *
 * Desktop opens the OS file chooser; Android opens SAF's directory tree picker
 * and maps the chosen tree back to a filesystem path the Rust core can read
 * (SAF content URIs never reach the core). A tree the platform cannot map, or
 * one the app cannot read, reports [DirectoryPick.Unavailable] so the caller
 * can fall back to [listDirectories].
 */
interface DirectoryBrowser {
    val isAvailable: Boolean

    suspend fun pick(title: String, initialPath: String?): DirectoryPick

    fun listDirectories(path: String): List<DirectoryEntry>

    fun parentDirectory(path: String): String?
}

/** Outcome of [FileBrowser.pick]. */
sealed interface FilePick {
    /** Absolute path of a readable file. */
    data class Picked(val path: String) : FilePick

    /** The user dismissed the picker; nothing should happen. */
    data object Cancelled : FilePick

    /** No native picker, or a document the platform cannot copy to a path. */
    data class Unavailable(val reason: String) : FilePick
}

/**
 * 6b. Single-file picking for `.mrpack`/`.zip` imports and data packs.
 *
 * The Rust core only reads filesystem paths, so Android copies the selected
 * document into the app cache before returning it; desktop returns the native
 * path untouched. [extensions] are lower-case without the dot and only drive
 * the filter, never validation.
 */
interface FileBrowser {
    val isAvailable: Boolean

    suspend fun pick(title: String, extensions: List<String>): FilePick
}

/** Outcome of [DirectoryPicker.pick]. */
sealed interface DirectoryPick {
    /** Absolute path of a readable directory. */
    data class Picked(val path: String) : DirectoryPick

    /** The user dismissed the picker; nothing should happen. */
    data object Cancelled : DirectoryPick

    /** No native picker, or a tree that cannot be mapped to a readable path. */
    data class Unavailable(val reason: String) : DirectoryPick
}

/** 7. Platform logging facade (`android.util.Log` / stdout). */
interface AppLogger {
    fun debug(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null)
}

/**
 * 8. Window appearance: Material You dynamic schemes and the status/navigation
 * bar icon contrast. Both are no-ops (or `null`) where the OS has no concept.
 */
interface SystemAppearance {
    val supportsDynamicColor: Boolean

    /** `null` when dynamic color is unavailable; the Fograin palette is used. */
    @Composable
    fun dynamicColorScheme(darkTheme: Boolean): ColorScheme?

    /** Applies light/dark system bar icons when the in-app theme changes. */
    @Composable
    fun applySystemBars(darkTheme: Boolean)
}

/** 9. Back gesture/escape handling. Desktop installs a no-op until R4. */
interface BackDispatcher {
    @Composable
    fun handleBack(enabled: Boolean, onBack: () -> Unit)
}

/** Everything the shared UI needs from the host, provided once per process. */
data class PlatformServices(
    val platformName: String,
    val nativeLibrary: NativeLibrary,
    val coreFactory: CoreClientFactory,
    val deviceInfo: DeviceInfoProvider,
    val clipboard: ClipboardService,
    val preferences: LocalPreferences,
    val directories: DirectoryBrowser,
    val files: FileBrowser,
    val logger: AppLogger,
    val appearance: SystemAppearance,
    val backDispatcher: BackDispatcher,
)

/**
 * Returns the process-wide services. Android shells must call
 * `AndroidPlatform.install(context)` first; desktop builds it lazily.
 */
expect fun platformServices(): PlatformServices

/**
 * Loads the native bridge. Kept as a primitive so `JvmCoreClient` can load the
 * library during construction without depending on the full services graph.
 */
internal expect fun ensureNativeLibraryLoaded()
