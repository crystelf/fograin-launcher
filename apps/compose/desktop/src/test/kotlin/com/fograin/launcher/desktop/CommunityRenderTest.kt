package com.fograin.launcher.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.community.CommunityAccount
import com.fograin.launcher.app.CommunityFeed
import com.fograin.launcher.app.CommunityProfileUiState
import com.fograin.launcher.app.CommunitySessionState
import com.fograin.launcher.app.CommunityUiState
import com.fograin.launcher.community.CommunityMeta
import com.fograin.launcher.community.CommunityNote
import com.fograin.launcher.community.CommunityTab
import com.fograin.launcher.community.CommunityUser
import com.fograin.launcher.platform.CoreAccount
import com.fograin.launcher.settings.ThemeMode
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.theme.FograinTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class CommunityRenderTest {
    @Test
    fun rendersCommunityFeedInWideAndCompactWindows() = runBlocking {
        val model = AppModel.create(
            core = FakeCoreClient(),
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val base = model.state.value
        val user = CommunityUser(
            id = "u1",
            name = "Jerry",
            username = "jerry",
            avatarUrl = "",
        )
        val note = CommunityNote(
            id = "note-1",
            createdAt = "2026-11-26T02:00:00.000Z",
            text = "社区的第一条帖子。\n\n**Markdown 预览**、@Jerry 和 #Fograin 都可以显示。",
            user = user,
            reactions = mapOf("👍" to 2, "❤️" to 1),
            reactionCount = 3,
            repliesCount = 1,
        )
        val reply = note.copy(
            id = "note-2",
            text = "这是一条回复，用来检查详情页的上下文。",
            replyId = note.id,
            reply = note,
        )
        val state = base.copy(
            destination = AppDestination.COMMUNITY,
            account = base.account.copy(
                accounts = listOf(CoreAccount(id = "a1", name = "Steve_Fog", selected = true)),
                selectedId = "a1",
            ),
            community = CommunityUiState(
                account = CommunityAccount(
                    server = "https://lab.crystelf.top",
                    user = user,
                    savedAtEpochMillis = 1,
                ),
                sessionState = CommunitySessionState.LOGGED_IN,
                meta = CommunityMeta(),
                tab = CommunityTab.LOCAL,
                feeds = mapOf(
                    CommunityTab.LOCAL to CommunityFeed(
                        notes = listOf(note, reply),
                        lastLoadedAt = 1,
                    ),
                ),
            ),
        )

        val wide = render(model, state, 1120, 780)
        val compact = render(model, state, 420, 900)
        val wideFile = Files.createTempFile("fograin-community-wide-", ".png").also { it.toFile().writeBytes(wide) }
        val compactFile = Files.createTempFile("fograin-community-compact-", ".png").also { it.toFile().writeBytes(compact) }
        println("FOGRAIN_COMMUNITY_PNG_WIDE=$wideFile")
        println("FOGRAIN_COMMUNITY_PNG_COMPACT=$compactFile")
        assertTrue(wide.size > 8_000, "wide community frame too small: ${wide.size}")
        assertTrue(compact.size > 6_000, "compact community frame too small: ${compact.size}")

        val guestState = state.copy(
            community = state.community.copy(
                account = null,
                sessionState = CommunitySessionState.LOGGED_OUT,
            ),
        )
        val guest = render(model, guestState, 420, 900)
        val guestFile = Files.createTempFile("fograin-community-guest-", ".png")
            .also { it.toFile().writeBytes(guest) }
        println("FOGRAIN_COMMUNITY_PNG_GUEST=$guestFile")
        assertTrue(guest.size > 6_000, "guest community frame too small: ${guest.size}")

        val homeState = state.copy(destination = AppDestination.HOME)
        val home = render(model, homeState, 420, 900)
        val homeFile = Files.createTempFile("fograin-community-home-", ".png")
            .also { it.toFile().writeBytes(home) }
        println("FOGRAIN_COMMUNITY_PNG_HOME=$homeFile")
        assertTrue(home.size > 6_000, "home frame too small: ${home.size}")

        val detailState = state.copy(
            community = state.community.copy(
                selectedNote = note,
                replies = listOf(reply),
            ),
        )
        val detail = render(model, detailState, 420, 900)
        val detailFile = Files.createTempFile("fograin-community-detail-", ".png")
            .also { it.toFile().writeBytes(detail) }
        println("FOGRAIN_COMMUNITY_PNG_DETAIL=$detailFile")
        assertTrue(detail.size > 6_000, "detail frame too small: ${detail.size}")

        val profileUser = CommunityUser(
            id = "u2",
            name = "Mika",
            username = "mika",
            description = "Crystelf Lab 用户。",
            location = "Fograin",
            createdAt = "2026-10-03T08:00:00.000Z",
            followersCount = 12,
            followingCount = 4,
            notesCount = 7,
        )
        val profileState = state.copy(
            community = state.community.copy(
                profile = CommunityProfileUiState(
                    user = profileUser,
                    notes = listOf(
                        note.copy(id = "profile-note", user = profileUser, files = emptyList()),
                    ),
                ),
            ),
        )
        val profile = render(model, profileState, 420, 900)
        val profileFile = Files.createTempFile("fograin-community-profile-", ".png")
            .also { it.toFile().writeBytes(profile) }
        println("FOGRAIN_COMMUNITY_PNG_PROFILE=$profileFile")
        assertTrue(profile.size > 6_000, "profile frame too small: ${profile.size}")

        val profileWide = render(model, profileState, 1120, 780)
        val profileWideFile = Files.createTempFile("fograin-community-profile-wide-", ".png")
            .also { it.toFile().writeBytes(profileWide) }
        println("FOGRAIN_COMMUNITY_PNG_PROFILE_WIDE=$profileWideFile")
        assertTrue(profileWide.size > 8_000, "wide profile frame too small: ${profileWide.size}")

        val profileLight = render(
            model,
            profileState.copy(theme = ThemeMode.LIGHT),
            420,
            900,
        )
        val profileLightFile = Files.createTempFile("fograin-community-profile-light-", ".png")
            .also { it.toFile().writeBytes(profileLight) }
        println("FOGRAIN_COMMUNITY_PNG_PROFILE_LIGHT=$profileLightFile")
        assertTrue(profileLight.size > 6_000, "light profile frame too small: ${profileLight.size}")
    }

    private fun render(model: AppModel, state: AppUiState, width: Int, height: Int): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            FograinTheme(themeMode = state.theme, accent = state.accent.value) {
                AppLocale(state.language) {
                    FograinApp(model = model, state = state)
                }
            }
        }
        return try {
            repeat(6) { index -> scene.render((index + 1) * 100_000_000L) }
            val image = scene.render(700_000_000L)
            image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }
}
