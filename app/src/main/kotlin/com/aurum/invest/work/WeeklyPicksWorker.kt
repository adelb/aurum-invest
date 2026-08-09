package com.aurum.invest.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aurum.invest.AurumApp

/**
 * Weekly (Monday-morning) worker that recomputes the top-10 picks for the
 * current week. Success when a non-empty set of picks is stored; retry when
 * the computation produced nothing (e.g. network fully down).
 */
class WeeklyPicksWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AurumApp ?: return Result.success()
        val main = try {
            app.container.picks.recompute()
        } catch (_: Exception) {
            emptyList()
        }
        try {
            app.container.picks.recomputeBudget()
        } catch (_: Exception) {
            // budget picks are best-effort; the main list decides retry
        }
        try {
            app.container.wealth.recomputeIfConfigured()
        } catch (_: Exception) {
            // the wealth plan also refreshes on section open; best-effort here
        }
        return if (main.isEmpty()) Result.retry() else Result.success()
    }
}
