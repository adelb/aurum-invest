package com.aurum.invest.data.repo

import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.MarketPulse
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.NextWeekPlan
import com.aurum.invest.analytics.NextWeekPlanner
import com.aurum.invest.analytics.SectorStrategy
import com.aurum.invest.analytics.SectorTrend
import com.aurum.invest.analytics.SectorTrends
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.analytics.WealthPlan
import com.aurum.invest.analytics.WealthPlanner
import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import kotlinx.coroutines.flow.first
import org.json.JSONArray

/**
 * Persistence for the Wealth section: the user's base amount + 4-month profit
 * target live in DataStore (via [SettingsRepository]); the computed weekly
 * plan lives in the JSON cache under one key. A plan is stale when its
 * weekStart is no longer the current week — [ensurePlan] recomputes it then.
 * The Thursday→Monday next-week preview lives under its own week-suffixed key
 * so it never evicts the live plan. Never throws to callers.
 */
class WealthRepository(
    private val cacheDao: CacheDao,
    private val market: MarketRepository,
    private val news: NewsRepository,
    private val settings: SettingsRepository,
    private val portfolio: PortfolioRepository
) {

    companion object {
        private const val PLAN_KEY = "wealthplan"
        private const val PULSE_KEY = "marketpulse"
        private const val TRENDS_KEY = "sectortrends"
        private const val PREVIEW_KEY_PREFIX = "wealthplan:next:"
    }

    /** Open positions as symbol -> cost dollars, so plans can size around the book. */
    private suspend fun heldMap(): Map<String, Double> = try {
        portfolio.positionsNow()
            .filter { PortfolioRepository.isOpen(it) }
            .associate { it.symbol to it.shares * it.avgCost }
    } catch (_: Exception) {
        emptyMap()
    }

    /**
     * One sector scan per half hour, shared by the plan, the strategy card,
     * and the next-week preview — previously each consumer re-ran the whole
     * 16-ETF sweep for itself.
     */
    private suspend fun sectorTrendsCached(maxAgeMs: Long = 1_800_000L): List<SectorTrend> {
        val now = System.currentTimeMillis()
        val cached = try {
            cacheDao.get(TRENDS_KEY)
        } catch (_: Exception) {
            null
        }
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            try {
                val parsed = SectorTrends.fromJson(JSONArray(cached.json))
                if (parsed.isNotEmpty()) return parsed
            } catch (_: Exception) {
                // fall through to recompute
            }
        }
        val fresh = try {
            SectorTrends(market, news).compute()
        } catch (_: Exception) {
            emptyList()
        }
        if (fresh.isNotEmpty()) {
            try {
                cacheDao.put(
                    CacheEntity(
                        key = TRENDS_KEY,
                        json = SectorTrends.toJson(fresh).toString(),
                        updatedAt = now
                    )
                )
            } catch (_: Exception) {
                // cache write failure is non-fatal
            }
            return fresh
        }
        return cached?.let {
            try {
                SectorTrends.fromJson(JSONArray(it.json))
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
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
                .build(
                    Dates.currentWeekStartIso(), inputs.first, inputs.second,
                    held = heldMap(),
                    sectorTrends = sectorTrendsCached().ifEmpty { null }
                )
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

    // ---- weekly sector strategy --------------------------------------------

    /**
     * The week's sector answer for THIS book: which trending themes the
     * portfolio is missing, the stock to use for each, and how to split
     * [investable] across them. The expensive inputs (ETF trends, member
     * candles) are already cached by their own repositories, so repeat calls
     * are cheap. Returns null only when the market is unreachable.
     */
    suspend fun getStrategy(book: BookContext, investable: Double): WeeklyStrategy? =
        try {
            val trends = sectorTrendsCached()
            SectorStrategy(market, news).build(trends, book, investable)
        } catch (_: Exception) {
            null
        }

    // ---- next-week preview (Thursday → Monday) ------------------------------

    private fun previewKey(weekStartIso: String) = PREVIEW_KEY_PREFIX + weekStartIso

    /** The stored preview for the week [Dates.nextWeekPreview] points at, if any. */
    suspend fun getNextWeek(): NextWeekPlan? = try {
        val (_, weekIso) = Dates.nextWeekPreview()
        cacheDao.get(previewKey(weekIso))?.let { NextWeekPlanner.fromJson(it.json) }
    } catch (_: Exception) {
        null
    }

    /**
     * The next-week preview, freshened at most every [maxAgeMs] (2 h default)
     * while the Thursday→Monday window is open. Outside the window it serves
     * whatever is stored and computes nothing. Null when Wealth is unconfigured.
     */
    suspend fun ensureNextWeek(maxAgeMs: Long = 7_200_000L): NextWeekPlan? {
        val (active, weekIso) = Dates.nextWeekPreview()
        if (!active) return getNextWeek()
        val cached = try {
            cacheDao.get(previewKey(weekIso))
        } catch (_: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            NextWeekPlanner.fromJson(cached.json)?.let { return it }
        }
        return recomputeNextWeek() ?: cached?.let { NextWeekPlanner.fromJson(it.json) }
    }

    /** Rebuilds the preview from live data and stores it under its week key. */
    suspend fun recomputeNextWeek(): NextWeekPlan? {
        val inputs = getInputs() ?: return null
        val (_, weekIso) = Dates.nextWeekPreview()
        return try {
            val plan = NextWeekPlanner(market, news).build(
                weekStart = weekIso,
                investable = inputs.first,
                held = heldMap(),
                sectorTrends = sectorTrendsCached().ifEmpty { null },
                pulse = getMarketPulse()
            )
            if (plan != null) {
                cacheDao.put(
                    CacheEntity(
                        key = previewKey(weekIso),
                        json = NextWeekPlanner.toJson(plan),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            plan
        } catch (_: Exception) {
            null
        }
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
