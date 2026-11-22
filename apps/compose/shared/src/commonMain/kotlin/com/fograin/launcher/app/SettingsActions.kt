package com.fograin.launcher.app

import com.fograin.launcher.platform.CoreDownloadSettings
import com.fograin.launcher.platform.CoreGameConfig
import com.fograin.launcher.platform.CoreLauncherSettings
import com.fograin.launcher.platform.CoreLauncherSettingsUpdate
import com.fograin.launcher.platform.CoreNetworkSettings
import com.fograin.launcher.platform.decode
import com.fograin.launcher.settings.AccentPreference
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.settings.InitialPage
import com.fograin.launcher.settings.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The settings destination's controller.
 *
 * Appearance lives in `launcher.toml` and is mirrored into [AppUiState] so the
 * theme and language change immediately; download/network settings are
 * launcher-wide and only take effect after a restart. Game defaults keep using
 * `gameConfig.set` and the Java policy uses `java.policy.set`, so there is one
 * writer per section instead of a catch-all settings endpoint.
 */
class SettingsActions(private val model: AppModel) {

    /** Serializes writes so two quick toggles cannot interleave read-modify-write. */
    private val writeMutex = Mutex()
    private var loadGeneration = 0

    fun open(section: SettingsSection? = null) {
        model.setState { state ->
            state.copy(
                settingsWindow = state.settingsWindow.copy(
                    open = true,
                    section = section ?: state.settingsWindow.section,
                    detailOpen = section != null,
                    sectionHistory = emptyList(),
                    sectionForward = true,
                ),
            )
        }
        load()
    }

    fun close() {
        model.setState {
            it.copy(
                settingsWindow = it.settingsWindow.copy(
                    open = false,
                    detailOpen = false,
                    sectionHistory = emptyList(),
                    sectionForward = true,
                ),
            )
        }
    }

    /** Top-level category navigation from the sidebar or the compact list. */
    fun select(section: SettingsSection) {
        model.setState { state ->
            val window = state.settingsWindow
            if (window.section == section && window.detailOpen) return@setState state
            state.copy(
                settingsWindow = window.copy(
                    section = section,
                    detailOpen = window.compact,
                    // A sidebar/list pick is a new root, not a child page.
                    sectionHistory = emptyList(),
                    sectionForward = true,
                ),
            )
        }
    }

    /**
     * Nested shortcut such as "General → Java runtimes". Always pushes the
     * current section so Back returns to it on both compact and wide layouts.
     */
    fun push(section: SettingsSection) {
        model.setState { state ->
            val window = state.settingsWindow
            if (window.section == section) return@setState state
            state.copy(
                settingsWindow = window.copy(
                    section = section,
                    detailOpen = true,
                    sectionHistory = window.sectionHistory + window.section,
                    sectionForward = true,
                ),
            )
        }
    }

    /**
     * Back inside settings: pop a pushed section, then the compact detail page,
     * and finally close settings. Wide layouts have no list stack, so Back
     * closes settings directly.
     */
    fun back() {
        val current = model.state.value.settingsWindow
        when {
            // A nested section push (General → Java → General) works on both
            // compact and wide layouts.
            current.sectionHistory.isNotEmpty() -> {
                model.setState { state ->
                    val window = state.settingsWindow
                    state.copy(
                        settingsWindow = window.copy(
                            section = window.sectionHistory.last(),
                            detailOpen = true,
                            sectionHistory = window.sectionHistory.dropLast(1),
                            sectionForward = false,
                        ),
                    )
                }
            }

            // Compact detail root: animate back to the category list.
            current.compact && current.detailOpen -> {
                model.setState {
                    it.copy(
                        settingsWindow = it.settingsWindow.copy(
                            detailOpen = false,
                            sectionHistory = emptyList(),
                            sectionForward = false,
                        ),
                    )
                }
            }

            // Wide layout has no list stack; Back closes settings.
            else -> close()
        }
    }

    /** Tracks the responsive layout so [back] knows whether a list exists. */
    fun setCompact(compact: Boolean) {
        if (model.state.value.settingsWindow.compact == compact) return
        model.setState {
            it.copy(
                settingsWindow = it.settingsWindow.copy(
                    compact = compact,
                    // A wide pane shows the selected category directly; going
                    // back to a narrow window starts from the category list.
                    detailOpen = if (compact) it.settingsWindow.detailOpen else false,
                    sectionHistory = if (compact) it.settingsWindow.sectionHistory else emptyList(),
                ),
            )
        }
    }

