package com.fograin.launcher.platform

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM implementation shared by the desktop shell and Android.
 *
 * `java.net.http.HttpClient` is not part of Android's API, so the check uses
 * `HttpURLConnection`, which exists on both runtimes. Requests are short and
 * failure is silent: the launcher must start even when GitHub is unreachable.
 */
internal object JvmUpdateService : UpdateService {
    private const val LatestReleaseApi =
        "https://api.github.com/repos/$FograinRepository/releases/latest"

    override suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val connection = (URI.create(LatestReleaseApi).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 6_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FograinLauncher")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseLatestRelease(body)
        } finally {
            connection.disconnect()
        }
    }
}