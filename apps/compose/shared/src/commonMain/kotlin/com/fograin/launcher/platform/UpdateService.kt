package com.fograin.launcher.platform

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/** GitHub repository that owns the release feed and the in-app links. */
const val FograinRepository: String = "crystelf/fograin-launcher"

/** Human-facing release page; used when a release has no usable API URL. */
const val FograinReleasesUrl: String = "https://github.com/crystelf/fograin-launcher/releases"

/** A published release the launcher can point users at. */
data class ReleaseInfo(
    /** Version without the `v` prefix, for example `0.2.0`. */
    val version: String,
    /** Release page opened by the update prompt. */
    val url: String,
    val title: String = "",
)

/**
 * Network dependency behind the startup update check.
 *
 * Desktop and Android share the JVM implementation; tests and platforms
 * without a network get [Disabled], which keeps the check a no-op.
 */
interface UpdateService {
    suspend fun fetchLatestRelease(): ReleaseInfo?

    data object Disabled : UpdateService {
        override suspend fun fetchLatestRelease(): ReleaseInfo? = null
    }
}

/** Shape of GitHub's `/releases/latest` response; unknown fields are ignored. */
@Serializable
internal data class GitHubReleasePayload(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

/**
 * Converts the API payload into the small model the UI needs.
 *
 * `/releases/latest` already excludes drafts and prereleases, but the fields
 * are checked again so a payload from a different endpoint cannot make a
 * prerelease look like a stable update.
 */
internal fun GitHubReleasePayload.toReleaseInfo(): ReleaseInfo? {
    if (draft || prerelease) return null
    val version = tagName.trim().removePrefix("v").removePrefix("V")
    if (version.isBlank()) return null
    val url = htmlUrl.trim().takeIf {
        it.startsWith("https://github.com/$FograinRepository/releases/")
    } ?: "$FograinReleasesUrl/tag/${tagName.trim()}"
    return ReleaseInfo(version = version, url = url, title = name)
}

/** Parses one API response; malformed JSON is treated as "no update". */
internal fun parseLatestRelease(json: String): ReleaseInfo? = runCatching {
    CoreJson.decodeFromString<GitHubReleasePayload>(json).toReleaseInfo()
}.getOrNull()