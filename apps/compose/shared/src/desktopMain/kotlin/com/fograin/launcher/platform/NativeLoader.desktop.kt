package com.fograin.launcher.platform

import java.io.File
import java.nio.file.Files

private var loaded = false

/**
 * Desktop has no `jniLibs` directory: the library is packed as a classpath
 * resource keyed by OS/arch. Extract it once to a temp directory and load the
 * absolute path, which works both under `gradle run` and inside a jpackage
 * app image.
 */
@Synchronized
actual fun ensureNativeLibraryLoaded() {
    if (loaded) return

    val triple = hostTriple()
    val fileName = when {
        triple.startsWith("darwin") -> "libfg_ffi_jni.dylib"
        triple.startsWith("windows") -> "fg_ffi_jni.dll"
        else -> "libfg_ffi_jni.so"
    }
    val resource = "/native/$triple/$fileName"
    val input = object {}.javaClass.getResourceAsStream(resource)
        ?: error("native library not found: $resource")

    val directory = Files.createTempDirectory("fograin-native").toFile()
    directory.deleteOnExit()
    val library = File(directory, fileName)
    input.use { stream ->
        library.outputStream().use { output -> stream.copyTo(output) }
    }
    library.deleteOnExit()
    System.load(library.absolutePath)
    loaded = true
}

private fun hostTriple(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (val value = System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        else -> value
    }
    return when {
        os.contains("mac") || os.contains("darwin") -> "darwin-$arch"
        os.contains("win") -> "windows-$arch"
        else -> "linux-$arch"
    }
}

actual fun createCoreClient(): CoreClient = createJvmCoreClient()
