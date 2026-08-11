package com.aurum.invest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

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

/** Aurum is always dark — a deliberate premium-fintech night look. */
@Composable
fun AurumTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // theme is fixed; call keeps the API surface familiar
    MaterialTheme(
        colorScheme = AurumColorScheme,
        typography = AurumTypography,
        content = content
    )
}
