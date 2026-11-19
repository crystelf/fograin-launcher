package com.fograin.launcher.ui.modpack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.ModpackUiState
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.CoreModpack
import com.fograin.launcher.platform.CoreModpackExport
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.KeyValueRow
import org.jetbrains.compose.resources.stringResource

const val ModpackImportSheetTag: String = "modpack-import-sheet"
const val ModpackImportConfirmTag: String = "modpack-import-confirm"

/**
 * Confirmation sheet for a modpack import (M9).
 *
 * It reads like the install page summary: what the pack is, which game and
 * loader it targets, and how much it will download. The only editable value is
 * the new instance name.
 */
@Composable
fun ModpackImportSheet(
    pack: CoreModpack,
    state: ModpackUiState,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ModpackImportSheetTag)
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(Res.string.modpack_import_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = kindLabel(pack),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(Res.string.modpack_import_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pack.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (pack.version.isNotBlank()) {
                        Text(
                            text = pack.version,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                KeyValueRow(
                    label = stringResource(Res.string.modpack_minecraft),
                    value = pack.minecraft.ifBlank { "—" },
                )
                KeyValueRow(
                    label = stringResource(Res.string.modpack_loader),
                    value = loaderLabel(pack),
                )
                val filesText = stringResource(Res.string.modpack_files, pack.fileCount)
                KeyValueRow(
                    label = stringResource(Res.string.modpack_files_label),
                    value = buildString {
                        append(filesText)
                        if (pack.totalBytes > 0) {
                            append(" · ")
                            append(InstanceFormat.byteSize(pack.totalBytes))
                        }
                    },
                )
                if (pack.skipped > 0) {
                    KeyValueRow(
                        label = stringResource(Res.string.modpack_overrides),
                        value = "${pack.skipped}",
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(Res.string.modpack_name_label)) },
            singleLine = true,
            enabled = !state.isImporting,
            modifier = Modifier.fillMaxWidth(),
        )

        if (pack.external) {
            Text(
                text = stringResource(Res.string.modpack_external_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.error != null) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isImporting,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
            androidx.compose.material3.Button(
                onClick = onImport,
                enabled = state.canImport,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ModpackImportConfirmTag),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (state.isImporting) {
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
        Spacer(Modifier.height(2.dp))
    }
}

/** Result dialog for `modpack.export`. */
@Composable
fun ModpackExportDialog(
    result: CoreModpackExport,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.modpack_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        Res.string.modpack_export_body,
                        result.path,
                        result.files,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = result.path,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(Res.string.modpack_copy_path))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.modpack_done))
            }
        },
    )
}

@Composable
private fun kindLabel(pack: CoreModpack): String = when (pack.kind) {
    "curseForge" -> "CurseForge"
    "hmcl" -> "HMCL"
    "multiMc" -> "MultiMC / Prism"
    else -> "Modrinth"
}

@Composable
private fun loaderLabel(pack: CoreModpack): String = when (pack.loaderKind.lowercase()) {
    "fabric" -> "Fabric ${pack.loaderVersion}".trim()
    "quilt" -> "Quilt ${pack.loaderVersion}".trim()
    "forge" -> "Forge ${pack.loaderVersion}".trim()
    "neoforge" -> "NeoForge ${pack.loaderVersion}".trim()
    else -> "原版"
}