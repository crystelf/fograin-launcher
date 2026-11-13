package com.fograin.launcher.desktop

import java.nio.file.Files

/**
 * One data root for every JNI test in this module.
 *
 * `JvmCoreClient` is a process singleton and the Rust service pins the first
 * data root it sees, so each test must not install its own directory.
 */
object TestDataRoot {
    val path: String by lazy {
        Files.createTempDirectory("fograin-jni-test").toFile().absolutePath
    }
}
