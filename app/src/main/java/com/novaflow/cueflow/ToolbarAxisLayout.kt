package com.novaflow.cueflow

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Keep toolbar parents and their drawing nodes attached when the window changes orientation.
// Only measurement and placement change; switching between Row and Column replaces those nodes.
@Composable
internal fun ToolbarAxisLayout(
    vertical: Boolean,
    modifier: Modifier = Modifier,
    reverseOrder: Boolean = false,
    spacing: Dp = 0.dp,
    weights: List<Float> = emptyList(),
    crossAxisAlignment: Float = 0.5f,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val minMain = if (vertical) constraints.minHeight else constraints.minWidth
        val maxMain = if (vertical) constraints.maxHeight else constraints.maxWidth
        val minCross = if (vertical) constraints.minWidth else constraints.minHeight
        val maxCross = if (vertical) constraints.maxWidth else constraints.maxHeight
        val gap = spacing.roundToPx()
        val totalGaps = gap * (measurables.size - 1).coerceAtLeast(0)
        val children = arrayOfNulls<Placeable>(measurables.size)
        fun weight(index: Int) = weights.getOrElse(index) { 0f }
        fun childConstraints(min: Int, max: Int) = if (vertical) {
            Constraints(0, maxCross, min, max)
        } else {
            Constraints(min, max, 0, maxCross)
        }
        fun Placeable.mainSize() = if (vertical) height else width
        fun Placeable.crossSize() = if (vertical) width else height

        var used = totalGaps
        var totalWeight = 0f
        for (index in measurables.indices) {
            if (weight(index) > 0f) {
                totalWeight += weight(index)
            } else {
                val available = if (maxMain == Constraints.Infinity) maxMain else (maxMain - used).coerceAtLeast(0)
                val child = measurables[index].measure(childConstraints(0, available))
                children[index] = child
                used += child.mainSize()
            }
        }
        var remaining = ((if (maxMain == Constraints.Infinity) minMain else maxMain) - used).coerceAtLeast(0)
        for (index in measurables.indices) {
            val childWeight = weight(index)
            if (childWeight > 0f) {
                val share = (remaining * childWeight / totalWeight).roundToInt().coerceIn(0, remaining)
                val child = measurables[index].measure(childConstraints(share, share))
                children[index] = child
                used += child.mainSize()
                remaining -= share
                totalWeight -= childWeight
            }
        }
        val mainSize = used.coerceIn(minMain, maxMain)
        val crossSize = (children.maxOfOrNull { it!!.crossSize() } ?: 0).coerceIn(minCross, maxCross)
        layout(if (vertical) crossSize else mainSize, if (vertical) mainSize else crossSize) {
            var position = 0
            val indices = if (reverseOrder) children.indices.reversed() else children.indices
            for (index in indices) {
                val child = children[index]!!
                val crossPosition = ((crossSize - child.crossSize()) * crossAxisAlignment).roundToInt()
                if (vertical) child.placeRelative(crossPosition, position) else child.placeRelative(position, crossPosition)
                position += child.mainSize() + gap
            }
        }
    }
}
