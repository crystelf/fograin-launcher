package com.fograin.launcher

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.generated.FograinTokens
import com.fograin.launcher.settings.AppearancePreferences
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.theme.FograinTheme
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The core is initialized before the first frame so the stored theme
        // and data root are available to the starting window (macOS does the
        // same through AppModel.init).
        val model = AppModel.get(applicationContext)
        val appearance = AppearancePreferences(this)
        val darkTheme = AppearancePreferences.resolveDark(this, model.state.value.theme)

        // Resolve the appearance before the first frame so the status/navigation
        // bar icons and the window background match the in-app theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
        )
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(
            ColorDrawable(
                (if (darkTheme) FograinTokens.Dark.surface else FograinTokens.Light.surface).toArgb(),
            ),
        )

        setContent {
            val themeMode by model.state.map { it.theme }.collectAsState(
                initial = model.state.value.theme,
            )
            var dynamicColor by remember { mutableStateOf(appearance.dynamicColor) }
            val darkTheme = when (themeMode) {
                com.fograin.launcher.settings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                com.fograin.launcher.settings.ThemeMode.LIGHT -> false
                com.fograin.launcher.settings.ThemeMode.DARK -> true
            }

            // Re-apply the system bar styles when the in-app theme changes.
            // `enableEdgeToEdge` captures a fixed detectDarkMode value, so a
            // later dark switch would otherwise leave dark icons on a dark bar.
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
            }

            FograinTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
            ) {
                FograinApp(
                    model = model,
                    dynamicColorEnabled = dynamicColor,
                    onDynamicColorChange = { enabled ->
                        dynamicColor = enabled
                        appearance.dynamicColor = enabled
                    },
                )
            }
        }
    }
}
