package com.aurum.invest.data.repo

import com.aurum.invest.analytics.ActionKind
import com.aurum.invest.analytics.EngineCall
import com.aurum.invest.analytics.EngineRecord
import com.aurum.invest.analytics.EngineRecordReport
import com.aurum.invest.analytics.ManagementAction
import com.aurum.invest.analytics.NextSessionReport
import com.aurum.invest.data.db.EngineCallDao
import com.aurum.invest.data.db.EngineCallEntity

/**
 * The engines' self-scoring ledger: every ADD / TRIM / EXIT the wealth engine
 * issues and every next-session pick is logged at its live price, then scored
 * against the real closes 5 and 20 sessions later. Calls are never edited
 * (beyond filling their outcomes) and never deleted — a record that can be
 * pruned isn't a record. Never throws to callers.
 */
class RecordRepository(
    private val dao: EngineCallDao,
    private val market: MarketRepository
) {

    companion object {
        /** One ADD/TRIM/EXIT per symbol per two weeks — the engine repeats
         * itself every recompute; the record logs the CALL, not the echo. */
        private const val ACTION_DEDUPE_MS = 14L * 86_400_000L

        /** One pick log per symbol per day (20h guards timezone edges). */
        private const val PICK_DEDUPE_MS = 20L * 3_600_000L

        /** Scoring fetches per run are capped; the rest score next run. */
        private const val MAX_SCORE_SYMBOLS_PER_RUN = 25
    }

    /** Logs the wealth engine's buy/sell-side actions, deduplicated. */
    suspend fun logActions(actions: List<ManagementAction>, prices: Map<String, Double>) {
        val now = System.currentTimeMillis()
        for (action in actions) {
            val kind = when (action.kind) {
                ActionKind.ADD -> EngineRecord.KIND_ADD
                ActionKind.TRIM -> EngineRecord.KIND_TRIM
                ActionKind.EXIT -> EngineRecord.KIND_EXIT
                else -> continue
            }
            val symbol = action.symbol.trim().uppercase()
            if (symbol.isEmpty()) continue
            // No live price, no log — a call graded from a guessed baseline
            // would corrupt the record it exists to keep honest.
            val price = prices[symbol] ?: continue
            if (price <= 0.0) continue
            try {
                if (dao.countSince(kind, symbol, now - ACTION_DEDUPE_MS) > 0) continue
                dao.insert(
                    EngineCallEntity(
                        ts = now,
                        kind = kind,
                        symbol = symbol,
                        refPrice = price,
                        note = action.headline
                    )
                )
            } catch (_: Exception) {
                // A failed log loses one data point, never the feature.
            }
        }
    }

    /** Logs the next-session engine's picks, one per symbol per day. */
    suspend fun logPicks(report: NextSessionReport) {
        for (pick in report.picks) {
            val symbol = pick.symbol.trim().uppercase()
            if (symbol.isEmpty() || pick.price <= 0.0) continue
            try {
                if (dao.countSince(
                        EngineRecord.KIND_PICK, symbol,
                        report.computedAt - PICK_DEDUPE_MS
                    ) > 0
                ) continue
                dao.insert(
                    EngineCallEntity(
                        ts = report.computedAt,
                        kind = EngineRecord.KIND_PICK,
                        symbol = symbol,
                        refPrice = pick.price,
                        note = pick.reason
                    )
                )
            } catch (_: Exception) {
                // Non-fatal, same as above.
            }
        }
    }

    /**
     * Fills forward outcomes for every call whose sessions have since played
     * out. Candles come from the shared cached store, so a run costs at most
     * one fetch per distinct unscored symbol (capped per run). Returns how
     * many calls gained an outcome.
     */
    suspend fun scorePending(): Int {
        val pending = try {
            dao.unscored()
        } catch (_: Exception) {
            return 0
        }
        if (pending.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var scored = 0
        val bySymbol = pending.groupBy { it.symbol }.entries.take(MAX_SCORE_SYMBOLS_PER_RUN)
        for ((symbol, calls) in bySymbol) {
            val oldestAgeDays =
                ((now - calls.minOf { it.ts }) / 86_400_000L).toInt() + 40
            val candles = try {
                market.getDailyCandles(symbol, oldestAgeDays.coerceIn(60, 730))
            } catch (_: Exception) {
                emptyList()
            }
            if (candles.isEmpty()) continue
            for (call in calls) {
                // The bars strictly after the call — the sessions it aimed at.
                // A series that doesn't reach back to the call can't grade it.
                if (candles.first().ts > call.ts + 5 * 86_400_000L) continue
                val after = candles.filter { it.ts > call.ts }
                var updated = call
                if (updated.fwd5Pct == null && after.size >= 5) {
                    updated = updated.copy(
                        fwd5Pct = (after[4].close - call.refPrice) / call.refPrice * 100.0
                    )
                }
                if (updated.fwd20Pct == null && after.size >= 20) {
                    updated = updated.copy(
                        fwd20Pct = (after[19].close - call.refPrice) / call.refPrice * 100.0
                    )
                }
                if (updated !== call) {
                    try {
                        dao.update(updated)
                        scored++
                    } catch (_: Exception) {
                        // Next run retries.
                    }
                }
            }
        }
        return scored
    }

    /** The graded report card over every call ever logged. */
    suspend fun getRecord(): EngineRecordReport? = try {
        EngineRecord.grade(
            dao.all().map {
                EngineCall(
                    kind = it.kind,
                    symbol = it.symbol,
                    ts = it.ts,
                    refPrice = it.refPrice,
                    fwd5Pct = it.fwd5Pct,
                    fwd20Pct = it.fwd20Pct
                )
            }
        )
    } catch (_: Exception) {
        null
    }
}
