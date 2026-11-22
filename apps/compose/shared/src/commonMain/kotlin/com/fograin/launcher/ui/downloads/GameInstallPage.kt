package com.fograin.launcher.ui.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.model.GameLoaderKind
import com.fograin.launcher.model.InstanceFormat
import com.fograin.launcher.platform.CoreVersionEntry
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.SkeletonBox
import com.fograin.launcher.ui.titleText
import org.jetbrains.compose.resources.stringResource

/** Test tag for the primary download button on the install page. */
const val InstallDownloadTag: String = "install-download"

/**
 * Second page of the download flow. The version is pinned (no selector here);
 * loader family, loader build and instance name all sit together with the
 * Java/file/size preview, so users decide everything before starting the task.
 */
@Composable
internal fun GameInstallPage(
    model: AppModel,
    state: AppUiState,
    modifier: Modifier = Modifier,
) {
    val download = state.download
    val version = download.installVersionEntry ?: return
    val loader = download.installLoader
    val loaderVersion = download.installLoaderVersion

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 820.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                InstallHero(version = version)

                InstallContents(
                    model = model,
                    download = download,
                )

                LoaderSection(
                    model = model,
                    loader = loader,
                    loaderVersion = loaderVersion,
                    state = download,
                )

                InstanceSection(
                    model = model,
                    version = version,
                    name = download.installInstanceName,
                )

                AnimatedVisibility(
                    visible = download.installError != null,
                    enter = expandVertically(tween(200)) + fadeIn(tween(160)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    ) {
                        Text(
                            text = download.installError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

            }
        }

        InstallActionBar(model = model, download = download)
    }
}

@Composable
private fun InstallHero(version: CoreVersionEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            RoundedCornerShape(18.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = versionIcon(version.kind),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = version.id,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (version.kind != "unknown") {
                            VersionKindBadge(version.kind)
                        }
                    }
                    Text(
                        text = version.released?.let { released ->
                            stringResource(Res.string.downloads_released_on, released.take(10))
                        } ?: stringResource(Res.string.downloads_edition),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (version.released != null) {
                        Text(
                            text = stringResource(Res.string.downloads_edition),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallContents(
    model: AppModel,
    download: com.fograin.launcher.app.DownloadUiState,
) {
    val plan = download.plan
    val planError = download.planError
    val loader = download.installLoader
    val loaderVersion = download.installLoaderVersion
    val withLoader = loader != GameLoaderKind.VANILLA && loaderVersion.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.install_contents),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    withLoader -> stringResource(
                        Res.string.install_contents_loader,
                        loader.titleText(),
                        loaderVersion,
                    )

                    loader == GameLoaderKind.VANILLA ->
                        stringResource(Res.string.install_contents_vanilla)

                    else -> stringResource(Res.string.install_contents_base)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val phase = when {
            plan != null -> "ready"
            planError != null -> "error"
            else -> "loading"
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "plan-phase",
        ) { currentPhase ->
            when (currentPhase) {
                "ready" -> {
                    val readyPlan = plan
                    if (readyPlan != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatTile(
                                icon = Icons.Outlined.Code,
                                label = stringResource(Res.string.downloads_java_label),
                                value = readyPlan.javaMajor.toString(),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                icon = Icons.Outlined.Folder,
                                label = stringResource(Res.string.downloads_files),
                                value = stringResource(
                                    Res.string.downloads_file_count,
                                    readyPlan.files,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                icon = Icons.Outlined.Storage,
                                label = stringResource(Res.string.downloads_size),
                                value = InstanceFormat.byteSize(readyPlan.totalBytes),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                "error" -> ErrorState(
                    message = stringResource(
                        Res.string.downloads_plan_error,
                        planError.orEmpty(),
                    ),
                    onRetry = model.downloads::refreshInstallPlan,
                )

                else -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(3) {
                        SkeletonBox(
                            modifier = Modifier
                                .weight(1f)
                                .height(76.dp),
                            shape = RoundedCornerShape(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoaderSection(
    model: AppModel,
    loader: GameLoaderKind,
    loaderVersion: String,
    state: com.fograin.launcher.app.DownloadUiState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(Res.string.install_loader),
            style = MaterialTheme.typography.titleMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GameLoaderKind.entries.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { option ->
                        LoaderOptionCard(
                            option = option,
                            selected = option == loader,
                            onClick = { model.downloads.setInstallLoader(option) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = loader != GameLoaderKind.VANILLA,
            enter = expandVertically(tween(220)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.height(2.dp))
                LoaderVersionRow(
                    title = stringResource(Res.string.install_loader_versions, loader.titleText()),
                    value = if (state.isLoadingLoaderVersions) "" else loaderVersion,
                    loading = state.isLoadingLoaderVersions,
                    hasError = state.loaderVersionsError != null,
                    onClick = model.downloads::openLoaderVersionPicker,
                )
                state.loaderVersionsError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstanceSection(
    model: AppModel,
    version: CoreVersionEntry,
    name: String,
) {
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.install_instance_name),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = name,
            onValueChange = model.downloads::setInstallInstanceName,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(version.id) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus(force = true) },
            ),
        )
    }
}

@Composable
private fun InstallActionBar(
    model: AppModel,
    download: com.fograin.launcher.app.DownloadUiState,
) {
    val version = download.installVersionId.orEmpty()
    val loader = download.installLoader
    val loaderVersion = download.installLoaderVersion
    val summary = when {
        loader == GameLoaderKind.VANILLA -> stringResource(Res.string.install_summary, version)
        download.isLoadingLoaderVersions -> stringResource(Res.string.install_loader_loading)
        loaderVersion.isEmpty() -> stringResource(Res.string.install_loader_choose)
        else -> stringResource(
            Res.string.install_summary_loader,
            version,
            loader.titleText(),
            loaderVersion,
        )
    }
    val canDownload = download.canStartInstall

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                val compact = maxWidth < 520.dp
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryText(summary = summary)
                        InstallButton(
                            model = model,
                            download = download,
                            enabled = canDownload,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SummaryText(summary = summary, modifier = Modifier.weight(1f))
                        InstallButton(
                            model = model,
                            download = download,
                            enabled = canDownload,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryText(summary: String, modifier: Modifier = Modifier) {
    Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun InstallButton(
    model: AppModel,
    download: com.fograin.launcher.app.DownloadUiState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val busy = download.isInstallRequesting || download.isGameDownloadRunning
    Button(
        onClick = model.downloads::submitInstall,
        enabled = enabled,
        modifier = modifier
            .height(46.dp)
            .testTag(InstallDownloadTag),
        shape = RoundedCornerShape(14.dp),
    ) {
        AnimatedContent(
            targetState = busy,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
            label = "install-button",
        ) { running ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        stringResource(
                            if (download.isGameDownloadRunning) {
                                Res.string.downloads_install_running
                            } else {
                                Res.string.install_starting
                            },
                        ),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(Res.string.install_download))
                }
            }
        }
    }
}
