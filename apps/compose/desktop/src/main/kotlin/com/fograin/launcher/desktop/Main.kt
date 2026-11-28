package com.fograin.launcher.desktop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fograin.launcher.App
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.platform.AppRuntime
import com.fograin.launcher.platform.WindowFrame
import com.fograin.launcher.platform.createCoreClient
import com.fograin.launcher.platform.currentSystemLanguageTag
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.settings.AppLanguage
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.components.FograinMotion
import com.fograin.launcher.ui.components.LocalWindowCaptionRegionReporter
import com.fograin.launcher.ui.components.LocalWindowControlsInset
import com.fograin.launcher.ui.components.LocalWindowDragAreaActive
import com.fograin.launcher.ui.components.LocalWindowDragModifier
import com.fograin.launcher.ui.components.WindowChromeMetrics
import com.fograin.launcher.ui.settings.LocalSettingsTopInset
import com.fograin.launcher.ui.theme.FograinTheme
import java.awt.Color as AwtColor
import java.awt.Frame as AwtFrame
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.Image as SkiaImage

private val osName: String = System.getProperty("os.name").orEmpty()
private val isMac = osName.contains("mac", ignoreCase = true)

/**
 * Development aid for inspecting the app-drawn caption on a Mac:
 * `-Dfograin.window.chrome=custom`. Never set by the released application.
 */
private val forceCustomChrome = System.getProperty("fograin.window.chrome") == "custom"
private val windowChrome = windowChromeKind(osName, forceCustomChrome)

/** Windows-style caption drawn by Compose (also the custom dev preview). */
private val drawsAppCaption =
    windowChrome == WindowChromeKind.WINDOWS || windowChrome == WindowChromeKind.CUSTOM

/** Windows keeps its native frame styles but hides the non-client area. */
private val usesWindowsNativeFrame = windowChrome == WindowChromeKind.WINDOWS

/** macOS puts the traffic lights over the Compose content. */
private val usesImmersiveTitleBar = windowChrome == WindowChromeKind.MACOS

private fun shortcut(key: Key, shift: Boolean = false): KeyShortcut =
    if (isMac) KeyShortcut(key, meta = true, shift = shift) else KeyShortcut(key, ctrl = true, shift = shift)

/** 512 px PNG packaged next to the launcher classes. */
private val appIconBytes: ByteArray? by lazy {
    object {}.javaClass.getResourceAsStream("/icon.png")?.use { it.readBytes() }
}

private fun appIconPainter(): Painter? = appIconBytes?.let { bytes ->
    runCatching {
        BitmapPainter(SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap())
    }.getOrNull()
}

/**
 * The Java launcher's default icon is the JDK's coffee cup in `:desktop:run`.
 * Packaging still needs .icns/.ico assets; this keeps the
 * development shell from showing the JDK icon.
 */
