package com.novaflow.cueflow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun CameraToolbarAdjustments(
    temperature: CameraTemperature,
    temperatureOpen: Boolean,
    vertical: Boolean,
    reversed: Boolean,
    enabled: Boolean,
    onToggleTemperature: () -> Unit,
    onTemperature: (Float?) -> Unit,
    iconModifier: Modifier = Modifier,
    zoom: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val showTemperature = temperatureOpen && temperature.supported
    val toggle: @Composable () -> Unit = {
        if (temperature.supported) {
            FilledTonalIconToggleButton(
                checked = showTemperature,
                onCheckedChange = { onToggleTemperature() },
                enabled = enabled,
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = if (temperature.warmth == null) colors.surfaceContainerHighest else colors.secondary,
                    checkedContainerColor = colors.primary,
                ),
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "White balance"
                },
            ) {
                Icon(
                    painterResource(R.drawable.ic_white_balance),
                    contentDescription = null,
                    modifier = iconModifier,
                )
            }
        }
    }
    ToolbarAxisLayout(
        vertical = !vertical,
        reverseOrder = vertical && !reversed,
        spacing = 4.dp,
    ) {
        AnimatedVisibility(
            visible = showTemperature,
            enter = fadeIn() + if (vertical) expandHorizontally() else expandVertically(),
            exit = fadeOut() + if (vertical) shrinkHorizontally() else shrinkVertically(),
        ) {
            TemperatureSlider(temperature, vertical, reversed, enabled && showTemperature, onTemperature)
        }
        ToolbarAxisLayout(
            vertical = vertical,
            modifier = if (vertical) Modifier.fillMaxHeight().widthIn(min = 48.dp) else {
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
            },
            reverseOrder = reversed,
            weights = listOf(0f, 1f, 0f),
            crossAxisAlignment = if (vertical && !reversed) 0f else 1f,
        ) {
            Box {
                if (temperature.supported) Spacer(Modifier.size(48.dp))
            }
            Box(contentAlignment = Alignment.Center) { zoom() }
            Box { toggle() }
        }
    }
}

@Composable
private fun TemperatureSlider(
    temperature: CameraTemperature,
    vertical: Boolean,
    reversed: Boolean,
    enabled: Boolean,
    onTemperature: (Float?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val warmth = temperature.warmth
    val auto: @Composable () -> Unit = {
        FilterChip(
            selected = warmth == null,
            onClick = { onTemperature(null) },
            enabled = enabled,
            label = { Text("Auto", maxLines = 1) },
            shape = CircleShape,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = colors.primary,
                selectedLabelColor = colors.onPrimary,
            ),
            modifier = Modifier.semantics { contentDescription = "Automatic white balance" },
        )
    }
    val slider: @Composable (Modifier) -> Unit = { modifier ->
        CameraAdjustmentSlider(
            value = warmth ?: 0f,
            onValueChange = onTemperature,
            valueRange = -1f..1f,
            steps = 19,
            vertical = vertical,
            reversed = reversed,
            enabled = enabled && temperature.adjustable,
            modifier = modifier.semantics {
                contentDescription = "White balance temperature"
                stateDescription = when {
                    warmth == null -> "Auto"
                    warmth == 0f -> "Neutral"
                    warmth < 0f -> "${(abs(warmth) * 100).roundToInt()}% cooler"
                    else -> "${(warmth * 100).roundToInt()}% warmer"
                }
            },
        )
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerHigh) {
        if (vertical) {
            Column(
                Modifier.heightIn(max = 360.dp).fillMaxHeight().padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (reversed) auto()
                TemperatureEnd(warm = reversed)
                slider(Modifier.weight(1f).width(48.dp))
                TemperatureEnd(warm = !reversed)
                if (!reversed) auto()
            }
        } else {
            Row(
                Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (reversed) auto()
                TemperatureEnd(warm = reversed)
                slider(Modifier.weight(1f))
                TemperatureEnd(warm = !reversed)
                if (!reversed) auto()
            }
        }
    }
}

@Composable
private fun TemperatureEnd(warm: Boolean) {
    Text(
        if (warm) "Warm" else "Cool",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
internal fun CameraAdjustmentSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    vertical: Boolean,
    reversed: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    // Vertical order follows the camera toolbar even when the interface reads right to left.
    val direction = if (vertical) LayoutDirection.Ltr else LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                inactiveTrackColor = colors.outline,
                inactiveTickColor = colors.onPrimary,
            ),
            modifier = modifier.orientSlider(vertical, reversed),
        )
    }
}

// Rotate the Material slider and its touch target together in landscape.
internal fun Modifier.orientSlider(vertical: Boolean, reversed: Boolean): Modifier {
    if (!vertical) return if (reversed) scale(scaleX = -1f, scaleY = 1f) else this
    return layout { measurable, constraints ->
        val slider = measurable.measure(
            Constraints(constraints.minHeight, constraints.maxHeight, constraints.minWidth, constraints.maxWidth),
        )
        layout(slider.height, slider.width) {
            slider.placeWithLayer((slider.height - slider.width) / 2, (slider.width - slider.height) / 2) {
                rotationZ = if (reversed) -90f else 90f
            }
        }
    }
}
