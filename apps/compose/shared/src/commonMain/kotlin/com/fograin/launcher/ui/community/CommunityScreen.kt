package com.fograin.launcher.ui.community

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.CommunityProfileUiState
import com.fograin.launcher.community.CommunityTab
import com.fograin.launcher.community.CommunityFailure
import com.fograin.launcher.community.CommunityFailureKind
import com.fograin.launcher.community.CommunityNote
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_composer_title
import com.fograin.launcher.resources.community_empty_body
import com.fograin.launcher.resources.community_empty_guest
import com.fograin.launcher.resources.community_empty_title
import com.fograin.launcher.resources.community_error_auth
import com.fograin.launcher.resources.community_error_network
import com.fograin.launcher.resources.community_error_not_found
import com.fograin.launcher.resources.community_error_rate_limit
import com.fograin.launcher.resources.community_error_server
import com.fograin.launcher.resources.community_error_title
import com.fograin.launcher.resources.community_error_unknown
import com.fograin.launcher.resources.community_error_validation
import com.fograin.launcher.resources.community_guest_banner_body
import com.fograin.launcher.resources.community_guest_banner_title
import com.fograin.launcher.resources.community_loading
import com.fograin.launcher.resources.community_login_action
import com.fograin.launcher.resources.community_login_required
import com.fograin.launcher.resources.community_login_required_body
import com.fograin.launcher.resources.community_no_more
import com.fograin.launcher.resources.community_open_browser
import com.fograin.launcher.resources.community_refresh
import com.fograin.launcher.resources.community_reply
import com.fograin.launcher.resources.community_retry
import com.fograin.launcher.resources.community_tab_following
import com.fograin.launcher.resources.community_tab_global
import com.fograin.launcher.resources.community_tab_local
import com.fograin.launcher.resources.community_title
import com.fograin.launcher.ui.components.AccountAvatarButton
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.components.SegmentedTabRail
import com.fograin.launcher.ui.components.SkeletonBox
import com.fograin.launcher.ui.components.fograinPageTransition
import com.fograin.launcher.ui.components.pageSurfaceColor
import org.jetbrains.compose.resources.stringResource

const val CommunityScreenTag: String = "community-screen"
const val CommunityFeedTag: String = "community-feed"
const val CommunityComposeFabTag: String = "community-compose-fab"
const val CommunityHeaderLoginTag: String = "community-header-login"
const val CommunityBannerLoginTag: String = "community-banner-login"
const val CommunityGateLoginTag: String = "community-gate-login"

@Composable
fun CommunityScreen(model: AppModel, state: AppUiState) {
    val community = state.community
    LaunchedEffect(Unit) { model.community.open() }

    val selected = community.selectedNote
    val page: CommunityPage = when {
        selected != null -> CommunityPage.Note(selected)
        community.profile.user != null -> CommunityPage.Profile(community.profile)
        else -> CommunityPage.Feed
    }

    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize(),
        contentKey = { it.key },
        transitionSpec = {
            fograinPageTransition(forward = targetState.rank >= initialState.rank)
        },
        label = "community-page",
    ) { target ->
        when (target) {
            CommunityPage.Feed -> CommunityFeedPage(model = model, state = state)
            is CommunityPage.Note -> CommunityNoteDetail(
                model = model,
                state = state,
                note = target.note,
            )
            is CommunityPage.Profile -> CommunityProfileScreen(
                model = model,
                state = state,
                profile = target.profile,
            )
        }
    }
}

/**
 * The community sub-pages behave like every other in-destination page: a
 * snapshot of the target page travels with the transition, so closing a
 * profile or note does not blank the outgoing screen while the model state is
 * already cleared. [key] keeps data updates (a follow, loaded notes) from
 * restarting the page animation.
 */
private sealed interface CommunityPage {
    val key: Any
    val rank: Int

    data object Feed : CommunityPage {
        override val key: Any = "feed"
        override val rank: Int = 0
    }

    data class Profile(val profile: CommunityProfileUiState) : CommunityPage {
        override val key: Any = "profile"
        override val rank: Int = 1
    }

    data class Note(val note: CommunityNote) : CommunityPage {
        override val key: Any = "note"
        override val rank: Int = 2
    }
}

