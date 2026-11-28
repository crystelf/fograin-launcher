package com.fograin.launcher.ui.community

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fograin.launcher.community.CommunityFailure
import com.fograin.launcher.community.CommunityUser
import com.fograin.launcher.community.formatCommunityTimestamp
import com.fograin.launcher.platform.platformServices
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.action_back
import com.fograin.launcher.resources.community_account_admin
import com.fograin.launcher.resources.community_account_moderator
import com.fograin.launcher.resources.community_open_browser
import com.fograin.launcher.resources.community_profile_avatar_image
import com.fograin.launcher.resources.community_profile_banner_image
import com.fograin.launcher.resources.community_profile_bio_empty
import com.fograin.launcher.resources.community_profile_birthday
import com.fograin.launcher.resources.community_profile_empty_notes
import com.fograin.launcher.resources.community_profile_follow
import com.fograin.launcher.resources.community_profile_follow_busy
import com.fograin.launcher.resources.community_profile_followers
import com.fograin.launcher.resources.community_profile_following
import com.fograin.launcher.resources.community_profile_follows_you
import com.fograin.launcher.resources.community_profile_joined
import com.fograin.launcher.resources.community_profile_location
import com.fograin.launcher.resources.community_login_action
import com.fograin.launcher.resources.community_profile_notes
import com.fograin.launcher.resources.community_profile_unfollow
import com.fograin.launcher.resources.community_retry
import com.fograin.launcher.resources.home_fallback
import com.fograin.launcher.ui.components.LocalPageHeaderTopInset
import com.fograin.launcher.ui.components.LocalWindowControlsInset
import com.fograin.launcher.ui.components.PageHeaderBackTag
import com.fograin.launcher.ui.components.PageHeaderMetrics
import com.fograin.launcher.ui.components.windowTitleDragArea
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

const val CommunityProfileAvatarTag: String = "community-profile-avatar"
const val CommunityProfileBannerTag: String = "community-profile-banner"
const val CommunityProfileTopBarTag: String = "community-profile-top-bar"
const val CommunityProfileTopBarIdentityTag: String = "community-profile-top-bar-identity"
const val CommunityFollowButtonTag: String = "community-follow-button"

/** Width of the profile column, including its 20 dp side padding. */
internal val ProfileContentMaxWidth: Dp = 760.dp

/** Keeps white identity text legible when a cover is bright around the middle. */
private val ProfileIdentityShadow = Shadow(
    color = Color.Black.copy(alpha = 0.42f),
    offset = Offset(0f, 1f),
    blurRadius = 8f,
)

/**
 * Fixed profile app bar drawn over the cover image.
 *
 * The controls are transparent; a black gradient over the cover provides the
 * contrast instead of opaque pills. Once the cover scrolls away the bar
 * settles into the normal page surface, the icons switch to the theme colour,
 * and the avatar plus display name slide into the bar as one compact row.
 */
@Composable
internal fun ProfileTopBar(
    user: CommunityUser,
    collapse: Float,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val platformName = remember { platformServices().platformName }
    val barHeight = PageHeaderMetrics.heightFor(platformName)
    val topInset = LocalPageHeaderTopInset.current
    val windowControlsInset = LocalWindowControlsInset.current
    val progress = collapse.coerceIn(0f, 1f)
    // The photo needs white icons; as the bar becomes a surface, move to the
    // theme's content colour early enough that the controls never sit on a
    // half-toned background.
    val tintMix = ((progress - 0.35f) / 0.30f).coerceIn(0f, 1f)
    val contentColor = lerp(Color.White, scheme.onSurface, tintMix)
    val compactIdentityVisible = progress > 0.2f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface.copy(alpha = progress))
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag(CommunityProfileTopBarTag),
    ) {
        Spacer(Modifier.height(topInset))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .padding(start = 20.dp, end = 20.dp + windowControlsInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfileTopIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(Res.string.action_back),
                tint = contentColor,
                onClick = onBack,
                modifier = Modifier.testTag(PageHeaderBackTag),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowTitleDragArea(),
                contentAlignment = Alignment.CenterStart,
            ) {
                ProfileCompactIdentity(
                    user = user,
                    visible = compactIdentityVisible,
                    contentColor = contentColor,
                )
            }
            ProfileTopIconButton(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = stringResource(Res.string.community_open_browser),
                tint = contentColor,
                onClick = onOpenBrowser,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outlineVariant.copy(alpha = progress * 0.55f)),
        )
    }
}

/**
 * The avatar + name that joins the app bar as the cover scrolls away. Keeping
 * this in its own composable also keeps the top bar's RowScope extension for
 * [AnimatedVisibility] out of the call site.
 */
