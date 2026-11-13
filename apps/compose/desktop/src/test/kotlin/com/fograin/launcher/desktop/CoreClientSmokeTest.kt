package com.fograin.launcher.desktop

import com.fograin.launcher.platform.AppRuntime
import com.fograin.launcher.platform.CoreInstanceRow
import com.fograin.launcher.platform.decode
import com.fograin.launcher.platform.createCoreClient
import com.fograin.launcher.platform.resultRows
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smoke tests: load the desktop JNI library from classpath resources and run
 * real RPCs through the Rust service. Uses a temporary data root so the
 * developer's launcher.toml is never touched.
 */
class CoreClientSmokeTest {
    @Test
    fun snapshotLoadsThroughJni() = runBlocking {
        AppRuntime.dataRoot = TestDataRoot.path
        val core = createCoreClient()

        assertNull(core.initError, "native library / settings snapshot failed")
        assertTrue(core.isAvailable)

        val snapshot = core.rpc("app.snapshot")
        val platform = snapshot["platform"]?.jsonPrimitive?.contentOrNull
        assertNotNull(platform)
        assertTrue(platform.isNotBlank(), "platform must not be blank")

        val instances = snapshot["instanceCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        assertNotNull(instances, "instanceCount missing from app.snapshot")
        assertTrue(instances >= 0)
    }

    @Test
    fun instancesListRoundTripsThroughJni() = runBlocking {
        AppRuntime.dataRoot = TestDataRoot.path
        val core = createCoreClient()

        val before = core.rpc("instances.list").resultRows()
        if (before.none { it["id"]?.jsonPrimitive?.contentOrNull == "r1-desktop" }) {
            core.rpc(
                "instances.create",
                buildJsonObject {
                    put("id", "r1-desktop")
                    put("name", "R1 桌面验证")
                    put("minecraft", "1.21.4")
                },
            )
        }

        val rows = core.rpc("instances.list")
            .resultRows()
            .map { it.decode<CoreInstanceRow>() }
        val row = rows.firstOrNull { it.id == "r1-desktop" }
        assertNotNull(row, "desktop core did not list the created instance")
        assertTrue(row.minecraft == "1.21.4")
    }
}
