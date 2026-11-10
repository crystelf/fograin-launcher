package com.fograin.launcher.platform

/**
 * JNI entry points implemented by `bindings/fg-ffi-jni`.
 *
 * Every method returns a JSON string in the same envelope as the C ABI, so the
 * Kotlin models in [CoreWire] and the Swift models in `CoreWire.swift` decode
 * the same contract. Do not add UI types here.
 */
internal object CoreNative {
    init {
        System.loadLibrary("fg_ffi_jni")
    }

    external fun nativeAbiVersion(): Int

    external fun nativeSetDataRoot(path: String): String

    external fun nativeSettingsSnapshot(): String

    external fun nativeSetTheme(theme: String): String

    external fun nativeServiceCurrent(): String

    external fun nativeRpcCall(request: String): String

    external fun nativeEventsNext(): String
}
