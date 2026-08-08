package com.aurum.invest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aurum.invest.ui.nav.AurumRoot
import com.aurum.invest.ui.theme.AurumTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            AurumTheme {
                AurumRoot()
            }
        }
    }
}
