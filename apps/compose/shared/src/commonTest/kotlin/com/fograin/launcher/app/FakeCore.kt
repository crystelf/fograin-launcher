package com.fograin.launcher.app

import androidx.compose.runtime.Composable
import com.fograin.launcher.platform.AppLogger
import com.fograin.launcher.platform.BackDispatcher
import com.fograin.launcher.platform.ClipboardService
import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.platform.CoreJson
import com.fograin.launcher.platform.CoreSettings
import com.fograin.launcher.platform.DirectoryBrowser
import com.fograin.launcher.platform.DirectoryEntry
import com.fograin.launcher.platform.DirectoryPick
import com.fograin.launcher.platform.DeviceInfoProvider
import com.fograin.launcher.platform.LocalPreferences
import com.fograin.launcher.platform.NativeLibrary
import com.fograin.launcher.platform.PlatformServices
import com.fograin.launcher.platform.SystemAppearance
import com.fograin.launcher.platform.WindowFrame
import com.fograin.launcher.settings.ThemeMode
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

    override val initError: String? = null
    override val abiVersion: Int = 1
    override val isAvailable: Boolean = true
    override var settings: CoreSettings = CoreSettings(
        abiVersion = 1,
        appVersion = "0.1.0",
        platform = "test",
        theme = ThemeMode.DARK,
        dataRoot = "/tmp/fograin-test",
        configPath = "/tmp/fograin-test/launcher.toml",
    )
        private set

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

            "tasks.list" -> json("""{"ok":true,"result":[]}""")

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

            "instances.settings.get" -> json(
                """{"ok":true,
                   "effective":{"username":"Steve_Fog","memoryMb":6144,"java":null,"jvmArgs":"","resolution":[1920,1080],"fullscreen":false,"showLog":false,"autoCompleteResources":true},
                   "overrides":{"java":null,"memoryMb":6144,"jvmArgs":"","resolution":[1920,1080],"fullscreen":false,"gameDir":null},
                   "globals":{"username":"Steve_Fog","memoryMb":0,"java":null,"jvmArgs":"","resolution":null,"fullscreen":false,"showLog":false,"autoCompleteResources":true}}""",
            )

            "instances.dir" -> json("""{"ok":true,"gameDir":"/tmp/fograin-test/instances/fog-forest"}""")

            else -> json("""{"ok":true}""")
        }
    }

    override suspend fun setTheme(theme: ThemeMode): CoreSettings {
        settings = settings.copy(theme = theme)
        return settings
    }

    override fun nextEvents(): List<JsonObject> = emptyList()

    private fun json(raw: String): JsonObject = CoreJson.parseToJsonElement(raw).jsonObject

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}

/** In-memory platform services; nothing touches the host. */
internal class FakePlatform(
    private val clipboardSink: MutableList<String> = mutableListOf(),
) {
    private val preferences = object : LocalPreferences {
        override var dynamicColor: Boolean = false
        override fun supportsDynamicColor(): Boolean = false
        override var windowFrame: WindowFrame? = null
    }

    private val directories = object : DirectoryBrowser {
        override val isAvailable: Boolean = false
        override suspend fun pick(title: String, initialPath: String?): DirectoryPick =
            DirectoryPick.Unavailable("test")

        override fun listDirectories(path: String): List<DirectoryEntry> = emptyList()

        override fun parentDirectory(path: String): String? = null
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
        },
        clipboard = object : ClipboardService {
            override fun copy(text: String) {
                clipboardSink += text
            }
        },
        preferences = preferences,
        directories = directories,
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
    )
}
