package com.fograin.launcher.platform

/**
 * Loads `libfg_ffi_jni` for the current platform.
 *
 * Android resolves the library from the APK's `jniLibs`; the desktop target
 * extracts it from the classpath resources and calls `System.load`.
 * Implementations must be idempotent.
 */
expect fun ensureNativeLibraryLoaded()