    fun load() {
        if (model.state.value.settingsWindow.isLoading) return
        loadGeneration += 1
        val generation = loadGeneration
        model.setState {
            it.copy(settingsWindow = it.settingsWindow.copy(isLoading = true, error = null))
        }
        model.scope.launch {
            try {
                val settings = model.core.rpc("settings.get").decode<CoreLauncherSettings>()
                if (generation != loadGeneration) return@launch
                applyLoaded(settings)
                model.setState {
                    it.copy(
                        settingsWindow = it.settingsWindow.copy(
                            isLoading = false,
                            loaded = true,
                            error = null,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != loadGeneration) return@launch
                model.setState {
                    it.copy(
                        settingsWindow = it.settingsWindow.copy(
                            isLoading = false,
                            error = failure(error),
                        ),
                    )
                }
            }
        }
    }

    private fun applyLoaded(settings: CoreLauncherSettings) {
        model.setState { state ->
            state.copy(
                theme = ThemeMode.fromWire(settings.theme),
                language = AppLanguage.fromWire(settings.language),
                accent = AccentPreference.fromWire(settings.accent),
                initialPage = InitialPage.fromWire(settings.initialPage),
                settingsWindow = state.settingsWindow.copy(
                    download = settings.download,
                    network = settings.network,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Appearance
    // ------------------------------------------------------------------

    fun setTheme(theme: ThemeMode) {
        if (theme == model.state.value.theme) return
        model.setState { it.copy(theme = theme) }
        persist(buildJsonObject { put("theme", theme.wireValue) })
    }

    fun setLanguage(language: AppLanguage) {
        if (language == model.state.value.language) return
        model.setState { it.copy(language = language) }
        persist(buildJsonObject { put("language", language.wireValue) })
    }

    fun setAccent(accent: AccentPreference) {
        if (accent == model.state.value.accent) return
        model.setState { it.copy(accent = accent) }
        persist(buildJsonObject { put("accent", accent.value) })
    }

    fun setInitialPage(page: InitialPage) {
        if (page == model.state.value.initialPage) return
        model.setState { it.copy(initialPage = page) }
        persist(buildJsonObject { put("initialPage", page.wireValue) })
    }

    /**
     * Writes one appearance field. The optimistic value stays when the write
     * fails and [SettingsUiState.error] explains why; a later successful save
     * replaces both from the returned snapshot.
     */
    private fun persist(patch: JsonObject) {
        model.scope.launch {
            writeMutex.withLock {
                try {
                    val update = model.core.rpc("settings.set", patch)
                        .decode<CoreLauncherSettingsUpdate>()
                    applyLoaded(update.settings)
                    model.setState {
                        it.copy(
                            settings = update.snapshot,
                            theme = update.snapshot.theme,
                            language = AppLanguage.fromWire(update.snapshot.language),
                            accent = AccentPreference.fromWire(update.snapshot.accent),
                            initialPage = InitialPage.fromWire(update.snapshot.initialPage),
                            settingsWindow = it.settingsWindow.copy(error = null),
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    model.setState {
                        it.copy(
                            settingsWindow = it.settingsWindow.copy(error = failure(error)),
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Game defaults (global [game] section)
    // ------------------------------------------------------------------

    fun configureGame(patch: JsonObject) {
        model.scope.launch {
            writeMutex.withLock {
                try {
                    val config = model.core.rpc("gameConfig.set", patch).decode<CoreGameConfig>()
                    model.setState {
                        it.copy(
                            gameConfig = config,
                            settingsWindow = it.settingsWindow.copy(error = null),
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    model.setState {
                        it.copy(
                            settingsWindow = it.settingsWindow.copy(error = failure(error)),
                        )
                    }
                }
            }
        }
    }

    fun setMemoryMb(memoryMb: Int) {
        val current = model.state.value.gameConfig?.memoryMb ?: 0
        if (memoryMb == current) return
        model.setState {
            it.copy(gameConfig = (it.gameConfig ?: CoreGameConfig()).copy(memoryMb = memoryMb))
        }
        configureGame(buildJsonObject { put("memoryMb", memoryMb) })
    }

    fun setJvmArgs(args: String) {
        if (args == model.state.value.gameConfig?.jvmArgs) return
        model.setState {
            it.copy(gameConfig = (it.gameConfig ?: CoreGameConfig()).copy(jvmArgs = args))
        }
        configureGame(buildJsonObject { put("jvmArgs", args) })
    }

    fun setFullscreen(enabled: Boolean) {
        if (enabled == model.state.value.gameConfig?.fullscreen) return
        model.setState {
            it.copy(gameConfig = (it.gameConfig ?: CoreGameConfig()).copy(fullscreen = enabled))
        }
        configureGame(buildJsonObject { put("fullscreen", enabled) })
    }

    fun setShowLog(enabled: Boolean) {
        if (enabled == model.state.value.gameConfig?.showLog) return
        model.setState {
            it.copy(gameConfig = (it.gameConfig ?: CoreGameConfig()).copy(showLog = enabled))
        }
        configureGame(buildJsonObject { put("showLog", enabled) })
    }

    fun setAutoCompleteResources(enabled: Boolean) {
        if (enabled == model.state.value.gameConfig?.autoCompleteResources) return
        model.setState {
            it.copy(
                gameConfig = (it.gameConfig ?: CoreGameConfig())
                    .copy(autoCompleteResources = enabled),
            )
        }
        configureGame(buildJsonObject { put("autoCompleteResources", enabled) })
    }

    fun setResolution(width: Int?, height: Int?) {
        val resolution = if (width == null || height == null) null else listOf(width, height)
        model.setState {
            it.copy(gameConfig = (it.gameConfig ?: CoreGameConfig()).copy(resolution = resolution))
        }
        configureGame(
            buildJsonObject {
                if (resolution == null) {
                    put("resolution", kotlinx.serialization.json.JsonNull)
                } else {
                    put(
                        "resolution",
                        kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive(width))
                            add(kotlinx.serialization.json.JsonPrimitive(height))
                        },
                    )
                }
            },
        )
    }

    // ------------------------------------------------------------------
    // Download engine
    // ------------------------------------------------------------------

    fun updateDownload(transform: (CoreDownloadSettings) -> CoreDownloadSettings) {
        val current = model.state.value.settingsWindow.download
        val next = transform(current)
        if (next == current) return
        model.setState {
            it.copy(settingsWindow = it.settingsWindow.copy(download = next))
        }
        persist(
            buildJsonObject {
                put(
                    "download",
                    buildJsonObject {
                        put("source", next.source)
                        put("versionSource", next.versionSource)
                        put("concurrency", next.concurrency)
                        put("perHost", next.perHost)
                        put("rateLimitKib", next.rateLimitKib)
                        put("segments", next.segments)
                        put("segmentThresholdMb", next.segmentThresholdMb)
                        put("retries", next.retries)
                    },
                )
            },
        )
    }

    // ------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------

    fun updateNetwork(transform: (CoreNetworkSettings) -> CoreNetworkSettings) {
        val current = model.state.value.settingsWindow.network
        val next = transform(current)
        if (next == current) return
        model.setState {
            it.copy(settingsWindow = it.settingsWindow.copy(network = next))
        }
        persist(
            buildJsonObject {
                put(
                    "network",
                    buildJsonObject {
                        put("proxy", next.proxy)
                        put("timeoutSecs", next.timeoutSecs)
                    },
                )
            },
        )
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    /** Stores the folder the next launch should use; no files are moved. */
    fun setDataRootOverride(path: String?) {
        model.platform.preferences.dataRootOverride = path
        model.setState {
            it.copy(
                settingsWindow = it.settingsWindow.copy(dataRootOverride = path),
            )
        }
    }

    fun refreshDataRootOverride() {
        val path = model.platform.preferences.dataRootOverride
        if (path != model.state.value.settingsWindow.dataRootOverride) {
            model.setState {
                it.copy(settingsWindow = it.settingsWindow.copy(dataRootOverride = path))
            }
        }
    }

    private fun failure(error: Throwable): SettingsFailure = when (error) {
        is com.fograin.launcher.platform.CoreException -> SettingsFailure(
            messageKey = error.payload.messageKey,
            detail = error.payload.detail,
        )

        else -> SettingsFailure(detail = error.message ?: error::class.simpleName)
    }
}
