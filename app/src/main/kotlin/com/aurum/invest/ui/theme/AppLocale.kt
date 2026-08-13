package com.aurum.invest.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/** The active UI locale, exposed to any composable that needs it directly. */
val LocalAppLocale = staticCompositionLocalOf<Locale> { Locale.ENGLISH }

/**
 * Overrides the Compose tree with a specific locale so `stringResource(...)`
 * and layout direction respond to a language toggle at runtime, without an
 * activity recreate. Works purely inside Compose — no AppCompat migration.
 */
@Composable
fun ProvideAppLocale(locale: Locale, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current

    val localizedContext = remember(locale, baseContext) {
        val cfg = Configuration(baseConfig).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        baseContext.createConfigurationContext(cfg)
    }
    val localizedConfig = remember(locale, baseConfig) {
        Configuration(baseConfig).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
    }
    val direction = if (locale.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfig,
        LocalLayoutDirection provides direction,
        LocalAppLocale provides locale
    ) {
        content()
    }
}
