package com.fograin.launcher.app

import androidx.compose.runtime.Composable
import com.fograin.launcher.platform.AppLogger
import com.fograin.launcher.platform.BackDispatcher
import com.fograin.launcher.platform.ClipboardService
import com.fograin.launcher.platform.CoreAccount
import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.platform.CoreJson
import com.fograin.launcher.platform.CoreSettings
import com.fograin.launcher.platform.DeviceInfoProvider
import com.fograin.launcher.platform.DirectoryBrowser
import com.fograin.launcher.platform.DirectoryEntry
import com.fograin.launcher.platform.DirectoryPick
import com.fograin.launcher.platform.FileBrowser
import com.fograin.launcher.platform.FilePick
import com.fograin.launcher.platform.FileSavePick
import com.fograin.launcher.platform.LocalPreferences
import com.fograin.launcher.platform.NativeLibrary
import com.fograin.launcher.platform.PlatformServices
import com.fograin.launcher.platform.SystemAppearance
import com.fograin.launcher.platform.SystemLinks
import com.fograin.launcher.platform.WindowFrame
import com.fograin.launcher.settings.ThemeMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Deterministic core for the shared state-machine tests.
 *
 * Mirrors the JSON envelopes `bindings/fg-ffi` returns; [rpcCalls] records the
 * call order so tests can assert a flow without a data root or network.
 */
internal class FakeCore : CoreClient {
    val rpcCalls = mutableListOf<String>()

    var installedInstanceIds = mutableListOf<String>()
    val installRequests = mutableListOf<JsonObject>()
    val planRequests = mutableListOf<JsonObject>()
    val javaInstallRequests = mutableListOf<JsonObject>()
    var javaAutoDownload = true
    var javaRemovedIds = mutableListOf<String>()
    val launchRequests = mutableListOf<JsonObject>()
    val contentSearchRequests = mutableListOf<JsonObject>()
    val contentInstallRequests = mutableListOf<JsonObject>()
    val modpackInstallRequests = mutableListOf<JsonObject>()
    val taskControlRequests = mutableListOf<Pair<String, String>>()
    var taskRows = """{"ok":true,"result":[]}"""
    var resumeTaskId = "task-resumed"
    val accountLoginRequests = mutableListOf<JsonObject>()
    var yggdrasilNeedsProfile = false
    var accountRows = mutableListOf(
        CoreAccount(
            id = "offline-steve",
            kind = "offline",
            name = "Steve_Fog",
            uuid = "5627dd98-e6be-3c21-b8a8-e92344183641",
            selected = true,
        ),
    )
    var selectedAccountId: String? = "offline-steve"

    override val initError: String? = null
    override val abiVersion: Int = 1
    override val isAvailable: Boolean = true
    override var settings: CoreSettings = CoreSettings(
        abiVersion = 1,
        appVersion = "0.1.0",
        platform = "test",
        theme = ThemeMode.DARK,
        language = "system",
        accent = "auto",
        initialPage = "home",
        dataRoot = "/tmp/fograin-test",
        configPath = "/tmp/fograin-test/launcher.toml",
    )
        private set

    private var language = "system"
    private var accent = "auto"
    private var initialPage = "home"

