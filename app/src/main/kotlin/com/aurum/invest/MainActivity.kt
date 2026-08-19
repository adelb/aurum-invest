package com.aurum.invest

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.aurum.invest.ui.nav.AurumRoot
import com.aurum.invest.ui.theme.AurumColors
import com.aurum.invest.ui.theme.AurumTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : FragmentActivity() {

    /** True once the user has passed the (optional) app lock this process. */
    private var unlocked by mutableStateOf(false)
    private var lockRequired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // One synchronous read at launch: the lock either gates this process
        // or it doesn't — an async read would flash the portfolio first.
        lockRequired = runCatching {
            runBlocking { (application as AurumApp).container.settings.appLock.first() }
        }.getOrDefault(false)
        // Only enforce when the device actually has a credential to check.
        if (lockRequired && !canAuthenticate()) lockRequired = false
        unlocked = !lockRequired

        setContent {
            AurumTheme {
                if (unlocked) {
                    AurumRoot()
                } else {
                    LockedScreen(onUnlock = { showLockPrompt() })
                }
            }
        }
        if (!unlocked) showLockPrompt()
    }

    private fun canAuthenticate(): Boolean =
        BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun showLockPrompt() {
        try {
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        unlocked = true
                    }
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Aurum")
                    .setSubtitle("Your portfolio is protected by your device screen lock")
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build()
            )
        } catch (_: Exception) {
            // A broken prompt must not brick the app the user owns.
            unlocked = true
        }
    }

    companion object {
        private const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}

@androidx.compose.runtime.Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Aurum is locked",
            style = MaterialTheme.typography.headlineMedium,
            color = AurumColors.text
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Unlock with your device screen lock",
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onUnlock,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AurumColors.gold,
                contentColor = AurumColors.bg
            )
        ) { Text("Unlock") }
    }
}
