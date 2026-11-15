package com.fograin.launcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.JavaUiState
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.CoreJavaCatalogEntry
import com.fograin.launcher.platform.CoreJavaRuntime
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.components.ThinProgressBar
import org.jetbrains.compose.resources.stringResource

const val JavaScreenTag: String = "java-screen"
const val JavaAutoDownloadTag: String = "java-auto-download"

fun javaRuntimeTag(id: String): String = "java-runtime-$id"
fun javaCatalogTag(major: Int): String = "java-catalog-$major"
fun javaInstallTag(major: Int): String = "java-install-$major"

/**
 * Settings · Java runtime management (M6).
 *
 * One page for the full lifecycle: installed runtimes (system + managed), the
 * auto-download policy, and the official catalog with per-major download
 * buttons. Progress is read from the shared task rows, so a download started
 * here keeps reporting even if the user navigates away and back.
 */
@Composable
fun JavaRuntimeScreen(model: AppModel, state: AppUiState) {
    val java = state.java
    var deleteTarget by remember { mutableStateOf<CoreJavaRuntime?>(null) }

    LaunchedEffect(Unit) {
        if (java.runtimes.isEmpty() && java.catalog.isEmpty() && !java.isLoading) {
            model.refreshJavaSettings()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(JavaScreenTag),
    ) {
        PageHeader(
            title = stringResource(Res.string.java_title),
            onBack = model::closeJavaSettings,
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                JavaCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Res.string.java_auto_download),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(Res.string.java_auto_download_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = java.autoDownload,
                            onCheckedChange = model::setJavaAutoDownload,
                            modifier = Modifier.testTag(JavaAutoDownloadTag),
                        )
                    }
                }
            }

            if (!java.supported) {
                item {
                    JavaCard {
                        Text(
                            text = stringResource(Res.string.java_unsupported),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionTitle(
                    title = stringResource(Res.string.java_installed_title),
                    trailing = stringResource(
                        Res.string.java_installed_count,
                        java.runtimes.size,
                    ),
                )
            }
            if (java.error != null) {
                item {
                    JavaCard {
                        Text(
                            text = stringResource(Res.string.java_load_error, java.error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = model::refreshJavaSettings) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(Res.string.java_retry))
                        }
                    }
                }
            } else if (java.isLoading && java.runtimes.isEmpty()) {
                item {
                    JavaCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(Res.string.java_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (java.runtimes.isEmpty()) {
                item {
                    JavaCard {
                        Text(
                            text = stringResource(Res.string.java_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(java.runtimes.size) { index ->
                    val runtime = java.runtimes[index]
                    JavaRuntimeCard(
                        runtime = runtime,
                        onDelete = if (runtime.managed && runtime.id != null) {
                            { deleteTarget = runtime }
                        } else {
                            null
                        },
                    )
                }
            }

            if (java.supported) {
                item {
                    SectionTitle(
                        title = stringResource(Res.string.java_available_title),
                        trailing = if (java.isLoading) {
                            stringResource(Res.string.java_loading)
                        } else {
                            null
                        },
                    )
                }
                if (java.catalogError != null) {
                    item {
                        JavaCard {
                            Text(
                                text = stringResource(
                                    Res.string.java_catalog_error,
                                    java.catalogError,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = model::refreshJavaSettings) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(Res.string.java_retry))
                            }
                        }
                    }
                } else if (java.isLoading) {
                    item {
                        JavaCard {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(Res.string.java_loading),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else if (java.catalog.isEmpty()) {
                    item {
                        JavaCard {
                            Text(
                                text = stringResource(Res.string.java_empty_catalog),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(java.catalog.size) { index ->
                        val entry = java.catalog[index]
                        JavaCatalogCard(
                            entry = entry,
                            installing = java.installingMajor == entry.major,
                            busy = java.installTaskId != null,
                            fraction = java.installFraction(state.downloads),
                            onInstall = { model.installJavaRuntime(entry.major) },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(Res.string.java_delete_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.java_delete_body,
                        "Java ${target.version}",
                        target.arch,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        deleteTarget = null
                        if (id != null) model.removeJavaRuntime(id)
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
private fun JavaCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JavaRuntimeCard(runtime: CoreJavaRuntime, onDelete: (() -> Unit)?) {
    val id = runtime.id ?: runtime.home
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(javaRuntimeTag(id)),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp).size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            Res.string.java_runtime_version,
                            runtime.version,
                            runtime.arch,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RuntimeBadge(managed = runtime.managed)
                }
                val vendorLabel = if (runtime.vendor == "mojang") "Mojang" else runtime.vendor
                val sourceLabel = if (runtime.source == "Fograin runtimes") {
                    stringResource(Res.string.java_source_managed)
                } else {
                    runtime.source
                }
                Text(
                    text = listOfNotNull(
                        vendorLabel.takeIf { it.isNotBlank() },
                        sourceLabel.takeIf { it.isNotBlank() },
                        runtime.sizeBytes.takeIf { it > 0 }
                            ?.let { stringResource(Res.string.java_size, InstanceFormat.byteSize(it)) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.java_runtime_home, runtime.home),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(
                            Res.string.java_runtime_delete,
                            runtime.version,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeBadge(managed: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (managed) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (managed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = stringResource(
                if (managed) Res.string.java_runtime_managed else Res.string.java_runtime_system,
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun JavaCatalogCard(
    entry: CoreJavaCatalogEntry,
    installing: Boolean,
    busy: Boolean,
    fraction: Double?,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(javaCatalogTag(entry.major)),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Java ${entry.major} · ${entry.version}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val compat = if (entry.fallback) {
                        " · " + stringResource(Res.string.java_compat)
                    } else {
                        ""
                    }
                    Text(
                        text = "${entry.component} · ${entry.arch}$compat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    entry.installed && !installing -> {
                        Text(
                            text = stringResource(
                                Res.string.java_installed_version,
                                entry.installedVersion ?: entry.version,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    else -> {
                        Button(
                            onClick = onInstall,
                            enabled = !busy,
                            modifier = Modifier.testTag(javaInstallTag(entry.major)),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(Res.string.java_install))
                        }
                    }
                }
            }
            if (installing) {
                val percent = ((fraction ?: 0.0) * 100).toInt().coerceIn(0, 100)
                ThinProgressBar(
                    progress = (fraction ?: 0.0).coerceIn(0.0, 1.0),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (fraction == null) {
                        stringResource(Res.string.java_installing_unknown)
                    } else {
                        stringResource(Res.string.java_installing, percent)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
