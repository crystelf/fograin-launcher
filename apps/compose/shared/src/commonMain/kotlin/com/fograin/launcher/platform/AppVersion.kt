package com.fograin.launcher.platform

/**
 * Small SemVer 2.0 subset used by the update prompt.
 *
 * The launcher only ships `MAJOR.MINOR.PATCH[-PRERELEASE]` tags, so build
 * metadata is dropped and numeric prerelease identifiers are compared
 * numerically just like the specification requires.
 */
internal data class ParsedVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
)

private val VersionPattern = Regex(
    """^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$""",
)

internal fun parseVersion(raw: String): ParsedVersion? {
    val value = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
    val match = VersionPattern.matchEntire(value) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].toIntOrNull() ?: return null
    val preRelease = match.groupValues[4]
        .takeIf { it.isNotEmpty() }
        ?.split('.')
        .orEmpty()
    return ParsedVersion(major, minor, patch, preRelease)
}

/** Negative when [left] is older, positive when it is newer, zero when equal. */
internal fun compareVersions(left: ParsedVersion, right: ParsedVersion): Int {
    if (left.major != right.major) return left.major.compareTo(right.major)
    if (left.minor != right.minor) return left.minor.compareTo(right.minor)
    if (left.patch != right.patch) return left.patch.compareTo(right.patch)
    return comparePreRelease(left.preRelease, right.preRelease)
}

private fun comparePreRelease(left: List<String>, right: List<String>): Int {
    // A release is newer than any of its prereleases.
    if (left.isEmpty() && right.isEmpty()) return 0
    if (left.isEmpty()) return 1
    if (right.isEmpty()) return -1
    for (index in 0 until minOf(left.size, right.size)) {
        val a = left[index]
        val b = right[index]
        val aNumber = a.toIntOrNull()
        val bNumber = b.toIntOrNull()
        val order = when {
            aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
            aNumber != null -> -1
            bNumber != null -> 1
            else -> a.compareTo(b)
        }
        if (order != 0) return order
    }
    return left.size.compareTo(right.size)
}

/** True only when [candidate] is a valid version strictly newer than [current]. */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val next = parseVersion(candidate) ?: return false
    val installed = parseVersion(current) ?: return false
    return compareVersions(next, installed) > 0
}