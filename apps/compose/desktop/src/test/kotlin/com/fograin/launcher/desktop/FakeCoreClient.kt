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

        "accounts.list" -> json(
            """{"ok":true,"selectedId":"offline-steve","accounts":[
              {"id":"offline-steve","kind":"offline","name":"Steve_Fog","uuid":"5627dd98-e6be-3c21-b8a8-e92344183641","selected":true},
              {"id":"ygg-littleskin","kind":"yggdrasil","name":"Fograin","uuid":"8f0c1d2e-3a4b-5c6d-7e8f-90a1b2c3d4e5","server":"https://littleskin.cn/api/yggdrasil","serverName":"LittleSkin"}
            ]}""",
        )

        "accounts.yggdrasil.login" -> json(
            """{"ok":true,"selectionRequired":true,"pendingId":"pending-1","serverName":"LittleSkin","profiles":[
              {"id":"profile-1","name":"Fograin"},
              {"id":"profile-2","name":"FograinAlt"}
            ]}""",
        )

        "launch.start" -> json("""{"ok":true,"pid":4242}""")

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

        "loaders.versions" -> json(
            """{"ok":true,"versions":[
              {"version":"0.16.9","stable":true},
              {"version":"0.15.11","stable":true}
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

        "java.list" -> json(
            """{"ok":true,"autoDownload":true,"supported":true,"platform":"macos","hostArch":"arm64","runtimes":[
              {"id":"mojang-java-runtime-delta-21.0.7-arm64","vendor":"mojang","version":"21.0.7","major":21,"arch":"arm64","home":"/tmp/fograin-test/runtimes/mojang-java-runtime-delta-21.0.7-arm64/jre.bundle/Contents/Home","executable":"/tmp/fograin-test/runtimes/mojang-java-runtime-delta-21.0.7-arm64/jre.bundle/Contents/Home/bin/java","source":"Fograin runtimes","managed":true,"sizeBytes":104202936,"installedAtMs":1794712058419,"component":"java-runtime-delta","platform":"mac-os-arm64"},
              {"id":null,"vendor":"Oracle Corporation","version":"21.0.9","major":21,"arch":"arm64","home":"/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home","executable":"/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java","source":"java_home","managed":false,"sizeBytes":0}
            ]}""",
        )

        "java.catalog" -> json(
            """{"ok":true,"autoDownload":true,"supported":true,"platform":"macos","hostArch":"arm64","versions":[
              {"runtimeId":"mojang-jre-legacy-8u74-x86_64","major":8,"component":"jre-legacy","version":"8u74-cacert462b08","arch":"x86_64","platform":"mac-os","fallback":true,"installed":false},
              {"runtimeId":"mojang-java-runtime-gamma-17.0.15-arm64","major":17,"component":"java-runtime-gamma","version":"17.0.15","arch":"arm64","platform":"mac-os-arm64","fallback":false,"installed":false},
              {"runtimeId":"mojang-java-runtime-delta-21.0.7-arm64","major":21,"component":"java-runtime-delta","version":"21.0.7","arch":"arm64","platform":"mac-os-arm64","fallback":false,"installed":true,"installedVersion":"21.0.7"}
            ]}""",
        )

        "java.policy.set" -> json("""{"ok":true,"autoDownload":false}""")
        "java.install" -> json("""{"ok":true,"taskId":"java-task-1","runtimeId":"mojang-java-runtime-delta-21.0.7-arm64"}""")
        "java.remove" -> json("""{"ok":true}""")

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
