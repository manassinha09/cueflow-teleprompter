package com.novaflow.cueflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val CueShapes = Shapes(large = RoundedCornerShape(20.dp))

@Composable
fun CueFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) StudioColors else CueLightColors,
        typography = CueTypography,
        shapes = CueShapes,
        content = content,
    )
}
