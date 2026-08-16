package com.aurum.invest.data.repo

import com.aurum.invest.data.db.AdviceLogDao
import com.aurum.invest.data.db.AdviceLogEntity
import com.aurum.invest.core.Dates
import kotlinx.coroutines.flow.Flow

/**
 * Immutable record of what the app recommended and when (H6/M6), so every
 * recommendation can be reviewed against what actually happened. One row per
 * (engine, symbol, action, day); re-renders of the same advice do not
 * duplicate. Never throws to callers.
 */
class AdviceLogRepository(
    private val dao: AdviceLogDao,
    private val modelVersion: String
) {

    suspend fun log(
        engine: String,
        symbol: String,
        action: String,
        priceAt: Double,
        score: Int = -1,
        detail: String = ""
    ) {
        try {
            val day = Dates.todayIso()
            if (dao.countForDay(engine, symbol, action, day) > 0) return
            dao.insert(
                AdviceLogEntity(
                    ts = System.currentTimeMillis(),
                    day = day,
                    engine = engine,
                    symbol = symbol,
                    action = action,
                    priceAt = priceAt,
                    score = score,
                    modelVersion = modelVersion,
                    detail = detail.take(200)
                )
            )
        } catch (_: Exception) {
            // Logging must never break the engine that called it.
        }
    }

    fun observeRecent(limit: Int = 300): Flow<List<AdviceLogEntity>> = dao.observeRecent(limit)

    suspend fun purgeOlderThan(days: Int = 400): Int = try {
        dao.purgeOlderThan(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)
    } catch (_: Exception) {
        0
    }
}
