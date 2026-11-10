package com.fograin.launcher.platform

import com.fograin.launcher.model.Instance
import com.fograin.launcher.model.LoaderKind
import com.fograin.launcher.settings.ThemeMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreWireTest {
    @Test
    fun instanceRowParsesTheCamelCaseContract() {
        val json = JSONObject(
            """
            {
              "id": "fog-forest",
              "name": "雾林生存",
              "minecraft": "1.21.4",
              "loaderKind": "fabric",
              "loaderVersion": "0.16.9",
              "favorite": true,
              "installed": true,
              "modCount": 42,
              "sizeBytes": 3100000000,
              "playSeconds": 3600,
              "lastPlayedMs": 1800000000000
            }
            """.trimIndent(),
        )

        val row = CoreInstanceRow.from(json)!!
        assertEquals("fog-forest", row.id)
        assertEquals("fabric", row.loaderKind)
        assertTrue(row.installed)

        val instance = Instance.from(row)!!
        assertEquals(LoaderKind.FABRIC, instance.loader)
        assertEquals(42, instance.modCount)
        assertEquals(60, instance.playTimeMinutes)
        assertEquals("1.21.4 · Fabric · 42 模组", instance.meta)
        assertEquals("1.21.4 · Fabric 0.16.9", instance.versionLine)
        assertTrue(instance.isFavorite)
    }

    @Test
    fun optionalFieldsDecodeToNull() {
        val json = JSONObject(
            """{"id":"vanilla","name":"","minecraft":"1.21.4","loaderKind":"vanilla","loaderVersion":"","favorite":false,"installed":false,"modCount":null,"sizeBytes":0,"playSeconds":0,"lastPlayedMs":null}""",
        )

        val row = CoreInstanceRow.from(json)!!
        assertNull(row.modCount)
        assertNull(row.lastPlayedMs)
        val instance = Instance.from(row)!!
        assertEquals("vanilla", instance.name)
        assertNull(instance.playTimeMinutes)
        assertNull(instance.lastPlayedEpochMillis)
    }

    @Test
    fun unknownLoaderFallsBackToVanilla() {
        assertEquals(LoaderKind.VANILLA, LoaderKind.fromWire("mystery"))
        assertEquals(LoaderKind.VANILLA, LoaderKind.fromWire(null))
        assertEquals(LoaderKind.NEOFORGE, LoaderKind.fromWire("NEOFORGE"))
    }

    @Test
    fun planPreviewReadsTheMergedEnvelope() {
        val json = JSONObject(
            """{"ok":true,"versionId":"1.21.4","baseId":"1.21.4","javaMajor":21,"files":42,"totalBytes":310000000}""",
        )
        val plan = CorePlanPreview.from(json)!!
        assertEquals("1.21.4", plan.versionId)
        assertEquals(21, plan.javaMajor)
        assertEquals(42, plan.files)
    }

    @Test
    fun settingsSnapshotFallsBackToSystemForUnknownTheme() {
        val json = JSONObject(
            """{"abiVersion":1,"appVersion":"0.1.0","platform":"android","theme":"sepia","dataRoot":"/data","configPath":"/data/launcher.toml"}""",
        )
        val snapshot = CoreSettingsSnapshot.from(json)
        assertEquals(ThemeMode.SYSTEM, snapshot.theme)
        assertEquals("/data", snapshot.dataRoot)
        assertNull(snapshot.configError)
    }

    @Test
    fun errorPayloadKeepsTheMessageKeyAndDetail() {
        val json = JSONObject(
            """{"kind":"notFound","messageKey":"error.java.none_installed","detail":"no runtime"}""",
        )
        val payload = CoreErrorPayload.from(json)
        assertEquals("error.java.none_installed", payload.messageKey)
        assertEquals("no runtime", payload.detail)
        assertTrue(payload.userMessage.contains("Java"))
    }

    @Test
    fun eventParserHandlesEveryQueueType() {
        val progress = CoreEvent.parse(
            JSONObject("""{"type":"task.progress","taskId":"task-1","name":"install:1.21.4","fraction":0.5,"speed":123.0}"""),
        )
        assertEquals(
            CoreEvent.TaskProgress("task-1", "install:1.21.4", 0.5, 123.0),
            progress,
        )

        val finished = CoreEvent.parse(
            JSONObject("""{"type":"task.finished","taskId":"task-1","name":"install:1.21.4","state":"succeeded","detail":["a","b"]}"""),
        )
        assertEquals(
            CoreEvent.TaskFinished("task-1", "install:1.21.4", "succeeded", listOf("a", "b")),
            finished,
        )

        val exited = CoreEvent.parse(
            JSONObject("""{"type":"game.exited","instanceId":"fog","exitCode":0,"playSeconds":44}"""),
        )
        assertEquals(CoreEvent.GameExited("fog", 0, 44), exited)
        assertNull(CoreEvent.parse(JSONObject("""{"type":"unknown"}""")))
    }
}
