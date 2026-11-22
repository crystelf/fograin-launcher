package com.fograin.launcher.ui.artwork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.fograin.launcher.platform.fetchTextDocument
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Base host for the official launcher content feed and its images. */
private const val LAUNCHER_CONTENT_HOST = "https://launchercontent.mojang.com"

/**
 * Maps Minecraft version ids to the key art used by Mojang's own patch notes.
 *
 * The launcher feed ships one square poster per release/snapshot, which is the
 * closest thing to a per-version "release poster". Versions older than the
 * feed (or anything unreachable) deliberately fall back to the bundled
 * landscape backdrop instead of borrowing a neighbouring version's art.
 */
object MinecraftArtwork {
    private val json = Json { ignoreUnknownKeys = true }
    private val loadMutex = Mutex()

    @Volatile
    private var artworks: Map<String, String>? = null

    @Volatile
    private var loadFailed = false

    /** Returns the official poster URL for [minecraftVersion], if one exists. */
    suspend fun posterUrl(minecraftVersion: String): String? {
        val version = minecraftVersion.trim()
        if (version.isEmpty()) return null
        val map = ensureLoaded() ?: return null
        candidates(version).forEach { candidate ->
            map[candidate]?.let { return it }
        }
        return null
    }

    private suspend fun ensureLoaded(): Map<String, String>? {
        artworks?.let { return it }
        if (loadFailed) return null
        loadMutex.withLock {
            artworks?.let { return it }
            if (loadFailed) return null
            val feed = runCatching {
                fetchTextDocument("$LAUNCHER_CONTENT_HOST/v2/javaPatchNotes.json")
            }.getOrNull()
            if (feed.isNullOrBlank()) {
                loadFailed = true
                return null
            }
            val parsed = runCatching {
                json.decodeFromString<PatchNotesFeed>(feed)
            }.getOrNull()
            if (parsed == null) {
                loadFailed = true
                return null
            }
            val map = parsed.entries.mapNotNull { entry ->
                val path = entry.image?.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                entry.version.takeIf { it.isNotBlank() }?.let { version ->
                    version to absoluteUrl(path)
                }
            }.toMap()
            artworks = map
            if (map.isEmpty()) loadFailed = true
            return map
        }
    }

    private fun absoluteUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            LAUNCHER_CONTENT_HOST + path
        }

    /**
     * Exact version first, then the release a pre-release/RC was cut from.
     * `1.21.4-pre1` therefore uses the 1.21.4 poster, while an unknown old
     * version stays unmatched.
     */
    private fun candidates(version: String): List<String> = buildList {
        add(version)
        listOf("-pre", "-rc", "-snapshot").forEach { marker ->
            val base = version.substringBefore(marker)
            if (base.isNotEmpty() && base != version) add(base)
        }
    }
}

/** Poster URL for one version; `null` while loading or when the feed has no art. */
@Composable
fun rememberVersionPoster(minecraftVersion: String): String? {
    val url by produceState<String?>(
        initialValue = null,
        key1 = minecraftVersion,
    ) {
        value = MinecraftArtwork.posterUrl(minecraftVersion)
    }
    return url
}

@Serializable
private data class PatchNotesFeed(
    val entries: List<PatchNoteEntry> = emptyList(),
)

@Serializable
private data class PatchNoteEntry(
    val version: String = "",
    val image: PatchNoteImage? = null,
)

@Serializable
private data class PatchNoteImage(
    val url: String = "",
)
