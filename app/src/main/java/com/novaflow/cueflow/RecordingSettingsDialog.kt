package com.novaflow.cueflow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun RecordingSettingsDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = MaterialTheme.colorScheme
        val scroll = rememberScrollState()
        Surface(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 720.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.surfaceContainerHigh,
        ) {
            Column {
                Text(
                    "Recording settings",
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 12.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Column(
                    Modifier.weight(1f, fill = false).drawWithContent {
                        drawContent()
                        if (scroll.maxValue in 1 until Int.MAX_VALUE && size.height > 0f) {
                            val track = (size.height - 8.dp.toPx()).coerceAtLeast(0f)
                            val thumb = (track * size.height / (size.height + scroll.maxValue))
                                .coerceIn(minOf(20.dp.toPx(), track), track)
                            val top = 4.dp.toPx() + (track - thumb) * scroll.value / scroll.maxValue
                            val x = size.width - 6.dp.toPx()
                            drawLine(
                                colors.outline.copy(alpha = 0.6f),
                                Offset(x, top), Offset(x, top + thumb),
                                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                            )
                        }
                    }.verticalScroll(scroll)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
                HorizontalDivider(color = colors.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
internal fun SettingRow(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(modifier)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { action() }
    }
}

@Composable
internal fun SettingPickerRow(
    title: String,
    value: String,
    detail: String? = null,
    enabled: Boolean = true,
    options: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val dismiss = { expanded = false }
    SettingRow(
        title = title,
        detail = detail,
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = "Show options") {
            expanded = true
        },
    ) {
        Box {
            SettingValue(value, enabled = enabled, dropdown = true, expanded = expanded && enabled)
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = dismiss) {
                options(dismiss)
            }
        }
    }
}

@Composable
internal fun SettingValue(
    value: String,
    enabled: Boolean,
    dropdown: Boolean = false,
    expanded: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        !enabled -> colors.surfaceContainer
        expanded -> colors.primaryContainer
        else -> colors.secondaryContainer
    }
    val foreground = when {
        !enabled -> colors.onSurfaceVariant
        expanded -> colors.onPrimaryContainer
        else -> colors.onSecondaryContainer
    }
    Row(
        Modifier.widthIn(max = 176.dp).padding(vertical = 4.dp)
            .background(background, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            value,
            Modifier.weight(1f, fill = false),
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (dropdown) {
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
                tint = foreground,
            )
        }
    }
}
