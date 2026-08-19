package com.aurum.invest.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aurum.invest.AurumApp
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Notify

/**
 * The next-session watcher behind the extreme-probability alerts. It runs on
 * the 30-minute WorkManager cadence but acts only when a fresh read can say
 * something about the NEXT session: after the close (16:00–20:00 ET), when
 * the day's full candle exists, and pre-market (7:00–9:30 ET), when the
 * latest extended prints can confirm or kill a setup. Each run refreshes the
 * next-session report and pushes one notification per pick that cleared
 * every alert gate — never twice for the same name on the same ET day.
 */
class NextSessionWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AurumApp ?: return Result.success()
        val container = app.container
        return try {
            if (!inAlertWindow()) return Result.success()

            val report = container.wealth.getNextSession() ?: return Result.success()
            val alerts = report.alerts
            if (alerts.isEmpty()) return Result.success()

            val notified = container.wealth.nsNotifiedToday()
            val fresh = alerts.filter { it.symbol !in notified }
            if (fresh.isNotEmpty()) {
                val delivered = Notify.nextSessionAlert(applicationContext, fresh)
                container.wealth.markNsNotified(delivered)
            }
            Result.success()
        } catch (_: Exception) {
            // Best-effort: the next tick is the retry.
            Result.success()
        }
    }

    /** After the close or pre-open, ET weekdays — when a next-session read is fresh. */
    private fun inAlertWindow(): Boolean {
        val session = Dates.marketSessionNow()
        if (session == Dates.MarketSession.WEEKEND) return false
        val nowEt = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"))
        val minute = nowEt.hour * 60 + nowEt.minute
        return when (session) {
            Dates.MarketSession.POST -> true
            Dates.MarketSession.PRE -> minute >= 7 * 60
            else -> false
        }
    }
}
