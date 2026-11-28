package com.fograin.launcher.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.CommunityFeed
import com.fograin.launcher.app.CommunityProfileUiState
import com.fograin.launcher.app.CommunitySessionState
import com.fograin.launcher.app.CommunityUiState
import com.fograin.launcher.community.CommunityAccount
import com.fograin.launcher.community.CommunityMeta
import com.fograin.launcher.community.CommunityNote
import com.fograin.launcher.community.CommunityTab
import com.fograin.launcher.community.CommunityUser
import com.fograin.launcher.platform.CoreAccount
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.community.CommunityProfileScreenTag
import com.fograin.launcher.ui.community.CommunityScreenTag
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.theme.FograinTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Opening a profile from the feed must use the shell's page transition, and
 * the outgoing feed must stay composed long enough to animate out. The mid
 * frame is saved for visual review.
 */
@OptIn(ExperimentalTestApi::class)
class CommunityTransitionTest {
    @Test
    fun render() = runDesktopComposeUiTest(width = 420, height = 900) {
        val model = AppModel.create(
            core = FakeCoreClient(),
            platform = testPlatform(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val base = model.state.value
        val user = CommunityUser(id = "u1", name = "Jerry", username = "jerry")
        val profileUser = CommunityUser(id = "u2", name = "Mika", username = "mika", notesCount = 3)
        val note = CommunityNote(
            id = "n1",
            createdAt = "2026-11-26T02:00:00.000Z",
            text = "Hello community",
            user = user,
        )
        val feedState: AppUiState = base.copy(
            destination = AppDestination.COMMUNITY,
            account = base.account.copy(
                accounts = listOf(CoreAccount(id = "a1", name = "Steve", selected = true)),
                selectedId = "a1",
            ),
            community = CommunityUiState(
                account = CommunityAccount("https://lab.crystelf.top", user, 1),
                sessionState = CommunitySessionState.LOGGED_IN,
                meta = CommunityMeta(),
                tab = CommunityTab.LOCAL,
                feeds = mapOf(CommunityTab.LOCAL to CommunityFeed(notes = listOf(note), lastLoadedAt = 1)),
            ),
        )
        val profileState = feedState.copy(
            community = feedState.community.copy(
                profile = CommunityProfileUiState(user = profileUser),
            ),
        )
        val holder = mutableStateOf(feedState)
        setContent {
            val state = holder.value
            FograinTheme(themeMode = state.theme, accent = state.accent.value) {
                AppLocale(state.language) { FograinApp(model = model, state = state) }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        runOnIdle { holder.value = profileState }
        mainClock.advanceTimeBy(90)
        val mid = onRoot().captureToImage()
        val midBytes = Image.makeFromBitmap(mid.asSkiaBitmap())
            .encodeToData(EncodedImageFormat.PNG)!!.bytes
        val midFile = Files.createTempFile("fograin-profile-transition-", ".png")
        midFile.toFile().writeBytes(midBytes)
        println("FOGRAIN_PROFILE_TRANSITION_MID=$midFile")
        onNodeWithTag(CommunityScreenTag).assertExists()
        onNodeWithTag(CommunityProfileScreenTag).assertExists()
        mainClock.advanceTimeBy(500)
        onNodeWithTag(CommunityScreenTag).assertDoesNotExist()
        onNodeWithTag(CommunityProfileScreenTag).assertExists()
        val end = onRoot().captureToImage()
        val endBytes = Image.makeFromBitmap(end.asSkiaBitmap())
            .encodeToData(EncodedImageFormat.PNG)!!.bytes
        val endFile = Files.createTempFile("fograin-profile-transition-end-", ".png")
        endFile.toFile().writeBytes(endBytes)
        println("FOGRAIN_PROFILE_TRANSITION_END=$endFile")
        assertTrue(midBytes.size > 10_000)

        // Closing must keep the outgoing profile snapshot alive for the exit
        // animation instead of blanking as soon as the model clears it.
        runOnIdle { holder.value = feedState }
        mainClock.advanceTimeBy(90)
        onNodeWithTag(CommunityProfileScreenTag).assertExists()
        onNodeWithTag(CommunityScreenTag).assertExists()
        mainClock.advanceTimeBy(500)
        onNodeWithTag(CommunityProfileScreenTag).assertDoesNotExist()
        onNodeWithTag(CommunityScreenTag).assertExists()
    }
}
