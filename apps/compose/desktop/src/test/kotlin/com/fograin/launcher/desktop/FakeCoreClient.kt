package com.fograin.launcher.desktop

import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.platform.CoreDownloadSettings
import com.fograin.launcher.platform.CoreGameConfig
import com.fograin.launcher.platform.CoreJson
import com.fograin.launcher.platform.CoreNetworkSettings
import com.fograin.launcher.platform.CoreSettings
import com.fograin.launcher.settings.ThemeMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deterministic in-memory core for off-screen rendering tests.
 *
 * The real JNI core is exercised by [CoreClientSmokeTest]; this fake exists so
 * the pages and remaining sheets can be rendered with known data on CI
 * machines without a launcher data root or network access.
 */
class FakeCoreClient : CoreClient {
    private data class FakeTask(
        val id: String,
        val name: String,
        var state: String,
        var fraction: Double,
        var speed: Double,
    )

    private val tasks = mutableListOf(
        FakeTask("task-1", "install:1.21.5", "running", 0.42, 12_300_000.0),
        FakeTask("task-2", "整合包下载:Calm Pack", "paused", 0.71, 0.0),
        FakeTask("task-3", "content:Sodium", "succeeded", 1.0, 0.0),
    )

    override val initError: String? = null
    override val abiVersion: Int = 1
    override val isAvailable: Boolean = true
    override var settings: CoreSettings = CoreSettings(
        abiVersion = 1,
        appVersion = "0.1.0",
        platform = "macos",
        theme = ThemeMode.DARK,
        language = "system",
        accent = "auto",
        initialPage = "home",
        dataRoot = "/tmp/fograin-test",
        configPath = "/tmp/fograin-test/launcher.toml",
    )
        private set

