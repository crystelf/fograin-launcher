package com.fograin.launcher.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.DropdownField
import com.fograin.launcher.ui.components.MilestoneChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Install dialog: the only place that asks for loader / loader version /
 * instance name. Memory and display options belong to 游戏设置 after install.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameInstallSheet(
    model: AppModel,
    state: AppUiState,
    initialVersionId: String,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectedVersionId by remember(initialVersionId) {
        mutableStateOf(initialVersionId.ifEmpty { state.download.selectedVersionId.orEmpty() })
    }
    var loader by remember { mutableStateOf(GameLoaderKind.VANILLA) }
    var loaderVersion by remember { mutableStateOf("") }
    var instanceName by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val versionOptions = remember(state.download.versions, selectedVersionId) {
        val ids = state.download.versions.map { it.id }.toMutableList()
        if (selectedVersionId.isNotEmpty() && selectedVersionId !in ids) {
            ids.add(0, selectedVersionId)
        }
        ids
    }
    val canInstall = loader.isAvailable && selectedVersionId.isNotEmpty() && !isInstalling

    ModalBottomSheet(
        onDismissRequest = model::closeInstallSheet,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.install_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(Res.string.install_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = model::closeInstallSheet) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.action_close),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(Res.string.install_game_version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownField(
                    value = selectedVersionId.ifEmpty {
                        stringResource(Res.string.install_choose_version)
                    },
                    options = versionOptions.map { id ->
                        id to { selectedVersionId = id }
                    },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.install_loader),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.install_loader_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GameLoaderKind.entries.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { option ->
                            LoaderCard(
                                option = option,
                                selected = option == loader,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    loader = option
                                    loaderVersion = option.placeholderVersions.firstOrNull().orEmpty()
                                    errorMessage = null
                                },
                            )
                        }
                        if (row.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (!loader.isAvailable) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(Res.string.install_loader_versions, loader.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DropdownField(
                        value = loaderVersion.ifEmpty {
                            stringResource(Res.string.install_choose_version)
                        },
                        options = loader.placeholderVersions.map { version ->
                            version to { loaderVersion = version }
                        },
                    )
                    Text(
                        text = stringResource(Res.string.install_loader_pending, loader.title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(Res.string.install_instance_name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = instanceName,
                    onValueChange = { instanceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            selectedVersionId.ifEmpty {
                                stringResource(Res.string.install_instance_name)
                            },
                        )
                    },
                    singleLine = true,
                )
                Text(
                    text = stringResource(Res.string.install_instance_name_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            errorMessage?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            RoundedCornerShape(10.dp),
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (loader.isAvailable) {
                        stringResource(Res.string.install_summary, selectedVersionId)
                    } else {
                        stringResource(Res.string.install_loader_unavailable, loader.title)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = model::closeInstallSheet) {
                    Text(stringResource(Res.string.action_cancel))
                }
                Button(
                    onClick = {
                        if (!canInstall) return@Button
                        isInstalling = true
                        errorMessage = null
                        scope.launch {
                            try {
                                model.startGameInstall(selectedVersionId, instanceName)
                                model.closeInstallSheet()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                errorMessage = model.errorMessage(error)
                            } finally {
                                isInstalling = false
                            }
                        }
                    },
                    enabled = canInstall,
                ) {
                    if (isInstalling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(Res.string.action_install))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoaderCard(
    option: GameLoaderKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        modifier = modifier
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                RoundedCornerShape(12.dp),
            )
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = loaderIcon(option),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                option.milestone?.let { MilestoneChip(it) }
            }
            Text(
                text = option.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun loaderIcon(kind: GameLoaderKind): ImageVector = when (kind) {
    GameLoaderKind.VANILLA -> Icons.Outlined.ViewInAr
    GameLoaderKind.FABRIC, GameLoaderKind.FABRIC_API -> Icons.Outlined.Extension
    GameLoaderKind.QUILT -> Icons.Outlined.GridView
    GameLoaderKind.FORGE -> Icons.Outlined.Build
    GameLoaderKind.NEOFORGE -> Icons.Outlined.LocalFireDepartment
    GameLoaderKind.OPTIFINE -> Icons.Outlined.AutoAwesome
    GameLoaderKind.SQL_API -> Icons.Outlined.Storage
}
