package com.fograin.launcher.desktop

import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.platform.CoreJson
import com.fograin.launcher.platform.CoreSettings
import com.fograin.launcher.settings.ThemeMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Deterministic in-memory core for off-screen rendering tests.
 *
 * The real JNI core is exercised by [CoreClientSmokeTest]; this fake exists so
 * the four pages and three sheets can be rendered with known data on CI
 * machines without a launcher data root or network access.
 */
class FakeCoreClient : CoreClient {
    override val initError: String? = null
    override val abiVersion: Int = 1
    override val isAvailable: Boolean = true
    override var settings: CoreSettings = CoreSettings(
        abiVersion = 1,
        appVersion = "0.1.0",
        platform = "macos",
        theme = ThemeMode.DARK,
        dataRoot = "/tmp/fograin-test",
        configPath = "/tmp/fograin-test/launcher.toml",
    )
        private set

    override suspend fun rpc(method: String, params: JsonObject): JsonObject = when (method) {
        "instances.list" -> json(
            """
            {"ok":true,"result":[
              {"id":"fog-forest","name":"雾林生存","minecraft":"1.21.4","loaderKind":"fabric","loaderVersion":"0.16.9","favorite":true,"installed":true,"modCount":42,"sizeBytes":3100000000,"playSeconds":5400,"lastPlayedMs":1793000000000},
              {"id":"machinery","name":"机械动力 6","minecraft":"1.20.1","loaderKind":"forge","loaderVersion":"47.2.0","favorite":false,"installed":true,"modCount":128,"sizeBytes":4200000000,"playSeconds":18000,"lastPlayedMs":1780000000000},
              {"id":"vanilla","name":"原版纯净","minecraft":"1.21.4","loaderKind":"vanilla","loaderVersion":"","favorite":false,"installed":false,"modCount":null,"sizeBytes":0,"playSeconds":0,"lastPlayedMs":null}
            ]}
            """.trimIndent(),
        )

        "app.snapshot" -> json(
            """{"ok":true,"platform":"macos","appVersion":"0.1.0","dataRoot":"/tmp/fograin-test","javaCount":5,"activeTasks":0,"diskFreeBytes":510000000000,"totalMemoryBytes":17179869184,"instanceCount":3}""",
        )

        "gameConfig.get" -> json(
            """{"ok":true,"username":"Steve_Fog","memoryMb":6144,"java":null,"jvmArgs":"","resolution":[1920,1080],"fullscreen":false,"showLog":false,"autoCompleteResources":true}""",
        )

        "tasks.list" -> json(
            """{"ok":true,"result":[
              {"id":"task-1","name":"install:1.21.5","state":"running","fraction":0.42,"speed":12300000.0}
            ]}""",
        )

        "versions.list" -> json(
            """{"ok":true,"versions":[
              {"id":"1.21.5","kind":"release","released":"2026-03-25T00:00:00Z"},
              {"id":"1.21.4","kind":"release","released":"2025-12-03T00:00:00Z"},
              {"id":"25w14a","kind":"snapshot","released":"2025-04-02T00:00:00Z"}
            ]}""",
        )

        "version.plan" -> json(
            """{"ok":true,"versionId":"1.21.5","baseId":"1.21.5","javaMajor":21,"files":42,"totalBytes":310000000}""",
        )

        "instances.settings.get" -> json(
            """
            {"ok":true,
             "effective":{"username":"Steve_Fog","memoryMb":6144,"java":null,"jvmArgs":"","resolution":[1920,1080],"fullscreen":false,"showLog":false,"autoCompleteResources":true},
             "overrides":{"java":null,"memoryMb":6144,"jvmArgs":"","resolution":[1920,1080],"fullscreen":false,"gameDir":null},
             "globals":{"username":"Steve_Fog","memoryMb":0,"java":null,"jvmArgs":"","resolution":null,"fullscreen":false,"showLog":false,"autoCompleteResources":true}}
            """.trimIndent(),
        )

        "instances.dir" -> json("""{"ok":true,"gameDir":"/tmp/fograin-test/instances/fog-forest"}""")

        else -> json("""{"ok":true}""")
    }

    override suspend fun setTheme(theme: ThemeMode): CoreSettings {
        settings = settings.copy(theme = theme)
        return settings
    }

    override fun nextEvents(): List<JsonObject> = emptyList()

    private fun json(raw: String): JsonObject =
        CoreJson.parseToJsonElement(raw).jsonObject
}
