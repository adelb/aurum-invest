package com.aurum.invest.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aurum.invest.AurumApp
import com.aurum.invest.core.Fmt
import com.aurum.invest.core.Notify
import com.aurum.invest.data.db.AlertDirection

/**
 * Checks the user's price alerts (M1) every 15 minutes: fetches quotes for
 * the watched symbols, fires a notification for each crossed level, and
 * deactivates it (alerts fire once; the trigger stays as history).
 */
class AlertsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val container = (applicationContext as? AurumApp)?.container
                ?: return Result.success()
            val active = container.alerts.activeAlerts()
            if (active.isEmpty()) return Result.success()

            val quotes = container.market.getQuotes(
                active.map { it.symbol }.distinct(),
                maxAgeMs = 120_000L
            )
            val fired = container.alerts.markTriggered(active, quotes)
            fired.forEach { alert ->
                val verb = if (alert.direction == AlertDirection.ABOVE) "rose above" else "fell below"
                val note = if (alert.note.isNotBlank()) " (${alert.note})" else ""
                Notify.priceAlert(
                    applicationContext,
                    alert.symbol,
                    "${alert.symbol} $verb ${Fmt.money(alert.threshold)}$note — " +
                        "now ${Fmt.money(alert.priceAtTrigger ?: alert.threshold)}."
                )
            }
            Result.success()
        } catch (_: Exception) {
            // Best-effort: a failed sweep just waits for the next cadence.
            Result.success()
        }
    }
}
