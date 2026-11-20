package com.fograin.launcher.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Android has no "reveal in file manager" intent for app-private
 * directories, so [revealDirectory] reports failure and the UI copies the
 * path instead. URLs open in the browser.
 */
internal object AndroidSystemLinks : SystemLinks {
    override fun revealDirectory(path: String): Boolean = false

    override fun openUrl(url: String): Boolean = runCatching {
        val context = AndroidPlatform.contextOrNull() ?: return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

actual fun resizeHorizontalPointerIcon(): PointerIcon = PointerIcon.Default
