package com.fograin.launcher.ui.modpack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.platform.CoreModpack
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.action_cancel
import com.fograin.launcher.resources.modpack_author_label
import com.fograin.launcher.resources.modpack_external_note
import com.fograin.launcher.resources.modpack_files
import com.fograin.launcher.resources.modpack_files_label
import com.fograin.launcher.resources.modpack_import_action
import com.fograin.launcher.resources.modpack_import_title
import com.fograin.launcher.resources.modpack_importing
import com.fograin.launcher.resources.modpack_loader
import com.fograin.launcher.resources.modpack_minecraft
import com.fograin.launcher.resources.modpack_name_label
import com.fograin.launcher.resources.modpack_readme_empty
import com.fograin.launcher.resources.modpack_readme_title
import com.fograin.launcher.resources.modpack_version_label
import com.fograin.launcher.ui.components.FograinSnackbarHost
import com.fograin.launcher.ui.components.KeyValueRow
import com.fograin.launcher.ui.components.PageHeader
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource

const val ModpackImportPageTag: String = "modpack-import-page"
const val ModpackImportConfirmTag: String = "modpack-import-confirm"

/**
 * Full-window import confirmation.
 *
 * Dropping a pack or picking "导入整合包" no longer installs on the spot: the
 * inspected pack opens here with its name, author, version, loader and README,
 * and the install button sits at the bottom with Cancel. The same page is
 * reused for archives and MultiMC folders.
 */
@Composable
fun ModpackImportPage(model: AppModel, state: AppUiState) {
    val pack = state.modpack.pack ?: return
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.message)
        model.consumeNotice(notice.id)
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            PageHeader(
                title = stringResource(Res.string.modpack_import_title),
                onBack = model::dismissModpackImport,
                actions = {
                    Text(
                        text = kindLabel(pack),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ModpackImportPageTag)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 820.dp)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PackHeader(pack)
                        PackInfo(pack)
                        OutlinedTextField(
                            value = state.modpack.name,
                            onValueChange = model::setModpackName,
                            label = { Text(stringResource(Res.string.modpack_name_label)) },
                            singleLine = true,
                            enabled = !state.modpack.isImporting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ReadmeSection(pack)
                        if (pack.external) {
                            Text(
                                text = stringResource(Res.string.modpack_external_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.modpack.error != null) {
                            Text(
                                text = state.modpack.error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                FograinSnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(18.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = model::dismissModpackImport,
                    enabled = !state.modpack.isImporting,
                ) {
                    Text(stringResource(Res.string.action_cancel))
                }
                Button(
                    onClick = model::confirmModpackImport,
                    enabled = state.modpack.canImport,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag(ModpackImportConfirmTag),
                ) {
                    if (state.modpack.isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(Res.string.modpack_importing))
                    } else {
                        Text(stringResource(Res.string.modpack_import_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun PackHeader(pack: CoreModpack) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = pack.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!pack.summary.isNullOrBlank()) {
                Text(
                    text = pack.summary.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PackInfo(pack: CoreModpack) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            KeyValueRow(
                label = stringResource(Res.string.modpack_author_label),
                value = pack.author?.takeIf { it.isNotBlank() } ?: "—",
            )
            KeyValueRow(
                label = stringResource(Res.string.modpack_version_label),
                value = pack.version.ifBlank { "—" },
            )
            KeyValueRow(
                label = stringResource(Res.string.modpack_minecraft),
                value = pack.minecraft.ifBlank { "—" },
            )
            KeyValueRow(
                label = stringResource(Res.string.modpack_loader),
                value = loaderLabel(pack),
            )
            KeyValueRow(
                label = stringResource(Res.string.modpack_files_label),
                value = stringResource(Res.string.modpack_files, pack.fileCount),
            )
        }
    }
}

@Composable
private fun ReadmeSection(pack: CoreModpack) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.modpack_readme_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val readme = pack.readme?.trim()
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (readme.isNullOrEmpty()) {
                Text(
                    text = stringResource(Res.string.modpack_readme_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                ) {
                    Text(
                        text = readme,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
