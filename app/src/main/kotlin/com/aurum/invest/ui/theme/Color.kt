package com.aurum.invest.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * The Ledger palette. Warm ink instead of the default fintech navy: the
 * ground reads as unlit paper, rules are faded sepia, and the accent is
 * brass rather than bright gold. Gains sit in a ledger green, losses in
 * oxide red — both muted, both legible against the ink.
 */
object AurumColors {
    val bg = Color(0xFF0B0908)
    val surface = Color(0xFF141109)
    val surfaceHigh = Color(0xFF1D1810)
    val hairline = Color(0xFF2C2517)

    val gold = Color(0xFFC8A25E)
    val goldBright = Color(0xFFE9D6A7)
    val goldDeep = Color(0xFF8A6D38)

    val text = Color(0xFFEDE5D4)
    val textDim = Color(0xFF938970)

    val gain = Color(0xFF83B489)
    val loss = Color(0xFFCC7160)
    val info = Color(0xFF92A7B4)
    val infoSoft = Color(0x2292A7B4)
    val gainSoft = Color(0x2283B489)
    val lossSoft = Color(0x22CC7160)
    val goldSoft = Color(0x22C8A25E)

    /** Flat series colors for the portfolio allocation bar; cycled when holdings exceed it. */
    val allocation = listOf(
        gold,
        info,
        gain,
        Color(0xFFA98BB4),
        Color(0xFF6FAE9E),
        Color(0xFFC98A55),
        Color(0xFFBA7E92),
        Color(0xFF9BA36B)
    )

    /** Brand accent as a Brush. Flat brass — Aurum does not use gradients. */
    fun goldGradient(width: Float = 600f): Brush = SolidColor(gold)

    /** Card ground as a Brush. Flat surface fill. */
    val cardWash: Brush = SolidColor(surface)

    fun deltaColor(value: Double): Color = if (value >= 0) gain else loss
}
