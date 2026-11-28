package com.fograin.launcher.app

import com.fograin.launcher.community.CommunityAccount
import com.fograin.launcher.community.CommunityUser
import com.fograin.launcher.platform.CoreAccount
import com.fograin.launcher.platform.CoreSettings
import com.fograin.launcher.settings.DefaultDisplayAccount
import com.fograin.launcher.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeProfileTest {
    private val launcherAccount = CoreAccount(id = "a1", name = "Steve_Fog")
    private val forumUser = CommunityUser(
        id = "u1",
        name = "Jerry",
        username = "jerry",
        avatarUrl = "https://lab.crystelf.top/avatar.webp",
    )

    private fun state(
        display: DefaultDisplayAccount = DefaultDisplayAccount.COMMUNITY,
        forumAccount: CommunityAccount? = CommunityAccount(
            server = "https://lab.crystelf.top",
            user = forumUser,
            savedAtEpochMillis = 1,
        ),
        sessionState: CommunitySessionState = CommunitySessionState.LOGGED_IN,
    ) = AppUiState(
        settings = CoreSettings(),
        theme = ThemeMode.SYSTEM,
        defaultDisplayAccount = display,
        account = AccountUiState(
            accounts = listOf(launcherAccount),
            selectedId = launcherAccount.id,
        ),
        community = CommunityUiState(
            account = forumAccount,
            sessionState = sessionState,
        ),
    )

    @Test
    fun communityIdentityWinsWhenSelectedAndLoggedIn() {
        val profile = state().homeProfile
        assertEquals("Jerry", profile.name)
        assertEquals(forumUser.avatarUrl, profile.avatarUrl)
        assertEquals(true, profile.isCommunity)
    }

    @Test
    fun launcherPreferenceKeepsMinecraftAccount() {
        val profile = state(display = DefaultDisplayAccount.LAUNCHER).homeProfile
        assertEquals("Steve_Fog", profile.name)
        assertNull(profile.avatarUrl)
        assertEquals(false, profile.isCommunity)
    }

    @Test
    fun loggedOutOrExpiredFallsBackToLauncher() {
        val loggedOut = state(forumAccount = null).homeProfile
        assertEquals("Steve_Fog", loggedOut.name)

        val expired = state(sessionState = CommunitySessionState.EXPIRED).homeProfile
        assertEquals("Steve_Fog", expired.name)
    }

    @Test
    fun displayAccountWireValuesAreTolerant() {
        assertEquals(DefaultDisplayAccount.COMMUNITY, DefaultDisplayAccount.fromWire(null))
        assertEquals(DefaultDisplayAccount.COMMUNITY, DefaultDisplayAccount.fromWire("unknown"))
        assertEquals(DefaultDisplayAccount.LAUNCHER, DefaultDisplayAccount.fromWire("launcher"))
    }

    @Test
    fun usernameIsTheFallbackWhenForumNameIsBlank() {
        val noName = forumUser.copy(name = "  ")
        val profile = state(
            forumAccount = CommunityAccount(
                server = "https://lab.crystelf.top",
                user = noName,
                savedAtEpochMillis = 1,
            ),
        ).homeProfile
        assertEquals("jerry", profile.name)
    }
}
