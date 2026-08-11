package com.aurum.invest.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aurum.invest.R

/**
 * The Ledger type system — three voices with strict roles:
 *  - Spectral (serif): display and headlines only. The hero money figure and
 *    screen titles carry the app's editorial identity.
 *  - IBM Plex Sans: titles, body, labels — the working text.
 *  - IBM Plex Mono: small data captions (timestamps, ratios, axis labels) and
 *    anything that should read like a terminal print-out.
 * Numeric styles enable tabular figures ("tnum") so columns of money align.
 */
val Spectral = FontFamily(
    Font(R.font.spectral_medium, FontWeight.Medium),
    Font(R.font.spectral_semibold, FontWeight.SemiBold)
)

val PlexSans = FontFamily(
    Font(R.font.plexsans_regular, FontWeight.Normal),
    Font(R.font.plexsans_medium, FontWeight.Medium),
    Font(R.font.plexsans_semibold, FontWeight.SemiBold)
)

val PlexMono = FontFamily(
    Font(R.font.plexmono_regular, FontWeight.Normal),
    Font(R.font.plexmono_medium, FontWeight.Medium),
    Font(R.font.plexmono_semibold, FontWeight.SemiBold)
)

/** Kept for source compatibility with early modules; now the working sans. */
val Inter = PlexSans

private const val TABULAR = "tnum"

val AurumTypography = Typography(
    // Serif display — the hero money figure.
    displayLarge = TextStyle(
        fontFamily = Spectral, fontWeight = FontWeight.SemiBold,
        fontSize = 42.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR
    ),
    displaySmall = TextStyle(
        fontFamily = Spectral, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR
    ),
    // Serif headlines — screen titles.
    headlineMedium = TextStyle(
        fontFamily = Spectral, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Spectral, fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp, letterSpacing = 0.sp
    ),
    // Working sans — prices and row titles, tabular so columns align.
    titleMedium = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR
    ),
    titleSmall = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR
    ),
    bodyLarge = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR
    ),
    labelMedium = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR
    ),
    // Mono captions — the terminal voice for small data prints.
    labelSmall = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.Normal,
        fontSize = 10.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR
    )
)
