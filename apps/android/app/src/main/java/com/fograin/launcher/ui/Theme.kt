package com.fograin.launcher.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FograinDark = darkColorScheme(
    primary = Color(0xFF8ED5AE),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005235),
    onPrimaryContainer = Color(0xFFAAF2CA),
    secondaryContainer = Color(0xFF354C3D),
    tertiaryContainer = Color(0xFF2D4A56),
    background = Color(0xFF101410),
    surface = Color(0xFF101410),
    surfaceVariant = Color(0xFF1C201D),
    onSurface = Color(0xFFE2E4DF),
    outline = Color(0xFF8A938C),
    error = Color(0xFFFFB4AB),
)

private val FograinLight = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2C9),
    onPrimaryContainer = Color(0xFF002114),
    secondaryContainer = Color(0xFFB9CCBE),
    tertiaryContainer = Color(0xFFC7E9F8),
    background = Color(0xFFF7FBF7),
    surface = Color(0xFFF7FBF7),
    surfaceVariant = Color(0xFFEBEFEB),
    onSurface = Color(0xFF191C1A),
    outline = Color(0xFF717970),
    error = Color(0xFFBA1A1A),
)

@Composable
fun FograinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FograinDark
        else -> FograinLight
    }

    MaterialTheme(colorScheme = colors, content = content)
}
