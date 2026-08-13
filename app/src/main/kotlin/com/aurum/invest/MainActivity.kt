package com.aurum.invest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.invest.ui.nav.AurumRoot
import com.aurum.invest.ui.theme.AurumTheme
import com.aurum.invest.ui.theme.ProvideAppLocale
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val settings = (application as AurumApp).container.settings
        setContent {
            val lang by settings.language.collectAsStateWithLifecycle(initialValue = "en")
            val locale = if (lang == "ar") Locale("ar") else Locale.ENGLISH
            ProvideAppLocale(locale) {
                AurumTheme {
                    AurumRoot()
                }
            }
        }
    }
}
