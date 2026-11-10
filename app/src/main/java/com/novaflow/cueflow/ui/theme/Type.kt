package com.novaflow.cueflow.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

private val MaterialType = Typography()

internal val CueTypography = MaterialType.copy(
    headlineLarge = MaterialType.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = MaterialType.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = MaterialType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = MaterialType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)
