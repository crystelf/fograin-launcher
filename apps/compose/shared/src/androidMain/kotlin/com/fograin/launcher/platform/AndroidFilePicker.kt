package com.fograin.launcher.platform

import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * SAF single-file picker for `.mrpack` / `.zip` imports.
 *
 * The Rust core cannot read `content://` URIs, so the chosen document is
 * copied into the app cache and the copy's absolute path is returned. A 2 GiB
 * cap keeps a hostile document provider from filling the device.
 */
internal object AndroidFilePicker : FileBrowser {
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    override val isAvailable: Boolean
        get() = AndroidPlatform.filePickerLauncher != null

    override suspend fun pick(title: String, extensions: List<String>): FilePick {
        val launch = AndroidPlatform.filePickerLauncher
            ?: return FilePick.Unavailable("no-activity")
        val uri = suspendCancellableCoroutine<Uri?> { continuation ->
            AndroidPlatform.setPendingFilePick { picked ->
                if (continuation.isActive) continuation.resume(picked)
            }
            // SAF ignores unknown MIME types; zip covers `.mrpack` on most
            // providers while the octet-stream fallback catches the rest.
            launch(
                arrayOf(
                    "application/zip",
                    "application/java-archive",
                    "application/octet-stream",
                ),
            )
        }
        if (uri == null) {
            AndroidPlatform.setPendingFilePick {}
            return FilePick.Cancelled
        }
        return withContext(Dispatchers.IO) { copyToCache(uri) }
    }

    private fun copyToCache(uri: Uri): FilePick {
        val context = AndroidPlatform.contextOrNull()
            ?: return FilePick.Unavailable("no-context")
        val resolver = context.contentResolver
        val name = displayName(uri).ifBlank { "import-${System.currentTimeMillis()}.zip" }
        val directory = File(context.cacheDir, "imports").apply { mkdirs() }
        val target = File(directory, "import-${System.currentTimeMillis()}-$name")
        return try {
            val input = resolver.openInputStream(uri)
                ?: return FilePick.Unavailable("unreadable-document")
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            target.delete()
                            return FilePick.Unavailable("document-too-large")
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }
            FilePick.Picked(target.absolutePath)
        } catch (error: Throwable) {
            target.delete()
            FilePick.Unavailable(error.message ?: "copy-failed")
        }
    }

    private fun displayName(uri: Uri): String {
        val context = AndroidPlatform.contextOrNull() ?: return ""
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")
    }
}
