package com.fograin.launcher.ui.modpack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.platform.CoreModpack
import com.fograin.launcher.platform.CoreModpackExport
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.library_scope_vanilla
import com.fograin.launcher.resources.modpack_copy_path
import com.fograin.launcher.resources.modpack_done
import com.fograin.launcher.resources.modpack_export_body
import com.fograin.launcher.resources.modpack_export_title
import org.jetbrains.compose.resources.stringResource

/** Result dialog for `modpack.export`; the wizard owns the page behind it. */
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
                        maxLines = 3,
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
internal fun kindLabel(pack: CoreModpack): String = when (pack.kind) {
    "curseForge" -> "CurseForge"
    "hmcl" -> "HMCL"
    "multiMc" -> "MultiMC / Prism"
    else -> "Modrinth"
}

@Composable
internal fun loaderLabel(pack: CoreModpack): String = when (pack.loaderKind.lowercase()) {
    "fabric" -> "Fabric ${pack.loaderVersion}".trim()
    "quilt" -> "Quilt ${pack.loaderVersion}".trim()
    "forge" -> "Forge ${pack.loaderVersion}".trim()
    "neoforge" -> "NeoForge ${pack.loaderVersion}".trim()
    else -> stringResource(Res.string.library_scope_vanilla)
}
