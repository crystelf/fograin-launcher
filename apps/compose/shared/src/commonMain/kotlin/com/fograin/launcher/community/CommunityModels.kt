package com.fograin.launcher.community

import kotlinx.serialization.Serializable

/**
 * Everything the launcher needs from Crystelf Lab's Misskey API.
 *
 * Only the posting/browsing surface is modelled. Chat, notifications, lists,
 * antennas and Misskey's own settings are deliberately absent.
 */
object CommunityConfig {
    const val BASE_URL: String = "https://lab.crystelf.top"
    const val APP_NAME: String = "Fograin Launcher"

    /**
     * Scopes for browsing, posting, media, polls, scheduled notes/reports.
     * Changing this list invalidates stored sessions through
     * [CommunitySession.CURRENT_VERSION].
     */
    val PERMISSIONS: List<String> = listOf(
        "read:account",
        "write:notes",
        "write:drive",
        "write:reactions",
        "write:votes",
        "write:account",
        "write:following",
        "write:report-abuse",
    )

    const val MAX_ATTACHMENTS: Int = 16
    const val PAGE_SIZE: Int = 20
    const val MAX_NOTE_LENGTH_FALLBACK: Int = 3000
}

enum class CommunityTab {
    LOCAL,
    GLOBAL,
    FOLLOWING,
}

/**
 * Misskey's `reactionAcceptance` note option.
 *
 * The wire value is deliberately nullable: `null` means "every reaction".
 */
enum class CommunityReactionAcceptance(val wireValue: String?) {
    ALL(null),
    LIKE_ONLY("likeOnly"),
    LIKE_ONLY_FOR_REMOTE("likeOnlyForRemote"),
    NON_SENSITIVE_ONLY("nonSensitiveOnly"),
    NON_SENSITIVE_ONLY_FOR_LOCAL_LIKE_ONLY_FOR_REMOTE(
        "nonSensitiveOnlyForLocalLikeOnlyForRemote",
    ),
    ;

    companion object {
        fun fromWire(value: String?): CommunityReactionAcceptance =
            entries.firstOrNull { it.wireValue == value } ?: ALL
    }
}

enum class CommunityVisibility(val wireValue: String) {
    PUBLIC("public"),
    HOME("home"),
    FOLLOWERS("followers"),
    ;

    companion object {
        fun fromWire(value: String?): CommunityVisibility =
            entries.firstOrNull { it.wireValue == value } ?: PUBLIC
    }
}

/** A file chosen by the user but not uploaded yet. */
data class LocalAttachment(
    val path: String,
    val name: String,
    val isVideo: Boolean,
)

/** Poll values ready to be sent to `notes/create` or `notes/drafts/create`. */
data class CommunityPollDraft(
    val choices: List<String>,
    val multiple: Boolean = false,
    val expiresAtEpochMillis: Long? = null,
)

/**
 * One note create/schedule request.
 *
 * Keeping the payload in one value type means the composer, scheduling and
 * quoting paths all send the same fields to Misskey.
 */
data class CommunityNoteDraft(
    val text: String? = null,
    val visibility: CommunityVisibility = CommunityVisibility.PUBLIC,
    val cw: String? = null,
    val replyId: String? = null,
    val renoteId: String? = null,
    val fileIds: List<String> = emptyList(),
    val poll: CommunityPollDraft? = null,
    val reactionAcceptance: CommunityReactionAcceptance = CommunityReactionAcceptance.ALL,
)

@Serializable
data class CommunityUserField(
    val name: String = "",
    val value: String = "",
)

@Serializable
data class CommunityUser(
    val id: String,
    val name: String? = null,
    val username: String,
    val host: String? = null,
    val avatarUrl: String = "",
    val bannerUrl: String? = null,
    val description: String? = null,
    val isBot: Boolean = false,
    val isCat: Boolean = false,
    val isAdmin: Boolean = false,
    val isModerator: Boolean = false,
    val isFollowing: Boolean = false,
    val isFollowed: Boolean = false,
    val followersCount: Int = 0,
    val followingCount: Int = 0,
    val notesCount: Int = 0,
    val location: String? = null,
    val birthday: String? = null,
    val createdAt: String? = null,
    val fields: List<CommunityUserField> = emptyList(),
) {
    /** Misskey's `name` is optional; fall back to the unique username. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: username

    /** `@name` locally, `@name@host` for federated users. */
    val handle: String
        get() = if (host.isNullOrBlank()) "@$username" else "@$username@$host"

    /** Admins and moderators may remove notes written by other users. */
    val canModerate: Boolean
        get() = isAdmin || isModerator
}

@Serializable
data class CommunityFileProperties(
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int? = null,
    val avgColor: String? = null,
)

@Serializable
data class CommunityFile(
    val id: String,
    val name: String = "",
    val type: String = "",
    val url: String = "",
    val thumbnailUrl: String? = null,
    val isSensitive: Boolean = false,
    val size: Long? = null,
    val properties: CommunityFileProperties = CommunityFileProperties(),
) {
    val isVideo: Boolean
        get() = type.startsWith("video/")
}

