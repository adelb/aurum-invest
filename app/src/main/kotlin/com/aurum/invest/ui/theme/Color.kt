package com.aurum.invest.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

object AurumColors {
    val bg = Color(0xFF070B14)
    val surface = Color(0xFF0E1524)
    val surfaceHigh = Color(0xFF141D31)
    val hairline = Color(0xFF1E2A44)

    val gold = Color(0xFFE9B95C)
    val goldBright = Color(0xFFF7D98E)
    val goldDeep = Color(0xFFB98A2F)

    val text = Color(0xFFF2F5FA)
    val textDim = Color(0xFF8B96AC)

    val gain = Color(0xFF34D399)
    val loss = Color(0xFFF87171)
    val info = Color(0xFF7DA2E8)
    val infoSoft = Color(0x267DA2E8)
    val gainSoft = Color(0x2634D399)
    val lossSoft = Color(0x26F87171)
    val goldSoft = Color(0x26E9B95C)

    /** Brand accent as a Brush. Flat gold — Aurum does not use gradients. */
    fun goldGradient(width: Float = 600f): Brush = SolidColor(gold)

    /** Card ground as a Brush. Flat surface fill. */
    val cardWash: Brush = SolidColor(surface)

    fun deltaColor(value: Double): Color = if (value >= 0) gain else loss
}
