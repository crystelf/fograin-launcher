package com.fograin.launcher.platform

import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * SAF single-file picker for `.mrpack` / `.zip` imports.
 *
 * The Rust core cannot read `content://` URIs, so the chosen document is
 * copied into the app cache and the copy's absolute path is returned. A 2 GiB
 * cap keeps a hostile document provider from filling the device.
 */
internal object AndroidFilePicker : FileBrowser {
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "webm", "mkv", "m4v")
    private val ARCHIVE_EXTENSIONS = setOf("zip", "mrpack", "jar", "mcpack")

    override val isAvailable: Boolean
        get() = AndroidPlatform.filePickerLauncher != null

    override suspend fun pick(title: String, extensions: List<String>): FilePick {
        val launch = AndroidPlatform.filePickerLauncher
            ?: return FilePick.Unavailable("no-activity")
        val uri = suspendCancellableCoroutine<Uri?> { continuation ->
            AndroidPlatform.setPendingFilePick { picked ->
                if (continuation.isActive) continuation.resume(picked)
            }
            // SAF filters by MIME, not by extension. Map the requested
            // extensions so the same picker serves modpacks and community
            // image/video attachments.
            launch(mimeTypesFor(extensions))
        }
        if (uri == null) {
            AndroidPlatform.setPendingFilePick {}
            return FilePick.Cancelled
        }
        return withContext(Dispatchers.IO) { copyToCache(uri) }
    }

    override suspend fun save(
        title: String,
        suggestedName: String,
        extensions: List<String>,
    ): FileSavePick {
        val launch = AndroidPlatform.savePickerLauncher
            ?: return FileSavePick.Unavailable("no-activity")
        val uri = suspendCancellableCoroutine<Uri?> { continuation ->
            AndroidPlatform.setPendingSavePick { picked ->
                if (continuation.isActive) continuation.resume(picked)
            }
            launch(suggestedName)
        }
        if (uri == null) {
            AndroidPlatform.setPendingSavePick {}
            return FileSavePick.Cancelled
        }
        // External-storage documents expose a readable `primary:Download/…`
        // segment; providers with opaque ids fall back to the file name.
        val segment = uri.lastPathSegment.orEmpty()
        val readable = segment.takeIf { it.contains(':') || it.contains('/') }
            ?: displayName(uri).ifBlank { suggestedName }
        return FileSavePick.Picked(
            path = uri.toString(),
            displayPath = readable,
            direct = false,
        )
    }

    override suspend fun writePicked(target: FileSavePick.Picked, sourcePath: String): Boolean =
        withContext(Dispatchers.IO) {
            if (target.direct) return@withContext true
            val context = AndroidPlatform.contextOrNull() ?: return@withContext false
            runCatching {
                val source = File(sourcePath)
                val uri = Uri.parse(target.path)
                context.contentResolver.openOutputStream(uri, "w")?.use { sink ->
                    source.inputStream().use { input -> input.copyTo(sink) }
                } != null
            }.getOrDefault(false)
        }

    override suspend fun discard(path: String) {
        withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
    }

    private fun copyToCache(uri: Uri): FilePick {
        val context = AndroidPlatform.contextOrNull()
            ?: return FilePick.Unavailable("no-context")
        val resolver = context.contentResolver
        val rawName = displayName(uri).ifBlank { "upload-${System.currentTimeMillis()}" }
        val name = rawName.replace('/', '_').replace('\\', '_')
        // One directory per pick keeps the original display name (which the
        // community uploader uses as the Misskey file name) without collisions.
        val directory = File(
            context.cacheDir,
            "imports/${System.currentTimeMillis()}-${System.nanoTime()}",
        ).apply { mkdirs() }
        val target = File(directory, name)
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

    private fun mimeTypesFor(extensions: List<String>): Array<String> {
        val normalized = extensions.map { it.lowercase() }.toSet()
        val types = linkedSetOf<String>()
        if (normalized.any { it in IMAGE_EXTENSIONS }) types += "image/*"
        if (normalized.any { it in VIDEO_EXTENSIONS }) types += "video/*"
        if (normalized.any { it in ARCHIVE_EXTENSIONS }) {
            types += "application/zip"
            types += "application/java-archive"
            types += "application/octet-stream"
        }
        if (types.isEmpty()) types += "*/*"
        return types.toTypedArray()
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
