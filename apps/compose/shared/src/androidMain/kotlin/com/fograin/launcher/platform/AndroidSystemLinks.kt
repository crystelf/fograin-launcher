package com.fograin.launcher.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens an instance folder through a FileProvider URI. Android has no
 * "reveal in file manager" action, but document browsers (Files, Solid
 * Explorer, …) accept a granted `resource/folder` URI. When no app can handle
 * it the caller falls back to an in-app message; the path is never copied.
 */
internal object AndroidSystemLinks : SystemLinks {
    override fun revealDirectory(path: String): Boolean = runCatching {
        val context = AndroidPlatform.contextOrNull() ?: return false
        val directory = File(path)
        if (!directory.isDirectory) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            directory,
        )
        // Document providers disagree on the type for a folder; try the
        // platform's document MIME first, then the common file-manager one.
        val opened = listOf(
            "vnd.android.document/directory",
            "resource/folder",
        ).any { type ->
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
        opened
    }.getOrDefault(false)

    override fun openUrl(url: String): Boolean = runCatching {
        val context = AndroidPlatform.contextOrNull() ?: return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    override fun share(text: String): Boolean = runCatching {
        val context = AndroidPlatform.contextOrNull() ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    }.getOrDefault(false)
}

actual fun resizeHorizontalPointerIcon(): PointerIcon = PointerIcon.Default
