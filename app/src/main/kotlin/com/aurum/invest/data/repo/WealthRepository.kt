package com.aurum.invest.data.repo

import com.aurum.invest.analytics.MarketPulse
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.WealthPlan
import com.aurum.invest.analytics.WealthPlanner
import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import kotlinx.coroutines.flow.first

/**
 * Persistence for the Wealth section: the user's base amount + 4-month profit
 * target live in DataStore (via [SettingsRepository]); the computed weekly
 * plan lives in the JSON cache under one key. A plan is stale when its
 * weekStart is no longer the current week — [ensurePlan] recomputes it then.
 * Never throws to callers.
 */
class WealthRepository(
    private val cacheDao: CacheDao,
    private val market: MarketRepository,
    private val news: NewsRepository,
    private val settings: SettingsRepository
) {

    companion object {
        private const val PLAN_KEY = "wealthplan"
        private const val PULSE_KEY = "marketpulse"
    }

    /** (base, target) or null when the user has not set the inputs yet. */
    suspend fun getInputs(): Pair<Double, Double>? = try {
        val base = settings.wealthBase.first()
        val target = settings.wealthTarget.first()
        if (base > 0.0 && target > 0.0) base to target else null
    } catch (_: Exception) {
        null
    }

    suspend fun setInputs(base: Double, target: Double) {
        try {
            settings.setWealthInputs(base, target)
        } catch (_: Exception) {
            // DataStore failure is non-fatal; the caller recomputes anyway.
        }
    }

    /** The stored plan, whatever week it belongs to. */
    suspend fun getPlan(): WealthPlan? = try {
        cacheDao.get(PLAN_KEY)?.let { WealthPlanner.fromJson(it.json) }
    } catch (_: Exception) {
        null
    }

    /**
     * The plan for THIS week: stored one when fresh, recomputed when missing,
     * stale, or built for different inputs. Null when inputs are unset or the
     * market is unreachable.
     */
    suspend fun ensurePlan(): WealthPlan? {
        val inputs = getInputs() ?: return null
        val existing = getPlan()
        if (existing != null &&
            existing.weekStart == Dates.currentWeekStartIso() &&
            existing.baseAmount == inputs.first &&
            existing.targetProfit == inputs.second
        ) {
            return existing
        }
        return recompute()
    }

    /** Recomputes this week's plan from fresh market data and stores it. */
    suspend fun recompute(): WealthPlan? {
        val inputs = getInputs() ?: return null
        return try {
            val plan = WealthPlanner(market, news)
                .build(Dates.currentWeekStartIso(), inputs.first, inputs.second)
            if (plan != null) {
                cacheDao.put(
                    CacheEntity(
                        key = PLAN_KEY,
                        json = WealthPlanner.toJson(plan),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            plan ?: getPlan()
        } catch (_: Exception) {
            getPlan()
        }
    }

    /** Weekly-worker entry point: recompute only when the user configured Wealth. */
    suspend fun recomputeIfConfigured(): WealthPlan? {
        getInputs() ?: return null
        return recompute()
    }

    // ---- market pulse -------------------------------------------------------

    /**
     * The whole-market rating for the Wealth header. Served from cache while
     * fresh (30 min); recomputed otherwise, with the stale copy as fallback.
     */
    suspend fun getMarketPulse(maxAgeMs: Long = 1_800_000L): MarketRating? {
        val cached = try {
            cacheDao.get(PULSE_KEY)
        } catch (_: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            MarketPulse.fromJson(cached.json)?.let { return it }
        }
        return recomputeMarketPulse() ?: cached?.let { MarketPulse.fromJson(it.json) }
    }

    /** Recomputes the market rating from live data and stores it. */
    suspend fun recomputeMarketPulse(): MarketRating? {
        return try {
            val rating = MarketPulse(market).compute(Dates.todayIso())
            if (rating != null) {
                cacheDao.put(
                    CacheEntity(
                        key = PULSE_KEY,
                        json = MarketPulse.toJson(rating),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            rating
        } catch (_: Exception) {
            null
        }
    }
}
