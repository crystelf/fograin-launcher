package com.fograin.launcher.community

import kotlinx.serialization.json.Json

/**
 * JSON settings for every Misskey payload.
 *
 * Misskey adds fields between releases and occasionally returns `null` for an
 * optional value, so unknown keys are ignored and missing values fall back to
 * the DTO defaults.
 */
val CommunityJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

enum class CommunityFailureKind {
    AUTH,
    NETWORK,
    RATE_LIMIT,
    SERVER,
    VALIDATION,
    UNKNOWN,
}

/** User-presentable failure; the raw server message stays in logs. */
data class CommunityFailure(
    val kind: CommunityFailureKind,
    val message: String,
    val code: String = "",
    val httpStatus: Int = 0,
)

class CommunityException(
    val code: String,
    override val message: String,
    val httpStatus: Int = 0,
) : Exception(message) {
    fun toFailure(): CommunityFailure {
        val kind = when {
            httpStatus == 401 || httpStatus == 403 -> CommunityFailureKind.AUTH
            httpStatus == 429 -> CommunityFailureKind.RATE_LIMIT
            httpStatus >= 500 -> CommunityFailureKind.SERVER
            code == "CREDENTIAL_REQUIRED" || code == "AUTHENTICATION_FAILED" ->
                CommunityFailureKind.AUTH

            code == "RATE_LIMIT_EXCEEDED" -> CommunityFailureKind.RATE_LIMIT
            code == "INVALID_PARAM" || code == "CONTAINS_PROHIBITED_WORDS" ||
                code == "MAX_FILE_SIZE_EXCEEDED" || code == "UNALLOWED_FILE_TYPE" ||
                code == "NO_FREE_SPACE" -> CommunityFailureKind.VALIDATION

            else -> CommunityFailureKind.UNKNOWN
        }
        return CommunityFailure(kind = kind, message = message, code = code, httpStatus = httpStatus)
    }
}

/**
 * Misskey's REST API, narrowed to the launcher's community surface.
 *
 * Default methods throw [CommunityException] so the disabled test gateway does
 * not need a method-by-method implementation. The real implementation lives in
 * `jvmMain` and uses OkHttp.
 */
interface CommunityApi {
    suspend fun meta(): CommunityMeta = unsupported()

    suspend fun stats(): CommunityStats = unsupported()

    suspend fun timeline(
        tab: CommunityTab,
        token: String?,
        untilId: String? = null,
        limit: Int = CommunityConfig.PAGE_SIZE,
    ): List<CommunityNote> = unsupported()

    suspend fun showNote(noteId: String, token: String? = null): CommunityNote = unsupported()

    suspend fun children(
        noteId: String,
        token: String? = null,
        untilId: String? = null,
        limit: Int = CommunityConfig.PAGE_SIZE,
    ): List<CommunityNote> = unsupported()

    suspend fun createNote(
        token: String,
        draft: CommunityNoteDraft,
    ): CommunityNote = unsupported()

    /**
     * Schedules a note through Misskey's server-side draft queue. Misskey stores
     * scheduled posts as drafts (`isActuallyScheduled = true`), not as notes
     * with a future timestamp.
     */
    suspend fun scheduleNote(
        token: String,
        draft: CommunityNoteDraft,
        scheduledAtEpochMillis: Long,
    ): Unit = unsupported()

    suspend fun deleteNote(token: String, noteId: String): Unit = unsupported()

    suspend fun votePoll(token: String, noteId: String, choice: Int): Unit = unsupported()

    suspend fun reportAbuse(token: String, userId: String, comment: String): Unit = unsupported()

    suspend fun uploadFile(
        token: String,
        path: String,
        name: String,
        isSensitive: Boolean,
        mimeType: String,
    ): CommunityFile = unsupported()

    suspend fun me(token: String): CommunityUser = unsupported()

    suspend fun showUser(userId: String, token: String? = null): CommunityUser = unsupported()

    suspend fun userNotes(
        userId: String,
        token: String? = null,
        limit: Int = CommunityConfig.PAGE_SIZE,
    ): List<CommunityNote> = unsupported()

    suspend fun followUser(token: String, userId: String): Unit = unsupported()

    suspend fun unfollowUser(token: String, userId: String): Unit = unsupported()

    suspend fun searchUsers(
        query: String,
        token: String? = null,
        limit: Int = 6,
    ): List<CommunityUser> = unsupported()

    suspend fun checkMiAuth(session: String): CommunityMiAuthCheck = unsupported()

    suspend fun revokeToken(token: String): Unit = unsupported()

    suspend fun createReaction(token: String, noteId: String, reaction: String): Unit = unsupported()

    suspend fun deleteReaction(token: String, noteId: String): Unit = unsupported()

    /**
     * Downloads a drive file to an app-cache path so the UI can hand it to the
     * native save dialog. Returns that absolute path.
     */
    suspend fun downloadFile(url: String, name: String): String = unsupported()
}

private fun unsupported(): Nothing =
    throw CommunityException("COMMUNITY_DISABLED", "Community API is disabled")

/** Platform-backed gateway; tests replace it with [Disabled]. */
interface CommunityGateway {
    val api: CommunityApi
    val sessions: CommunitySessionStore
    val isEnabled: Boolean

    object Disabled : CommunityGateway {
        override val api: CommunityApi = object : CommunityApi {}
        override val sessions: CommunitySessionStore = CommunitySessionStore.Empty
        override val isEnabled: Boolean = false
    }
}