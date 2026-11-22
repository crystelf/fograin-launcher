package com.fograin.launcher.ui.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.DiagTab
import com.fograin.launcher.platform.CoreFinding
import com.fograin.launcher.resources.*
import com.fograin.launcher.ui.components.PageHeader
import com.fograin.launcher.ui.components.SegmentedTabRail
import org.jetbrains.compose.resources.stringResource

const val DiagnosticsPageTag: String = "diagnostics-page"
const val DiagnosticsExportTag: String = "diagnostics-export"

/**
 * Instance · 日志与诊断.
 *
 * The page leads with the conclusion: if the analyzer matched rules, the first
 * cards say what is wrong and what to do. Logs sit below in a compact,
 * monospace pane because they are evidence, not the headline.
 */
@Composable
fun DiagnosticsPage(model: AppModel, state: AppUiState) {
    val diag = state.diag
    val instance = state.libraryDetailInstance
    val analysis = diag.analysis
    val activeTab = diag.activeTab

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(DiagnosticsPageTag),
    ) {
        PageHeader(
            title = stringResource(Res.string.diag_title),
            onBack = model::closeDiagnostics,
            actions = {
                IconButton(onClick = model.diag::refresh, enabled = !diag.isLoading) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(Res.string.diag_refresh),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (diag.isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        onClick = model.diag::export,
                        enabled = instance != null,
                        modifier = Modifier.testTag(DiagnosticsExportTag),
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = stringResource(Res.string.diag_export),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            AnimatedVisibility(
                visible = diag.error != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                        Text(
                            text = diag.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (analysis != null) {
                val findings = analysis.findings
                if (findings.isEmpty()) {
                    CleanSummary(summary = analysis.summary)
                } else {
                    FindingSummary(
                        findings = findings,
                        summary = analysis.summary,
                        onFix = model::applyDiagFix,
                    )
                }
            } else if (diag.isLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.content_loading),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (diag.bundlePath != null) {
                BundleBanner(
                    path = diag.bundlePath.orEmpty(),
                    onCopy = model.diag::copyBundlePath,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.diag_log_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                val activeLines = diag.logs?.sources
                    ?.firstOrNull { it.kind == activeTab.kind }
                    ?.lines
                    ?: 0
                Text(
                    text = if (activeLines > 0) {
                        "${diagTabLabel(activeTab)} · " +
                            stringResource(Res.string.diag_lines, activeLines)
                    } else {
                        diagTabLabel(activeTab)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SegmentedTabRail(
                options = diag.availableTabs,
                selected = activeTab,
                onSelect = model.diag::selectTab,
                modifier = Modifier.fillMaxWidth(),
                label = { tab -> diagTabLabel(tab) },
            )

            val text = diag.textFor(activeTab)
            if (text.isBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(Res.string.diag_empty_log),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            } else {
                LogPane(text = text, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // The log tab list can shrink after a refresh; keep the selection valid.
    LaunchedEffect(diag.availableTabs, diag.tab) {
        if (diag.tab !in diag.availableTabs) model.diag.selectTab(diag.activeTab)
    }
}

@Composable
private fun CleanSummary(summary: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.diag_no_findings_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FindingSummary(
    findings: List<CoreFinding>,
    summary: String,
    onFix: (CoreFinding) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // The three most confident findings are enough above the fold; the
        // full list is still reachable by scrolling the log pane's parent.
        findings.take(3).forEach { finding ->
            FindingCard(finding = finding, onFix = { onFix(finding) })
        }
        if (findings.size > 3) {
            Text(
                text = stringResource(Res.string.diag_more_findings, findings.size - 3),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FindingCard(finding: CoreFinding, onFix: () -> Unit) {
    val tone = severityTone(finding.severity)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tone.container,
        contentColor = tone.content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = tone.icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = finding.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        Res.string.diag_confidence,
                        (finding.confidence * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = tone.content.copy(alpha = 0.78f),
                )
            }
            if (finding.body.isNotBlank()) {
                Text(
                    text = finding.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = tone.content.copy(alpha = 0.9f),
                )
            }
            if (finding.excerpt.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = finding.excerpt,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = tone.content.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        Res.string.diag_line,
                        finding.source,
                        finding.line,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = tone.content.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (finding.fix != "none") {
                    TextButton(onClick = onFix) {
                        Text(
                            text = fixLabel(finding.fix),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BundleBanner(path: String, onCopy: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Download,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.diag_bundle_ready),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(Res.string.diag_copy_path),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun LogPane(text: String, modifier: Modifier = Modifier) {
    val lines = androidx.compose.runtime.remember(text) {
        text.lineSequence().take(4000).toList()
    }
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.lastIndex)
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        ),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        modifier = Modifier.width(34.dp),
                    )
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        ),
                        color = lineColor(line),
                    )
                }
            }
        }
    }
}

@Composable
private fun lineColor(line: String): Color = when {
    line.contains("/ERROR", ignoreCase = true) || line.contains("Exception", ignoreCase = true) ->
        MaterialTheme.colorScheme.error

    line.contains("/WARN", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun diagTabLabel(tab: DiagTab): String = stringResource(
    when (tab) {
        DiagTab.LOG -> Res.string.diag_tab_log
        DiagTab.CRASH -> Res.string.diag_tab_crash
        DiagTab.SESSION -> Res.string.diag_tab_session
        DiagTab.JVM -> Res.string.diag_tab_jvm
    },
)

@Composable
private fun fixLabel(fix: String): String = stringResource(
    when (fix) {
        "installJava", "openJavaSettings" -> Res.string.diag_fix_java
        "openContent" -> Res.string.diag_fix_content
        "disableMod" -> Res.string.diag_fix_disable_mod
        "openGraphics" -> Res.string.diag_fix_graphics
        else -> Res.string.diag_fix_logs
    },
)

private data class SeverityTone(
    val container: Color,
    val content: Color,
    val icon: ImageVector,
)

@Composable
private fun severityTone(severity: String): SeverityTone = when (severity) {
    "error" -> SeverityTone(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        icon = Icons.Outlined.ErrorOutline,
    )

    "warning" -> SeverityTone(
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        icon = Icons.Outlined.WarningAmber,
    )

    else -> SeverityTone(
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = MaterialTheme.colorScheme.onSurface,
        icon = Icons.Outlined.Info,
    )
}