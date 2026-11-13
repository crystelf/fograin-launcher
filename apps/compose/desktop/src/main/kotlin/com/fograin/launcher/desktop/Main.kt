package com.fograin.launcher.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fograin.launcher.App
import com.fograin.launcher.platform.createCoreClient
import java.time.Duration
import java.time.Instant

fun main() {
    val processStart = ProcessHandle.current().info().startInstant().orElse(null)

    // Read launcher.toml / the data root before the first Compose frame.
    val core = createCoreClient()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "雾雨启动器",
            state = rememberWindowState(size = DpSize(1120.dp, 780.dp)),
        ) {
            LaunchedEffect(Unit) {
                if (System.getenv("FOGRAIN_STARTUP_LOG") != null) {
                    val elapsed = processStart
                        ?.let { Duration.between(it, Instant.now()).toMillis() }
                        ?: -1
                    println("FOGRAIN_STARTUP_MS=$elapsed")
                }
            }
            App(core)
        }
    }
}
