package com.fograin.launcher.platform

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor
import java.awt.Desktop
import java.io.File

/** Opens folders and URLs with the host file manager / browser. */
internal object DesktopSystemLinks : SystemLinks {
    override fun revealDirectory(path: String): Boolean = runCatching {
        if (path.isBlank()) return false
        val file = File(path)
        if (!file.exists()) return false
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(file)
                return true
            }
        }
        false
    }.getOrDefault(false)

    override fun openUrl(url: String): Boolean = runCatching {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(java.net.URI(url))
                return true
            }
        }
        false
    }.getOrDefault(false)
}

actual fun resizeHorizontalPointerIcon(): PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
