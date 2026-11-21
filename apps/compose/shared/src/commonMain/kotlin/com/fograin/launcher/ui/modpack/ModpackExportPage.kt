package com.fograin.launcher.ui.modpack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.ExportStep
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.CoreExportSection
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.action_cancel
import com.fograin.launcher.resources.modpack_export_action
import com.fograin.launcher.resources.modpack_export_author
import com.fograin.launcher.resources.modpack_export_back
import com.fograin.launcher.resources.modpack_export_content_body
import com.fograin.launcher.resources.modpack_export_content_title
import com.fograin.launcher.resources.modpack_export_continue
import com.fograin.launcher.resources.modpack_export_description
import com.fograin.launcher.resources.modpack_export_description_hint
import com.fograin.launcher.resources.modpack_export_empty
import com.fograin.launcher.resources.modpack_export_files
import com.fograin.launcher.resources.modpack_export_loading
import com.fograin.launcher.resources.modpack_export_name
import com.fograin.launcher.resources.modpack_export_section_configs
import com.fograin.launcher.resources.modpack_export_section_game_settings
import com.fograin.launcher.resources.modpack_export_section_mods
import com.fograin.launcher.resources.modpack_export_section_resourcepacks
import com.fograin.launcher.resources.modpack_export_section_servers
import com.fograin.launcher.resources.modpack_export_section_shaderpacks
import com.fograin.launcher.resources.modpack_export_subtitle
import com.fograin.launcher.resources.modpack_export_summary
import com.fograin.launcher.resources.modpack_export_version
import org.jetbrains.compose.resources.stringResource

const val ModpackExportPageTag: String = "modpack-export-page"
const val ModpackExportContinueTag: String = "modpack-export-continue"
const val ModpackExportConfirmTag: String = "modpack-export-confirm"

/**
 * Two-step `.mrpack` export (M10 UI pass).
 *
 * Step one collects the Modrinth metadata (name defaults to the instance,
 * author to the signed-in account, version 1.0). Step two lists every exportable
 * section with the recommended defaults; only then does the save dialog open.
 */
@Composable
fun ModpackExportPage(model: AppModel, state: AppUiState, modifier: Modifier = Modifier) {
    val export = state.modpackExport
    Box(modifier = modifier.fillMaxSize().testTag(ModpackExportPageTag)) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 820.dp)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (export.step == ExportStep.FORM) {
                            ExportForm(export, model)
                        } else {
                            ExportContent(export, model)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            ExportFooter(export, model)
        }
    }
}

@Composable
private fun ExportForm(
    export: com.fograin.launcher.app.ExportModpackUiState,
    model: AppModel,
) {
    Text(
        text = stringResource(Res.string.modpack_export_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(Res.string.modpack_export_summary),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
    OutlinedTextField(
        value = export.name,
        onValueChange = model::setModpackExportName,
        label = { Text(stringResource(Res.string.modpack_export_name)) },
        singleLine = true,
        enabled = !export.isExporting,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = export.author,
        onValueChange = model::setModpackExportAuthor,
        label = { Text(stringResource(Res.string.modpack_export_author)) },
        singleLine = true,
        enabled = !export.isExporting,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = export.version,
        onValueChange = model::setModpackExportVersion,
        label = { Text(stringResource(Res.string.modpack_export_version)) },
        singleLine = true,
        enabled = !export.isExporting,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = export.description,
        onValueChange = model::setModpackExportDescription,
        label = { Text(stringResource(Res.string.modpack_export_description)) },
        supportingText = {
            Text(stringResource(Res.string.modpack_export_description_hint))
        },
        minLines = 5,
        maxLines = 10,
        enabled = !export.isExporting,
        modifier = Modifier.fillMaxWidth(),
    )
    if (export.error != null) {
        Text(
            text = export.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ExportContent(
    export: com.fograin.launcher.app.ExportModpackUiState,
    model: AppModel,
) {
    Text(
        text = stringResource(Res.string.modpack_export_content_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(Res.string.modpack_export_content_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (export.isLoadingPlan) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(Res.string.modpack_export_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (export.sections.isEmpty()) {
                Text(
                    text = stringResource(Res.string.modpack_export_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                export.sections.forEachIndexed { index, section ->
                    ExportSectionRow(
                        section = section,
                        checked = section.id in export.selected,
                        enabled = section.files > 0 && !export.isExporting,
                        onToggle = { model.toggleModpackExportSection(section.id) },
                    )
                    if (index != export.sections.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
    if (export.error != null) {
        Text(
            text = export.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ExportSectionRow(
    section: CoreExportSection,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = exportSectionTitle(section),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    Res.string.modpack_export_files,
                    section.files,
                    InstanceFormat.byteSize(section.bytes),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
        )
    }
}

@Composable
private fun exportSectionTitle(section: CoreExportSection): String = when (section.id) {
    "servers" -> stringResource(Res.string.modpack_export_section_servers)
    "configs" -> stringResource(Res.string.modpack_export_section_configs)
    "mods" -> stringResource(Res.string.modpack_export_section_mods)
    "shaderpacks" -> stringResource(Res.string.modpack_export_section_shaderpacks)
    "resourcepacks" -> stringResource(Res.string.modpack_export_section_resourcepacks)
    "gameSettings" -> stringResource(Res.string.modpack_export_section_game_settings)
    else -> section.label
}

@Composable
private fun ExportFooter(
    export: com.fograin.launcher.app.ExportModpackUiState,
    model: AppModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        if (export.step == ExportStep.FORM) {
            TextButton(onClick = model::cancelModpackExport, enabled = !export.isExporting) {
                Text(stringResource(Res.string.action_cancel))
            }
            Button(
                onClick = model::nextModpackExportStep,
                enabled = export.canContinue,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag(ModpackExportContinueTag),
            ) {
                Text(stringResource(Res.string.modpack_export_continue))
            }
        } else {
            TextButton(
                onClick = model::backModpackExportStep,
                enabled = !export.isExporting,
            ) {
                Text(stringResource(Res.string.modpack_export_back))
            }
            Button(
                onClick = model::confirmModpackExport,
                enabled = export.canExport,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag(ModpackExportConfirmTag),
            ) {
                if (export.isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(stringResource(Res.string.modpack_export_action))
            }
        }
    }
}
