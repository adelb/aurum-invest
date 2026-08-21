package com.aurum.invest.data.repo

import com.aurum.invest.analytics.TechniqueEvaluation
import com.aurum.invest.analytics.TechniqueEvaluator
import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import com.aurum.invest.data.model.Candle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one home of the cached 12-month technique evaluation. Both the analysis
 * screen and the portfolio review read the SAME key and the same freshness
 * rule, so a stock graded on one screen is already graded on the other.
 * Versioned key: a grade stored for an older board must never score this one.
 */
class EvaluationStore(private val cacheDao: CacheDao) {

    companion object {
        private const val KEY_PREFIX = "techeval:v6:"

        /** The back-test replays daily closes; a run stays valid for a session. */
        private const val MAX_AGE_MS = 6L * 3_600_000L
    }

    /**
     * Cached 12-month evaluation, recomputed off the main thread when stale.
     * Null when [candles] cannot back a complete replay — an expired grade is
     * never presented as if it measured the current board.
     */
    suspend fun get(symbol: String, candles: List<Candle>): TechniqueEvaluation? {
        val sym = symbol.trim().uppercase()
        if (sym.isEmpty() || candles.size < TechniqueEvaluator.MIN_CANDLES_FOR_FULL_REPLAY) {
            return null
        }
        val key = KEY_PREFIX + sym
        val cached = try {
            cacheDao.get(key)
        } catch (_: Exception) {
            null
        }
        if (cached != null && System.currentTimeMillis() - cached.updatedAt <= MAX_AGE_MS) {
            TechniqueEvaluator.fromJson(cached.json)
                ?.takeIf { TechniqueEvaluator.isComplete(it, sym) }
                ?.let { return it }
        }
        val fresh = withContext(Dispatchers.Default) {
            runCatching { TechniqueEvaluator.evaluate(sym, candles) }.getOrNull()
        } ?: return null
        try {
            cacheDao.put(
                CacheEntity(
                    key = key,
                    json = TechniqueEvaluator.toJson(fresh),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {
            // Cache write failure is non-fatal.
        }
        return fresh
    }
}
