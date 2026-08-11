package com.aurum.invest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val AurumColorScheme = darkColorScheme(
    primary = AurumColors.gold,
    onPrimary = AurumColors.bg,
    primaryContainer = AurumColors.goldSoft,
    onPrimaryContainer = AurumColors.goldBright,
    secondary = AurumColors.goldBright,
    onSecondary = AurumColors.bg,
    background = AurumColors.bg,
    onBackground = AurumColors.text,
    surface = AurumColors.surface,
    onSurface = AurumColors.text,
    surfaceVariant = AurumColors.surfaceHigh,
    onSurfaceVariant = AurumColors.textDim,
    outline = AurumColors.hairline,
    outlineVariant = AurumColors.hairline,
    error = AurumColors.loss,
    onError = AurumColors.bg
)

/** Near-sharp corners everywhere — the ledger prints in rules, not bubbles. */
private val AurumShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

/** Aurum is always dark — unlit paper, brass rules, ledger ink. */
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