    override suspend fun rpc(method: String, params: JsonObject): JsonObject {
        rpcCalls += method
        return when (method) {
            "instances.list" -> json(
                """{"ok":true,"result":[
                  {"id":"fog-forest","name":"雾林生存","minecraft":"1.21.4","loaderKind":"fabric","loaderVersion":"0.16.9","favorite":true,"installed":true,"modCount":42,"sizeBytes":3100000000,"playSeconds":5400,"lastPlayedMs":1793000000000},
                  {"id":"vanilla","name":"原版纯净","minecraft":"1.21.4","loaderKind":"vanilla","loaderVersion":"","favorite":false,"installed":false,"modCount":null,"sizeBytes":0,"playSeconds":0,"lastPlayedMs":null}
                ]}""".trimIndent(),
            )

            "app.snapshot" -> json(
                """{"ok":true,"platform":"test","appVersion":"0.1.0","dataRoot":"/tmp/fograin-test","javaCount":5,"activeTasks":0,"diskFreeBytes":510000000000,"totalMemoryBytes":17179869184,"instanceCount":2}""",
            )

            "gameConfig.get" -> json(
                """{"ok":true,"username":"Steve_Fog","memoryMb":6144,"java":null,"jvmArgs":"","resolution":[1920,1080],"fullscreen":false,"showLog":false,"autoCompleteResources":true}""",
            )

            "gameConfig.set" -> json(
                """{"ok":true,"username":"Steve_Fog","memoryMb":${params.stringOrNull("memoryMb") ?: "6144"},"java":null,"jvmArgs":"${params.stringOrNull("jvmArgs").orEmpty()}","resolution":null,"fullscreen":false,"showLog":false,"autoCompleteResources":true}""",
            )

            "settings.get" -> json(
                """{"ok":true,"theme":"${settings.theme.wireValue}","language":"$language","accent":"$accent","initialPage":"$initialPage","download":{"source":"auto","versionSource":"auto","concurrency":0,"perHost":32,"rateLimitKib":0,"segments":4,"segmentThresholdMb":16,"retries":5},"network":{"proxy":"auto","timeoutSecs":10}}""",
            )

            "settings.set" -> {
                language = params.stringOrNull("language") ?: language
                accent = params.stringOrNull("accent") ?: accent
                initialPage = params.stringOrNull("initialPage") ?: initialPage
                params.stringOrNull("theme")?.let { theme ->
                    settings = settings.copy(theme = ThemeMode.fromWire(theme))
                }
                json(
                    """{"ok":true,"settings":{"theme":"${settings.theme.wireValue}","language":"$language","accent":"$accent","initialPage":"$initialPage","download":{"source":"auto","versionSource":"auto","concurrency":0,"perHost":32,"rateLimitKib":0,"segments":4,"segmentThresholdMb":16,"retries":5},"network":{"proxy":"auto","timeoutSecs":10}},"snapshot":{"abiVersion":1,"appVersion":"0.1.0","platform":"test","theme":"${settings.theme.wireValue}","language":"$language","accent":"$accent","initialPage":"$initialPage","dataRoot":"/tmp/fograin-test","configPath":"/tmp/fograin-test/launcher.toml"}}""",
                )
            }

            "accounts.list" -> json(accountsPayload())

            "accounts.addOffline" -> {
                val name = params.stringOrNull("name").orEmpty()
                val id = "offline-$name"
                if (accountRows.none { it.id == id }) {
                    accountRows += CoreAccount(
                        id = id,
                        kind = "offline",
                        name = name,
                        uuid = "00000000-0000-0000-0000-000000000001",
                    )
                }
                selectedAccountId = id
                json("""{"ok":true,"selectedId":"$id","account":${CoreJson.encodeToString(accountRows.first { it.id == id })}}""")
            }

            "accounts.select" -> {
                val id = params.stringOrNull("id").orEmpty()
                selectedAccountId = id
                json("""{"ok":true,"selectedId":"$id"}""")
            }

            "accounts.remove" -> {
                val id = params.stringOrNull("id").orEmpty()
                accountRows.removeAll { it.id == id }
                if (selectedAccountId == id) selectedAccountId = accountRows.firstOrNull()?.id
                json("""{"ok":true,"selectedId":${selectedAccountId?.let { "\"$it\"" } ?: "null"}}""")
            }

            "accounts.refresh" -> json(
                """{"ok":true,"account":${CoreJson.encodeToString(accountRows.first { it.id == (params.stringOrNull("id") ?: selectedAccountId) })}}""",
            )

            "accounts.yggdrasil.login" -> {
                accountLoginRequests += params
                if (yggdrasilNeedsProfile) {
                    json(
                        """{"ok":true,"selectionRequired":true,"pendingId":"pending-1","serverName":"LittleSkin","profiles":[{"id":"profile-1","name":"Fograin"},{"id":"profile-2","name":"FograinAlt"}]}""",
                    )
                } else {
                    addYggdrasilAccount("profile-1", "Fograin")
                }
            }

            "accounts.yggdrasil.selectProfile" -> {
                val profileId = params.stringOrNull("profileId").orEmpty()
                val name = if (profileId == "profile-2") "FograinAlt" else "Fograin"
                addYggdrasilAccount(profileId, name)
            }

            "tasks.list" -> json(taskRows)

            "tasks.pause", "tasks.resume", "tasks.cancel" -> {
                val id = params.stringOrNull("taskId").orEmpty()
                taskControlRequests += method to id
                when (method) {
                    "tasks.pause" ->
                        json("""{"ok":true,"taskId":"$id","state":"paused"}""")

                    "tasks.resume" ->
                        json("""{"ok":true,"taskId":"$resumeTaskId","oldTaskId":"$id"}""")

                    else -> json("""{"ok":true,"taskId":"$id","state":"cancelled"}""")
                }
            }

            "loaders.versions" -> json(
                """{"ok":true,"versions":[
                  {"version":"0.16.9","stable":true},
                  {"version":"0.15.11","stable":true}
                ]}""",
            )

            "versions.list" -> json(
                """{"ok":true,"versions":[
                  {"id":"1.21.5","kind":"release","released":"2026-03-25T00:00:00Z"}
                ]}""",
            )

            "version.plan" -> {
                planRequests += params
                json(
                    """{"ok":true,"versionId":"1.21.5","baseId":"1.21.5","javaMajor":21,"files":42,"totalBytes":310000000}""",
                )
            }

            "install.start" -> {
                installRequests += params
                val id = params.stringOrNull("instanceId").orEmpty()
                if (id.isNotEmpty()) installedInstanceIds += id
                json("""{"ok":true,"taskId":"task-42"}""")
            }

            "launch.start" -> {
                launchRequests += params
                json("""{"ok":true,"pid":4242}""")
            }

            "instances.settings.get" -> json(
                """{"ok":true,
                   "effective":{"username":"Steve_Fog","memoryMb":6144,"java":null,"jvmArgs":"","resolution":[1920,1080],"fullscreen":false,"showLog":false,"autoCompleteResources":true},
                   "overrides":{"java":null,"memoryMb":6144,"jvmArgs":"","resolution":[1920,1080],"fullscreen":false,"gameDir":null},
                   "globals":{"username":"Steve_Fog","memoryMb":0,"java":null,"jvmArgs":"","resolution":null,"fullscreen":false,"showLog":false,"autoCompleteResources":true}}""",
            )

            "instances.dir" -> json("""{"ok":true,"gameDir":"/tmp/fograin-test/instances/fog-forest"}""")

            "java.list" -> json(
                """{"ok":true,"autoDownload":${javaAutoDownload},"supported":true,"platform":"test","hostArch":"arm64","runtimes":[
                  {"id":"mojang-java-runtime-delta-21.0.7-arm64","vendor":"mojang","version":"21.0.7","major":21,"arch":"arm64","home":"/tmp/fograin-test/runtimes/mojang-java-runtime-delta-21.0.7-arm64/jre.bundle/Contents/Home","executable":"/tmp/fograin-test/runtimes/mojang-java-runtime-delta-21.0.7-arm64/jre.bundle/Contents/Home/bin/java","source":"Fograin runtimes","managed":true,"sizeBytes":104202936,"installedAtMs":1794712058419,"component":"java-runtime-delta","platform":"mac-os-arm64"},
                  {"id":null,"vendor":"Oracle Corporation","version":"21.0.9","major":21,"arch":"arm64","home":"/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home","executable":"/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java","source":"java_home","managed":false,"sizeBytes":0}
                ]}""",
            )

            "java.catalog" -> json(
                """{"ok":true,"autoDownload":${javaAutoDownload},"supported":true,"platform":"test","hostArch":"arm64","versions":[
                  {"runtimeId":"mojang-jre-legacy-8u74-x86_64","major":8,"component":"jre-legacy","version":"8u74-cacert462b08","arch":"x86_64","platform":"mac-os","fallback":true,"installed":false},
                  {"runtimeId":"mojang-java-runtime-gamma-17.0.15-arm64","major":17,"component":"java-runtime-gamma","version":"17.0.15","arch":"arm64","platform":"mac-os-arm64","fallback":false,"installed":false},
                  {"runtimeId":"mojang-java-runtime-delta-21.0.7-arm64","major":21,"component":"java-runtime-delta","version":"21.0.7","arch":"arm64","platform":"mac-os-arm64","fallback":false,"installed":true,"installedVersion":"21.0.7"}
                ]}""",
            )

            "java.policy.set" -> {
                javaAutoDownload = (params["autoDownload"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content
                    ?.toBooleanStrictOrNull()
                    ?: javaAutoDownload
                json("""{"ok":true,"autoDownload":${javaAutoDownload}}""")
            }

            "java.install" -> {
                javaInstallRequests += params
                json("""{"ok":true,"taskId":"java-task-1","runtimeId":"mojang-java-runtime-delta-21.0.7-arm64","existing":false}""")
            }

            "java.remove" -> {
                params.stringOrNull("id")?.let { javaRemovedIds += it }
                json("""{"ok":true,"id":"${params.stringOrNull("id").orEmpty()}"}""")
            }

            "content.local" -> json(
                """{"ok":true,"counts":{"mod":1,"resourcepack":1,"shaderpack":0,"enabled":2},"items":[
                  {"kind":"mod","fileName":"sodium.jar","displayName":"sodium.jar","enabled":true,"sizeBytes":100,"title":"Sodium","version":"0.6.13","loader":"fabric","source":"modrinth","projectId":"AANobbMI","projectName":"Sodium","versionId":"c3YkZvne"},
                  {"kind":"resourcepack","fileName":"fograin-ui.zip","displayName":"fograin-ui.zip","enabled":true,"sizeBytes":200,"title":"Fograin UI","packFormat":34}
                ]}""",
            )

            "worlds.list" -> json(
                """{"ok":true,"worlds":[{"name":"New World","sizeBytes":1000,"modifiedMs":1793000000000,"hasIcon":true}],"backups":[]}""",
            )

            "content.search" -> {
                contentSearchRequests += params
                json(
                """{"ok":true,"source":"modrinth","total":1,"offset":0,"limit":50,"projects":[
                  {"source":"modrinth","projectId":"AANobbMI","slug":"sodium","title":"Sodium","description":"Faster rendering","downloads":100,"categories":["fabric"]}
                ]}""",
                )
            }

            "content.versions" -> json(
                """{"ok":true,"source":"modrinth","versions":[
                  {"source":"modrinth","projectId":"AANobbMI","versionId":"c3YkZvne","name":"Sodium 0.6.13","versionNumber":"0.6.13","gameVersions":["1.21.4"],"loaders":["fabric"],"versionType":"release","published":"2026-11-01T00:00:00Z","file":{"fileName":"sodium.jar","size":100},"dependencies":[]},
                  {"source":"modrinth","projectId":"AANobbMI","versionId":"beta-v","name":"Sodium 0.7.0-beta","versionNumber":"0.7.0-beta","gameVersions":["1.21.4"],"loaders":["fabric"],"versionType":"beta","published":"2026-11-10T00:00:00Z","file":{"fileName":"sodium-beta.jar","size":120},"dependencies":[{"projectId":"P7dR8mSH","versionId":null,"kind":"required","name":"Fabric API"}]},
                  {"source":"modrinth","projectId":"AANobbMI","versionId":"alpha-v","name":"Sodium 0.8.0-alpha","versionNumber":"0.8.0-alpha","gameVersions":["1.21.4"],"loaders":["fabric"],"versionType":"alpha","published":"2026-11-15T00:00:00Z","file":{"fileName":"sodium-alpha.jar","size":140},"dependencies":[]}
                ]}""",
            )

            "content.plan" -> json(
                """{"ok":true,"source":"modrinth","projectId":"AANobbMI","projectName":"Sodium","versionId":"beta-v","versionNumber":"0.7.0-beta","kind":"mod","files":2,"totalBytes":220,"warnings":[],"items":[
                  {"fileName":"sodium-beta.jar","projectId":"AANobbMI","projectName":"Sodium","versionId":"beta-v","versionNumber":"0.7.0-beta","dependency":false,"size":120},
                  {"fileName":"fabric-api.jar","projectId":"P7dR8mSH","projectName":"Fabric API","versionId":"api-v","versionNumber":"0.119.4","dependency":true,"size":100}
                ]}""",
            )

            "content.updates" -> json(
                """{"ok":true,"checked":2,"unknown":0,"warnings":[],"updates":[
                  {"kind":"mod","fileName":"sodium.jar","displayName":"sodium.jar","source":"modrinth","projectId":"AANobbMI","projectName":"Sodium","currentVersion":"0.6.12","latestVersion":"0.6.13","latestVersionId":"c3YkZvne"}
                ]}""",
            )

            "content.toggle", "content.delete" -> json("""{"ok":true}""")
            "content.install" -> {
                contentInstallRequests += params
                json(
                    """{"ok":true,"taskId":"content-task-1","source":"modrinth","projectId":"AANobbMI","projectName":"Sodium","versionId":"c3YkZvne","versionNumber":"0.6.13","kind":"mod","files":1,"totalBytes":100,"items":[]}""",
                )
            }
            "worlds.backup" -> json(
                """{"ok":true,"backup":{"name":"20261115-180000.zip","sizeBytes":10},"backups":[{"name":"20261115-180000.zip","sizeBytes":10}]}""",
            )
            "worlds.delete" -> json("""{"ok":true,"worlds":[]}""")
            "worlds.backup.delete" -> json("""{"ok":true,"backups":[]}""")

            "datapacks.list", "datapacks.toggle", "datapacks.delete", "datapacks.import" -> json(
                """{"ok":true,"count":1,"items":[
                  {"fileName":"terralith.zip","title":"Terralith","enabled":true,"sizeBytes":100,"modifiedMs":1793000000000,"packFormat":48}
                ]}""",
            )

            "diag.logs" -> json(
                """{"ok":true,"rules":15,"sources":[{"kind":"log","name":"latest.log","path":"/tmp/logs/latest.log","exists":true,"sizeBytes":42,"lines":1}],
                "crash":null,"texts":{"log":"java.lang.OutOfMemoryError: Java heap space"}}""",
            )

            "diag.analyze" -> json(
                """{"ok":true,"findings":[{"id":"java.memory","severity":"error","confidence":0.75,"title":"内存不足","body":"提高内存","excerpt":"OutOfMemoryError","line":1,"source":"latest.log","sourceKind":"log","fix":"openJavaSettings"}],"severity":"error","summary":"找到 1 个可能原因","scannedLines":1,"rules":15,"analyzedAtMs":1}""",
            )

            "diag.export" -> json(
                """{"ok":true,"bundle":{"path":"/tmp/diag.zip","sizeBytes":100,"files":["report.md"]},"analysis":{"findings":[],"summary":"","scannedLines":0,"rules":15}}""",
            )

            "modpack.inspect" -> json(
                """{"ok":true,"kind":"modrinth","name":"Calm","version":"1.0","minecraft":"1.21.4","loaderKind":"fabric","loaderVersion":"0.16.9","files":[{"path":"mods/a.jar","size":10,"url":"https://example/a.jar"}],"sourcePath":"/tmp/calm.mrpack","external":false,"skipped":0}""",
            )

            "modpack.install" -> {
                modpackInstallRequests += params
                json(
                    """{"ok":true,"taskId":"modpack-task-1","name":"Calm","versionId":"calm-v1","versionNumber":"1.0","source":"modrinth"}""",
                )
            }

            "modpack.import" -> json(
                """{"ok":true,"instanceId":"calm","taskId":"task-modpack","external":false}""",
            )

            "modpack.export.plan" -> json(
                """{"ok":true,"sections":[
                  {"id":"servers","label":"servers","files":1,"bytes":100,"default":false,"folder":false},
                  {"id":"configs","label":"configs","files":2,"bytes":200,"default":true,"folder":false},
                  {"id":"mods","label":"mods","files":3,"bytes":300,"default":true,"folder":false},
                  {"id":"gameSettings","label":"gameSettings","files":1,"bytes":50,"default":false,"folder":false},
                  {"id":"folder:kubejs","label":"kubejs","files":4,"bytes":400,"default":true,"folder":true}
                ]}""",
            )

            "modpack.export" -> json(
                """{"ok":true,"path":"/tmp/exports/calm.mrpack","sizeBytes":100,"files":2}""",
            )

            else -> json("""{"ok":true}""")
        }
    }

    override suspend fun setTheme(theme: ThemeMode): CoreSettings {
        settings = settings.copy(theme = theme)
        return settings
    }

    override fun nextEvents(): List<JsonObject> = emptyList()

    private fun accountsPayload(): String {
        val rows = accountRows.map { it.copy(selected = it.id == selectedAccountId) }
        val selected = selectedAccountId?.let { "\"$it\"" } ?: "null"
        return """{"ok":true,"selectedId":$selected,"accounts":${CoreJson.encodeToString(rows)}}"""
    }

    private fun addYggdrasilAccount(profileId: String, name: String): JsonObject {
        val id = "yggdrasil-$profileId"
        accountRows.removeAll { it.id == id }
        accountRows += CoreAccount(
            id = id,
            kind = "yggdrasil",
            name = name,
            uuid = "8f0c1d2e-3a4b-5c6d-7e8f-90a1b2c3d4e5",
            server = "https://littleskin.cn/api/yggdrasil",
            serverName = "LittleSkin",
        )
        selectedAccountId = id
        return json("""{"ok":true,"selectedId":"$id","account":${CoreJson.encodeToString(accountRows.first { it.id == id })}}""")
    }

    private fun json(raw: String): JsonObject = CoreJson.parseToJsonElement(raw).jsonObject

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}

/** In-memory platform services; nothing touches the host. */
internal class FakePlatform(
    private val clipboardSink: MutableList<String> = mutableListOf(),
) {
    /** Next single-file pick; `null` behaves like a dismissed picker. */
    var nextFilePick: String? = null

    private val preferences = object : LocalPreferences {
        override fun supportsDynamicColor(): Boolean = false
        override var windowFrame: WindowFrame? = null
        override var dataRootOverride: String? = null
        override var settingsSidebarWidth: Float = 0f
    }

    private val directories = object : DirectoryBrowser {
        override val isAvailable: Boolean = false
        override suspend fun pick(title: String, initialPath: String?): DirectoryPick =
            DirectoryPick.Unavailable("test")

        override fun listDirectories(path: String): List<DirectoryEntry> = emptyList()

        override fun parentDirectory(path: String): String? = null
    }

    private val files = object : FileBrowser {
        override val isAvailable: Boolean = true

        override suspend fun pick(title: String, extensions: List<String>): FilePick =
            nextFilePick?.let { FilePick.Picked(it) } ?: FilePick.Cancelled

        override suspend fun save(
            title: String,
            suggestedName: String,
            extensions: List<String>,
        ): FileSavePick = FileSavePick.Picked(
            path = "/tmp/fograin-test-$suggestedName",
            displayPath = suggestedName,
            direct = true,
        )

        override suspend fun writePicked(
            target: FileSavePick.Picked,
            sourcePath: String,
        ): Boolean = true
    }

    val services: PlatformServices = PlatformServices(
        platformName = "test",
        nativeLibrary = object : NativeLibrary {
            override fun ensureLoaded() = Unit
        },
        coreFactory = object : com.fograin.launcher.platform.CoreClientFactory {
            override fun create(): CoreClient = error("the tests pass a fake core")
        },
        deviceInfo = object : DeviceInfoProvider {
            override val totalMemoryBytes: Long = 16L * 1024 * 1024 * 1024
            override val deviceText: String = "Test Device"
            override val socText: String = "test-soc"
            override val cpuCount: Int = 8
        },
        clipboard = object : ClipboardService {
            override fun copy(text: String) {
                clipboardSink += text
            }
        },
        preferences = preferences,
        directories = directories,
        files = files,
        logger = object : AppLogger {
            override fun debug(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String, error: Throwable?) = Unit
            override fun error(tag: String, message: String, error: Throwable?) = Unit
        },
        appearance = object : SystemAppearance {
            override val supportsDynamicColor: Boolean = false

            @Composable
            override fun dynamicColorScheme(darkTheme: Boolean) = null

            @Composable
            override fun applySystemBars(darkTheme: Boolean) = Unit
        },
        backDispatcher = object : BackDispatcher {
            @Composable
            override fun handleBack(enabled: Boolean, onBack: () -> Unit) = Unit
        },
        links = object : SystemLinks {
            override fun revealDirectory(path: String): Boolean = false
            override fun openUrl(url: String): Boolean = false
        },
    )
}