@Composable
private fun CommunityFeedPage(model: AppModel, state: AppUiState) {
    val community = state.community
    val pageColor = pageSurfaceColor()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageColor)
            .testTag(CommunityScreenTag),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(
                title = stringResource(Res.string.community_title),
                actions = {
                    CommunityHeaderActions(model = model, state = state)
                },
            )
            CommunityFeedContent(model = model, state = state)
        }
        if (community.account != null) {
            FloatingActionButton(
                onClick = model.community::openComposer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 20.dp)
                    .testTag(CommunityComposeFabTag),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(Res.string.community_composer_title),
                )
            }
        }
    }
}

@Composable
private fun CommunityHeaderActions(
    model: AppModel,
    state: AppUiState,
    showRefresh: Boolean = true,
) {
    val community = state.community
    if (showRefresh) IconButton(onClick = model.community::refresh) {
        if (community.feed.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = stringResource(Res.string.community_refresh),
            )
        }
    }
    IconButton(onClick = model.community::openInBrowser) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = stringResource(Res.string.community_open_browser),
        )
    }
    val account = community.account
    if (account != null) {
        AccountAvatarButton(
            label = account.user.displayName,
            onClick = model.account::open,
            avatarUrl = account.user.avatarUrl,
            isCommunity = true,
        )
    } else {
        TextButton(
            onClick = model.community::beginLogin,
            modifier = Modifier.testTag(CommunityHeaderLoginTag),
        ) {
            Text(stringResource(Res.string.community_login_action))
        }
    }
}

@Composable
private fun CommunityFeedContent(model: AppModel, state: AppUiState) {
    val community = state.community
    val feed = community.feed
    val guestBlocked = community.account == null &&
        community.meta?.clientOptions?.showTimelineForVisitor == false

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            if (guestBlocked) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 760.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        CommunityLoginGate(model = model)
                    }
                }
                return@Column
            }
            // Header rows keep the 760 dp reading column. The list below spans
            // the whole window, so the wheel (and touch) scroll works over the
            // black margins beside the cards too.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) {
                    if (community.account == null) {
                        GuestBanner(model = model)
                        Spacer(Modifier.height(10.dp))
                    }
                    CommunityTabs(model = model, state = state)
                    Spacer(Modifier.height(8.dp))
                    if (feed.isLoading && feed.notes.isNotEmpty()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(CommunityFeedTag),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    feed.isLoading && feed.notes.isEmpty() -> items(3) {
                        CommunityFeedRow { CommunitySkeletonCard() }
                    }

                    feed.error != null && feed.notes.isEmpty() -> item {
                        CommunityFeedRow {
                            CommunityErrorCard(
                                failure = feed.error,
                                onRetry = model.community::refresh,
                            )
                        }
                    }

                    feed.notes.isEmpty() -> item {
                        CommunityFeedRow {
                            CommunityEmptyCard(isGuest = community.account == null)
                        }
                    }

                    else -> items(feed.notes, key = { it.id }) { note ->
                        CommunityFeedRow {
                            CommunityNoteCard(
                                model = model,
                                state = state,
                                note = note,
                            )
                        }
                    }
                }

                item {
                    CommunityFeedRow {
                        CommunityFeedFooter(
                            feed = feed,
                            onLoadMore = model.community::loadMore,
                            onRetry = model.community::refresh,
                        )
                    }
                }
            }
        }
    }
}

/** Centres one feed row in the 760 dp reading column. */
@Composable
private fun CommunityFeedRow(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun CommunityTabs(model: AppModel, state: AppUiState) {
    val community = state.community
    val options = buildList {
        if (community.meta?.policies?.ltlAvailable != false) add(CommunityTab.LOCAL)
        if (community.meta?.policies?.gtlAvailable != false) add(CommunityTab.GLOBAL)
        add(CommunityTab.FOLLOWING)
    }
    if (community.tab !in options) return
    SegmentedTabRail(
        options = options,
        selected = community.tab,
        onSelect = model.community::selectTab,
        label = { tab -> communityTabLabel(tab) },
    )
}

@Composable
private fun communityTabLabel(tab: CommunityTab): String = stringResource(
    when (tab) {
        CommunityTab.LOCAL -> Res.string.community_tab_local
        CommunityTab.GLOBAL -> Res.string.community_tab_global
        CommunityTab.FOLLOWING -> Res.string.community_tab_following
    },
)

@Composable
private fun GuestBanner(model: AppModel) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.community_guest_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(Res.string.community_guest_banner_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = model.community::beginLogin,
                modifier = Modifier.testTag(CommunityBannerLoginTag),
            ) {
                Text(stringResource(Res.string.community_login_action))
            }
        }
    }
}

