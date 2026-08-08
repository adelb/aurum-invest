package com.aurum.invest.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aurum.invest.core.Dates
import java.util.concurrent.TimeUnit

/**
 * Registers Aurum's periodic background work. Idempotent — uses unique work
 * names with [ExistingPeriodicWorkPolicy.KEEP], so calling on every app start
 * never reschedules or duplicates existing work.
 */
object Schedules {

    private const val REFRESH_WORK_NAME = "aurum-refresh"
    private const val WEEKLY_PICKS_WORK_NAME = "aurum-weekly-picks"

    fun ensure(context: Context) {
        try {
            val workManager = WorkManager.getInstance(context.applicationContext)
            val connected = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val refresh = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(connected)
                .build()
            workManager.enqueueUniquePeriodicWork(
                REFRESH_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                refresh
            )

            val weeklyPicks = PeriodicWorkRequestBuilder<WeeklyPicksWorker>(7, TimeUnit.DAYS)
                .setConstraints(connected)
                .setInitialDelay(Dates.nextMondayMorningDelayMs(), TimeUnit.MILLISECONDS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WEEKLY_PICKS_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                weeklyPicks
            )
        } catch (_: Exception) {
            // Scheduling must never crash app startup; workers are best-effort.
        }
    }
}
