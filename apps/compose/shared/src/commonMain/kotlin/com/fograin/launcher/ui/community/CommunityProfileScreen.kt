package com.fograin.launcher.ui.community

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.fograin.launcher.platform.platformServices
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.CommunityProfileUiState
import com.fograin.launcher.community.CommunityUser
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_profile_avatar_image
import com.fograin.launcher.resources.community_profile_banner_image
import com.fograin.launcher.ui.components.FograinMotion
import com.fograin.launcher.ui.components.pageSurfaceColor
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

const val CommunityProfileScreenTag: String = "community-profile-screen"

/** Height range of the cover before any pull-to-expand offset. */
private val ProfileHeroMinHeight = 240.dp
private val ProfileHeroMaxHeight = 360.dp

/**
 * Fraction of the available height used by the cover on a normal profile.
 * The reference screenshot spends roughly a third of the screen on the cover;
 * this keeps that proportion on both phones and desktop windows.
 */
private const val ProfileHeroFraction = 0.36f

/** How much of a downward drag is translated into cover expansion. */
private const val ProfilePullGain = 1.25f

/**
 * A pull that reaches this fraction of the available expansion snaps the
 * cover to the full viewport and pins it there. Below it the cover folds back.
 */
private const val ProfilePinThreshold = 0.22f

/**
 * A user profile with a full-bleed cover, pull-to-expand preview and the
 * user's notes.
 *
 * The cover is the first lazy-list item, so it scrolls away with the profile
 * like any other content. Pulling down while the list is at the top grows it
 * until it fills the viewport; releasing settles it back with the same spring
 * the rest of the launcher uses. Tapping the avatar or the cover opens the
 * shared in-app image viewer.
 */
