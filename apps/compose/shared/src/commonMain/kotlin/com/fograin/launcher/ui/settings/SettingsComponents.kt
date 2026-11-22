package com.fograin.launcher.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fograin.launcher.resources.Res
import com.fograin.launcher.resources.settings_retry
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

const val SettingsPageListTag: String = "settings-page-list"

/** Horizontal inset shared by every settings page, so rows line up. */
private val SettingsSidePadding = 30.dp

/**
 * A settings page: one scrolling column with the shared page title. Keeping
 * the padding and title here means the six category pages only describe their
 * rows.
 */
@Composable
internal fun SettingsPage(
    title: String,
    subtitle: String?,
    showTitle: Boolean,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 900.dp)
                .align(Alignment.TopCenter)
                .testTag(SettingsPageListTag),
            contentPadding = PaddingValues(
                start = SettingsSidePadding,
                end = SettingsSidePadding,
                top = 22.dp,
                bottom = 36.dp,
            ),
        ) {
            if (showTitle) {
                item {
                    Column(modifier = Modifier.padding(bottom = 6.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            content()
        }
    }
}

/** Small green group caption, mirroring the reference settings layout. */
@Composable
internal fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp, top = 24.dp, bottom = 5.dp),
    )
}

/**
 * One flat settings row: title, optional description, trailing control. Rows
 * are separated by a hairline rather than living in cards, which keeps the
 * right pane calm at desktop widths.
 */
@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke(this)
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        thickness = 0.7.dp,
    )
}

/** Switch row that keeps the whole row clickable, not just the 52 dp switch. */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = { if (enabled) onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
            )
        },
    )
}

/** Right-aligned value that opens a menu — the reference's primary control. */
@Composable
internal fun SettingsChoiceRow(
    title: String,
    subtitle: String? = null,
    value: String,
    options: List<Pair<String, () -> Unit>>,
    enabled: Boolean = true,
    testTag: String? = null,
    onSelected: ((String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = { if (enabled) expanded = true },
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
        trailing = {
            Box {
                // The reference layout has no secondary chrome here: the
                // current value is highlighted text and the whole row opens
                // the menu.
                Row(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { (label, action) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                expanded = false
                                action()
                                onSelected?.invoke(label)
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * Slider row with a live value. [onCommit] runs once the user releases the
 * thumb (or presses a keyboard arrow), so dragging does not spam the core.
 */
@Composable
internal fun SettingsSliderRow(
    title: String,
    subtitle: String? = null,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    testTag: String? = null,
    onValueChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    var current by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (current != value) current = value
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 9.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        SettingsSlider(
            value = current,
            valueRange = range,
            onValueChange = {
                current = it
                onValueChange(it)
            },
            onValueChangeFinished = { value -> onCommit(value) },
            enabled = enabled,
            testTag = testTag,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Text row with a 600 ms debounce. The local value only resets when the model
 * publishes a different value (for example after an external settings change),
 * so typing is not interrupted by the echo of the previous save.
 */
@Composable
internal fun SettingsTextRow(
    title: String,
    subtitle: String? = null,
    value: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != text) text = value
    }
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(600)
        onCommit(text)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = (if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .fillMaxWidth()
                .padding(top = 8.dp),
            enabled = enabled,
            singleLine = singleLine,
            placeholder = { Text(placeholder) },
            isError = isError,
            supportingText = if (supportingText != null) {
                { Text(supportingText) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
        )
    }
}

/** Muted informational card used for restart hints and platform notes. */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 14.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp),
            )
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Read-only label/value row for the About and Storage pages. */
@Composable
internal fun SettingsValueRow(
    label: String,
    value: String,
    mono: Boolean = false,
    testTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = (if (testTag != null) Modifier.testTag(testTag) else Modifier).weight(0.6f),
        )
    }
}

/** Error banner with a retry affordance. */
@Composable
internal fun SettingsErrorBanner(message: String, onRetry: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsSidePadding, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                Surface(
                    onClick = onRetry,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Text(
                        text = stringResource(Res.string.settings_retry),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Centered spinner used until the first `settings.get` answers. */
@Composable
internal fun SettingsLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
    }
}
