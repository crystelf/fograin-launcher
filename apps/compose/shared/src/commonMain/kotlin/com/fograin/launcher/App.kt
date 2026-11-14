package com.fograin.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.theme.FograinTheme

/**
 * Shared application root: platform services, the launcher [AppModel], the
 * theme (including dynamic colour) and the four-destination shell.
 *
 * Android's `MainActivity` creates the core client and hosts this composable.
 * The desktop shell creates the model itself so its menu bar can drive the
 * same model, then calls the [App] overload that takes it.
 */
@Composable
fun App(core: CoreClient) {
    val platform = remember { platformServices() }
    val scope = rememberCoroutineScope()
    val model = remember(core) { AppModel.create(core = core, platform = platform, scope = scope) }
    App(model)
}

/** Hosts an [AppModel] that the caller already created (the desktop shell). */
@Composable
fun App(model: AppModel) {
    val platform = remember { platformServices() }
    val state by model.state.collectAsState()
    var dynamicColor by remember { mutableStateOf(platform.preferences.dynamicColor) }

    FograinTheme(themeMode = state.theme, dynamicColor = dynamicColor) {
        FograinApp(
            model = model,
            state = state,
            dynamicColorEnabled = dynamicColor,
            onDynamicColorChange = { enabled ->
                dynamicColor = enabled
                platform.preferences.dynamicColor = enabled
            },
        )
    }
}
