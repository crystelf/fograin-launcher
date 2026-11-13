package com.fograin.launcher.platform

import java.io.File

/** Directory browsing shared by the Android and desktop targets. */
internal class JvmDirectoryBrowser : DirectoryBrowser {
    override fun listDirectories(path: String): List<DirectoryEntry> = runCatching {
        File(path).listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.map { DirectoryEntry(name = it.name, path = it.absolutePath) }
            .orEmpty()
    }.getOrDefault(emptyList())

    override fun parentDirectory(path: String): String? =
        runCatching { File(path).parentFile?.absolutePath }.getOrNull()
}

/** stdout-backed logger used by both JVM targets until a file sink lands. */
internal object JvmLogger : AppLogger {
    override fun debug(tag: String, message: String) = println("D/$tag: $message")

    override fun warn(tag: String, message: String, error: Throwable?) =
        println("W/$tag: $message${error?.let { " (${it.message ?: it::class.simpleName})" }.orEmpty()}")

    override fun error(tag: String, message: String, error: Throwable?) {
        println("E/$tag: $message")
        error?.printStackTrace()
    }
}

/** Factory shared by both JVM targets; the client itself is a lazy singleton. */
internal object JvmCoreClientFactory : CoreClientFactory {
    override fun create(): CoreClient = createJvmCoreClient()
}
