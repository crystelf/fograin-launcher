package com.fograin.launcher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fograin.launcher.app.AppModel
import com.fograin.launcher.app.AppUiState
import com.fograin.launcher.app.SettingsSection
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.settings_group_connection
import com.fograin.launcher.resources.settings_group_proxy
import com.fograin.launcher.resources.settings_network_subtitle
import com.fograin.launcher.resources.settings_proxy_auto
import com.fograin.launcher.resources.settings_proxy_auto_desc
import com.fograin.launcher.resources.settings_proxy_custom
import com.fograin.launcher.resources.settings_proxy_invalid
import com.fograin.launcher.resources.settings_proxy_mode
import com.fograin.launcher.resources.settings_proxy_mode_desc
import com.fograin.launcher.resources.settings_proxy_none
import com.fograin.launcher.resources.settings_proxy_url
import com.fograin.launcher.resources.settings_proxy_url_desc
import com.fograin.launcher.resources.settings_proxy_url_hint
import com.fograin.launcher.resources.settings_restart_network
import com.fograin.launcher.resources.settings_timeout
import com.fograin.launcher.resources.settings_timeout_desc
import com.fograin.launcher.resources.settings_timeout_value
import org.jetbrains.compose.resources.stringResource

const val SettingsProxyModeTag = "settings-proxy-mode"
const val SettingsProxyUrlTag = "settings-proxy-url"
const val SettingsTimeoutTag = "settings-timeout"

private const val ProxyAuto = "auto"
private const val ProxyNone = "none"
private val ProxySchemes = listOf("http://", "https://", "socks5://", "socks5h://")

/** Network: proxy mode/URL and the request timeout. */
@Composable
internal fun NetworkSettingsPage(
    model: AppModel,
    state: AppUiState,
    showTitle: Boolean,
) {
    val network = state.settingsWindow.network
    val mode = when {
        network.proxy.equals(ProxyAuto, ignoreCase = true) -> ProxyAuto
        network.proxy.equals(ProxyNone, ignoreCase = true) -> ProxyNone
        else -> "custom"
    }
    val customUrl = if (mode == "custom") network.proxy else ""
    val validCustom = customUrl.isNotBlank() && ProxySchemes.any {
        customUrl.startsWith(it, ignoreCase = true)
    }

    SettingsPage(
        title = SettingsSection.NETWORK.title(),
        subtitle = stringResource(Res.string.settings_network_subtitle),
        showTitle = showTitle,
    ) {
        item { SettingsGroupHeader(stringResource(Res.string.settings_group_proxy)) }
        item {
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_proxy_mode),
                subtitle = stringResource(Res.string.settings_proxy_mode_desc),
                value = when (mode) {
                    ProxyAuto -> stringResource(Res.string.settings_proxy_auto)
                    ProxyNone -> stringResource(Res.string.settings_proxy_none)
                    else -> stringResource(Res.string.settings_proxy_custom)
                },
                options = listOf(
                    stringResource(Res.string.settings_proxy_auto) to {
                        model.updateNetworkSettings { it.copy(proxy = ProxyAuto) }
                    },
                    stringResource(Res.string.settings_proxy_none) to {
                        model.updateNetworkSettings { it.copy(proxy = ProxyNone) }
                    },
                    stringResource(Res.string.settings_proxy_custom) to {
                        val target = customUrl.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:7890"
                        model.updateNetworkSettings { it.copy(proxy = target) }
                    },
                ),
                testTag = SettingsProxyModeTag,
            )
            SettingsDivider()
        }
        if (mode == ProxyAuto) {
            item {
                SettingsNote(text = stringResource(Res.string.settings_proxy_auto_desc))
            }
        }
        if (mode == "custom") {
            item {
                SettingsTextRow(
                    title = stringResource(Res.string.settings_proxy_url),
                    subtitle = stringResource(Res.string.settings_proxy_url_desc),
                    value = customUrl,
                    placeholder = stringResource(Res.string.settings_proxy_url_hint),
                    isError = !validCustom,
                    supportingText = if (!validCustom) {
                        stringResource(Res.string.settings_proxy_invalid)
                    } else {
                        null
                    },
                    testTag = SettingsProxyUrlTag,
                    onCommit = { value ->
                        if (value.isNotBlank() && ProxySchemes.any {
                                value.startsWith(it, ignoreCase = true)
                            }
                        ) {
                            model.updateNetworkSettings { it.copy(proxy = value.trim()) }
                        }
                    },
                )
                SettingsDivider()
            }
        }

        item { SettingsGroupHeader(stringResource(Res.string.settings_group_connection)) }
        item {
            var timeout by remember(network.timeoutSecs) {
                mutableStateOf(network.timeoutSecs)
            }
            SettingsSliderRow(
                title = stringResource(Res.string.settings_timeout),
                subtitle = stringResource(Res.string.settings_timeout_desc),
                valueText = stringResource(Res.string.settings_timeout_value, timeout),
                value = timeout.toFloat(),
                range = 5f..60f,
                testTag = SettingsTimeoutTag,
                onValueChange = { timeout = it.toInt().coerceIn(5, 60) },
                onCommit = { value ->
                    val seconds = value.toInt().coerceIn(5, 60)
                    timeout = seconds
                    model.updateNetworkSettings { it.copy(timeoutSecs = seconds) }
                },
            )
            SettingsDivider()
        }
        item {
            SettingsNote(text = stringResource(Res.string.settings_restart_network))
        }
    }
}
