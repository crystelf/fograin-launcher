package com.fograin.launcher.desktop

import com.fograin.launcher.platform.AppRuntime
import com.fograin.launcher.platform.CoreAccount
import com.fograin.launcher.platform.CoreInstanceRow
import com.fograin.launcher.platform.decode
import com.fograin.launcher.platform.createCoreClient
import com.fograin.launcher.platform.resultRows
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
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
    fun javaListRoundTripsThroughJni() = runBlocking {
        AppRuntime.dataRoot = TestDataRoot.path
        val core = createCoreClient()

        val listed = core.rpc("java.list")
        assertNotNull(
            listed["autoDownload"]?.jsonPrimitive?.booleanOrNull,
            "java.list must report [java] auto_download",
        )
        assertNotNull(
            listed["runtimes"]?.jsonArray,
            "java.list must return a runtimes array",
        )
        Unit
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
    @Test
    fun accountsRoundTripThroughJni() = runBlocking {
        AppRuntime.dataRoot = TestDataRoot.path
        val core = createCoreClient()

        val listed = core.rpc("accounts.list")
        val rows = listed["accounts"]?.jsonArray
        assertNotNull(rows, "accounts.list must return an accounts array")
        assertTrue(rows.isNotEmpty(), "the core must migrate a default offline account")
        assertNotNull(listed["selectedId"]?.jsonPrimitive?.contentOrNull)

        val added = core.rpc(
            "accounts.addOffline",
            buildJsonObject { put("name", "JniTester") },
        )
        val account = added["account"]?.jsonObject?.decode<CoreAccount>()
        assertNotNull(account, "accounts.addOffline must return the created row")
        assertEquals("JniTester", account.name)
        assertEquals("offline", account.kind)
        assertEquals(account.id, added["selectedId"]?.jsonPrimitive?.contentOrNull)

        val after = core.rpc("accounts.list")
        val names = after["accounts"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        assertTrue(names.contains("JniTester"))
    }

}