@Composable
fun CommunityProfileScreen(
    model: AppModel,
    state: AppUiState,
    profile: CommunityProfileUiState = state.community.profile,
) {
    val user = profile.user ?: return
    val savingIds = state.community.savingMediaIds
    val avatarImageTitle = stringResource(Res.string.community_profile_avatar_image)
    val bannerImageTitle = stringResource(Res.string.community_profile_banner_image)
    var viewer by remember(user.id) { mutableStateOf<ProfileImageTarget?>(null) }
    val pageColor = pageSurfaceColor()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(pageColor)
            .testTag(CommunityProfileScreenTag),
    ) {
        val density = LocalDensity.current
        val listState = key(user.id) { rememberLazyListState() }
        val scope = rememberCoroutineScope()
        val heroBaseHeight = (maxHeight * ProfileHeroFraction)
            .coerceIn(ProfileHeroMinHeight, ProfileHeroMaxHeight)
            .coerceAtMost(maxHeight)
        val heroBasePx = with(density) { heroBaseHeight.toPx() }
        val maxPullPx = with(density) {
            (maxHeight - heroBaseHeight).toPx().coerceAtLeast(0f)
        }
        val maxPullState by rememberUpdatedState(maxPullPx)

        var pullPx by remember(user.id) { mutableFloatStateOf(0f) }
        var coverJob by remember(user.id) { mutableStateOf<Job?>(null) }
        var pinned by remember(user.id) { mutableStateOf(false) }
        // A downward drag should snap to the pinned cover; an upward drag
        // cancels that intent and folds the cover back on release.
        var pinOnRelease by remember(user.id) { mutableStateOf(true) }

        fun atTop(): Boolean =
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0

        fun animateCoverTo(targetPx: Float) {
            coverJob?.cancel()
            val target = targetPx.coerceIn(0f, maxPullState)
            if (abs(pullPx - target) < 0.5f) {
                pullPx = target
                return
            }
            coverJob = scope.launch {
                animate(
                    initialValue = pullPx,
                    targetValue = target,
                    animationSpec = FograinMotion.Layout,
                ) { value, _ ->
                    pullPx = value.coerceIn(0f, maxPullState)
                }
                pullPx = target
            }
        }

        fun pinCover() {
            if (maxPullState <= 0f) return
            pinned = true
            pinOnRelease = true
            animateCoverTo(maxPullState)
        }

        fun unpinCover() {
            pinned = false
            pinOnRelease = false
            animateCoverTo(0f)
        }

        val nestedScrollConnection = remember(listState, scope) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // Closing the pinned cover takes precedence over scrolling
                    // the list, so one upward drag folds it back first.
                    if (available.y < 0f && (pullPx > 0f || pinned)) {
                        coverJob?.cancel()
                        pinOnRelease = false
                        val next = (pullPx + available.y).coerceAtLeast(0f)
                        val consumed = next - pullPx
                        pullPx = next
                        if (next <= 0f) pinned = false
                        return Offset(0f, consumed)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (available.y <= 0f || !atTop()) return Offset.Zero
                    // Only direct input (drag or wheel) expands the cover.
                    // Flings and programmatic jumps must not grow it.
                    if (source == NestedScrollSource.SideEffect) return Offset.Zero
                    val maxPull = maxPullState
                    if (maxPull <= 0f) return Offset.Zero
                    if (pinned) {
                        // The cover already fills the viewport; keep the list
                        // still until the user folds it with an upward drag.
                        return Offset(0f, available.y)
                    }
                    coverJob?.cancel()
                    pullPx = (pullPx + available.y * ProfilePullGain)
                        .coerceIn(0f, maxPull)
                    pinOnRelease = true
                    // Consume the downward pull so the platform overscroll
                    // effect does not stretch the list a second time.
                    return Offset(0f, available.y)
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    if (pullPx > 0f) {
                        if (pinOnRelease && pullPx >= maxPullState * ProfilePinThreshold) {
                            pinCover()
                        } else {
                            unpinCover()
                        }
                    }
                    return Velocity.Zero
                }
            }
        }

        val heroHeight = heroBaseHeight + with(density) { pullPx.toDp() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val wheelHandler by rememberUpdatedState<(Float, Float) -> Boolean> { deltaY, pointerY ->
            // The wheel pulls the cover while the pointer is over the profile
            // header and the profile is still at the top. Once expanded the
            // whole viewport becomes the hit area so a wheel up can release it;
            // scrolling over the note list keeps its normal meaning.
            val pullZone = if (pinned || pullPx > 0f) {
                viewportHeightPx
            } else {
                viewportHeightPx * 0.55f
            }
            if (!atTop() || pointerY > pullZone) {
                false
            } else if (deltaY < 0f) {
                // Match the pull gesture: moving the page down (finger down on
                // a natural-scrolling trackpad) takes the cover to the full
                // viewport and pins it. One wheel step commits the transition.
                pinCover()
                true
            } else if (deltaY > 0f && (pinned || pullPx > 0f)) {
                // The opposite direction returns to the profile and then keeps
                // scrolling normally once the cover has folded away.
                unpinCover()
                true
            } else {
                false
            }
        }

        LaunchedEffect(maxPullPx) {
            when {
                pinned && maxPullPx > 0f -> pullPx = maxPullPx
                pullPx > maxPullPx -> pullPx = maxPullPx.coerceAtLeast(0f)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(user.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val delta = change.scrollDelta.y
                            if (delta == 0f) continue
                            if (wheelHandler(delta, change.position.y)) {
                                change.consume()
                            }
                        }
                    }
                }
                .nestedScroll(nestedScrollConnection),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "hero", contentType = "hero") {
                    ProfileHero(
                        user = user,
                        height = heroHeight,
                        isSelf = state.community.account?.user?.id == user.id,
                        isLoggedIn = state.community.isLoggedIn,
                        isLoading = profile.isLoading,
                        hasError = profile.error != null,
                        followBusy = profile.followBusy,
                        onFollow = model.community::toggleFollow,
                        onLogin = model.community::beginLogin,
                        onAvatarClick = {
                            if (user.avatarUrl.isNotBlank()) {
                                viewer = ProfileImageTarget(
                                    url = user.avatarUrl,
                                    fileName = profileImageFileName(
                                        prefix = "avatar",
                                        username = user.username,
                                        url = user.avatarUrl,
                                    ),
                                    title = avatarImageTitle,
                                )
                            }
                        },
                        onBannerClick = {
                            val banner = user.bannerUrl
                            if (!banner.isNullOrBlank()) {
                                viewer = ProfileImageTarget(
                                    url = banner,
                                    fileName = profileImageFileName(
                                        prefix = "banner",
                                        username = user.username,
                                        url = banner,
                                    ),
                                    title = bannerImageTitle,
                                )
                            }
                        },
                    )
                }
                if (profile.isLoading) {
                    item(key = "loading", contentType = "loading") {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                        )
                    }
                }
                item(key = "bio", contentType = "bio") {
                    ProfileSection(modifier = Modifier.padding(top = 6.dp)) {
                        ProfileDetails(user = user)
                    }
                }
                item(key = "stats", contentType = "stats") {
                    ProfileSection {
                        ProfileStats(user = user)
                    }
                }
                profile.error?.let { failure ->
                    item(key = "error", contentType = "error") {
                        ProfileSection {
                            ProfileErrorCard(
                                failure = failure,
                                onRetry = model.community::retryUserProfile,
                            )
                        }
                    }
                }
                item(key = "posts-header", contentType = "header") {
                    ProfileSection(modifier = Modifier.padding(top = 8.dp)) {
                        ProfilePostsHeader(count = user.notesCount)
                    }
                }
                if (profile.notes.isEmpty() && !profile.isLoading && profile.error == null) {
                    item(key = "empty", contentType = "empty") {
                        ProfileSection {
                            ProfileEmptyPosts()
                        }
                    }
                }
                items(profile.notes, key = { it.id }, contentType = { "note" }) { note ->
                    ProfileSection {
                        CommunityNoteCard(
                            model = model,
                            state = state,
                            note = note,
                        )
                    }
                }
            }

            ProfileAppBar(
                listState = listState,
                collapseDistancePx = heroBasePx * 0.6f,
                user = user,
                onBack = {
                    if (pinned) unpinCover() else model.community.closeUserProfile()
                },
                onOpenBrowser = { model.community.openUserInBrowser(user) },
            )
        }

        viewer?.let { target ->
            CommunityImageDialog(
                url = target.url,
                name = target.fileName,
                title = target.title,
                isSaving = "remote-${target.url.hashCode()}" in savingIds,
                onSave = { model.community.saveRemoteImage(target.url, target.fileName) },
                onDismiss = { viewer = null },
            )
        }
    }
}

