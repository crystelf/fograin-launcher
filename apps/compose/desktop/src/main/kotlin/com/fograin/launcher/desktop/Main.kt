package com.fograin.launcher.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val isMac = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

private fun shortcut(key: Key, shift: Boolean = false): KeyShortcut =
    if (isMac) KeyShortcut(key, meta = true, shift = shift) else KeyShortcut(key, ctrl = true, shift = shift)

fun main() {
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

        Window(
            onCloseRequest = {
                platform.preferences.windowFrame = windowFrame(windowState)
                exitApplication()
            },
            title = "雾雨启动器",
            state = windowState,
        ) {
            WindowMenuBar(
                model = model,
                onExit = {
                    platform.preferences.windowFrame = windowFrame(windowState)
                    exitApplication()
                },
            )

            LaunchedEffect(Unit) {
                if (System.getenv("FOGRAIN_STARTUP_LOG") != null) {
                    val elapsed = processStart
                        ?.let { Duration.between(it, Instant.now()).toMillis() }
                        ?: -1
                    println("FOGRAIN_STARTUP_MS=$elapsed")
                }
            }
            App(model)
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
                "搜索实例",
                shortcut = shortcut(Key.F),
                onClick = { model.requestLibrarySearch() },
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