@Composable
private fun CommunityLoginGate(model: AppModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Forum,
            contentDescription = null,
            modifier = Modifier.size(46.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.community_login_required),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(Res.string.community_login_required_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = model.community::beginLogin,
            modifier = Modifier.testTag(CommunityGateLoginTag),
        ) {
            Text(stringResource(Res.string.community_login_action))
        }
    }
}

@Composable
private fun CommunityFeedFooter(
    feed: com.fograin.launcher.app.CommunityFeed,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            feed.isLoadingMore -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(Res.string.community_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            feed.error != null && feed.notes.isNotEmpty() -> TextButton(onClick = onRetry) {
                Text(stringResource(Res.string.community_retry))
            }

            feed.endReached && feed.notes.isNotEmpty() -> Text(
                text = stringResource(Res.string.community_no_more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            feed.notes.isNotEmpty() -> {
                LaunchedEffect(feed.notes.size) { onLoadMore() }
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

@Composable
private fun CommunitySkeletonCard() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SkeletonBox(modifier = Modifier.size(40.dp), shape = MaterialTheme.shapes.large)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBox(modifier = Modifier.fillMaxWidth(0.42f).height(12.dp))
                SkeletonBox(modifier = Modifier.fillMaxWidth().height(12.dp))
                SkeletonBox(modifier = Modifier.fillMaxWidth(0.75f).height(12.dp))
            }
        }
    }
}

@Composable
private fun CommunityEmptyCard(isGuest: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(Res.string.community_empty_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    if (isGuest) {
                        Res.string.community_empty_guest
                    } else {
                        Res.string.community_empty_body
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CommunityErrorCard(failure: CommunityFailure, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.community_error_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = communityFailureText(failure),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(Res.string.community_retry))
            }
        }
    }
}

@Composable
private fun CommunityNoteDetail(model: AppModel, state: AppUiState, note: CommunityNote) {
    val community = state.community
    val pageColor = pageSurfaceColor()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageColor),
    ) {
        PageHeader(
            title = stringResource(Res.string.community_title),
            onBack = model.community::closeNote,
            actions = {
                CommunityHeaderActions(model = model, state = state, showRefresh = false)
            },
        )
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 760.dp)
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CommunityNoteCard(
                        model = model,
                        state = state,
                        note = note,
                        detail = true,
                    )
                }
                item {
                    Text(
                        text = "${stringResource(Res.string.community_reply)} ${note.repliesCount}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    HorizontalDivider()
                }
                if (community.detailLoading && community.replies.isEmpty()) {
                    item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                community.detailError?.let { failure ->
                    item {
                        CommunityErrorCard(
                            failure = failure,
                            onRetry = { model.community.openNote(note) },
                        )
                    }
                }
                items(community.replies, key = { it.id }) { reply ->
                    CommunityNoteCard(model = model, state = state, note = reply)
                }
            }
        }
    }
}

@Composable
fun communityFailureText(failure: CommunityFailure): String = when {
    failure.message == "login-timeout" -> stringResource(Res.string.community_error_auth)
    failure.code == "NO_SUCH_NOTE" -> stringResource(Res.string.community_error_not_found)
    failure.kind == CommunityFailureKind.AUTH -> stringResource(Res.string.community_error_auth)
    failure.kind == CommunityFailureKind.NETWORK -> stringResource(Res.string.community_error_network)
    failure.kind == CommunityFailureKind.RATE_LIMIT -> stringResource(Res.string.community_error_rate_limit)
    failure.kind == CommunityFailureKind.SERVER -> stringResource(Res.string.community_error_server)
    failure.kind == CommunityFailureKind.VALIDATION -> stringResource(Res.string.community_error_validation)
    else -> stringResource(Res.string.community_error_unknown)
}
