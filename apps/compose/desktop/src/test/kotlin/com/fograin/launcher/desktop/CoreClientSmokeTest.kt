package com.fograin.launcher.desktop

import com.fograin.launcher.platform.AppRuntime
import com.fograin.launcher.platform.CoreAccount
import com.fograin.launcher.platform.CoreContentLocal
import com.fograin.launcher.platform.CoreInstanceRow
import com.fograin.launcher.platform.CoreDatapacks
import com.fograin.launcher.platform.CoreDiagExport
import com.fograin.launcher.platform.CoreDiagnostics
import com.fograin.launcher.platform.CoreModpack
import com.fograin.launcher.platform.CoreWorlds
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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    fun launcherSettingsRoundTripThroughJni() = runBlocking {
        AppRuntime.dataRoot = TestDataRoot.path
        val core = createCoreClient()

        val initial = core.rpc("settings.get")
        assertEquals("system", initial["language"]?.jsonPrimitive?.contentOrNull)
        assertEquals("auto", initial["accent"]?.jsonPrimitive?.contentOrNull)
        assertEquals("home", initial["initialPage"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(initial["download"]?.jsonObject, "settings.get must return [download]")
        assertNotNull(initial["network"]?.jsonObject, "settings.get must return [network]")

        val updated = core.rpc(
            "settings.set",
            buildJsonObject {
                put("language", "ja")
                put("accent", "#336699")
                put("initialPage", "downloads")
                put(
                    "download",
                    buildJsonObject {
                        put("source", "mirror")
                        put("concurrency", 12)
                    },
                )
                put(
                    "network",
                    buildJsonObject {
                        put("proxy", "none")
                        put("timeoutSecs", 30)
                    },
                )
            },
        )
        assertEquals("ja", updated["settings"]?.jsonObject?.get("language")?.jsonPrimitive?.contentOrNull)
        assertEquals("#336699", updated["settings"]?.jsonObject?.get("accent")?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "downloads",
            updated["snapshot"]?.jsonObject?.get("initialPage")?.jsonPrimitive?.contentOrNull,
        )

        val read = core.rpc("settings.get")
        assertEquals("mirror", read["download"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull)
        assertEquals(12, read["download"]?.jsonObject?.get("concurrency")?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals("none", read["network"]?.jsonObject?.get("proxy")?.jsonPrimitive?.contentOrNull)

        // Restore defaults so other JNI tests see a predictable config.
        core.rpc(
            "settings.set",
            buildJsonObject {
                put("language", "system")
                put("accent", "auto")
                put("initialPage", "home")
                put("download", buildJsonObject { put("source", "auto"); put("concurrency", 0) })
                put("network", buildJsonObject { put("proxy", "auto"); put("timeoutSecs", 10) })
            },
        )
        Unit
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
    fun contentAndWorldsRoundTripThroughJni() = runBlocking {
        AppRuntime.dataRoot = TestDataRoot.path
        val core = createCoreClient()
        val id = "m8-jni-${System.nanoTime()}"
        core.rpc(
            "instances.create",
            buildJsonObject {
                put("id", id)
                put("name", "M8 JNI")
                put("minecraft", "1.21.4")
                put("loaderKind", "fabric")
            },
        )

        val gameDir = File(TestDataRoot.path, "instances/$id/.minecraft")
        val pack = File(gameDir, "resourcepacks/M8 Pack")
        assertTrue(pack.mkdirs())
        File(pack, "pack.mcmeta").writeText(
            """{"pack":{"pack_format":34,"description":"JNI pack"}}""",
        )

        val local = core.rpc(
            "content.local",
            buildJsonObject { put("instanceId", id) },
        ).decode<CoreContentLocal>()
        assertEquals(1, local.counts.resourcepack)
        assertEquals("M8 Pack", local.items.first().title)
        assertEquals(34, local.items.first().packFormat)

        val toggled = core.rpc(
            "content.toggle",
            buildJsonObject {
                put("instanceId", id)
                put("kind", "resourcepack")
                put("fileName", "M8 Pack")
                put("enabled", false)
            },
        )
        assertEquals("M8 Pack.disabled", toggled["fileName"]?.jsonPrimitive?.contentOrNull)
        assertTrue(File(gameDir, "resourcepacks/M8 Pack.disabled").isDirectory)

        val world = File(gameDir, "saves/New World")
        assertTrue(world.mkdirs())
        File(world, "level.dat").writeText("level")
        val worlds = core.rpc(
            "worlds.list",
            buildJsonObject { put("instanceId", id) },
        ).decode<CoreWorlds>()
        assertEquals("New World", worlds.worlds.first().name)

        val backup = core.rpc(
            "worlds.backup",
            buildJsonObject {
                put("instanceId", id)
                put("name", "New World")
            },
        )
        assertNotNull(backup["backup"]?.jsonObject, "worlds.backup must return a backup row")
        assertEquals(1, backup["backups"]?.jsonArray?.size)
    }

    @Test
    fun m9RpcSurfaceRoundTripsThroughJni() = runBlocking {
        AppRuntime.dataRoot = TestDataRoot.path
        val core = createCoreClient()
        val id = "m9-jni-${System.nanoTime()}"
        core.rpc(
            "instances.create",
            buildJsonObject {
                put("id", id)
                put("name", "M9 JNI")
                put("minecraft", "1.21.4")
                put("loaderKind", "fabric")
            },
        )
        val gameDir = File(TestDataRoot.path, "instances/$id/.minecraft")

        // Data packs: a real zip with pack.mcmeta.
        val datapacks = File(gameDir, "saves/World/datapacks")
        assertTrue(datapacks.mkdirs())
        ZipOutputStream(File(datapacks, "terralith.zip").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.mcmeta"))
            zip.write("""{"pack":{"pack_format":48,"description":"Terralith"}}""".toByteArray())
            zip.closeEntry()
        }
        val packs = core.rpc(
            "datapacks.list",
            buildJsonObject {
                put("instanceId", id)
                put("world", "World")
            },
        ).decode<CoreDatapacks>()
        assertEquals(1, packs.items.size)
        assertEquals("Terralith", packs.items.first().title)

        // Diagnostics: analysis finds the memory crash and export writes a zip.
        File(gameDir, "logs").mkdirs()
        File(gameDir, "logs/latest.log").writeText(
            "[13:20:07] [Render thread/ERROR]: java.lang.OutOfMemoryError: Java heap space\n",
        )
        val analysis = core.rpc(
            "diag.analyze",
            buildJsonObject { put("instanceId", id) },
        ).decode<CoreDiagnostics>()
        assertTrue(
            analysis.findings.any { it.id == "java.memory" },
            "diag.analyze did not match the heap error: ${analysis.summary}",
        )
        val exported = core.rpc(
            "diag.export",
            buildJsonObject { put("instanceId", id) },
        ).decode<CoreDiagExport>()
        assertTrue(File(exported.bundle.path).isFile, "diagnostic bundle was not written")

        // Modpacks: inspect a minimal .mrpack.
        val mrpack = File(TestDataRoot.path, "$id.mrpack")
        ZipOutputStream(mrpack.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("modrinth.index.json"))
            zip.write(
                """
                {"formatVersion":1,"name":"Calm","versionId":"1.0",
                 "files":[{"path":"mods/a.jar","fileSize":10,
                           "hashes":{"sha1":"aa"},"downloads":["https://example.invalid/a.jar"]}],
                 "dependencies":{"minecraft":"1.21.4","fabric-loader":"0.16.9"}}
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        val pack = core.rpc(
            "modpack.inspect",
            buildJsonObject { put("path", mrpack.absolutePath) },
        ).decode<CoreModpack>()
        assertEquals("modrinth", pack.kind)
        assertEquals("Calm", pack.name)
        assertEquals("fabric", pack.loaderKind)
        assertEquals(1, pack.files.size)
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
