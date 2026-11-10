package com.novaflow.cueflow

import androidx.compose.ui.geometry.Offset

// Invert the centered, fit-to-view rotation and mirroring applied to the preview surface.
internal fun previewMeteringPoint(
    tap: Offset,
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    rotation: Int,
    mirrorX: Boolean,
    mirrorY: Boolean,
): Offset? {
    if (viewWidth <= 0 || viewHeight <= 0) return null
    val sideways = rotation == 90 || rotation == 270
    val width = if (sideways) bufferHeight else bufferWidth
    val height = if (sideways) bufferWidth else bufferHeight
    val scale = minOf(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
    val scaledWidth = width * scale
    val scaledHeight = height * scale
    val x = (tap.x - (viewWidth - scaledWidth) / 2f) / scaledWidth
    val y = (tap.y - (viewHeight - scaledHeight) / 2f) / scaledHeight
    if (x !in 0f..1f || y !in 0f..1f) return null
    val source = when (rotation) {
        90 -> Offset(y, 1f - x)
        180 -> Offset(1f - x, 1f - y)
        270 -> Offset(1f - y, x)
        else -> Offset(x, y)
    }
    return Offset(if (mirrorX) 1f - source.x else source.x, if (mirrorY) 1f - source.y else source.y)
}
