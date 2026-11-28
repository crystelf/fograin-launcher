package com.fograin.launcher.ui.community

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

actual val communityVideoTouchNavigation: Boolean = true

actual val communityVideoAutoHideChrome: Boolean = false

/**
 * Hides the status and navigation bars for the fullscreen player and restores
 * them when it leaves the composition. Swiping at an edge reveals them
 * temporarily without leaving fullscreen.
 */
@Composable
actual fun CommunityVideoImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(enabled, view) {
        val window = context.findHostActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (enabled) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (enabled) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
