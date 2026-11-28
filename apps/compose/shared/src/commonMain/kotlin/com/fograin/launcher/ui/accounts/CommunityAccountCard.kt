package com.fograin.launcher.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.CommunitySessionState
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_account_admin
import com.fograin.launcher.resources.community_account_expired
import com.fograin.launcher.resources.community_account_login
import com.fograin.launcher.resources.community_account_logged_out
import com.fograin.launcher.resources.community_account_logout
import com.fograin.launcher.resources.community_account_moderator
import com.fograin.launcher.resources.community_account_not_logged_in
import com.fograin.launcher.resources.community_account_offline
import com.fograin.launcher.ui.community.CommunityAvatar
import org.jetbrains.compose.resources.stringResource

const val AccountCommunityCardTag: String = "account-community-card"
const val AccountCommunityLoginTag: String = "account-community-login"
const val AccountCommunityLogoutTag: String = "account-community-logout"

/**
 * The Crystelf Lab identity inside the account sheet.
 *
 * This is intentionally a plain row, not a highlighted card: the community
 * account and Minecraft accounts are both identities in the same list. The
 * surrounding section label + divider provide the grouping.
 */
@Composable
fun CommunityAccountCard(model: AppModel, state: AppUiState) {
    val community = state.community
    val account = community.account
    val expired = community.sessionState == CommunitySessionState.EXPIRED
    val offline = community.sessionState == CommunitySessionState.OFFLINE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountCommunityCardTag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (account == null) {
            CommunityAvatar(user = null, size = 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.community_account_not_logged_in),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(Res.string.community_account_logged_out),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = model.community::beginLogin,
                modifier = Modifier.testTag(AccountCommunityLoginTag),
            ) {
                Text(stringResource(Res.string.community_account_login))
            }
        } else {
            CommunityAvatar(user = account.user, size = 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = account.user.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when {
                        account.user.isAdmin -> AccountRoleLabel(
                            stringResource(Res.string.community_account_admin),
                        )
                        account.user.isModerator -> AccountRoleLabel(
                            stringResource(Res.string.community_account_moderator),
                        )
                    }
                }
                Text(
                    text = when {
                        expired -> stringResource(Res.string.community_account_expired)
                        offline -> stringResource(Res.string.community_account_offline)
                        else -> account.user.handle
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (expired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expired) {
                TextButton(
                    onClick = model.community::beginLogin,
                    modifier = Modifier.testTag(AccountCommunityLoginTag),
                ) {
                    Text(stringResource(Res.string.community_account_login))
                }
            } else {
                IconButton(
                    onClick = model.community::logout,
                    modifier = Modifier.testTag(AccountCommunityLogoutTag),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = stringResource(Res.string.community_account_logout),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountRoleLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

