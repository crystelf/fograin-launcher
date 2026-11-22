package com.fograin.launcher

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.components.LocalPageHeaderTopInset
import com.fograin.launcher.ui.components.fograinOpaquePageTransition
import com.fograin.launcher.ui.components.fograinSettingsRootTransition
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.modpack.ModpackImportPage
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
fun App(
    core: CoreClient,
    /**
     * Android passes true so the startup update check runs once per activity
     * composition. Desktop renders [App] with a shell-owned model instead and
     * triggers the check from `main`; tests keep the default `false` so they
     * never touch the network.
     */
    checkForUpdates: Boolean = false,
) {
    val platform = remember { platformServices() }
    val scope = rememberCoroutineScope()
    val model = remember(core) { AppModel.create(core = core, platform = platform, scope = scope) }
    LaunchedEffect(model, checkForUpdates) {
        if (checkForUpdates) model.checkForUpdates(initial = true)
    }
    App(model)
}

/** Which top-level surface replaces the shell for the current state. */
private enum class RootPage {
    SHELL,
    SETTINGS,
    MODPACK_IMPORT,
}

/**
 * Hosts an [AppModel] that the caller already created.
 *
 * Settings and the modpack import confirmation are in-window pages rather than
 * second Windows or modal sheets: opening either replaces the shell using the
 * same forward/backward page transition as every other page. The desktop menu,
 * Android back gesture and Escape all go through the single handler below.
 */
@Composable
fun App(model: AppModel) {
    val state by model.state.collectAsState()
    val platform = remember { platformServices() }
    // The import page replaces the shell, so it must reserve the macOS
    // traffic-light strip itself; the shell normally provides this locally.
    val rootHeaderInset = if (platform.platformName == "macos") 40.dp else 0.dp

    // The shell owns its own handler (including the search-field back
    // behaviour inside Library/Downloads); only intercept Back while one of
    // the root-level pages is the visible destination.
    platform.backDispatcher.handleBack(
        enabled = state.settingsWindow.open || state.modpack.pack != null,
    ) {
        when {
            state.alert != null -> model.dismissAlert()
            state.modpack.pack != null -> model.modpack.dismiss()
            else -> model.settings.back()
        }
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
                val root = when {
                    // A dropped pack takes over even when settings is open;
                    // cancelling the import returns to that settings page.
                    state.modpack.pack != null -> RootPage.MODPACK_IMPORT
                    state.settingsWindow.open -> RootPage.SETTINGS
                    else -> RootPage.SHELL
                }
                AnimatedContent(
                    targetState = root,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        when {
                            // The settings page animates its sidebar and
                            // content panes independently; the root only keeps
                            // the home page underneath and swaps z-order.
                            targetState == RootPage.SETTINGS &&
                                initialState == RootPage.SHELL ->
                                fograinSettingsRootTransition(targetZIndex = 1f)
                            targetState == RootPage.SHELL &&
                                initialState == RootPage.SETTINGS ->
                                fograinSettingsRootTransition(targetZIndex = -1f)
                            else -> fograinOpaquePageTransition(
                                forward = targetState.ordinal > initialState.ordinal,
                            )
                        }
                    },
                    label = "root-page",
                ) { page ->
                    when (page) {
                        RootPage.SETTINGS -> SettingsPageHost(model = model, state = state)
                        RootPage.MODPACK_IMPORT -> CompositionLocalProvider(
                            LocalPageHeaderTopInset provides rootHeaderInset,
                        ) {
                            ModpackImportPage(model = model, state = state)
                        }
                        RootPage.SHELL -> FograinApp(model = model, state = state)
                    }
                }
            }
        }
    }
}
