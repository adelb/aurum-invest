package com.aurum.invest.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * The Toss (TDS) night palette. Three jobs, three colours, never mixed:
 * blue is for what you can DO (buttons, selection, links), gold is for gold
 * and for targets, green/red are only ever gain and loss.
 */
object AurumColors {
    /** The screen ground. */
    val bg = Color(0xFF17171C)
    /** Layer 1 — cards, sheets, the nav bar. */
    val surface = Color(0xFF202027)
    /** Layer 2 — inputs, tracks, chips on a card. */
    val surfaceHigh = Color(0xFF2C2C35)
    /** Layer 3 — the selected thumb of a segmented control. */
    val surfaceTop = Color(0xFF3C3C47)
    /** Hairlines between rows. */
    val hairline = Color(0xFF2C2C35)
    /** The 16dp band that separates one section of a screen from the next. */
    val separator = Color(0xFF101013)

    /** Actions: buttons, the selected tab, links. */
    val accent = Color(0xFF3182F6)
    val accentSoft = Color(0x293182F6)

    /** Gold itself, and the user's own targets. */
    val gold = Color(0xFFFFC342)
    val goldBright = Color(0xFFFFD87A)
    val goldDeep = Color(0xFFC9932A)
    val goldSoft = Color(0x29FFC342)
    /** The warm ground of a gold summary strip. */
    val goldGround = Color(0xFF3A3020)

    val text = Color(0xFFFFFFFF)
    /** Second-tier text: leads and supporting sentences. */
    val textSub = Color(0xFFB0B8C1)
    val textDim = Color(0xFF8B95A1)
    /** Hints and footnotes. */
    val textFaint = Color(0xFF6B7684)

    /** Western convention: green up, red down. */
    val gain = Color(0xFF1FC27A)
    val loss = Color(0xFFFF5C6A)
    val info = Color(0xFF64A8FF)
    val infoSoft = Color(0x2964A8FF)
    val gainSoft = Color(0x291FC27A)
    val lossSoft = Color(0x29FF5C6A)

    /**
     * The wallet stack's two bright cards: stocks on a white card, gold on a
     * gold one. Their ink is dark, and gain/loss on them take deeper shades
     * that still read on a light ground.
     */
    val cardWhite = Color(0xFFF2F4F6)
    val cardWhiteChip = Color(0xFFE5E8EB)
    val inkDark = Color(0xFF17171C)
    val inkDarkDim = Color(0xFF4E5968)
    val gainOnLight = Color(0xFF0A7D4B)
    val lossOnLight = Color(0xFFD22030)
    val goldInk = Color(0xFF2B1D00)
    val goldInkDim = Color(0xFF6B4E00)
    val gainOnGold = Color(0xFF0B6B3E)
    val lossOnGold = Color(0xFFB3261E)

    /**
     * Allocation series. Four calm, distinguishable tones instead of a
     * rainbow; a fifth slice and beyond falls back to the quiet greys, since
     * past four the bar is read by its labels, not its colours.
     */
    val allocation = listOf(
        accent,
        gold,
        Color(0xFF7FB8FF),
        Color(0xFF9AA4B2),
        Color(0xFF5A6472),
        Color(0xFF444B57)
    )

    /** Brand accent as a Brush. Flat — the design uses no gradients. */
    fun goldGradient(width: Float = 600f): Brush = SolidColor(gold)

    /** Card ground as a Brush. Flat surface fill. */
    val cardWash: Brush = SolidColor(surface)

    fun deltaColor(value: Double): Color = if (value >= 0) gain else loss
}
