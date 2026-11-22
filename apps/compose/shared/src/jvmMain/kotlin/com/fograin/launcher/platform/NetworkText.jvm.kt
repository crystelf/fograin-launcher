package com.fograin.launcher.platform

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Upper bound for one feed response; keeps a malformed server from exhausting memory. */
private const val MAX_TEXT_BYTES = 2 * 1024 * 1024

internal actual suspend fun fetchTextDocument(url: String): String? = withContext(Dispatchers.IO) {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return@withContext null
    val scheme = parsed.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return@withContext null
    val connection = runCatching {
        parsed.toURL().openConnection() as HttpURLConnection
    }.getOrNull() ?: return@withContext null
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("User-Agent", "FograinLauncher/1.0 (+https://github.com/crystelf/fograin-launcher)")
        val status = connection.responseCode
        if (status !in 200..299) return@withContext null
        val stream = connection.inputStream
        val bytes = stream.use { input ->
            val buffer = ByteArray(16 * 1024)
            val output = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_TEXT_BYTES) return@withContext null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        bytes.toString(Charsets.UTF_8)
    } catch (_: java.io.IOException) {
        null
    } finally {
        connection.disconnect()
    }
}
