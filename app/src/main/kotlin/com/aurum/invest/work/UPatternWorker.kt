package com.aurum.invest.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.UState
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Notify
import java.time.ZoneId

/**
 * The 15-minute in-session watcher behind the U-pattern alerts. Outside the
 * US regular session it returns immediately. Inside it, it makes sure
 * today's U list exists (the first run of the day computes it; fingerprints
 * cache across runs, so an interrupted first scan resumes where it stopped),
 * re-reads each pick's live state, and posts one notification per name the
 * moment it enters its buy zone — never twice for the same name on the same
 * day. Notifications stop at 15:30 ET: an entry signaled later has no room
 * left before the close.
 */
class UPatternWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AurumApp ?: return Result.success()
        val container = app.container
        return try {
            if (Dates.marketSessionNow() != Dates.MarketSession.REGULAR) {
                return Result.success()
            }
            val picks = container.picks.ensureUPattern()
            if (picks.isEmpty()) return Result.success()
            val updated = container.picks.refreshULive()

            val nowEt = java.time.ZonedDateTime.now(ZoneId.of("America/New_York"))
            val minuteEt = nowEt.hour * 60 + nowEt.minute
            if (minuteEt > 15 * 60 + 30) return Result.success()

            val notified = container.picks.uNotifiedToday()
            val fresh = updated.filter {
                it.state == UState.BUY_ZONE.name && it.symbol !in notified
            }
            if (fresh.isNotEmpty()) {
                Notify.uPatternBuyZone(applicationContext, fresh)
                container.picks.markUNotified(fresh.map { it.symbol }.toSet())
            }
            Result.success()
        } catch (_: Exception) {
            // Best-effort: the next 15-minute tick is the retry.
            Result.success()
        }
    }
}