@Composable
private fun ProfileCompactIdentity(
    user: CommunityUser,
    visible: Boolean,
    contentColor: Color,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) +
            slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut(tween(110)) +
            slideOutVertically(targetOffsetY = { it / 2 }),
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .testTag(CommunityProfileTopBarIdentityTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            CommunityAvatar(user = user, size = 28.dp)
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

@Composable
private fun ProfileTopIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Full-bleed cover with the identity row anchored to its bottom edge.
 *
 * The bundled launcher landscape is always painted underneath the remote
 * banner: a user without a cover, or a device that cannot load it yet, still
 * gets a deliberate image instead of an empty grey block. Tapping the cover or
 * the avatar opens the in-app image viewer.
 */
@Composable
internal fun ProfileHero(
    user: CommunityUser,
    height: Dp,
    isSelf: Boolean,
    isLoggedIn: Boolean,
    isLoading: Boolean,
    hasError: Boolean,
    followBusy: Boolean,
    onFollow: () -> Unit,
    onLogin: () -> Unit,
    onAvatarClick: () -> Unit,
    onBannerClick: () -> Unit,
) {
    val bannerUrl = user.bannerUrl?.takeIf { it.isNotBlank() }
    val avatarLabel = stringResource(Res.string.community_profile_avatar_image)
    val bannerLabel = stringResource(Res.string.community_profile_banner_image)
    val scheme = MaterialTheme.colorScheme
    val darkSurface = scheme.surface.luminance() < 0.5f
    val density = LocalDensity.current
    val topInset = LocalPageHeaderTopInset.current
    val windowControlsInset = LocalWindowControlsInset.current
    val statusBarHeight = with(density) {
        WindowInsets.statusBars.getTop(density).toDp()
    }
    val topScrimHeight = with(density) {
        (height * 0.52f).coerceIn(170.dp, 320.dp).toPx()
    }
    val imageModifier = if (bannerUrl != null) {
        Modifier
            .clickable(
                role = Role.Button,
                onClickLabel = bannerLabel,
                onClick = onBannerClick,
            )
            .semantics { contentDescription = bannerLabel }
    } else {
        Modifier
    }
    val avatarModifier = if (user.avatarUrl.isNotBlank()) {
        Modifier
            .clickable(
                role = Role.Button,
                onClickLabel = avatarLabel,
                onClick = onAvatarClick,
            )
            .semantics { contentDescription = avatarLabel }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .testTag(CommunityProfileBannerTag),
    ) {
        Box(modifier = Modifier.fillMaxSize().then(imageModifier)) {
            Image(
                painter = painterResource(Res.drawable.home_fallback),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (bannerUrl != null) {
                AsyncImage(
                    model = bannerUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The reference treatment: a strong black veil at the top so the
            // transparent controls and white identity text sit on a stable
            // dark base, no matter how bright the cover is.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.64f),
                            0.55f to Color.Black.copy(alpha = 0.28f),
                            1f to Color.Transparent,
                            endY = topScrimHeight,
                        ),
                    ),
            )
            // On Android, light-theme status icons are dark; give their strip
            // a light backing while the black veil sits below it. Desktop has
            // no status bar, so the image stays uninterrupted.
            if (!darkSurface && statusBarHeight > 0.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(statusBarHeight)
                        .background(scheme.surface.copy(alpha = 0.90f)),
                )
            }
            // Windows draws its caption controls in the theme's dark-on-light
            // colours; keep a light backing behind just that strip so they do
            // not disappear over the black veil in light theme. macOS traffic
            // lights are coloured and need no patch.
            if (!darkSurface && windowControlsInset > 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(windowControlsInset + 12.dp)
                        .height(PageHeaderMetrics.DesktopHeight + topInset)
                        .background(scheme.surface.copy(alpha = 0.86f)),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.34f to Color.Transparent,
                            0.50f to Color.Black.copy(alpha = 0.34f),
                            0.70f to Color.Black.copy(alpha = 0.78f),
                            1f to Color.Black.copy(alpha = 0.96f),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = ProfileContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .testTag(CommunityProfileAvatarTag)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .border(3.dp, Color.White.copy(alpha = 0.88f), CircleShape)
                        .then(avatarModifier)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CommunityAvatar(user = user, size = 80.dp)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = user.displayName,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                shadow = ProfileIdentityShadow,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false),
                        )
                        if (user.isBot) ProfileBadge("BOT")
                        if (user.isAdmin) {
                            ProfileBadge(stringResource(Res.string.community_account_admin))
                        } else if (user.isModerator) {
                            ProfileBadge(stringResource(Res.string.community_account_moderator))
                        }
                    }
                    Text(
                        text = user.handle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            shadow = ProfileIdentityShadow,
                        ),
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (user.isFollowed) {
                        Text(
                            text = stringResource(Res.string.community_profile_follows_you),
                            style = MaterialTheme.typography.labelMedium.copy(
                                shadow = ProfileIdentityShadow,
                            ),
                            color = Color.White.copy(alpha = 0.92f),
                        )
                    }
                }
                if (!isSelf) {
                    ProfileFollowButton(
                        isLoggedIn = isLoggedIn,
                        isLoading = isLoading,
                        hasError = hasError,
                        followBusy = followBusy,
                        isFollowing = user.isFollowing,
                        onFollow = onFollow,
                        onLogin = onLogin,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileFollowButton(
    isLoggedIn: Boolean,
    isLoading: Boolean,
    hasError: Boolean,
    followBusy: Boolean,
    isFollowing: Boolean,
    onFollow: () -> Unit,
    onLogin: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val neutralBorder = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f))
    val neutralColors = ButtonDefaults.outlinedButtonColors(
        containerColor = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        disabledContainerColor = scheme.surfaceContainerHigh,
        disabledContentColor = scheme.onSurfaceVariant,
    )
    if (!isLoggedIn) {
        Button(
            onClick = onLogin,
            modifier = Modifier.testTag(CommunityFollowButtonTag),
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Text(
                text = stringResource(Res.string.community_login_action),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        return
    }
    if (isLoading) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.testTag(CommunityFollowButtonTag),
            colors = neutralColors,
            border = neutralBorder,
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = scheme.onSurfaceVariant,
            )
        }
        return
    }
    if (hasError) return
    if (followBusy) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.testTag(CommunityFollowButtonTag),
            colors = neutralColors,
            border = neutralBorder,
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.community_profile_follow_busy),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    } else if (isFollowing) {
        OutlinedButton(
            onClick = onFollow,
            modifier = Modifier.testTag(CommunityFollowButtonTag),
            colors = neutralColors,
            border = neutralBorder,
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Text(
                text = stringResource(Res.string.community_profile_unfollow),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    } else {
        Button(
            onClick = onFollow,
            modifier = Modifier.testTag(CommunityFollowButtonTag),
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Text(
                text = stringResource(Res.string.community_profile_follow),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ProfileBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** Bio, custom fields and the account metadata, laid directly on the page. */
@Composable
internal fun ProfileDetails(user: CommunityUser) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val description = user.description?.takeIf { it.isNotBlank() }
        if (description != null) {
            CommunityRichText(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                text = stringResource(Res.string.community_profile_bio_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        user.fields.filter { it.name.isNotBlank() }.forEach { field ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = field.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 72.dp, max = 140.dp),
                )
                Text(
                    text = field.value,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val meta = buildList {
            user.location?.takeIf { it.isNotBlank() }?.let {
                add(ProfileMeta(Icons.Outlined.LocationOn, it))
            }
            user.birthday?.takeIf { it.isNotBlank() }?.let {
                add(ProfileMeta(Icons.Outlined.Cake, it))
            }
            user.createdAt?.takeIf { it.isNotBlank() }?.let { createdAt ->
                runCatching {
                    formatCommunityTimestamp(
                        kotlin.time.Instant.parse(createdAt).toEpochMilliseconds(),
                    ).substringBefore(' ')
                }.getOrNull()?.let {
                    add(
                        ProfileMeta(
                            Icons.Outlined.CalendarMonth,
                            stringResource(Res.string.community_profile_joined, it),
                        ),
                    )
                }
            }
        }
        if (meta.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                meta.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = row.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = row.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private data class ProfileMeta(
    val icon: ImageVector,
    val text: String,
)

@Composable
internal fun ProfileStats(user: CommunityUser) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileStat(
                value = user.notesCount,
                label = stringResource(Res.string.community_profile_notes),
                modifier = Modifier.weight(1f),
            )
            ProfileStatDivider()
            ProfileStat(
                value = user.followersCount,
                label = stringResource(Res.string.community_profile_followers),
                modifier = Modifier.weight(1f),
            )
            ProfileStatDivider()
            ProfileStat(
                value = user.followingCount,
                label = stringResource(Res.string.community_profile_following),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProfileStat(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(text = value.toString(), style = MaterialTheme.typography.titleLarge)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileStatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/** Section heading with the launcher's accent tick and the post count. */
@Composable
internal fun ProfilePostsHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = stringResource(Res.string.community_profile_notes),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.weight(1f))
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ProfileEmptyPosts() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(Res.string.community_profile_empty_notes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ProfileErrorCard(failure: CommunityFailure, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = communityFailureText(failure),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(Res.string.community_retry))
            }
        }
    }
}