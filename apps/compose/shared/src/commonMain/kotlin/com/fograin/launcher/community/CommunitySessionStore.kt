package com.fograin.launcher.community

import com.fograin.launcher.platform.LocalPreferences
import kotlinx.serialization.encodeToString

/**
 * Stores the token-bearing session and the MiAuth pending session.
 *
 * Android uses private SharedPreferences and desktop uses the user's Java
 * preference node. Neither is a system keychain; that upgrade is deliberately
 * left for a later release. The token must never reach `launcher.toml`, logs,
 * diagnostics or AppUiState.
 */
interface CommunitySessionStore {
    fun load(): CommunitySession?
    fun save(session: CommunitySession)
    fun clear()
    fun loadPending(): PendingCommunityLogin?
    fun savePending(pending: PendingCommunityLogin)
    fun clearPending()

    object Empty : CommunitySessionStore {
        override fun load(): CommunitySession? = null
        override fun save(session: CommunitySession) = Unit
        override fun clear() = Unit
        override fun loadPending(): PendingCommunityLogin? = null
        override fun savePending(pending: PendingCommunityLogin) = Unit
        override fun clearPending() = Unit
    }
}

class PreferencesCommunitySessionStore(
    private val preferences: LocalPreferences,
) : CommunitySessionStore {
    override fun load(): CommunitySession? {
        val raw = preferences.communitySessionJson ?: return null
        return runCatching {
            CommunityJson.decodeFromString<CommunitySession>(raw)
        }.getOrElse {
            preferences.communitySessionJson = null
            null
        }
    }

    override fun save(session: CommunitySession) {
        preferences.communitySessionJson = CommunityJson.encodeToString(session)
    }

    override fun clear() {
        preferences.communitySessionJson = null
    }

    override fun loadPending(): PendingCommunityLogin? {
        val raw = preferences.communityPendingLoginJson ?: return null
        return runCatching {
            CommunityJson.decodeFromString<PendingCommunityLogin>(raw)
        }.getOrElse {
            preferences.communityPendingLoginJson = null
            null
        }
    }

    override fun savePending(pending: PendingCommunityLogin) {
        preferences.communityPendingLoginJson = CommunityJson.encodeToString(pending)
    }

    override fun clearPending() {
        preferences.communityPendingLoginJson = null
    }
}