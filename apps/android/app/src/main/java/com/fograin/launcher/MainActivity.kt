package com.fograin.launcher

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.fograin.launcher.generated.FograinTokens
import com.fograin.launcher.settings.AppearancePreferences
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.theme.FograinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val appearance = AppearancePreferences(this)
        val darkTheme = appearance.isDark(this)

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
            var themeMode by remember { mutableStateOf(appearance.themeMode) }
            var dynamicColor by remember { mutableStateOf(appearance.dynamicColor) }

            FograinTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
            ) {
                FograinApp(
                    themeMode = themeMode,
                    dynamicColorEnabled = dynamicColor,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        appearance.themeMode = mode
                    },
                    onDynamicColorChange = { enabled ->
                        dynamicColor = enabled
                        appearance.dynamicColor = enabled
                    },
                )
            }
        }
    }
}
