package com.fograin.launcher

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.components.fograinOpaquePageTransition
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.settings.SettingsPageHost
import com.fograin.launcher.ui.theme.FograinTheme

/**
 * Shared application root: platform services, the launcher [AppModel], the
 * theme/language and the shell.
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

/**
 * Hosts an [AppModel] that the caller already created.
 *
 * Settings is an in-window destination rather than a second Window: opening it
 * replaces the shell with the settings surface using the same forward/backward
 * page transition as every other page. The desktop menu, Android back gesture
 * and Escape all go through the single handler below.
 */
@Composable
fun App(model: AppModel) {
    val state by model.state.collectAsState()
    val platform = remember { platformServices() }

    // The shell owns its own handler (including the search-field back
    // behaviour inside Library/Downloads); only intercept Back while settings
    // is the visible destination.
    platform.backDispatcher.handleBack(enabled = state.settingsWindow.open) {
        if (state.alert != null) model.dismissAlert() else model.settingsBack()
    }

    FograinTheme(themeMode = state.theme, accent = state.accent.value) {
        AppLocale(language = state.language) {
            // A themed background behind the page transition. Without it the
            // window's default (white) surface shows through the slide/fade
            // for a frame when opening or closing settings.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                AnimatedContent(
                    targetState = state.settingsWindow.open,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { fograinOpaquePageTransition(forward = targetState) },
                    label = "settings-root",
                ) { settingsOpen ->
                    if (settingsOpen) {
                        SettingsPageHost(model = model, state = state)
                    } else {
                        FograinApp(model = model, state = state)
                    }
                }
            }
        }
    }
}
