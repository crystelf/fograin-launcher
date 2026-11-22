package com.fograin.launcher.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AccountStage
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.platform.CoreAccount
import com.fograin.launcher.resources.*
import org.jetbrains.compose.resources.stringResource

const val AccountSheetTag: String = "account-sheet"
const val AccountOfflineNameTag: String = "account-offline-name"
const val AccountOfflineSubmitTag: String = "account-offline-submit"
const val AccountServerTag: String = "account-server"
const val AccountUsernameTag: String = "account-username"
const val AccountPasswordTag: String = "account-password"
const val AccountLoginTag: String = "account-login"
const val AccountAddOfflineTag: String = "account-add-offline"
const val AccountAddYggdrasilTag: String = "account-add-yggdrasil"

fun accountRowTag(id: String): String = "account-row-$id"

fun accountProfileTag(id: String): String = "account-profile-$id"

/**
 * Account panel: offline and third-party identities only. There is no
 * skin section by design; textures are provided by the third-party server and
 * authlib-injector at game time.
 */
@Composable
fun AccountSheet(model: AppModel, state: AppUiState) {
    val account = state.account
    var deleteTarget by remember { mutableStateOf<CoreAccount?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            .testTag(AccountSheetTag),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AccountHeader(
            stage = account.stage,
            onBack = model::back,
            onClose = model.account::close,
        )

        if (account.isLoading && account.accounts.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        account.error?.let { message ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    Column {
                        Text(
                            text = stringResource(Res.string.account_error_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(text = message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        when (account.stage) {
            AccountStage.LIST -> AccountListStage(
                model = model,
                state = state,
                onDelete = { deleteTarget = it },
            )

            AccountStage.OFFLINE -> OfflineAccountStage(model = model, state = state)
            AccountStage.YGGDRASIL -> YggdrasilAccountStage(model = model, state = state)
            AccountStage.PROFILES -> ProfileStage(model = model, state = state)
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(Res.string.account_delete_title)) },
            text = {
                Text(stringResource(Res.string.account_delete_body, target.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.account.remove(target.id)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AccountHeader(stage: AccountStage, onBack: () -> Unit, onClose: () -> Unit) {
    val title = when (stage) {
        AccountStage.LIST -> stringResource(Res.string.account_title)
        AccountStage.OFFLINE -> stringResource(Res.string.account_offline_title)
        AccountStage.YGGDRASIL -> stringResource(Res.string.account_yggdrasil_title)
        AccountStage.PROFILES -> stringResource(Res.string.account_profile_title)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (stage == AccountStage.LIST) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.account_back),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(Res.string.account_close),
            )
        }
    }
}

@Composable
private fun AccountListStage(
    model: AppModel,
    state: AppUiState,
    onDelete: (CoreAccount) -> Unit,
) {
    val account = state.account
    Text(
        text = stringResource(Res.string.account_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (account.accounts.isEmpty() && !account.isLoading) {
        Text(
            text = stringResource(Res.string.account_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    account.accounts.forEach { row ->
        AccountRow(model = model, account = row, onDelete = { onDelete(row) })
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = model.account::showOfflineForm,
            modifier = Modifier
                .weight(1f)
                .testTag(AccountAddOfflineTag),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(Res.string.account_add_offline))
        }
        OutlinedButton(
            onClick = model.account::showYggdrasilForm,
            modifier = Modifier
                .weight(1f)
                .testTag(AccountAddYggdrasilTag),
        ) {
            Text(stringResource(Res.string.account_add_yggdrasil))
        }
    }

    MicrosoftLoginCard()
}

@Composable
private fun AccountRow(model: AppModel, account: CoreAccount, onDelete: () -> Unit) {
    val selected = account.selected
    Surface(
        onClick = { model.account.select(account.id) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(accountRowTag(account.id)),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccountAvatar(
                initial = account.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                selected = selected,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = accountSubtitle(account),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(Res.string.account_selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = stringResource(Res.string.account_use),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (account.kind == "yggdrasil") {
                IconButton(onClick = { model.account.refreshAccount(account.id) }) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(Res.string.account_refresh_login),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.account_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OfflineAccountStage(model: AppModel, state: AppUiState) {
    var name by remember { mutableStateOf("") }
    val submitting = state.account.isSubmitting

    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            model.account.clearError()
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountOfflineNameTag),
        label = { Text(stringResource(Res.string.account_name_label)) },
        supportingText = { Text(stringResource(Res.string.account_name_support)) },
        singleLine = true,
        enabled = !submitting,
    )
    Button(
        onClick = { model.account.addOffline(name) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountOfflineSubmitTag),
        enabled = !submitting && name.trim().isNotEmpty(),
    ) {
        if (submitting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(Res.string.account_create_and_use))
        }
    }
}

@Composable
private fun YggdrasilAccountStage(model: AppModel, state: AppUiState) {
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val submitting = state.account.isSubmitting

    Text(
        text = stringResource(Res.string.account_yggdrasil_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.account_presets),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AssistChip(
            onClick = {
                server = "https://littleskin.cn/api/yggdrasil"
                model.account.clearError()
            },
            label = { Text(stringResource(Res.string.account_preset_littleskin)) },
        )
    }
    OutlinedTextField(
        value = server,
        onValueChange = {
            server = it
            model.account.clearError()
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountServerTag),
        label = { Text(stringResource(Res.string.account_server_label)) },
        placeholder = { Text("https://littleskin.cn/api/yggdrasil") },
        supportingText = { Text(stringResource(Res.string.account_server_support)) },
        singleLine = true,
        enabled = !submitting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = username,
        onValueChange = {
            username = it
            model.account.clearError()
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountUsernameTag),
        label = { Text(stringResource(Res.string.account_username_label)) },
        singleLine = true,
        enabled = !submitting,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = password,
        onValueChange = {
            password = it
            model.account.clearError()
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountPasswordTag),
        label = { Text(stringResource(Res.string.account_password_label)) },
        singleLine = true,
        enabled = !submitting,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
    )
    Button(
        onClick = { model.account.loginYggdrasil(server, username, password) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountLoginTag),
        enabled = !submitting && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
    ) {
        if (submitting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(Res.string.account_login_working))
        } else {
            Text(stringResource(Res.string.account_login_action))
        }
    }
    Text(
        text = stringResource(Res.string.account_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProfileStage(model: AppModel, state: AppUiState) {
    val account = state.account
    Text(
        text = stringResource(
            Res.string.account_profile_body,
            account.pendingServerName.ifBlank { stringResource(Res.string.account_kind_yggdrasil) },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    account.pendingProfiles.forEach { profile ->
        Surface(
            onClick = { model.account.selectYggdrasilProfile(profile.id) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(accountProfileTag(profile.id)),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccountAvatar(
                    initial = profile.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    selected = false,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = profile.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = profile.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(Res.string.account_profile_action),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun MicrosoftLoginCard() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.account_microsoft_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(Res.string.account_microsoft_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AccountAvatar(initial: String, selected: Boolean) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                },
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            },
        )
    }
}

@Composable
private fun accountSubtitle(account: CoreAccount): String = when (account.kind) {
    "yggdrasil" -> stringResource(
        Res.string.account_kind_server_format,
        account.serverName ?: account.server ?: stringResource(Res.string.account_kind_yggdrasil),
    )

    "microsoft" -> stringResource(Res.string.account_kind_microsoft)
    else -> stringResource(Res.string.account_kind_offline)
}
