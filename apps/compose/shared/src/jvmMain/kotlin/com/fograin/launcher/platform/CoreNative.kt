package com.fograin.launcher.platform

/**
 * JNI entry points implemented by `bindings/fg-ffi-jni`.
 *
 * The package and object name are part of the ABI: Rust exports
 * `Java_com_fograin_launcher_platform_CoreNative_*`. Do not rename.
 */
internal object CoreNative {
    init {
        ensureNativeLibraryLoaded()
    }

    external fun nativeAbiVersion(): Int

    external fun nativeSetDataRoot(path: String): String?

    external fun nativeSettingsSnapshot(): String?

    external fun nativeSetTheme(theme: String): String?

    external fun nativeServiceCurrent(): String?

    external fun nativeRpcCall(request: String): String?

    external fun nativeEventsNext(): String?
}