    private var gameConfig = CoreGameConfig(
        username = "Steve_Fog",
        memoryMb = 6144,
        resolution = listOf(1920, 1080),
    )
    private var download = CoreDownloadSettings()
    private var network = CoreNetworkSettings()
    private var language = "system"
    private var accent = "auto"
    private var initialPage = "home"
    private var javaAutoDownload = true

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
            """{"ok":true,"username":"${gameConfig.username}","memoryMb":${gameConfig.memoryMb},"java":null,"jvmArgs":"${gameConfig.jvmArgs}","resolution":[${gameConfig.resolution?.joinToString(",") ?: ""}],"fullscreen":${gameConfig.fullscreen},"showLog":${gameConfig.showLog},"autoCompleteResources":${gameConfig.autoCompleteResources}}""",
        )

        "gameConfig.set" -> {
            gameConfig = gameConfig.copy(
                memoryMb = params["memoryMb"]?.jsonPrimitive?.intOrNull ?: gameConfig.memoryMb,
                jvmArgs = params["jvmArgs"]?.jsonPrimitive?.content ?: gameConfig.jvmArgs,
                fullscreen = params["fullscreen"]?.jsonPrimitive?.booleanOrNull
                    ?: gameConfig.fullscreen,
                showLog = params["showLog"]?.jsonPrimitive?.booleanOrNull ?: gameConfig.showLog,
                autoCompleteResources = params["autoCompleteResources"]?.jsonPrimitive?.booleanOrNull
                    ?: gameConfig.autoCompleteResources,
                resolution = if (params.containsKey("resolution")) {
                    (params["resolution"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.intOrNull }
                } else {
                    gameConfig.resolution
                },
            )
            json(
                """{"ok":true,"username":"${gameConfig.username}","memoryMb":${gameConfig.memoryMb},"java":null,"jvmArgs":"${gameConfig.jvmArgs}","resolution":[${gameConfig.resolution?.joinToString(",") ?: ""}],"fullscreen":${gameConfig.fullscreen},"showLog":${gameConfig.showLog},"autoCompleteResources":${gameConfig.autoCompleteResources}}""",
            )
        }

        "settings.get" -> json(settingsPayload())

        "settings.set" -> {
            language = params["language"]?.jsonPrimitive?.content ?: language
            accent = params["accent"]?.jsonPrimitive?.content ?: accent
            initialPage = params["initialPage"]?.jsonPrimitive?.content ?: initialPage
            val theme = params["theme"]?.jsonPrimitive?.content
            if (theme != null) settings = settings.copy(theme = ThemeMode.fromWire(theme))
            (params["download"] as? JsonObject)?.let { value ->
                download = download.copy(
                    source = value["source"]?.jsonPrimitive?.content ?: download.source,
                    versionSource = value["versionSource"]?.jsonPrimitive?.content
                        ?: download.versionSource,
                    concurrency = value["concurrency"]?.jsonPrimitive?.intOrNull
                        ?: download.concurrency,
                    perHost = value["perHost"]?.jsonPrimitive?.intOrNull ?: download.perHost,
                    rateLimitKib = value["rateLimitKib"]?.jsonPrimitive?.intOrNull
                        ?: download.rateLimitKib,
                    segments = value["segments"]?.jsonPrimitive?.intOrNull ?: download.segments,
                    segmentThresholdMb = value["segmentThresholdMb"]?.jsonPrimitive?.intOrNull
                        ?: download.segmentThresholdMb,
                    retries = value["retries"]?.jsonPrimitive?.intOrNull ?: download.retries,
                )
            }
            (params["network"] as? JsonObject)?.let { value ->
                network = network.copy(
                    proxy = value["proxy"]?.jsonPrimitive?.content ?: network.proxy,
                    timeoutSecs = value["timeoutSecs"]?.jsonPrimitive?.intOrNull
                        ?: network.timeoutSecs,
                )
            }
            val settingsBody = settingsPayload()
                .removePrefix("{\"ok\":true,")
                .removeSuffix("}")
            json(
                """{"ok":true,"settings":{$settingsBody},"snapshot":${snapshotPayload()}}""",
            )
        }

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
            tasks.joinToString(
                prefix = """{"ok":true,"result":[""",
                postfix = "]}",
                separator = ",",
            ) { task ->
                """{"id":"${task.id}","name":"${task.name}","state":"${task.state}","fraction":${task.fraction},"speed":${task.speed}}"""
            },
        )

        "tasks.pause" -> {
            val id = (params["taskId"] as? JsonPrimitive)?.content.orEmpty()
            tasks.firstOrNull { it.id == id }?.let {
                it.state = "paused"
                it.speed = 0.0
            }
            json("""{"ok":true,"taskId":"$id","state":"paused"}""")
        }

        "tasks.resume" -> {
            val id = (params["taskId"] as? JsonPrimitive)?.content.orEmpty()
            tasks.firstOrNull { it.id == id }?.let {
                it.state = "running"
                it.speed = 8_400_000.0
            }
            json("""{"ok":true,"taskId":"$id","oldTaskId":"$id"}""")
        }

        "tasks.cancel" -> {
            val id = (params["taskId"] as? JsonPrimitive)?.content.orEmpty()
            tasks.firstOrNull { it.id == id }?.let {
                it.state = "cancelled"
                it.speed = 0.0
            }
            json("""{"ok":true,"taskId":"$id","state":"cancelled"}""")
        }

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
            """{"ok":true,"autoDownload":$javaAutoDownload,"supported":true,"platform":"macos","hostArch":"arm64","runtimes":[
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

        "java.policy.set" -> {
            javaAutoDownload = params["autoDownload"]?.jsonPrimitive?.booleanOrNull
                ?: javaAutoDownload
            json("""{"ok":true,"autoDownload":$javaAutoDownload}""")
        }

        "content.local" -> json(
            """
            {"ok":true,"indexPath":"/tmp/fograin-test/instances/fog-forest/content.json",
             "counts":{"mod":2,"resourcepack":1,"shaderpack":1,"enabled":3},
             "items":[
               {"kind":"mod","fileName":"sodium-fabric-0.6.13.jar","displayName":"sodium-fabric-0.6.13.jar","enabled":true,"sizeBytes":1306799,"title":"Sodium","version":"0.6.13","loader":"fabric","description":"Modern rendering engine for Minecraft.","source":"modrinth","projectId":"AANobbMI","projectName":"Sodium","versionId":"c3YkZvne","iconUrl":"https://cdn.modrinth.com/data/AANobbMI/295862f4724dc3f78df3447ad6072b2dcd3ef0c9_96.webp"},
               {"kind":"mod","fileName":"fabric-api-0.115.0.jar.disabled","displayName":"fabric-api-0.115.0.jar","enabled":false,"sizeBytes":2200000,"title":"Fabric API","version":"0.115.0","loader":"fabric","description":"Core API for Fabric mods.","source":"modrinth","projectId":"P7dR8mSH","projectName":"Fabric API","versionId":"api-v","iconUrl":"https://cdn.modrinth.com/data/P7dR8mSH/icon.png"},
               {"kind":"resourcepack","fileName":"fograin-ui.zip","displayName":"fograin-ui.zip","enabled":true,"sizeBytes":5400000,"title":"Fograin UI","packFormat":34,"description":"A calm, forest-toned interface pack."},
               {"kind":"shaderpack","fileName":"bsl-shaders.zip","displayName":"bsl-shaders.zip","enabled":true,"sizeBytes":3800000,"title":"BSL Shaders","description":"Warm lighting with soft shadows."}
             ]}
            """.trimIndent(),
        )

        "worlds.list" -> json(
            """
            {"ok":true,
             "worlds":[
               {"name":"New World","path":"/tmp/fograin-test/instances/fog-forest/.minecraft/saves/New World","sizeBytes":18400000,"modifiedMs":1793000000000,"hasIcon":true,"levelName":"雾林世界","gameMode":"survival","version":"1.21.4","lastPlayedMs":1793000000000,"seed":-42},
               {"name":"Create Test","path":"/tmp/fograin-test/instances/fog-forest/.minecraft/saves/Create Test","sizeBytes":9200000,"modifiedMs":1780000000000,"hasIcon":false,"levelName":"机械测试","gameMode":"creative","version":"1.20.1","lastPlayedMs":1780000000000,"seed":7}
             ],
             "backups":[
               {"name":"20261115-120000.zip","path":"/tmp/fograin-test/backups/fog-forest/20261115-120000.zip","sizeBytes":7400000,"modifiedMs":1793000000000}
             ]}
            """.trimIndent(),
        )

        "content.search" -> {
            val kind = (params["kind"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content
                .orEmpty()
            json(
                when (kind) {
                    "modpack" -> MODPACK_SEARCH_JSON
                    "datapack" -> DATAPACK_SEARCH_JSON
                    "resourcepack" -> RESOURCEPACK_SEARCH_JSON
                    else -> MOD_SEARCH_JSON
                },
            )
        }

        "content.versions" -> {
            val projectId = (params["projectId"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content
                .orEmpty()
            json(
                when (projectId) {
                    "calm-pack", "create-pack" -> MODPACK_VERSIONS_JSON
                    "terralith", "incendium" -> DATAPACK_VERSIONS_JSON
                    "fresh-animations", "better-leaves" -> RESOURCEPACK_VERSIONS_JSON
                    else -> MOD_VERSIONS_JSON
                },
            )
        }

        "content.updates" -> json(
            """
            {"ok":true,"checked":4,"unknown":0,"warnings":[],
             "updates":[{"kind":"mod","fileName":"sodium-fabric-0.6.13.jar","displayName":"sodium-fabric-0.6.13.jar","source":"modrinth","projectId":"AANobbMI","projectName":"Sodium","currentVersion":"0.6.12","latestVersion":"0.6.13","latestVersionId":"c3YkZvne","latestFileName":"sodium-fabric-0.6.13+mc1.21.4.jar","latestSize":1306799}]}
            """.trimIndent(),
        )

        "content.plan" -> json(
            """{"ok":true,"source":"modrinth","projectId":"AANobbMI","projectName":"Sodium","versionId":"old-v","versionNumber":"mc1.21.4-0.6.12-fabric","kind":"mod","files":2,"totalBytes":2000000,"warnings":[],"items":[
              {"fileName":"sodium-0.6.12.jar","projectId":"AANobbMI","projectName":"Sodium","versionId":"old-v","versionNumber":"mc1.21.4-0.6.12-fabric","dependency":false,"size":1290000},
              {"fileName":"fabric-api.jar","projectId":"P7dR8mSH","projectName":"Fabric API","versionId":"api-v","versionNumber":"0.119.4+1.21.4","dependency":true,"size":710000}
            ]}""",
        )

        "content.toggle", "content.delete", "content.install" -> json(
            """{"ok":true,"taskId":"content-task-1","projectName":"Sodium","versionNumber":"0.6.13","files":1,"totalBytes":1306799,"items":[]}""",
        )

        "worlds.backup" -> json(
            """{"ok":true,"backup":{"name":"20261115-180000.zip","path":"/tmp/fograin-test/backups/fog-forest/20261115-180000.zip","sizeBytes":7400000,"modifiedMs":1793000000000},"backups":[{"name":"20261115-180000.zip","path":"/tmp/fograin-test/backups/fog-forest/20261115-180000.zip","sizeBytes":7400000,"modifiedMs":1793000000000}]}""",
        )
        "worlds.restore" -> json(
            """{"ok":true,"path":"/tmp/fograin-test/instances/fog-forest/.minecraft/saves/New World-restored","worlds":[{"name":"New World","sizeBytes":18400000,"modifiedMs":1793000000000,"hasIcon":true}],"backups":[]}""",
        )
        "worlds.delete" -> json("""{"ok":true,"worlds":[]}""")
        "worlds.backup.delete" -> json("""{"ok":true,"backups":[]}""")
        "datapacks.list", "datapacks.toggle", "datapacks.delete", "datapacks.import" ->
            json(DATAPACKS_JSON)

        "diag.logs" -> json(DIAG_LOGS_JSON)
        "diag.analyze" -> json(DIAG_ANALYSIS_JSON)
        "diag.export" -> json(
            """{"ok":true,"bundle":{"path":"/tmp/fograin-test/diagnostics/雾林生存-20261117-133000.zip","sizeBytes":184320,"files":["report.md","manifest.json","instance.json","logs/latest.log","crash-reports/crash-2026-11-17.txt"]},"analysis":$DIAG_ANALYSIS_JSON_BODY}""",
        )

        "modpack.inspect" -> json(MODPACK_JSON)
        "modpack.install" -> json(
            """{"ok":true,"taskId":"modpack-task-1","instanceId":"calmpack","name":"Calm Pack","versionId":"calm-v1","versionNumber":"1.2.0","source":"modrinth"}""",
        )
        "modpack.import" -> json(
            """{"ok":true,"instanceId":"calmpack","taskId":"task-modpack","external":false,"pack":$MODPACK_JSON_BODY}""",
        )
        "modpack.export.plan" -> json(
            """{"ok":true,"sections":[
              {"id":"servers","label":"servers","files":2,"bytes":4096,"default":false,"folder":false},
              {"id":"configs","label":"configs","files":31,"bytes":262144,"default":true,"folder":false},
              {"id":"mods","label":"mods","files":42,"bytes":134217728,"default":true,"folder":false},
              {"id":"shaderpacks","label":"shaderpacks","files":3,"bytes":5242880,"default":true,"folder":false},
              {"id":"resourcepacks","label":"resourcepacks","files":5,"bytes":20971520,"default":true,"folder":false},
              {"id":"gameSettings","label":"gameSettings","files":1,"bytes":8192,"default":false,"folder":false},
              {"id":"folder:kubejs","label":"kubejs","files":12,"bytes":73400,"default":true,"folder":true}
            ]}""",
        )
        "modpack.export" -> json(
            """{"ok":true,"path":"/tmp/fograin-test/exports/雾林生存-20261117-133100.mrpack","sizeBytes":24117248,"files":37}""",
        )

        "java.install" -> json("""{"ok":true,"taskId":"java-task-1","runtimeId":"mojang-java-runtime-delta-21.0.7-arm64"}""")
        "java.remove" -> json("""{"ok":true}""")

        else -> json("""{"ok":true}""")
    }

    override suspend fun setTheme(theme: ThemeMode): CoreSettings {
        settings = settings.copy(theme = theme)
        return settings
    }

    private fun settingsPayload(): String {
        val downloadJson = CoreJson.encodeToJsonElement(
            CoreDownloadSettings.serializer(),
            download,
        )
        val networkJson = CoreJson.encodeToJsonElement(
            CoreNetworkSettings.serializer(),
            network,
        )
        return """{"ok":true,"theme":"${settings.theme.wireValue}","language":"$language","accent":"$accent","initialPage":"$initialPage","download":$downloadJson,"network":$networkJson}"""
    }

    private fun snapshotPayload(): String =
        """{"abiVersion":1,"appVersion":"0.1.0","platform":"macos","theme":"${settings.theme.wireValue}","language":"$language","accent":"$accent","initialPage":"$initialPage","dataRoot":"/tmp/fograin-test","configPath":"/tmp/fograin-test/launcher.toml"}"""

    override fun nextEvents(): List<JsonObject> = emptyList()

    private fun json(raw: String): JsonObject =
        CoreJson.parseToJsonElement(raw).jsonObject

    private companion object {
        const val DATAPACKS_JSON: String =
            """{"ok":true,"count":2,"items":[
              {"fileName":"terralith.zip","title":"Terralith 地形","enabled":true,"sizeBytes":8420000,"modifiedMs":1793000000000,"packFormat":48,"description":"让主世界生成全新的地形。"},
              {"fileName":"fog-recipes.zip","title":"雾雨配方","enabled":false,"sizeBytes":184000,"modifiedMs":1792000000000,"packFormat":48,"description":"一组更安静的合成配方。"}
            ]}"""

        const val DIAG_LOGS_JSON: String =
            """{"ok":true,"rules":15,
              "sources":[
                {"kind":"log","name":"latest.log","path":"/tmp/fograin-test/instances/fog-forest/.minecraft/logs/latest.log","exists":true,"sizeBytes":184320,"modifiedMs":1793000000000,"lines":9},
                {"kind":"crash","name":"crash-2026-11-17_13.20.00-client.txt","path":"/tmp/fograin-test/instances/fog-forest/.minecraft/crash-reports/crash-2026-11-17_13.20.00-client.txt","exists":true,"sizeBytes":48120,"modifiedMs":1793000000000,"lines":4},
                {"kind":"hsErr","name":"JVM 崩溃日志","path":"/tmp/fograin-test/instances/fog-forest/.minecraft","exists":false,"sizeBytes":0,"lines":0}
              ],
              "crash":{"description":"Rendering screen","minecraft":"1.21.4","java":"21.0.7","file":"crash-reports/crash-2026-11-17_13.20.00-client.txt","modifiedMs":1793000000000},
              "texts":{
                "log":"[13:20:00] [main/INFO]: Loading Minecraft 1.21.4 with Fabric Loader 0.16.9
[13:20:01] [main/INFO]: Loading 128 mods
[13:20:03] [main/WARN]: Mod 'sodium' is missing dependency 'fabric-api'
[13:20:04] [main/INFO]: Preparing spawn area
[13:20:06] [Render thread/INFO]: OpenGL 4.6 provided by Apple M3
[13:20:07] [Render thread/ERROR]: java.lang.OutOfMemoryError: Java heap space
[13:20:07] [Render thread/ERROR]: \tat net.minecraft.client.renderer.chunk.RenderChunk.rebuild(RenderChunk.java:412)
[13:20:08] [Render thread/ERROR]: Failed to allocate 2 GiB vertex buffer
[13:20:09] [main/INFO]: Stopping!",
                "crash":"---- Minecraft Crash Report ----
Description: Rendering screen

Minecraft Version: 1.21.4
Java Version: 21.0.7, Mojang
Caused by: java.lang.OutOfMemoryError: Java heap space
\tat net.minecraft.client.renderer.chunk.RenderChunk.rebuild(RenderChunk.java:412)",
                "stdout":"[13:19:58] [main/INFO]: Fograin launcher session
[13:20:00] [main/INFO]: Loading Minecraft 1.21.4"
              }}"""

        const val DIAG_ANALYSIS_JSON_BODY: String =
            """{"findings":[
              {"id":"java.memory","severity":"error","confidence":0.92,"title":"内存不足","body":"分配的内存不足以加载这个实例。请在实例设置里提高最大内存，或减少同时启用的模组。","excerpt":"java.lang.OutOfMemoryError: Java heap space","line":4,"source":"崩溃报告","sourceKind":"crash","fix":"openJavaSettings"},
              {"id":"mod.dependency","severity":"warning","confidence":0.75,"title":"缺少模组前置","body":"有模组缺少必需的前置或依赖了不存在的类。请在模组页检查依赖。","excerpt":"[13:20:03] [main/WARN]: Mod 'sodium' is missing dependency 'fabric-api'","line":3,"source":"最新日志","sourceKind":"log","fix":"openContent"}
            ],"severity":"error","summary":"崩溃报告：Rendering screen。找到 2 个可能原因，请先看第一条。","scannedLines":13,"rules":15,"analyzedAtMs":1793000000000}"""

        val DIAG_ANALYSIS_JSON: String =
            """{"ok":true,""" + DIAG_ANALYSIS_JSON_BODY.removePrefix("{")

        val MOD_VERSIONS_JSON: String =
            """
            {"ok":true,"source":"modrinth",
             "project":{"source":"modrinth","projectId":"AANobbMI","slug":"sodium","title":"Sodium","description":"A modern rendering engine.","downloads":252637937,"categories":["fabric"]},
             "versions":[
               {"source":"modrinth","projectId":"AANobbMI","versionId":"c3YkZvne","name":"Sodium 0.6.13 for Fabric 1.21.4","versionNumber":"mc1.21.4-0.6.13-fabric","gameVersions":["1.21.4"],"loaders":["fabric"],"downloads":5761310,"versionType":"release","published":"2025-04-04T00:00:00Z","file":{"fileName":"sodium-fabric-0.6.13+mc1.21.4.jar","size":1306799},"dependencies":[]},
               {"source":"modrinth","projectId":"AANobbMI","versionId":"old-v","name":"Sodium 0.6.12","versionNumber":"mc1.21.4-0.6.12-fabric","gameVersions":["1.21.4"],"loaders":["fabric"],"versionType":"beta","file":{"fileName":"sodium-0.6.12.jar","size":1290000},"dependencies":[{"projectId":"P7dR8mSH","kind":"required"}]}
             ]}
            """.trimIndent()

        val MODPACK_VERSIONS_JSON: String =
            """
            {"ok":true,"source":"modrinth",
             "project":{"source":"modrinth","projectId":"calm-pack","slug":"calm-pack","title":"Calm Pack","description":"A quiet Fabric pack for building and exploring.","downloads":182000,"categories":["fabric","adventure"]},
             "versions":[
               {"source":"modrinth","projectId":"calm-pack","versionId":"calm-v1","name":"Calm Pack 1.2.0","versionNumber":"1.2.0","gameVersions":["1.21.4"],"loaders":["fabric"],"downloads":12000,"versionType":"release","published":"2026-11-01T00:00:00Z","file":{"fileName":"calm-pack-1.2.0.mrpack","size":18400000},"dependencies":[]},
               {"source":"modrinth","projectId":"calm-pack","versionId":"calm-v0","name":"Calm Pack 1.1.0","versionNumber":"1.1.0","gameVersions":["1.21.4"],"loaders":["fabric"],"downloads":4200,"versionType":"beta","file":{"fileName":"calm-pack-1.1.0.mrpack","size":17800000},"dependencies":[]}
             ]}
            """.trimIndent()

        val DATAPACK_VERSIONS_JSON: String =
            """
            {"ok":true,"source":"modrinth",
             "project":{"source":"modrinth","projectId":"terralith","slug":"terralith","title":"Terralith","description":"New overworld terrain generation.","downloads":4200000,"categories":["datapack"]},
             "versions":[
               {"source":"modrinth","projectId":"terralith","versionId":"terra-26","name":"Terralith 2.6","versionNumber":"2.6","gameVersions":["1.21.4"],"loaders":[],"downloads":920000,"versionType":"release","published":"2026-11-01T00:00:00Z","file":{"fileName":"terralith-2.6.zip","size":8400000},"dependencies":[]},
               {"source":"modrinth","projectId":"terralith","versionId":"terra-25","name":"Terralith 2.5","versionNumber":"2.5","gameVersions":["1.20.4"],"loaders":[],"downloads":610000,"versionType":"release","file":{"fileName":"terralith-2.5.zip","size":8100000},"dependencies":[]}
             ]}
            """.trimIndent()

        val RESOURCEPACK_VERSIONS_JSON: String =
            """
            {"ok":true,"source":"modrinth",
             "project":{"source":"modrinth","projectId":"fresh-animations","slug":"fresh-animations","title":"Fresh Animations","description":"Dynamic animated entities.","downloads":52000000,"categories":["resourcepack"]},
             "versions":[
               {"source":"modrinth","projectId":"fresh-animations","versionId":"fresh-1","name":"Fresh Animations 1.9","versionNumber":"1.9","gameVersions":["1.21.4"],"loaders":[],"downloads":8200000,"versionType":"release","published":"2026-11-01T00:00:00Z","file":{"fileName":"FreshAnimations_v1.9.zip","size":5400000},"dependencies":[]}
             ]}
            """.trimIndent()

        val MOD_SEARCH_JSON: String =
            """
            {"ok":true,"source":"modrinth","total":3,"offset":0,"limit":50,
             "projects":[
               {"source":"modrinth","projectId":"AANobbMI","slug":"sodium","title":"Sodium","description":"A modern rendering engine that greatly improves frame rates.","downloads":252637937,"follows":42812,"iconUrl":"https://cdn.modrinth.com/data/AANobbMI/295862f4724dc3f78df3447ad6072b2dcd3ef0c9_96.webp","categories":["fabric","optimization"],"updated":"2026-11-12T00:00:00Z"},
               {"source":"modrinth","projectId":"P7dR8mSH","slug":"fabric-api","title":"Fabric API","description":"Lightweight and modular API for Fabric mods.","downloads":180000000,"follows":12000,"iconUrl":"https://cdn.modrinth.com/data/P7dR8mSH/icon.png","categories":["fabric","library"],"updated":"2026-11-01T00:00:00Z"},
               {"source":"modrinth","projectId":"YL57xq9U","slug":"iris","title":"Iris Shaders","description":"Modern shader pack loader for Minecraft.","downloads":90000000,"follows":8000,"iconUrl":"https://cdn.modrinth.com/data/YL57xq9U/18d0e7f076d3d6ed5bedd472b853909aac5da202_96.webp","categories":["fabric","neoforge"],"updated":"2026-10-20T00:00:00Z"}
             ]}
            """.trimIndent()

        val MODPACK_SEARCH_JSON: String =
            """
            {"ok":true,"source":"modrinth","total":2,"offset":0,"limit":50,
             "projects":[
               {"source":"modrinth","projectId":"calm-pack","slug":"calm-pack","title":"Calm Pack","description":"A quiet Fabric pack for building and exploring.","downloads":182000,"follows":1200,"categories":["fabric","adventure"],"updated":"2026-11-10T00:00:00Z"},
               {"source":"modrinth","projectId":"create-pack","slug":"create-above-beyond","title":"Create: Above and Beyond","description":"Automation-focused modpack with quests.","downloads":960000,"follows":5400,"categories":["forge","technology"],"updated":"2026-10-22T00:00:00Z"}
             ]}
            """.trimIndent()

        val DATAPACK_SEARCH_JSON: String =
            """
            {"ok":true,"source":"modrinth","total":2,"offset":0,"limit":50,
             "projects":[
               {"source":"modrinth","projectId":"terralith","slug":"terralith","title":"Terralith","description":"New overworld terrain generation with caves, peaks and islands.","downloads":4200000,"follows":22000,"categories":["datapack","worldgen"],"updated":"2026-11-12T00:00:00Z"},
               {"source":"modrinth","projectId":"incendium","slug":"incendium","title":"Incendium","description":"Nether generation overhaul with new biomes and structures.","downloads":2100000,"follows":11000,"categories":["datapack","worldgen"],"updated":"2026-10-30T00:00:00Z"}
             ]}
            """.trimIndent()

        val RESOURCEPACK_SEARCH_JSON: String =
            """
            {"ok":true,"source":"modrinth","total":2,"offset":0,"limit":50,
             "projects":[
               {"source":"modrinth","projectId":"fresh-animations","slug":"fresh-animations","title":"Fresh Animations","description":"Dynamic animated entities that bring the world to life.","downloads":52000000,"follows":18000,"iconUrl":"https://cdn.modrinth.com/data/50dA9Sha/3132c10e9e3c73fde9799720fd3da5561071708c_96.webp","categories":["resourcepack","entities"],"updated":"2026-11-14T00:00:00Z"},
               {"source":"modrinth","projectId":"better-leaves","slug":"better-leaves","title":"Motschen's Better Leaves","description":"Improved leaf shapes with high mod compatibility.","downloads":21000000,"follows":6000,"iconUrl":"https://cdn.modrinth.com/data/uvpymuxq/fe1a61998ae57dc6ad1a4bb028334c3c3925d22f_96.webp","categories":["resourcepack","decoration"],"updated":"2026-11-05T00:00:00Z"}
             ]}
            """.trimIndent()

        const val MODPACK_JSON_BODY: String =
            """{"kind":"modrinth","name":"Calm Pack","version":"1.2.0","minecraft":"1.21.4","loaderKind":"fabric","loaderVersion":"0.16.9","summary":"A quiet Fabric pack for building.","files":[
              {"path":"mods/sodium.jar","size":1306799,"sha1":"aa","sha512":"bb","url":"https://cdn.example/sodium.jar"},
              {"path":"mods/lithium.jar","size":920000,"sha1":"cc","url":"https://cdn.example/lithium.jar"}
            ],"overrides":"overrides","sourcePath":"/tmp/Calm Pack.mrpack","external":false,"skipped":0}"""

        val MODPACK_JSON: String =
            """{"ok":true,""" + MODPACK_JSON_BODY.removePrefix("{")
    }
}
