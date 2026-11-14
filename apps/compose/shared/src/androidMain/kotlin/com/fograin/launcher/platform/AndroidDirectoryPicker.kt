package com.fograin.launcher.platform

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * SAF-backed directory picker.
 *
 * The Rust core only understands filesystem paths, so the tree returned by
 * `ACTION_OPEN_DOCUMENT_TREE` is mapped back from its document id
 * (`primary:Games/.minecraft`). Trees on cloud providers or unreadable
 * volumes return [DirectoryPick.Unavailable] so the caller can fall back to
 * the in-app browser instead of silently doing nothing.
 */
internal object AndroidDirectoryPicker : JvmDirectoryBrowser() {
    override val isAvailable: Boolean
        get() = AndroidPlatform.treePickerLauncher != null

    override suspend fun pick(title: String, initialPath: String?): DirectoryPick {
        val launch = AndroidPlatform.treePickerLauncher ?: return DirectoryPick.Unavailable("no-activity")
        return suspendCancellableCoroutine { continuation ->
            AndroidPlatform.setPendingPick { uri ->
                if (continuation.isActive) {
                    continuation.resume(uri.toPickResult())
                }
            }
            launch()
        }
    }
}

/** Turns a SAF tree URI into an absolute path the core can read, or an explanation. */
internal fun Uri?.toPickResult(): DirectoryPick {
    if (this == null) return DirectoryPick.Cancelled
    val path = treePath(this) ?: return DirectoryPick.Unavailable("unmapped-tree")
    val directory = File(path)
    return if (directory.isDirectory && directory.canRead()) {
        DirectoryPick.Picked(directory.absolutePath)
    } else {
        DirectoryPick.Unavailable("unreadable-path")
    }
}

private fun treePath(uri: Uri): String? {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return null
    val parts = documentId.split(':', limit = 2)
    if (parts.size != 2) return null
    val (volume, relative) = parts
    val base = when {
        volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
        volume.matches(Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}")) -> File("/storage/$volume")
        else -> return null
    }
    return if (relative.isBlank()) base.absolutePath else File(base, relative).absolutePath
}
