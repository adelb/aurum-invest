package com.aurum.invest.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aurum.invest.AurumApp
import com.aurum.invest.data.repo.PortfolioRepository

/**
 * Periodic (30 min) cache warmer: refreshes quotes for every open position and
 * every watched symbol so the UI opens onto fresh numbers. Best-effort — a
 * partially failed refresh still succeeds; only a total failure asks for retry.
 */
class RefreshWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AurumApp ?: return Result.success()
        val container = app.container
        return try {
            val positionSymbols = try {
                container.portfolio.positionsNow()
                    .filter { PortfolioRepository.isOpen(it) }
                    .map { it.symbol }
            } catch (_: Exception) {
                emptyList()
            }
            val watchSymbols = try {
                container.watch.getAll().map { it.symbol }
            } catch (_: Exception) {
                emptyList()
            }
            val symbols = (positionSymbols + watchSymbols)
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (symbols.isEmpty()) return Result.success()

            // maxAgeMs = 0 forces a network fetch, warming the shared cache.
            val quotes = container.market.getQuotes(symbols, maxAgeMs = 0L)
            if (quotes.isEmpty()) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