private fun installTaskbarIcon() {
    if (GraphicsEnvironment.isHeadless()) return
    val bytes = appIconBytes ?: return
    val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return
    if (!java.awt.Taskbar.isTaskbarSupported()) return
    val taskbar = java.awt.Taskbar.getTaskbar()
    if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
        runCatching { taskbar.setIconImage(image) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Must run before AWT initializes: otherwise macOS paints the window title
    // bar with the light Aqua appearance even when the system is dark.
    if (isMac) {
        System.setProperty("apple.awt.application.appearance", "system")
        System.setProperty("apple.awt.application.name", "雾雨启动器")
    }

    val processStart = ProcessHandle.current().info().startInstant().orElse(null)
    val platform = platformServices()

    // Read launcher.toml / the data root before the first Compose frame. A
    // data folder chosen in settings applies on the next launch; the running
    // core keeps its already-open root.
    platform.preferences.dataRootOverride?.let { AppRuntime.dataRoot = it }
    val core = createCoreClient()
    // The shell owns the model so the menu bar can drive it; the shared App
    // composable only renders it.
    val model = AppModel.create(
        core = core,
        platform = platform,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )
    // Fire-and-forget: the shell paints the first frame while GitHub answers,
    // so a slow or blocked release feed never delays startup.
    model.checkForUpdates(initial = true)

    application {
        val state by model.state.collectAsState()
        val labels = menuStrings(state.language)

        // Restore geometry for every chrome style. A frame saved by an older
        // build may point at a monitor that is no longer connected, so
        // sanitise it before it reaches AWT.
        val workAreas = remember { screenWorkAreas() }
        val restoredFrame = remember {
            platform.preferences.windowFrame?.let { sanitizeWindowFrame(it, workAreas) }
        }
        val initiallyMaximized = restoredFrame?.maximized == true
        val restoredBounds = restoredFrame?.toRectangle()
        // A native frame restores through the OS placement: the window manager
        // maximises it with its own animation and work-area bounds. The
        // development-only custom chrome still moves the bounds by hand,
        // because AWT's MAXIMIZED_BOTH covers the taskbar on an undecorated
        // frame.
        val usesNativePlacement = windowChrome != WindowChromeKind.CUSTOM
        val initialBounds = restoredBounds?.let { bounds ->
            if (initiallyMaximized && !usesNativePlacement) {
                workAreaFor(bounds, workAreas)
            } else {
                bounds
            }
        }
        val windowState = rememberWindowState(
            position = initialBounds
                ?.let { WindowPosition(it.x.dp, it.y.dp) }
                ?: WindowPosition.PlatformDefault,
            size = initialBounds
                ?.let { DpSize(it.width.dp, it.height.dp) }
                ?: DpSize(1120.dp, 780.dp),
            placement = if (usesNativePlacement && initiallyMaximized) {
                WindowPlacement.Maximized
            } else {
                WindowPlacement.Floating
            },
        )
        // Custom preview only: the maximize rectangle the shell restores to.
        val customMaximized = remember { mutableStateOf(initiallyMaximized && !usesNativePlacement) }
        val customRestoreBounds = remember { mutableStateOf(restoredBounds) }
        // The last floating rectangle, kept while the OS maximizes or enters
        // full screen so the next launch reopens at a sane size.
        val floatingBounds = remember { mutableStateOf(restoredBounds) }
        val windowsCaptionRegions = remember {
            if (usesWindowsNativeFrame) WindowsCaptionRegions() else null
        }

        val persistAndExit: () -> Unit = {
            platform.preferences.windowFrame = if (usesNativePlacement) {
                nativeFrameToPersist(windowState, floatingBounds.value)
            } else {
                frameToPersist(
                    state = windowState,
                    maximized = customMaximized.value,
                    restoreBounds = customRestoreBounds.value,
                )
            }
            exitApplication()
        }

        val windowIcon = remember { appIconPainter() }
        val onPreviewKeyEvent = rememberDesktopShortcutHandler(
            model = model,
            // macOS still has the native menu bar; Windows/Linux and the
            // custom preview get the in-window shortcut handler.
            enabled = windowChrome != WindowChromeKind.MACOS,
            onQuit = persistAndExit,
        )
        val decoration = remember {
            if (windowChrome == WindowChromeKind.CUSTOM) {
                // Thin resize grips: the content (and scrollbars) run to the
                // window edge, so an 8 dp grip would eat too much of them.
                WindowDecoration.Undecorated(resizerThickness = 4.dp)
            } else {
                // Windows hides the non-client area from a WndProc but keeps
                // these styles (and therefore DWM animations, Snap and the
                // shadow). Linux lets the window manager draw the title bar.
                WindowDecoration.SystemDefault
            }
        }

        Window(
            onCloseRequest = persistAndExit,
            state = windowState,
            title = windowTitle(state.language),
            icon = windowIcon,
            decoration = decoration,
            onPreviewKeyEvent = onPreviewKeyEvent,
        ) {
            // Desktop drag-and-drop: dropping an .mrpack/.zip or a
            // MultiMC/Prism instance folder opens the same import sheet as the
            // home menu. Compose has no external-drop API on Window yet, so the
            // shell installs an AWT DropTarget.
            DisposableEffect(window) {
                val target = object : DropTarget() {
                    override fun dragEnter(dtde: DropTargetDragEvent) {
                        if (dtde.transferable?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true) {
                            dtde.acceptDrag(DnDConstants.ACTION_COPY)
                            model.modpack.setDragActive(true)
                        } else {
                            dtde.rejectDrag()
                        }
                    }

                    override fun dragOver(dtde: DropTargetDragEvent) {
                        if (dtde.transferable?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true) {
                            dtde.acceptDrag(DnDConstants.ACTION_COPY)
                        } else {
                            dtde.rejectDrag()
                        }
                    }

                    override fun dragExit(dtde: DropTargetEvent) {
                        model.modpack.setDragActive(false)
                    }

                    override fun drop(dtde: DropTargetDropEvent) {
                        model.modpack.setDragActive(false)
                        dtde.acceptDrop(DnDConstants.ACTION_COPY)
                        val files = runCatching {
                            @Suppress("UNCHECKED_CAST")
                            dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                        }.getOrNull().orEmpty()
                        val file = files.firstOrNull()
                        val supported = file != null && isSupportedDropFile(file)
                        if (supported) {
                            model.modpack.importPath(file.absolutePath)
                        } else {
                            model.showNotice("只支持 .mrpack、.zip 或 MultiMC/Prism 实例目录")
                        }
                        dtde.dropComplete(supported)
                    }
                }
                runCatching { window.dropTarget = target }
                onDispose { runCatching { window.dropTarget = null } }
            }

            // macOS keeps the native menu bar (and its accelerators). Windows
            // and Linux do not: the numbered File/Edit/Go strip was an obvious
            // non-native seam, and the same commands are reachable through the
            // UI and the shortcut handler below.
            if (windowChrome == WindowChromeKind.MACOS) {
                WindowMenuBar(
                    model = model,
                    language = state.language,
                    onExit = persistAndExit,
                )
            }

            // Windows keeps its native frame styles so DWM owns the animations,
            // Snap and the shadow; the procedure hides only the visible
            // non-client area and hands `HTCAPTION` back to the OS. Install it
            // before the first frame is presented.
            if (usesWindowsNativeFrame && windowsCaptionRegions != null) {
                DisposableEffect(window) {
                    val frame = installWindowsFrame(window, windowsCaptionRegions)
                    onDispose { frame.close() }
                }
            }

            LaunchedEffect(Unit) {
                if (windowChrome == WindowChromeKind.MACOS) {
                    // Full-window content + a transparent title bar put the
                    // native traffic lights inside the rail; the window title
                    // text is hidden so no separate top bar remains.
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
                installTaskbarIcon()
                if (System.getenv("FOGRAIN_STARTUP_LOG") != null) {
                    val elapsed = processStart
                        ?.let { Duration.between(it, Instant.now()).toMillis() }
                        ?: -1
                    println("FOGRAIN_STARTUP_MS=$elapsed")
                    println(
                        "FOGRAIN_WINDOW_CHROME=${windowChrome.name.lowercase()} " +
                            "undecorated=${window.isUndecorated} " +
                            "menu=${window.jMenuBar != null} " +
                            "insets=${window.insets}",
                    )
                }
            }

            DisposableEffect(window) {
                val listener = object : ComponentAdapter() {
                    override fun componentResized(event: ComponentEvent) {
                        trackGeometry()
                    }

                    override fun componentMoved(event: ComponentEvent) {
                        trackGeometry()
                    }

                    /**
                     * Remembers the last floating rectangle for the next launch
                     * and, for the custom preview, spots a manual maximize that
                     * the user ended by dragging the window edge.
                     */
                    private fun trackGeometry() {
                        if (windowChrome == WindowChromeKind.CUSTOM && customMaximized.value) {
                            val area = workAreaFor(window.bounds, screenWorkAreas())
                            if (window.bounds != area) {
                                customMaximized.value = false
                                customRestoreBounds.value = window.bounds
                            }
                        }
                        val placement = windowState.placement
                        val extendedState = (window as? AwtFrame)?.extendedState ?: 0
                        val canRecordFloatingBounds =
                            placement == WindowPlacement.Floating &&
                                extendedState and AwtFrame.MAXIMIZED_BOTH == 0 &&
                                extendedState and AwtFrame.ICONIFIED == 0
                        if (canRecordFloatingBounds) {
                            floatingBounds.value = window.bounds
                            // The custom preview maximizes by moving the
                            // bounds, so its "floating" state is still the
                            // live window size while maximized. Do not
                            // overwrite the rectangle it must restore to.
                            if (windowChrome == WindowChromeKind.CUSTOM &&
                                !customMaximized.value
                            ) {
                                customRestoreBounds.value = window.bounds
                            }
                        }
                    }
                }
                window.addComponentListener(listener)
                onDispose { window.removeComponentListener(listener) }
            }

            FograinTheme(
                themeMode = state.theme,
                accent = state.accent.value,
            ) {
                val windowBackground = MaterialTheme.colorScheme.surface
                // Compose paints over this, but a matching AWT background
                // avoids the white flash during the first frame, resize drags
                // and the brief gap when a sheet animates in.
                SideEffect {
                    val awtColor = AwtColor(windowBackground.toArgb(), false)
                    if (window.background != awtColor) {
                        window.background = awtColor
                        window.rootPane.background = awtColor
                    }
                    if (window.minimumSize.width != MinimumWindowSize.width ||
                        window.minimumSize.height != MinimumWindowSize.height
                    ) {
                        window.minimumSize = MinimumWindowSize
                    }
                }

                fun toggleMaximize() {
                    when (windowChrome) {
                        // The custom preview moves the bounds itself because
                        // MAXIMIZED_BOTH covers the taskbar on an undecorated
                        // frame.
                        WindowChromeKind.CUSTOM -> {
                            val areas = screenWorkAreas()
                            if (customMaximized.value) {
                                val target = customRestoreBounds.value
                                    ?.let {
                                        sanitizeWindowFrame(it.toWindowFrame(), areas).toRectangle()
                                    }
                                    ?: window.bounds
                                customMaximized.value = false
                                window.setBounds(target)
                            } else {
                                val current = window.bounds
                                customRestoreBounds.value = current
                                val area = workAreaFor(current, areas)
                                customMaximized.value = true
                                window.setBounds(area)
                            }
                        }

                        // Windows asks the OS for the placement. AWT calls
                        // ShowWindow(SW_MAXIMIZE/SW_RESTORE), which is what
                        // makes DWM play the native maximize/restore
                        // animation (and keeps Snap and Win+Arrow in sync).
                        WindowChromeKind.WINDOWS -> {
                            windowState.placement =
                                if (windowState.placement == WindowPlacement.Maximized) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Maximized
                                }
                        }

                        else -> Unit
                    }
                }

                val windowFocused = rememberWindowFocused(window)
                // The macOS traffic-light strip disappears in native full
                // screen. Animating the inset instead of switching it keeps
                // the settings sidebar and content from jumping 38 dp on the
                // frame the system reports the new placement.
                val animatedSettingsTopInset by animateDpAsState(
                    targetValue = if (
                        usesImmersiveTitleBar &&
                        windowState.placement != WindowPlacement.Fullscreen
                    ) {
                        38.dp
                    } else {
                        0.dp
                    },
                    animationSpec = FograinMotion.LayoutDp,
                    label = "settings-top-inset",
                )
                // A spring may overshoot zero; padding must stay non-negative.
                val settingsTopInset = animatedSettingsTopInset.coerceAtLeast(0.dp)
                val isMaximized = when (windowChrome) {
                    WindowChromeKind.CUSTOM -> customMaximized.value
                    WindowChromeKind.WINDOWS -> windowState.placement == WindowPlacement.Maximized
                    else -> false
                }
                // The page header applies this to its title slot; the search
                // field and action buttons sit outside it, so only the title
                // area starts a drag or a double-click maximize.
                //
                // Windows does not use this gesture: its title strips report
                // their bounds through LocalWindowCaptionRegionReporter and
                // the native WndProc answers HTCAPTION, which gives Snap and
                // the system drag loop instead of a Compose `setLocation`.
                val dragModifier = remember(window, windowChrome, isMaximized) {
                    when (windowChrome) {
                        WindowChromeKind.MACOS, WindowChromeKind.CUSTOM -> windowDragModifier(
                            window = window,
                            dragEnabled = !(windowChrome == WindowChromeKind.CUSTOM && isMaximized),
                            onDoubleClick = if (windowChrome == WindowChromeKind.CUSTOM) {
                                { toggleMaximize() }
                            } else {
                                null
                            },
                        )

                        else -> Modifier
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalWindowDragModifier provides dragModifier,
                        LocalWindowDragAreaActive provides (
                            windowChrome == WindowChromeKind.MACOS ||
                                windowChrome == WindowChromeKind.WINDOWS ||
                                windowChrome == WindowChromeKind.CUSTOM
                            ),
                        LocalWindowCaptionRegionReporter provides windowsCaptionRegions,
                        LocalWindowControlsInset provides if (drawsAppCaption) {
                            WindowChromeMetrics.ControlsInset
                        } else {
                            0.dp
                        },
                        // Settings is an in-window page; on macOS it owns the
                        // traffic-light strip exactly like the shell's rail.
                        // Full screen hides the lights, so the strip collapses.
                        LocalSettingsTopInset provides settingsTopInset,
                    ) {
                        App(model)
                    }

                    if (drawsAppCaption) {
                        WindowControls(
                            isMaximized = isMaximized,
                            focused = windowFocused,
                            labels = WindowControlLabels(
                                minimize = labels.windowMinimize,
                                maximize = labels.windowMaximize,
                                restore = labels.windowRestore,
                                close = labels.windowClose,
                            ),
                            onMinimize = { windowState.isMinimized = true },
                            onToggleMaximize = { toggleMaximize() },
                            onClose = persistAndExit,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Installs the shortcuts that used to live in the native menu bar.
 *
 * Used on Windows/Linux (and the custom preview). Keys are latched on KeyDown and released on
 * KeyUp so auto-repeat cannot toggle the theme (or re-open a sheet) dozens of
 * times while a key is held.
 */
@Composable
private fun rememberDesktopShortcutHandler(
    model: AppModel,
    enabled: Boolean,
    onQuit: () -> Unit,
): (KeyEvent) -> Boolean {
    val pressedKeys = remember { mutableSetOf<Key>() }
    val currentOnQuit by rememberUpdatedState(onQuit)
    return remember(model, enabled) {
        { event ->
            when {
                !enabled -> false
                event.type == KeyEventType.KeyUp -> {
                    pressedKeys.remove(event.key)
                    false
                }

                event.type != KeyEventType.KeyDown -> false
                else -> {
                    val command = desktopCommand(
                        key = event.key,
                        ctrl = event.isCtrlPressed,
                        meta = event.isMetaPressed,
                        alt = event.isAltPressed,
                        shift = event.isShiftPressed,
                    )
                    if (command == null) {
                        false
                    } else {
                        if (pressedKeys.add(event.key)) {
                            executeDesktopCommand(command, model, currentOnQuit)
                        }
                        true
                    }
                }
            }
        }
    }
}

private fun executeDesktopCommand(
    command: DesktopCommand,
    model: AppModel,
    onQuit: () -> Unit,
) {
    when (command) {
        DesktopCommand.NEW_INSTANCE -> model.library.openAddInstance()
        DesktopCommand.OPEN_LIBRARY, DesktopCommand.LIBRARY ->
            model.navigate(AppDestination.LIBRARY)

        DesktopCommand.OPEN_SETTINGS, DesktopCommand.SETTINGS ->
            model.openSettings()

        DesktopCommand.QUIT -> onQuit()
        DesktopCommand.GLOBAL_SEARCH -> model.openGlobalSearch()
        DesktopCommand.TOGGLE_THEME -> model.toggleTheme()
        DesktopCommand.HOME -> model.navigate(AppDestination.HOME)
        DesktopCommand.DOWNLOADS -> model.navigate(AppDestination.DOWNLOADS)
    }
}

/** Native menu bar, kept for macOS. */
@Composable
private fun FrameWindowScope.WindowMenuBar(
    model: AppModel,
    language: AppLanguage,
    onExit: () -> Unit,
) {
    val labels = menuStrings(language)
    MenuBar {
        Menu(labels.file, mnemonic = 'F') {
            Item(
                labels.newInstance,
                shortcut = shortcut(Key.N),
                onClick = { model.library.openAddInstance() },
            )
            Item(
                labels.importMc,
                shortcut = shortcut(Key.I),
                onClick = { model.navigate(AppDestination.LIBRARY) },
            )
            Separator()
            Item(
                labels.settings,
                shortcut = if (isMac) {
                    KeyShortcut(Key.Comma, meta = true)
                } else {
                    KeyShortcut(Key.Comma, ctrl = true)
                },
                onClick = { model.openSettings() },
            )
            Separator()
            Item(labels.quit, shortcut = shortcut(Key.Q), onClick = onExit)
        }
        Menu(labels.edit, mnemonic = 'E') {
            Item(
                labels.globalSearch,
                shortcut = shortcut(Key.F),
                onClick = { model.openGlobalSearch() },
            )
            Item(
                labels.toggleTheme,
                shortcut = shortcut(Key.T, shift = true),
                onClick = { model.toggleTheme() },
            )
        }
        Menu(labels.go, mnemonic = 'V') {
            AppDestination.entries.forEachIndexed { index, destination ->
                Item(
                    menuLabel(destination, labels),
                    shortcut = shortcut(DESTINATION_KEYS[index]),
                    onClick = { model.navigate(destination) },
                )
            }
        }
    }
}

/** Native menu labels. They live outside Compose resources because AWT menus
 * are built outside the shared AppLocale tree. */
private data class MenuStrings(
    val file: String,
    val edit: String,
    val go: String,
    val newInstance: String,
    val importMc: String,
    val settings: String,
    val quit: String,
    val globalSearch: String,
    val toggleTheme: String,
    val home: String,
    val library: String,
    val downloads: String,
    val community: String,
    val settingsTitle: String,
    val windowMinimize: String,
    val windowMaximize: String,
    val windowRestore: String,
    val windowClose: String,
)

private fun menuStrings(language: AppLanguage): MenuStrings =
    when (language.resolve(currentSystemLanguageTag())) {
        AppLanguage.TRADITIONAL_CHINESE -> MenuStrings(
            file = "檔案", edit = "編輯", go = "前往",
            newInstance = "新增實例", importMc = "匯入 .minecraft 目錄…",
            settings = "設定…", quit = "結束", globalSearch = "全域搜尋",
            toggleTheme = "切換主題", home = "首頁", library = "實例",
            downloads = "下載", community = "社群", settingsTitle = "設定",
            windowMinimize = "最小化", windowMaximize = "最大化",
            windowRestore = "還原", windowClose = "關閉視窗",
        )

        AppLanguage.JAPANESE -> MenuStrings(
            file = "ファイル", edit = "編集", go = "移動",
            newInstance = "新しいインスタンス", importMc = ".minecraft フォルダーをインポート…",
            settings = "設定…", quit = "終了", globalSearch = "全体検索",
            toggleTheme = "テーマを切り替え", home = "ホーム", library = "インスタンス",
            downloads = "ダウンロード", community = "コミュニティ", settingsTitle = "設定",
            windowMinimize = "最小化", windowMaximize = "最大化",
            windowRestore = "元に戻す", windowClose = "ウィンドウを閉じる",
        )

        AppLanguage.ENGLISH -> MenuStrings(
            file = "File", edit = "Edit", go = "Go",
            newInstance = "New instance", importMc = "Import .minecraft folder…",
            settings = "Settings…", quit = "Quit", globalSearch = "Global search",
            toggleTheme = "Toggle theme", home = "Home", library = "Instances",
            downloads = "Downloads", community = "Community", settingsTitle = "Settings",
            windowMinimize = "Minimize", windowMaximize = "Maximize",
            windowRestore = "Restore", windowClose = "Close window",
        )

        else -> MenuStrings(
            file = "文件", edit = "编辑", go = "前往",
            newInstance = "新建实例", importMc = "导入 .minecraft 目录…",
            settings = "设置…", quit = "退出", globalSearch = "全局搜索",
            toggleTheme = "切换主题", home = "主页", library = "实例",
            downloads = "下载", community = "社区", settingsTitle = "设置",
            windowMinimize = "最小化", windowMaximize = "最大化",
            windowRestore = "还原", windowClose = "关闭窗口",
        )
    }

private fun windowTitle(language: AppLanguage): String =
    when (language.resolve(currentSystemLanguageTag())) {
        AppLanguage.TRADITIONAL_CHINESE -> "霧雨啟動器"
        AppLanguage.JAPANESE -> "霧雨ランチャー"
        AppLanguage.ENGLISH -> "Fograin Launcher"
        else -> "雾雨启动器"
    }

/** Drop filter shared with `DropTargetTest`: archives and MultiMC instance folders. */
internal fun isSupportedDropFile(file: File): Boolean =
    file.isDirectory ||
        file.name.endsWith(".mrpack", ignoreCase = true) ||
        file.name.endsWith(".zip", ignoreCase = true)

private val DESTINATION_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four)

private fun menuLabel(destination: AppDestination, labels: MenuStrings): String =
    when (destination) {
        AppDestination.HOME -> labels.home
        AppDestination.LIBRARY -> labels.library
        AppDestination.DOWNLOADS -> labels.downloads
        AppDestination.COMMUNITY -> labels.community
    }

/**
 * Snapshot for persistence. A maximized window keeps its floating size so
 * restoring and un-maximizing returns to a sane rectangle.
 */
internal fun windowFrame(state: androidx.compose.ui.window.WindowState): WindowFrame {
    val position = state.position
    val width = state.size.width.value.toInt()
    val height = state.size.height.value.toInt()
    val maximized = state.placement == WindowPlacement.Maximized
    return if (position is WindowPosition.Absolute) {
        WindowFrame(
            x = position.x.value.toInt(),
            y = position.y.value.toInt(),
            width = width,
            height = height,
            maximized = maximized,
        )
    } else {
        WindowFrame(x = 0, y = 0, width = width, height = height, maximized = maximized)
    }
}