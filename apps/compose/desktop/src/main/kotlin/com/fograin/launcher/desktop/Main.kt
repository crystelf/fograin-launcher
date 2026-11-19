package com.fograin.launcher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fograin.launcher.App
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.platform.WindowFrame
import com.fograin.launcher.platform.createCoreClient
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.components.PageHeaderMetrics
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.Image as SkiaImage

private val isMac = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

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
 * R5 still owns the .icns/.ico for packaged distributions; this keeps the
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

fun main() {
    // Must run before AWT initializes: otherwise macOS paints the window title
    // bar with the light Aqua appearance even when the system is dark.
    if (isMac) {
        System.setProperty("apple.awt.application.appearance", "system")
        System.setProperty("apple.awt.application.name", "雾雨启动器")
    }

    val processStart = ProcessHandle.current().info().startInstant().orElse(null)
    val platform = platformServices()

    // Read launcher.toml / the data root before the first Compose frame.
    val core = createCoreClient()
    // The shell owns the model so the menu bar can drive it; the shared App
    // composable only renders it.
    val model = AppModel.create(
        core = core,
        platform = platform,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    application {
        val saved = remember { platform.preferences.windowFrame }
        val windowState = rememberWindowState(
            position = saved?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
            size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1120.dp, 780.dp),
            placement = if (saved?.maximized == true) {
                WindowPlacement.Maximized
            } else {
                WindowPlacement.Floating
            },
        )

        val windowIcon = remember { appIconPainter() }
        Window(
            onCloseRequest = {
                platform.preferences.windowFrame = windowFrame(windowState)
                exitApplication()
            },
            title = "雾雨启动器",
            icon = windowIcon,
            state = windowState,
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
                            model.setModpackDragActive(true)
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
                        model.setModpackDragActive(false)
                    }

                    override fun drop(dtde: DropTargetDropEvent) {
                        model.setModpackDragActive(false)
                        dtde.acceptDrop(DnDConstants.ACTION_COPY)
                        val files = runCatching {
                            @Suppress("UNCHECKED_CAST")
                            dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                        }.getOrNull().orEmpty()
                        val file = files.firstOrNull()
                        val supported = file != null && isSupportedDropFile(file)
                        if (supported) {
                            model.importModpackPath(file.absolutePath)
                        } else {
                            model.showNotice("只支持 .mrpack、.zip 或 MultiMC/Prism 实例目录")
                        }
                        dtde.dropComplete(supported)
                    }
                }
                runCatching { window.dropTarget = target }
                onDispose { runCatching { window.dropTarget = null } }
            }

            WindowMenuBar(
                model = model,
                onExit = {
                    platform.preferences.windowFrame = windowFrame(windowState)
                    exitApplication()
                },
            )

            LaunchedEffect(Unit) {
                // Full-window content + a transparent title bar put the native
                // traffic lights inside the rail; the window title text is
                // hidden so no separate top bar remains.
                if (isMac) {
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
                }
            }

            Box(Modifier.fillMaxSize()) {
                App(model)
                if (isMac) {
                    // Full-window content hides the native drag area. Keep the
                    // title region right of the traffic lights and past the
                    // back button draggable, but stop before the header actions
                    // so back, avatar, search and page buttons stay clickable.
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth(0.5f)
                            .height(PageHeaderMetrics.MacHeight)
                            .padding(start = 168.dp),
                    ) {
                        WindowDraggableArea(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** Native menu bar shared by the three desktop targets. */
@Composable
private fun FrameWindowScope.WindowMenuBar(model: AppModel, onExit: () -> Unit) {
    MenuBar {
        Menu("文件", mnemonic = 'F') {
            Item(
                "新建实例",
                shortcut = shortcut(Key.N),
                onClick = { model.openAddInstance() },
            )
            Item(
                "导入 .minecraft 目录…",
                shortcut = shortcut(Key.I),
                onClick = { model.navigate(AppDestination.LIBRARY) },
            )
            Separator()
            Item("退出", shortcut = shortcut(Key.Q), onClick = onExit)
        }
        Menu("编辑", mnemonic = 'E') {
            Item(
                "全局搜索",
                shortcut = shortcut(Key.F),
                onClick = { model.openGlobalSearch() },
            )
            Item(
                "切换主题",
                shortcut = shortcut(Key.T, shift = true),
                onClick = { model.toggleTheme() },
            )
        }
        Menu("前往", mnemonic = 'V') {
            AppDestination.entries.forEachIndexed { index, destination ->
                Item(
                    menuLabel(destination),
                    shortcut = shortcut(DESTINATION_KEYS[index]),
                    onClick = { model.navigate(destination) },
                )
            }
        }
    }
}

/** Drop filter shared with `DropTargetTest`: archives and MultiMC instance folders. */
internal fun isSupportedDropFile(file: File): Boolean =
    file.isDirectory ||
        file.name.endsWith(".mrpack", ignoreCase = true) ||
        file.name.endsWith(".zip", ignoreCase = true)

private val DESTINATION_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four)

private fun menuLabel(destination: AppDestination): String = when (destination) {
    AppDestination.HOME -> "主页"
    AppDestination.LIBRARY -> "实例"
    AppDestination.DOWNLOADS -> "下载"
    AppDestination.SETTINGS -> "设置"
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
