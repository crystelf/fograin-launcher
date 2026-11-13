package com.fograin.launcher.desktop

import com.fograin.launcher.platform.AppRuntime
import com.fograin.launcher.platform.createCoreClient
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smoke test: load the desktop JNI library from classpath resources and run
 * one real RPC through the Rust service. Uses a temporary data root so the
 * developer's launcher.toml is never touched.
 */
class CoreClientSmokeTest {
    @Test
    fun snapshotLoadsThroughJni() = runBlocking {
        AppRuntime.dataRoot = Files.createTempDirectory("fograin-smoke").toFile().absolutePath
        val core = createCoreClient()

        assertNull(core.initError, "native library / settings snapshot failed")

        val snapshot = core.appSnapshot()
        val platform = snapshot["platform"]?.jsonPrimitive?.contentOrNull
        assertNotNull(platform)
        assertTrue(platform.isNotBlank(), "platform must not be blank")

        val instances = snapshot["instanceCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        assertNotNull(instances, "instanceCount missing from app.snapshot")
        assertTrue(instances >= 0)
    }
}
