package com.fograin.launcher.platform

private var loaded = false

@Synchronized
actual fun ensureNativeLibraryLoaded() {
    if (loaded) return
    System.loadLibrary("fg_ffi_jni")
    loaded = true
}

actual fun createCoreClient(): CoreClient = createJvmCoreClient()
