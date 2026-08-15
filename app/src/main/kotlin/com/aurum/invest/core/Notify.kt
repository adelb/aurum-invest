package com.aurum.invest.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aurum.invest.MainActivity
import com.aurum.invest.R
import com.aurum.invest.analytics.NextSessionPick
import com.aurum.invest.analytics.UPick
import java.util.Locale

/**
 * Local notifications. One channel per feature; posting is always
 * best-effort — with alerts disabled or the 13+ permission not granted,
 * the call silently does nothing (the in-app card still shows the state).
 */
object Notify {

    private const val U_CHANNEL_ID = "aurum_upattern"
    private const val NS_CHANNEL_ID = "aurum_nextsession"

    /** The Android 13+ runtime permission the alerts card asks for. */
    const val POST_PERMISSION = "android.permission.POST_NOTIFICATIONS"

    private fun ensureUChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        if (manager.getNotificationChannel(U_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                U_CHANNEL_ID,
                "U-pattern buy zones",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "A tracked U-pattern stock confirmed its intraday turn"
            }
        )
    }

    /** One notification per pick that just entered its buy zone. */
    fun uPatternBuyZone(context: Context, picks: List<UPick>) {
        if (picks.isEmpty()) return
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        ensureUChannel(context)
        picks.forEach { p ->
            val open = PendingIntent.getActivity(
                context,
                p.symbol.hashCode(),
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val body = buildString {
                append(p.stateNote)
                val entry = p.entry
                val stop = p.stop
                val target = p.target
                if (entry != null && stop != null && target != null) {
                    append(
                        String.format(
                            Locale.US,
                            " Entry ≈ $%.2f · stop $%.2f · target $%.2f.",
                            entry, stop, target
                        )
                    )
                }
                if (p.sellWindow.isNotBlank()) {
                    append(" Sell window ${p.sellWindow}.")
                }
            }
            val notification = NotificationCompat.Builder(context, U_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_u)
                .setContentTitle("${p.symbol} entered its U buy zone")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try {
                compat.notify(p.symbol.hashCode(), notification)
            } catch (_: SecurityException) {
                // Permission revoked mid-flight — the in-app state still shows it.
            }
        }
    }

    private fun ensureNsChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        if (manager.getNotificationChannel(NS_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                NS_CHANNEL_ID,
                "Next-session alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "A stock cleared every extreme-probability gate for the next session"
            }
        )
    }

    /**
     * One notification per next-session pick that cleared every
     * extreme-probability gate. The body states the measured facts — the
     * analog record, the board, the levels — never a promise.
     */
    fun nextSessionAlert(context: Context, picks: List<NextSessionPick>) {
        if (picks.isEmpty()) return
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        ensureNsChannel(context)
        picks.forEach { p ->
            val open = PendingIntent.getActivity(
                context,
                ("ns" + p.symbol).hashCode(),
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val body = buildString {
                append(
                    String.format(
                        Locale.US,
                        "Score %d/100. After %d similar days it closed higher %d%% of the time " +
                            "(avg %+.1f%%). %d of %d techniques bullish.",
                        p.score, p.analogDays, p.probUpPct, p.avgNextDayPct,
                        p.techBullish, p.techTotal
                    )
                )
                append(
                    String.format(
                        Locale.US,
                        " Entry ≈ $%.2f · target $%.2f · stop $%.2f.",
                        p.entry, p.target, p.stop
                    )
                )
                append(" Measured history, not a promise.")
            }
            val notification = NotificationCompat.Builder(context, NS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_u)
                .setContentTitle("${p.symbol} set up for the next session")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try {
                compat.notify(("ns" + p.symbol).hashCode(), notification)
            } catch (_: SecurityException) {
                // Permission revoked mid-flight — the in-app card still shows it.
            }
        }
    }
}