/**
 * Reads the lazy-list scroll offset here rather than in [CommunityProfileScreen]
 * so a scrolling profile only recomposes the app bar, not the whole page.
 */
@Composable
private fun ProfileAppBar(
    listState: LazyListState,
    collapseDistancePx: Float,
    user: CommunityUser,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val collapse by remember(listState, collapseDistancePx) {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                collapseDistancePx <= 0f -> 0f
                else -> {
                    // Settle into the opaque app bar while part of the cover
                    // is still on screen; waiting for the whole cover to leave
                    // lets the bio scroll under nearly transparent icons.
                    (listState.firstVisibleItemScrollOffset / collapseDistancePx)
                        .coerceIn(0f, 1f)
                }
            }
        }
    }
    ProfileTopBar(
        user = user,
        collapse = collapse,
        onBack = onBack,
        onOpenBrowser = onOpenBrowser,
    )
}

@Composable
private fun ProfileSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ProfileContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .then(modifier),
            content = content,
        )
    }
}

private data class ProfileImageTarget(
    val url: String,
    val fileName: String,
    val title: String,
)

/**
 * Keep the real image extension when saving an avatar or cover; Misskey
 * commonly serves WebP, and a `.jpg` name would lie about the bytes.
 */
private fun profileImageFileName(prefix: String, username: String, url: String): String {
    val extension = url.substringBefore('?')
        .substringAfterLast('.', "")
        .lowercase()
    val safeExtension = extension.takeIf {
        it == "jpg" || it == "jpeg" || it == "png" || it == "webp" || it == "gif"
    } ?: "jpg"
    return "$prefix-$username.$safeExtension"
}
