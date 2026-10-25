package com.aurum.invest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val AurumColorScheme = darkColorScheme(
    primary = AurumColors.accent,
    onPrimary = AurumColors.text,
    primaryContainer = AurumColors.accentSoft,
    onPrimaryContainer = AurumColors.text,
    secondary = AurumColors.gold,
    onSecondary = AurumColors.bg,
    background = AurumColors.bg,
    onBackground = AurumColors.text,
    surface = AurumColors.surface,
    onSurface = AurumColors.text,
    surfaceVariant = AurumColors.surfaceHigh,
    onSurfaceVariant = AurumColors.textDim,
    surfaceContainer = AurumColors.surface,
    surfaceContainerHigh = AurumColors.surface,
    surfaceContainerHighest = AurumColors.surfaceHigh,
    surfaceContainerLow = AurumColors.surface,
    outline = AurumColors.surfaceTop,
    outlineVariant = AurumColors.hairline,
    error = AurumColors.loss,
    onError = AurumColors.text
)

/** Toss radii: chips 12, buttons 16, cards and sheets 20+. */
private val AurumShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Aurum is always dark — the Toss night theme. */
@Composable
fun AurumTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // theme is fixed; call keeps the API surface familiar
    MaterialTheme(
        colorScheme = AurumColorScheme,
        typography = AurumTypography,
        shapes = AurumShapes,
        content = content
    )
}