@Serializable
data class CommunityPollChoice(
    val text: String = "",
    val votes: Int = 0,
    val isVoted: Boolean = false,
)

@Serializable
data class CommunityPoll(
    val multiple: Boolean = false,
    val choices: List<CommunityPollChoice> = emptyList(),
    val expiresAt: String? = null,
)

@Serializable
data class CommunityNote(
    val id: String,
    val createdAt: String,
    val text: String? = null,
    val cw: String? = null,
    val user: CommunityUser,
    val userId: String? = null,
    val replyId: String? = null,
    val reply: CommunityNote? = null,
    val renoteId: String? = null,
    val renote: CommunityNote? = null,
    val files: List<CommunityFile> = emptyList(),
    val visibility: String = "public",
    val reactions: Map<String, Int> = emptyMap(),
    val reactionEmojis: Map<String, String> = emptyMap(),
    val reactionCount: Int = 0,
    val repliesCount: Int = 0,
    val renoteCount: Int = 0,
    val myReaction: String? = null,
    val poll: CommunityPoll? = null,
    val emojis: Map<String, String> = emptyMap(),
    val reactionAcceptance: String? = null,
) {
    val hasContent: Boolean
        get() = !text.isNullOrBlank() || files.isNotEmpty() || renote != null || poll != null

    /** The note shown when this row is a pure renote. */
    val quotedNote: CommunityNote?
        get() = renote
}

@Serializable
data class CommunityMetaClientOptions(
    /**
     * Assume visitor timelines are available until the server says otherwise;
     * a failed meta request must not hide a readable feed behind a login gate.
     */
    val showTimelineForVisitor: Boolean = true,
)

@Serializable
data class CommunityMetaPolicies(
    val ltlAvailable: Boolean = true,
    val gtlAvailable: Boolean = true,
    val canPublicNote: Boolean = true,
    /** Zero hides the schedule action; Misskey returns this in `policies`. */
    val scheduledNoteLimit: Int = 0,
)

@Serializable
data class CommunityMetaFeatures(
    val miauth: Boolean = false,
)

@Serializable
data class CommunityMeta(
    val name: String = "Crystelf Lab",
    val uri: String = CommunityConfig.BASE_URL,
    val version: String = "",
    val description: String = "",
    val iconUrl: String? = null,
    val bannerUrl: String? = null,
    val maxNoteTextLength: Int = CommunityConfig.MAX_NOTE_LENGTH_FALLBACK,
    val clientOptions: CommunityMetaClientOptions = CommunityMetaClientOptions(),
    val policies: CommunityMetaPolicies = CommunityMetaPolicies(),
    val features: CommunityMetaFeatures = CommunityMetaFeatures(),
    val disableRegistration: Boolean = false,
)

@Serializable
data class CommunityStats(
    val notesCount: Int = 0,
    val usersCount: Int = 0,
    val reactionsCount: Int = 0,
    val instances: Int = 0,
)

@Serializable
data class CommunityMiAuthCheck(
    val ok: Boolean = false,
    val token: String? = null,
    val user: CommunityUser? = null,
)

@Serializable
data class CommunityCreatedNote(
    val createdNote: CommunityNote,
)

@Serializable
data class CommunityErrorBody(
    val message: String = "",
    val code: String = "",
    val kind: String = "",
)

@Serializable
data class CommunityErrorEnvelope(
    val error: CommunityErrorBody = CommunityErrorBody(),
)

/** Token-bearing session. Persisted only; never stored in AppUiState. */
@Serializable
data class CommunitySession(
    val version: Int = CURRENT_VERSION,
    val server: String = CommunityConfig.BASE_URL,
    val token: String,
    val user: CommunityUser,
    val savedAtEpochMillis: Long,
) {
    companion object {
        /**
         * Bumped when the MiAuth scope set changes. Older sessions do not have
         * the scopes needed for polls, following, moderation or scheduled
         * notes, so they are discarded instead of failing mysteriously on write.
         */
        const val CURRENT_VERSION: Int = 3
    }
}

/** Token-free projection the UI can hold and log safely. */
data class CommunityAccount(
    val server: String,
    val user: CommunityUser,
    val savedAtEpochMillis: Long,
)

/** A MiAuth session that has been opened in the browser but not approved yet. */
@Serializable
data class PendingCommunityLogin(
    val session: String,
    val startedAtEpochMillis: Long,
)

fun CommunitySession.toAccount(): CommunityAccount = CommunityAccount(
    server = server,
    user = user,
    savedAtEpochMillis = savedAtEpochMillis,
)

fun CommunityUser.subtitle(): String =
    if (host.isNullOrBlank()) "" else "@${host.orEmpty()}"

fun CommunityNote.absoluteUrl(): String = "${CommunityConfig.BASE_URL}/notes/$id"

fun CommunityUser.profileUrl(): String = "${CommunityConfig.BASE_URL}/$handle"