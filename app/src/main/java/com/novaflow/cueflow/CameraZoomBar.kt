package com.novaflow.cueflow

import android.util.Range
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private val PresetSize = 48.dp
private val ScaleLength = 224.dp
private val ScalePadding = 8.dp
private val ValueWidth = 56.dp
private val RulerNeedleWidth = 3.dp

@Composable
internal fun CameraZoomBar(
    zoom: Float,
    range: Range<Float>,
    front: Boolean,
    vertical: Boolean,
    reversed: Boolean,
    enabled: Boolean,
    onZoom: (Float) -> Unit,
) = BoxWithConstraints(contentAlignment = Alignment.Center) {
    val colors = MaterialTheme.colorScheme
    val available = if (vertical) maxHeight else maxWidth
    val sideCapacity = ((available - PresetSize) / (PresetSize * 2)).toInt().coerceAtLeast(0)
    val lowerPresets = listOf(range.lower).filter { it < 1f }.take(sideCapacity)
    val higherPresets = (if (front) listOf(1.4f) else listOf(2f, 5f))
        .filter(range::contains).take(sideCapacity)
    val rulerMinimum = range.clamp(1f)
    val rulerRange = Range(rulerMinimum, range.upper)
    val presets = lowerPresets + rulerMinimum + higherPresets
    val ordered = if (reversed) presets.asReversed() else presets
    val activePreset = presets.minBy { abs(ln(zoom / it)) }
    val canAdjust = enabled && range.lower < range.upper
    val canScrub = enabled && rulerMinimum < range.upper
    val canOpenRuler = canScrub && zoom >= rulerMinimum
    val rtl = !vertical && LocalLayoutDirection.current == LayoutDirection.Rtl
    val scrubIncreasesTowardsStart = !vertical || reversed
    var expanded by remember(front, range, vertical, reversed, canScrub) { mutableStateOf(false) }
    val scaleLength = minOf(ScaleLength, available)
    val valueLength = if (vertical) PresetSize else ValueWidth
    val travelPx = with(LocalDensity.current) {
        (scaleLength - valueLength - ScalePadding * 2 - RulerNeedleWidth).toPx().coerceAtLeast(1f)
    }
    val logSpan = ln(range.upper / rulerMinimum)
    fun fractionOf(value: Float) = (ln(value / rulerMinimum) / logSpan).coerceIn(0f, 1f)
    val fraction = if (canScrub) fractionOf(zoom) else 0f
    val selectFraction: (Float) -> Unit = { position ->
        val next = when (position) {
            0f -> rulerMinimum
            1f -> range.upper
            else -> {
                val ratio = rulerMinimum * exp(position * logSpan)
                rulerRange.clamp((ratio * 10).roundToInt() / 10f)
            }
        }
        onZoom(next)
    }
    val currentZoom by rememberUpdatedState(zoom)
    val currentCanOpenRuler by rememberUpdatedState(canOpenRuler)
    val currentFraction by rememberUpdatedState(fraction)
    val currentTravelPx by rememberUpdatedState(travelPx)
    val currentPresets by rememberUpdatedState(ordered)
    val onScrub by rememberUpdatedState(selectFraction)
    val collapse = { expanded = false }

    BackHandler(enabled = expanded, onBack = collapse)

    // Balance the empty space around the presets so the 1× button stays on the Record axis.
    val extraBefore = PresetSize * (higherPresets.size - lowerPresets.size).coerceAtLeast(0)
    val extraAfter = PresetSize * (lowerPresets.size - higherPresets.size).coerceAtLeast(0)
    val start = if (expanded) 0.dp else if (reversed) extraAfter else extraBefore
    val end = if (expanded) 0.dp else if (reversed) extraBefore else extraAfter
    val positionPresets = if (vertical) {
        Modifier.padding(top = start, bottom = end)
    } else {
        Modifier.padding(start = start, end = end)
    }
    Box(
        positionPresets.pointerInput(canScrub, front, range, vertical, reversed, rtl) {
            if (!canScrub) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (expanded) return@awaitEachGesture

                val along = when {
                    vertical -> down.position.y
                    rtl -> size.width - down.position.x
                    else -> down.position.x
                }
                val index = (along / PresetSize.roundToPx()).toInt().coerceIn(currentPresets.indices)
                val openedOnPress = currentCanOpenRuler && currentPresets[index] == currentZoom
                if (openedOnPress) {
                    down.consume()
                    expanded = true
                }

                var dragFraction = currentFraction
                val travel = currentTravelPx
                fun scrub(delta: Float) {
                    val distance = if (scrubIncreasesTowardsStart) -delta else delta
                    dragFraction = (dragFraction + distance / travel).coerceIn(0f, 1f)
                    onScrub(dragFraction)
                }

                var dragged = false
                try {
                    // Finish dispatching the down event before checking movement on the Main pass.
                    awaitPointerEvent(PointerEventPass.Main)
                    val startDrag: (PointerInputChange, Float) -> Unit = { change, overSlop ->
                        change.consume()
                        dragged = true
                        expanded = true
                        scrub(overSlop)
                    }
                    val pointer = if (vertical) {
                        awaitVerticalTouchSlopOrCancellation(down.id, startDrag)
                    } else {
                        awaitHorizontalTouchSlopOrCancellation(down.id, startDrag)
                    }
                    if (pointer != null) {
                        drag(pointer.id) { change ->
                            val movement = change.positionChange()
                            change.consume()
                            scrub(if (vertical) movement.y else movement.x)
                        }
                    }
                } finally {
                    // A quick tap keeps the slider open so its track can be tapped separately.
                    if (dragged || (openedOnPress && currentEvent.changes.none { it.changedToUp() })) {
                        collapse()
                    }
                }
            }
        },
    ) {
        if (expanded) {
            ZoomRuler(
                zoom = zoom,
                range = rulerRange,
                fraction = fraction,
                majorTicks = (presets.filter(rulerRange::contains).map(::fractionOf) + 1f).distinct(),
                vertical = vertical,
                reversed = vertical && reversed,
                length = scaleLength,
                onZoom = onZoom,
                onFraction = selectFraction,
                onCollapse = collapse,
            )
        } else {
            Surface(
                shape = CircleShape,
                color = colors.surfaceContainerHigh,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                val buttons: @Composable () -> Unit = {
                    for (preset in ordered) {
                        val active = preset == activePreset
                        val label = zoomLabel(if (active) zoom else preset)
                        val opensRuler = canOpenRuler && zoom == preset
                        Box(
                            Modifier.size(PresetSize).clip(CircleShape)
                                .combinedClickable(
                                    enabled = canAdjust,
                                    role = Role.Button,
                                    onClickLabel = if (opensRuler) "Adjust camera zoom" else null,
                                    onLongClickLabel = "Adjust camera zoom",
                                    onLongClick = if (canOpenRuler) ({ expanded = true }) else null,
                                    onClick = { if (opensRuler) expanded = true else onZoom(preset) },
                                )
                                .semantics {
                                    selected = active
                                    contentDescription = if (active) "Camera zoom $label" else "Set camera zoom to $label"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(40.dp).then(
                                    if (active) Modifier.background(
                                        if (enabled) colors.primary else colors.surfaceContainerHighest,
                                        CircleShape,
                                    ) else Modifier,
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (active && enabled) colors.onPrimary else colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                if (vertical) Column { buttons() } else Row { buttons() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoomRuler(
    zoom: Float,
    range: Range<Float>,
    fraction: Float,
    majorTicks: List<Float>,
    vertical: Boolean,
    reversed: Boolean,
    length: Dp,
    onZoom: (Float) -> Unit,
    onFraction: (Float) -> Unit,
    onCollapse: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val value: @Composable () -> Unit = {
        TextButton(
            onClick = onCollapse,
            modifier = Modifier.size(if (vertical) PresetSize else ValueWidth, PresetSize).semantics {
                contentDescription = "Close zoom slider, camera zoom ${zoomLabel(zoom)}"
            },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(zoomLabel(zoom), style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
    val slider: @Composable (Modifier) -> Unit = { modifier ->
        Slider(
            value = fraction,
            onValueChange = onFraction,
            onValueChangeFinished = onCollapse,
            thumb = {
                Box(Modifier.size(RulerNeedleWidth, 18.dp).background(colors.primary, CircleShape))
            },
            track = { ZoomRulerTicks(majorTicks) },
            modifier = modifier.orientSlider(vertical, reversed).semantics {
                contentDescription = "Adjust camera zoom"
                stateDescription = zoomLabel(zoom)
                progressBarRangeInfo = ProgressBarRangeInfo(range.clamp(zoom), range.lower..range.upper)
                setProgress {
                    onZoom(range.clamp(it))
                    onCollapse()
                    true
                }
            },
        )
    }
    // Zoom values increase to the right in portrait, including in RTL layouts.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerHigh) {
            if (vertical) {
                Column(
                    Modifier.size(PresetSize, length).padding(vertical = ScalePadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (reversed) value()
                    slider(Modifier.weight(1f).fillMaxWidth())
                    if (!reversed) value()
                }
            } else {
                Row(
                    Modifier.size(length, 56.dp).padding(horizontal = ScalePadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        slider(Modifier.fillMaxWidth().height(PresetSize))
                        Text(
                            zoomLabel(range.lower),
                            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp)
                                .clearAndSetSemantics { },
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    value()
                }
            }
        }
    }
}

@Composable
private fun ZoomRulerTicks(majorTicks: List<Float>) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val divisions = (size.width / 7.dp.toPx()).roundToInt().coerceAtLeast(1)
        val baseline = center.y + 8.dp.toPx()
        fun tick(at: Float, height: Dp, width: Dp, color: Color) {
            val x = size.width * at
            drawLine(color, Offset(x, baseline - height.toPx()), Offset(x, baseline), width.toPx(), StrokeCap.Round)
        }
        for (index in 1 until divisions) {
            val at = index.toFloat() / divisions
            if (majorTicks.none { abs(it - at) < 0.55f / divisions }) {
                tick(at, 6.dp, 1.dp, colors.outline)
            }
        }
        for (at in majorTicks) tick(at, 13.dp, 1.5.dp, colors.onSurfaceVariant)
    }
}

private fun zoomLabel(value: Float): String =
    String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0") + "×"
