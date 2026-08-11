package com.aurum.invest.data.repo

import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Per-symbol sell targets: the profit percentage the user wants out of a
 * position. Stored in the generic key/value cache table (no schema change),
 * one row per symbol, and never expired — this is user intent, not fetched
 * data, so it stays until the user changes or clears it.
 */
class TargetsRepository(private val cacheDao: CacheDao) {

    /** The target profit percentage for [symbol], or null when none is set. */
    suspend fun getTarget(symbol: String): Double? = try {
        cacheDao.get(key(symbol))?.json?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }
    } catch (_: Exception) {
        null
    }

    suspend fun setTarget(symbol: String, pct: Double) {
        if (pct <= 0.0) {
            clearTarget(symbol)
            return
        }
        try {
            cacheDao.put(
                CacheEntity(
                    key = key(symbol),
                    json = pct.toString(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {
            // A failed write just means the flag is not set; nothing else breaks.
        }
    }

    suspend fun clearTarget(symbol: String) {
        try {
            // The cache table has no delete-by-key; an empty value reads as unset.
            cacheDao.put(
                CacheEntity(
                    key = key(symbol),
                    json = "",
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {
        }
    }

    /** Targets for many symbols at once; symbols without one are absent. */
    suspend fun getTargets(symbols: List<String>): Map<String, Double> {
        if (symbols.isEmpty()) return emptyMap()
        return coroutineScope {
            symbols.distinct()
                .map { s -> async { s to getTarget(s) } }
                .awaitAll()
                .mapNotNull { (s, pct) -> pct?.let { s to it } }
                .toMap()
        }
    }

    private fun key(symbol: String) = "selltarget:${symbol.trim().uppercase()}"

    companion object {
        /**
         * The sell price that realises [pct] profit on a position bought at
         * [avgCost]. Fees are not modelled here — the user's own fee is
         * already inside avgCost for buys recorded with fees.
         */
        fun sellPriceFor(avgCost: Double, pct: Double): Double = avgCost * (1.0 + pct / 100.0)
    }
}
