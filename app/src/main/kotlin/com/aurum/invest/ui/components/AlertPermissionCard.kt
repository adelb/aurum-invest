package com.aurum.invest.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurum.invest.core.Notify
import com.aurum.invest.ui.theme.AurumColors

/** Shared, lifecycle-aware notification opt-in used by every alerting engine. */
@Composable
fun AlertPermissionCard(
    enabledText: String,
    title: String,
    message: String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var settingsError by remember { mutableStateOf(false) }

    fun refreshPermission() {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermission()
    }

    if (enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.NotificationsActive,
                contentDescription = null,
                tint = AurumColors.gain,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = enabledText,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
        return
    }

    AurumCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        if (settingsError) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Android notification settings could not be opened on this device.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.loss
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Enable alerts",
            style = MaterialTheme.typography.labelLarge,
            color = AurumColors.gold,
            modifier = Modifier
                .clickable {
                    settingsError = false
                    val permissionGranted =
                        Build.VERSION.SDK_INT < 33 ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Notify.POST_PERMISSION
                            ) == PackageManager.PERMISSION_GRANTED
                    if (Build.VERSION.SDK_INT >= 33 && !permissionGranted) {
                        launcher.launch(Notify.POST_PERMISSION)
                    } else {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        } catch (_: ActivityNotFoundException) {
                            settingsError = true
                        } catch (_: SecurityException) {
                            settingsError = true
                        }
                    }
                }
                .padding(vertical = 4.dp)
        )
    }
}
