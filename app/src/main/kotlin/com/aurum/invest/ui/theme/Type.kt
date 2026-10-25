package com.aurum.invest.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aurum.invest.R

/** Plus Jakarta Sans — the open stand-in for Toss Product Sans. */
val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold)
)

/**
 * Tabular figures everywhere: a column of prices only lines up, and a live
 * figure only holds still between ticks, when every digit is the same width.
 */
private const val TNUM = "tnum"

/**
 * The Toss scale. Nothing is smaller than 11sp — the old 10sp label was the
 * most-used style in the app and the hardest to read.
 */
val AurumTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = (-1).sp,
        fontFeatureSettings = TNUM
    ),
    displaySmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp,
        fontFeatureSettings = TNUM
    ),
    headlineMedium = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp,
        fontFeatureSettings = TNUM
    ),
    headlineSmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp,
        fontFeatureSettings = TNUM
    ),
    titleLarge = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp,
        fontFeatureSettings = TNUM
    ),
    titleMedium = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp,
        fontFeatureSettings = TNUM
    ),
    titleSmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TNUM
    ),
    bodyLarge = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp,
        fontFeatureSettings = TNUM
    ),
    bodyMedium = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 21.sp,
        fontFeatureSettings = TNUM
    ),
    bodySmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp, lineHeight = 18.sp,
        fontFeatureSettings = TNUM
    ),
    labelLarge = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TNUM
    ),
    labelMedium = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TNUM
    ),
    labelSmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, letterSpacing = 0.1.sp,
        fontFeatureSettings = TNUM
    )
)
