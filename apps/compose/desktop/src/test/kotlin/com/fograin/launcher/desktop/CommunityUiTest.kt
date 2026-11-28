package com.fograin.launcher.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.CommunityComposerUiState
import com.fograin.launcher.app.CommunityFeed
import com.fograin.launcher.app.CommunityLoginUiState
import com.fograin.launcher.app.CommunityProfileUiState
import com.fograin.launcher.app.CommunitySessionState
import com.fograin.launcher.app.CommunityUiState
import com.fograin.launcher.community.CommunityAccount
import com.fograin.launcher.community.CommunityFile
import com.fograin.launcher.community.CommunityMeta
import com.fograin.launcher.community.CommunityMetaPolicies
import com.fograin.launcher.community.CommunityNote
import com.fograin.launcher.community.CommunityTab
import com.fograin.launcher.community.CommunityUser
import com.fograin.launcher.platform.CoreAccount
import com.fograin.launcher.ui.AppDestination
import com.fograin.launcher.ui.FograinApp
import com.fograin.launcher.ui.community.CommunityComposerFieldTag
import com.fograin.launcher.ui.community.CommunityComposerPollEditorTag
import com.fograin.launcher.ui.community.CommunityComposeFabTag
import com.fograin.launcher.ui.community.CommunityComposerScheduleButtonTag
import com.fograin.launcher.ui.community.CommunityComposerTag
import com.fograin.launcher.ui.community.CommunityFollowButtonTag
import com.fograin.launcher.ui.community.CommunityImageDialogTag
import com.fograin.launcher.ui.community.CommunityProfileAvatarTag
import com.fograin.launcher.ui.community.CommunityProfileBannerTag
import com.fograin.launcher.ui.community.CommunityLoginDialogTag
import com.fograin.launcher.ui.community.CommunityMediaViewerTag
import com.fograin.launcher.ui.community.CommunityProfileScreenTag
import com.fograin.launcher.ui.community.CommunityProfileTopBarIdentityTag
import com.fograin.launcher.ui.community.CommunityReactionPickerTag
import com.fograin.launcher.ui.community.communityDeleteTag
import com.fograin.launcher.ui.community.communityMediaTag
import com.fograin.launcher.ui.community.communityMoreTag
import com.fograin.launcher.ui.community.communityNoteTag
import com.fograin.launcher.ui.community.communityReactionTag
import com.fograin.launcher.ui.community.communityReportTag
import com.fograin.launcher.ui.community.communityReplyTag
import com.fograin.launcher.ui.accounts.AccountCommunityCardTag
import com.fograin.launcher.ui.accounts.AccountCommunityLogoutTag
import com.fograin.launcher.ui.locale.AppLocale
import com.fograin.launcher.ui.theme.FograinTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class CommunityUiTest {
    private fun model(): AppModel = AppModel.create(
        core = FakeCoreClient(),
        platform = testPlatform(),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private val user = CommunityUser(
        id = "u1",
        name = "Jerry",
        username = "jerry",
        avatarUrl = "",
    )

    private val note = CommunityNote(
        id = "n1",
        createdAt = "2026-11-26T02:00:00.000Z",
        text = "Hello **community** @jerry",
        user = user,
        repliesCount = 1,
    )

    private fun state(
        loggedIn: Boolean,
        composer: CommunityComposerUiState = CommunityComposerUiState(),
        login: CommunityLoginUiState = CommunityLoginUiState(),
    ): AppUiState {
        val base = model().state.value
        return base.copy(
            destination = AppDestination.COMMUNITY,
            account = base.account.copy(
                accounts = listOf(CoreAccount(id = "a1", name = "Steve_Fog", selected = true)),
                selectedId = "a1",
            ),
            community = CommunityUiState(
                account = if (loggedIn) {
                    CommunityAccount(
                        server = "https://lab.crystelf.top",
                        user = user,
                        savedAtEpochMillis = 1,
                    )
                } else {
                    null
                },
                sessionState = if (loggedIn) {
                    CommunitySessionState.LOGGED_IN
                } else {
                    CommunitySessionState.LOGGED_OUT
                },
                meta = CommunityMeta(
                    policies = CommunityMetaPolicies(scheduledNoteLimit = 1),
                ),
                tab = CommunityTab.LOCAL,
                feeds = mapOf(
                    CommunityTab.LOCAL to CommunityFeed(
                        notes = listOf(note),
                        lastLoadedAt = 1,
                    ),
                ),
                composer = composer,
                login = login,
            ),
        )
    }

    private val profileUser = CommunityUser(
        id = "u2",
        name = "Mika",
        username = "mika",
        description = "Crystelf Lab user",
        avatarUrl = "https://example.invalid/avatar.jpg",
        bannerUrl = "https://example.invalid/banner.jpg",
        location = "Fograin",
        createdAt = "2026-10-03T08:00:00.000Z",
        followersCount = 12,
        followingCount = 4,
        notesCount = 7,
    )

    private fun profileState(): AppUiState {
        val base = state(loggedIn = true)
        return base.copy(
            community = base.community.copy(
                profile = CommunityProfileUiState(
                    user = profileUser,
                    notes = (1..6).map { index ->
                        note.copy(
                            id = "profile-note-$index",
                            user = profileUser,
                            text = "Profile note $index with enough text to scroll the page.",
                        )
                    },
                ),
            ),
        )
    }

    @Test
    fun guestsSeeTheFeedButNoPostingOrReplyActions() = runDesktopComposeUiTest(width = 420, height = 900) {
        val appModel = model()
        val state = state(loggedIn = false)
        setContent {
            FograinTheme(themeMode = state.theme, accent = state.accent.value) {
                AppLocale(state.language) { FograinApp(model = appModel, state = state) }
            }
        }
        waitForIdle()
        onNodeWithTag(communityNoteTag("n1")).assertIsDisplayed()
        onNodeWithTag(CommunityComposeFabTag).assertDoesNotExist()
        onNodeWithTag(communityReplyTag("n1")).assertDoesNotExist()
    }

    @Test
    fun loggedInComposerShowsTheMarkdownEditor() = runDesktopComposeUiTest(width = 800, height = 900) {
        val appModel = model()
        val state = state(
            loggedIn = true,
            composer = CommunityComposerUiState(
                open = true,
                text = "**Hello** community",
                selection = 3,
            ),
        )
        setContent {
            FograinTheme(themeMode = state.theme, accent = state.accent.value) {
                AppLocale(state.language) { FograinApp(model = appModel, state = state) }
            }
        }
        waitForIdle()
        onNodeWithTag(CommunityComposerTag).assertIsDisplayed()
        onNodeWithTag(CommunityComposerFieldTag).assertIsDisplayed()
    }

    @Test
    fun loginDialogIsShownWhileWaitingForBrowserApproval() =
        runDesktopComposeUiTest(width = 800, height = 900) {
            val appModel = model()
            val state = state(
                loggedIn = false,
                login = CommunityLoginUiState(open = true, waiting = true),
            )
            setContent {
                FograinTheme(themeMode = state.theme, accent = state.accent.value) {
                    AppLocale(state.language) { FograinApp(model = appModel, state = state) }
                }
            }
            waitForIdle()
            onNodeWithTag(CommunityLoginDialogTag).assertIsDisplayed()
        }
    @Test
    fun composerIsWidthLimitedOnDesktop() = runDesktopComposeUiTest(width = 1000, height = 900) {
        val appModel = model()
        val state = state(
            loggedIn = true,
            composer = CommunityComposerUiState(
                open = true,
                text = "A desktop-width composer",
                pollEnabled = true,
                pollChoices = listOf("One", "Two"),
                hashtagsEnabled = true,
            ),
        )
        setContent {
            FograinTheme(themeMode = state.theme, accent = state.accent.value) {
                AppLocale(state.language) { FograinApp(model = appModel, state = state) }
            }
        }
        waitForIdle()
        val width = onNodeWithTag(CommunityComposerTag)
            .fetchSemanticsNode()
            .boundsInRoot
            .width
        assertTrue(width <= 720f, "composer width should be capped at 720 dp, was $width")
        onNodeWithTag(CommunityComposerPollEditorTag).assertIsDisplayed()
        onNodeWithTag(CommunityComposerScheduleButtonTag).assertIsDisplayed()
    }

    @Test
    fun reactionActionOpensTheCategoryPicker() = runDesktopComposeUiTest(width = 420, height = 900) {
        val appModel = model()
        val state = state(loggedIn = true)
        setContent {
            FograinTheme(themeMode = state.theme, accent = state.accent.value) {
                AppLocale(state.language) { FograinApp(model = appModel, state = state) }
            }
        }
        waitForIdle()
        onNodeWithTag(communityReactionTag("n1")).performClick()
        waitForIdle()
        onNodeWithTag(CommunityReactionPickerTag).assertIsDisplayed()
    }

    @Test
    fun ownPostOffersDeleteAndOtherPostOffersReport() = runDesktopComposeUiTest(width = 420, height = 900) {
        val appModel = model()
        val ownState = state(loggedIn = true)
        setContent {
            FograinTheme(themeMode = ownState.theme, accent = ownState.accent.value) {
                AppLocale(ownState.language) { FograinApp(model = appModel, state = ownState) }
            }
        }
        waitForIdle()
        onNodeWithTag(communityMoreTag("n1")).performClick()
        waitForIdle()
        onNodeWithTag(communityDeleteTag("n1")).assertIsDisplayed()
    }

    @Test
    fun adminCanDeleteSomeoneElsesPost() = runDesktopComposeUiTest(width = 420, height = 900) {
        val appModel = model()
        val admin = user.copy(isAdmin = true)
        val otherNote = note.copy(
            id = "n2",
            user = CommunityUser(id = "u2", name = "Other", username = "other"),
        )
        val base = state(loggedIn = true).copy(
            community = state(loggedIn = true).community.copy(
                account = CommunityAccount(
                    server = "https://lab.crystelf.top",
                    user = admin,
                    savedAtEpochMillis = 1,
                ),
                feeds = mapOf(
                    CommunityTab.LOCAL to CommunityFeed(
                        notes = listOf(otherNote),
                        lastLoadedAt = 1,
                    ),
                ),
            ),
        )
        setContent {
            FograinTheme(themeMode = base.theme, accent = base.accent.value) {
                AppLocale(base.language) { FograinApp(model = appModel, state = base) }
            }
        }
        waitForIdle()
        onNodeWithTag(communityNoteTag("n2")).assertIsDisplayed()
        onNodeWithTag(communityMoreTag("n2")).performClick()
        waitForIdle()
        onNodeWithTag(communityDeleteTag("n2")).assertIsDisplayed()
    }

    @Test
    fun accountSheetHasOneCommunityRowWithLogoutOnly() = runDesktopComposeUiTest(width = 420, height = 900) {
        val appModel = model()
        val state = state(loggedIn = true).copy(
            account = state(loggedIn = true).account.copy(open = true),
        )
        setContent {
            FograinTheme(themeMode = state.theme, accent = state.accent.value) {
                AppLocale(state.language) { FograinApp(model = appModel, state = state) }
            }
        }
        waitForIdle()
        onNodeWithTag(AccountCommunityCardTag).assertIsDisplayed()
        onNodeWithTag(AccountCommunityLogoutTag).assertIsDisplayed()
    }

    @Test
    fun nonAdminCannotDeleteSomeoneElsesPostButCanReportIt() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            val appModel = model()
            val otherNote = note.copy(
                id = "n3",
                user = CommunityUser(id = "u3", name = "Other", username = "other"),
            )
            val base = state(loggedIn = true).copy(
                community = state(loggedIn = true).community.copy(
                    feeds = mapOf(
                        CommunityTab.LOCAL to CommunityFeed(
                            notes = listOf(otherNote),
                            lastLoadedAt = 1,
                        ),
                    ),
                ),
            )
            setContent {
                FograinTheme(themeMode = base.theme, accent = base.accent.value) {
                    AppLocale(base.language) { FograinApp(model = appModel, state = base) }
                }
            }
            waitForIdle()
            onNodeWithTag(communityMoreTag("n3")).performClick()
            waitForIdle()
            onNodeWithTag(communityReportTag("n3")).assertIsDisplayed()
            onNodeWithTag(communityDeleteTag("n3")).assertDoesNotExist()
        }

    @Test
    fun profileShowsBannerStatsAndFollowAction() = runDesktopComposeUiTest(width = 420, height = 900) {
        val appModel = model()
        val base = profileState()
        setContent {
            FograinTheme(themeMode = base.theme, accent = base.accent.value) {
                AppLocale(base.language) { FograinApp(model = appModel, state = base) }
            }
        }
        waitForIdle()
        onNodeWithTag(CommunityProfileScreenTag).assertIsDisplayed()
        onNodeWithTag(CommunityFollowButtonTag).assertIsDisplayed()
        onNodeWithTag(CommunityProfileBannerTag).assertIsDisplayed()
        onNodeWithTag(CommunityProfileAvatarTag).performClick()
        waitForIdle()
        onNodeWithTag(CommunityImageDialogTag).assertIsDisplayed()
    }

    /**
     * The cover is the first lazy-list item. Pulling at the top snaps it to the
     * viewport and pins it there; an upward drag folds it back to the profile.
     */
    @Test
    fun pullingTheTopPinsTheCoverAndScrollingUpReleasesIt() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            val appModel = model()
            val base = profileState()
            setContent {
                FograinTheme(themeMode = base.theme, accent = base.accent.value) {
                    AppLocale(base.language) { FograinApp(model = appModel, state = base) }
                }
            }
            waitForIdle()
            val restHeight = onNodeWithTag(CommunityProfileBannerTag)
                .fetchSemanticsNode().boundsInRoot.height
            val viewportHeight = onNodeWithTag(CommunityProfileScreenTag)
                .fetchSemanticsNode().boundsInRoot.height

            mainClock.autoAdvance = false
            onNodeWithTag(CommunityProfileScreenTag).performTouchInput {
                down(Offset(centerX, height * 0.8f))
                moveBy(Offset(0f, 700f))
            }
            mainClock.advanceTimeBy(48)
            val pulledHeight = onNodeWithTag(CommunityProfileBannerTag)
                .fetchSemanticsNode().boundsInRoot.height
            assertTrue(
                pulledHeight > restHeight + 200f,
                "cover should expand on pull, was $pulledHeight from $restHeight",
            )

            onNodeWithTag(CommunityProfileScreenTag).performTouchInput { up() }
            mainClock.advanceTimeBy(2_000)
            val pinnedHeight = onNodeWithTag(CommunityProfileBannerTag)
                .fetchSemanticsNode().boundsInRoot.height
            assertTrue(
                abs(pinnedHeight - viewportHeight) < 2f,
                "cover should pin at $viewportHeight, was $pinnedHeight",
            )

            onNodeWithTag(CommunityProfileScreenTag).performTouchInput {
                down(Offset(centerX, height * 0.8f))
                moveBy(Offset(0f, -500f))
                up()
            }
            mainClock.advanceTimeBy(2_000)
            val releasedHeight = onNodeWithTag(CommunityProfileBannerTag)
                .fetchSemanticsNode().boundsInRoot.height
            assertTrue(
                abs(releasedHeight - restHeight) < 2f,
                "cover should fold back to $restHeight, was $releasedHeight",
            )
        }

    /**
     * The wheel follows the same direction as a downward finger pull: one step
     * toward the page (negative scroll delta) commits to the pinned cover, and
     * the opposite direction releases it.
     */
    @Test
    fun wheelOverTheCoverPinFollowsThePullDirection() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            val appModel = model()
            val base = profileState()
            setContent {
                FograinTheme(themeMode = base.theme, accent = base.accent.value) {
                    AppLocale(base.language) { FograinApp(model = appModel, state = base) }
                }
            }
            waitForIdle()
            val restHeight = onNodeWithTag(CommunityProfileBannerTag)
                .fetchSemanticsNode().boundsInRoot.height
            val viewportHeight = onNodeWithTag(CommunityProfileScreenTag)
                .fetchSemanticsNode().boundsInRoot.height

            mainClock.autoAdvance = false
            onNodeWithTag(CommunityProfileScreenTag).performMouseInput {
                moveTo(Offset(centerX, 120f))
                scroll(-10f)
            }
            mainClock.advanceTimeBy(2_000)
            val pinnedHeight = onNodeWithTag(CommunityProfileBannerTag)
                .fetchSemanticsNode().boundsInRoot.height
            assertTrue(
                abs(pinnedHeight - viewportHeight) < 2f,
                "wheel should pin the cover at $viewportHeight, was $pinnedHeight",
            )

            onNodeWithTag(CommunityProfileScreenTag).performMouseInput {
                scroll(10f)
            }
            mainClock.advanceTimeBy(2_000)
            val releasedHeight = onNodeWithTag(CommunityProfileBannerTag)
                .fetchSemanticsNode().boundsInRoot.height
            assertTrue(
                abs(releasedHeight - restHeight) < 2f,
                "wheel up should release the cover to $restHeight, was $releasedHeight",
            )
        }

    @Test
    fun scrollingTheProfileMovesTheAvatarAndNameIntoTheTopBar() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            val appModel = model()
            val base = profileState()
            setContent {
                FograinTheme(themeMode = base.theme, accent = base.accent.value) {
                    AppLocale(base.language) { FograinApp(model = appModel, state = base) }
                }
            }
            waitForIdle()
            onNodeWithTag(CommunityProfileScreenTag).performMouseInput {
                moveTo(Offset(centerX, 620f))
                scroll(100f)
            }
            waitForIdle()
            onNodeWithTag(CommunityProfileTopBarIdentityTag).assertIsDisplayed()
        }

    @Test
    fun imageAttachmentOpensTheInAppViewer() = runDesktopComposeUiTest(width = 420, height = 900) {
        val appModel = model()
        val withImage = note.copy(
            id = "n-media",
            files = listOf(
                CommunityFile(
                    id = "f1",
                    name = "photo.jpg",
                    type = "image/jpeg",
                    url = "https://example.com/photo.jpg",
                    thumbnailUrl = "https://example.com/photo.jpg",
                ),
            ),
        )
        val base = state(loggedIn = true).copy(
            community = state(loggedIn = true).community.copy(
                feeds = mapOf(
                    CommunityTab.LOCAL to CommunityFeed(
                        notes = listOf(withImage),
                        lastLoadedAt = 1,
                    ),
                ),
            ),
        )
        setContent {
            FograinTheme(themeMode = base.theme, accent = base.accent.value) {
                AppLocale(base.language) { FograinApp(model = appModel, state = base) }
            }
        }
        waitForIdle()
        onNodeWithTag(communityMediaTag("f1")).performClick()
        waitForIdle()
        onNodeWithTag(CommunityMediaViewerTag).assertIsDisplayed()
    }

    @Test
    fun loggedInFeedShowsComposeFab() = runDesktopComposeUiTest(width = 420, height = 900) {
        val appModel = model()
        val state = state(loggedIn = true)
        setContent {
            FograinTheme(themeMode = state.theme, accent = state.accent.value) {
                AppLocale(state.language) { FograinApp(model = appModel, state = state) }
            }
        }
        waitForIdle()
        onNodeWithTag(CommunityComposeFabTag).assertIsDisplayed()
    }

}
