package com.fograin.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fograin.launcher.generated.FograinTokens
import com.fograin.launcher.platform.CoreClient
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.app_name
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

private val FograinColors = darkColorScheme(
    primary = FograinTokens.Dark.primary,
    onPrimary = FograinTokens.Dark.onPrimary,
    primaryContainer = FograinTokens.Dark.primaryContainer,
    onPrimaryContainer = FograinTokens.Dark.onPrimaryContainer,
    secondary = FograinTokens.Dark.secondary,
    onSecondary = FograinTokens.Dark.onSecondary,
    surface = FograinTokens.Dark.surface,
    onSurface = FograinTokens.Dark.onSurface,
    surfaceVariant = FograinTokens.Dark.surfaceHigh,
    onSurfaceVariant = FograinTokens.Dark.onSurfaceVariant,
    background = FograinTokens.Dark.surface,
    outline = FograinTokens.Dark.outline,
    error = FograinTokens.Dark.error,
)

/**
 * Shared shell: one Compose screen on Android and desktop that renders the
 * Rust core snapshot through the JNI bridge. The four destinations are moved
 * in from the legacy Android client next.
 */
@Composable
fun App(core: CoreClient) {
    val scope = rememberCoroutineScope()
    val initError = core.initError
    val settings = core.settings
    var snapshot by remember { mutableStateOf<JsonObject?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (initError != null) return
        loading = true
        scope.launch {
            loadError = null
            try {
                snapshot = core.appSnapshot()
            } catch (t: Throwable) {
                loadError = t.message ?: t::class.simpleName
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    MaterialTheme(colorScheme = FograinColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 880.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 32.dp, vertical = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Header(appVersion = settings?.appVersion.orEmpty())
                    StatusRow(
                        ok = initError == null && loadError == null,
                        abiVersion = settings?.abiVersion ?: core.abiVersion,
                        message = initError ?: loadError,
                    )
                    if (initError != null) {
                        ErrorCard(initError)
                    } else {
                        CoreCard(snapshot)
                        EnvironmentCard(snapshot, core)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = ::refresh, enabled = !loading && initError == null) {
                            Text(if (loading) "读取中…" else "重新读取")
                        }
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    Text(
                        text = "共享 Compose UI、设计令牌与 Rust JNI 已接通；实例、下载、设置页面迁移中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(appVersion: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(FograinTokens.Dark.primaryContainer, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "雾",
                color = FograinTokens.Dark.onPrimaryContainer,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = buildString {
                    append("Compose Multiplatform · 技术验证")
                    if (appVersion.isNotBlank()) append(" · v").append(appVersion)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusRow(ok: Boolean, abiVersion: Int, message: String?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(if (ok) FograinTokens.Dark.primary else MaterialTheme.colorScheme.error, CircleShape),
        )
        Text(
            text = if (ok) "Rust 核心已连接" else "Rust 核心不可用",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "ABI $abiVersion",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CoreCard(snapshot: JsonObject?) {
    InfoCard(title = "启动器核心") {
        InfoRow("平台", snapshot?.text("platform"))
        InfoRow("应用版本", snapshot?.text("appVersion"))
        InfoRow("实例数", snapshot?.text("instanceCount"))
        InfoRow("发现的 Java", snapshot?.text("javaCount"))
        InfoRow("活动任务", snapshot?.text("activeTasks"))
    }
}

@Composable
private fun EnvironmentCard(snapshot: JsonObject?, core: CoreClient) {
    InfoCard(title = "运行时环境") {
        InfoRow("数据目录", snapshot?.text("dataRoot") ?: core.settings?.dataRoot)
        InfoRow("配置文件", core.settings?.configPath)
        InfoRow("总内存", snapshot?.bytes("totalMemoryBytes"))
        InfoRow("磁盘可用", snapshot?.bytes("diskFreeBytes"))
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "原生库加载失败",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "请先运行 ./scripts/build-jni-desktop.sh，再重新启动应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = FograinTokens.Dark.surfaceContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.widthIn(min = 120.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value?.takeIf { it.isNotBlank() } ?: "—",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(color = FograinTokens.Dark.outlineVariant.copy(alpha = 0.35f))
    }
}

private fun JsonObject.text(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: "—"

private fun JsonObject.bytes(key: String): String {
    val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return "—"
    return formatBytes(raw.toLongOrNull() ?: 0L)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    var value = bytes.toDouble()
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index += 1
    }
    val rounded = (value * 10).roundToLong() / 10.0
    return "$rounded ${units[index]}"
}
