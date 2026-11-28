package com.fograin.launcher.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.community_login_body
import com.fograin.launcher.resources.community_login_cancel
import com.fograin.launcher.resources.community_login_close
import com.fograin.launcher.resources.community_login_copied
import com.fograin.launcher.resources.community_login_copy
import com.fograin.launcher.resources.community_login_error
import com.fograin.launcher.resources.community_login_open
import com.fograin.launcher.resources.community_login_permissions
import com.fograin.launcher.resources.community_login_timeout
import com.fograin.launcher.resources.community_login_title
import com.fograin.launcher.resources.community_login_waiting
import org.jetbrains.compose.resources.stringResource

const val CommunityLoginDialogTag: String = "community-login-dialog"
const val CommunityLoginOpenTag: String = "community-login-open"
const val CommunityLoginCopyTag: String = "community-login-copy"

@Composable
fun CommunityLoginDialog(model: AppModel, state: AppUiState) {
    val login = state.community.login
    if (!login.open) return
    Dialog(
        onDismissRequest = model.community::cancelLogin,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 500.dp)
                    .testTag(CommunityLoginDialogTag),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Forum,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Text(
                            text = stringResource(Res.string.community_login_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        )
                        IconButton(onClick = model.community::cancelLogin) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(Res.string.community_login_close),
                            )
                        }
                    }
                    Text(
                        text = stringResource(Res.string.community_login_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(Res.string.community_login_permissions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    if (login.waiting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(Res.string.community_login_waiting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    login.error?.let { failure ->
                        Text(
                            text = when (failure.message) {
                                "login-timeout" -> stringResource(Res.string.community_login_timeout)
                                else -> communityFailureText(failure)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (login.copied) {
                        Text(
                            text = stringResource(Res.string.community_login_copied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = model.community::openLoginInBrowser,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(CommunityLoginOpenTag),
                        ) {
                            Text(stringResource(Res.string.community_login_open))
                        }
                        OutlinedButton(
                            onClick = model.community::copyLoginLink,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(CommunityLoginCopyTag),
                        ) {
                            Text(stringResource(Res.string.community_login_copy))
                        }
                    }
                    TextButton(
                        onClick = model.community::cancelLogin,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(Res.string.community_login_cancel))
                    }
                }
            }
        }
    }
}
