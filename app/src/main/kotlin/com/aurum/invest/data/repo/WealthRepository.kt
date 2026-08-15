package com.aurum.invest.data.repo

import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.MarketPulse
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.MoneyFlowEngine
import com.aurum.invest.analytics.MoneyFlowReport
import com.aurum.invest.analytics.NextSessionEngine
import com.aurum.invest.analytics.NextSessionReport
import com.aurum.invest.analytics.NextWeekPlan
import com.aurum.invest.analytics.NextWeekPlanner
import com.aurum.invest.analytics.PortfolioAdvisor
import com.aurum.invest.analytics.PortfolioReview
import com.aurum.invest.analytics.SectorStrategy
import com.aurum.invest.analytics.SectorTrend
import com.aurum.invest.analytics.SectorTrends
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Wealth section's data layer: the market pulse, the sector money-flow
 * report, the portfolio review, the sector-gap strategy, the next-session
 * report, and the Thursday→Monday next-week preview — each engine's output
 * cached under its own key with an honest freshness window. Never throws to
 * callers.
 */
class WealthRepository(
    private val cacheDao: CacheDao,
    private val market: MarketRepository,
    private val news: NewsRepository,
    private val portfolio: PortfolioRepository
) {

    companion object {
        private const val PULSE_KEY = "marketpulse"
        private const val TRENDS_KEY = "sectortrends"
        private const val FLOW_KEY = "moneyflow"
        private const val REVIEW_KEY = "portfolioreview"
        private const val NEXT_SESSION_KEY = "nextsession"
        private const val NS_NOTIFIED_PREFIX = "nextsession:notified:"
        private const val PREVIEW_KEY_PREFIX = "wealthplan:next:"
    }

    /** Open positions as symbol -> cost dollars, so engines can size around the book. */
    suspend fun heldMap(): Map<String, Double> = try {
        portfolio.positionsNow()
            .filter { PortfolioRepository.isOpen(it) }
            .associate { it.symbol to it.shares * it.avgCost }
    } catch (_: Exception) {
        emptyMap()
    }

    // ---- shared sector scan -------------------------------------------------

    /**
     * One sector scan per half hour, shared by every consumer — the strategy
     * card, the next-week preview, and the Stocks browse's rotation read.
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
            putCache(TRENDS_KEY, SectorTrends.toJson(fresh).toString())
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

    /** The shared sector scan for screens outside Wealth (the sector browse). */
    suspend fun sectorTrends(): List<SectorTrend> = sectorTrendsCached()

    // ---- money flow ---------------------------------------------------------

    /**
     * The standalone money-flow report: where the money and the volume are
     * measurably moving, sector by sector. Cached 30 minutes; the stale copy
     * serves as fallback when the market is unreachable.
     */
    suspend fun getMoneyFlow(maxAgeMs: Long = 1_800_000L): MoneyFlowReport? {
        val cached = getCache(FLOW_KEY)
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            MoneyFlowEngine.fromJson(cached.json)?.let { return it }
        }
        return recomputeMoneyFlow() ?: cached?.let { MoneyFlowEngine.fromJson(it.json) }
    }

    suspend fun recomputeMoneyFlow(): MoneyFlowReport? {
        return try {
            val report = MoneyFlowEngine(market, news).compute()
            if (report != null) {
                putCache(FLOW_KEY, MoneyFlowEngine.toJson(report))
            }
            report
        } catch (_: Exception) {
            null
        }
    }

    // ---- portfolio review ---------------------------------------------------

    /**
     * The portfolio-evaluation engine's answer for the CURRENT book. The
     * cached copy carries a fingerprint of the positions it was computed
     * from — any trade invalidates it, so the review never describes a book
     * the user no longer holds. Null when the book is empty.
     */
    suspend fun getPortfolioReview(maxAgeMs: Long = 1_800_000L): PortfolioReview? {
        val fp = bookFingerprint()
        if (fp.isEmpty()) return null
        val cached = getCache(REVIEW_KEY)
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            try {
                val o = JSONObject(cached.json)
                if (o.optString("fp") == fp) {
                    PortfolioReview.fromJson(o.optString("review"))?.let { return it }
                }
            } catch (_: Exception) {
                // fall through to recompute
            }
        }
        return recomputePortfolioReview()
    }

    suspend fun recomputePortfolioReview(): PortfolioReview? {
        return try {
            val open = portfolio.positionsNow().filter { PortfolioRepository.isOpen(it) }
            if (open.isEmpty()) return null
            val quotes = market.getQuotes(open.map { it.symbol })
            val views = open.map { PortfolioRepository.toView(it, quotes[it.symbol]) }
            val sectors = market.getSectors(open.map { it.symbol })
            val flow = getMoneyFlow()
            val book = com.aurum.invest.analytics.PortfolioLens.build(views, sectors)
            val strategy = try {
                SectorStrategy(market, news).build(sectorTrendsCached(), book, 0.0, flow)
            } catch (_: Exception) {
                null
            }
            val review = PortfolioAdvisor(market, news).review(views, sectors, flow, strategy)
            if (review != null) {
                putCache(
                    REVIEW_KEY,
                    JSONObject().apply {
                        put("fp", bookFingerprint())
                        put("review", PortfolioReview.toJson(review))
                    }.toString()
                )
            }
            review
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun bookFingerprint(): String = try {
        portfolio.positionsNow()
            .filter { PortfolioRepository.isOpen(it) }
            .sortedBy { it.symbol }
            .joinToString("|") { "${it.symbol}:${it.shares}" }
    } catch (_: Exception) {
        ""
    }

    // ---- market pulse -------------------------------------------------------

    /**
     * The whole-market rating for the Wealth header. Served from cache while
     * fresh (30 min); recomputed otherwise, with the stale copy as fallback.
     */
    suspend fun getMarketPulse(maxAgeMs: Long = 1_800_000L): MarketRating? {
        val cached = getCache(PULSE_KEY)
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
                putCache(PULSE_KEY, MarketPulse.toJson(rating))
            }
            rating
        } catch (_: Exception) {
            null
        }
    }

    // ---- weekly sector strategy --------------------------------------------

    /**
     * The week's sector answer for THIS book: which themes the money is
     * entering, which the portfolio is missing, and the strongest stock from
     * each theme's full shelf. The split is expressed in percentages — the
     * user decides the dollars. Returns null only when the market is
     * unreachable.
     */
    suspend fun getStrategy(book: BookContext): WeeklyStrategy? =
        try {
            val trends = sectorTrendsCached()
            val flow = getMoneyFlow()
            SectorStrategy(market, news).build(trends, book, 0.0, flow)
        } catch (_: Exception) {
            null
        }

    // ---- next session -------------------------------------------------------

    /**
     * The next-session report: 10 measured picks with the extreme-probability
     * alert flags. Cached 20 minutes so the in-app card and the background
     * watcher share one scan.
     */
    suspend fun getNextSession(maxAgeMs: Long = 1_200_000L): NextSessionReport? {
        val cached = getCache(NEXT_SESSION_KEY)
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            NextSessionReport.fromJson(cached.json)?.let { return it }
        }
        return recomputeNextSession() ?: cached?.let { NextSessionReport.fromJson(it.json) }
    }

    suspend fun recomputeNextSession(): NextSessionReport? {
        return try {
            val report = NextSessionEngine(market).compute(held = heldMap())
            if (report != null) {
                putCache(NEXT_SESSION_KEY, NextSessionReport.toJson(report))
            }
            report
        } catch (_: Exception) {
            null
        }
    }

    /** Symbols already alerted for the current ET day. */
    suspend fun nsNotifiedToday(): Set<String> {
        val key = NS_NOTIFIED_PREFIX + etToday()
        val cached = getCache(key) ?: return emptySet()
        return try {
            val arr = JSONArray(cached.json)
            buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Records that [symbols] were alerted today, so no pick alerts twice. */
    suspend fun markNsNotified(symbols: Set<String>) {
        if (symbols.isEmpty()) return
        val all = nsNotifiedToday() + symbols
        putCache(NS_NOTIFIED_PREFIX + etToday(), JSONArray(all.toList()).toString())
    }

    private fun etToday(): String =
        java.time.LocalDate.now(ZoneId.of("America/New_York")).toString()

    // ---- next-week preview (Thursday → Monday) ------------------------------

    private fun previewKey(weekStartIso: String) = PREVIEW_KEY_PREFIX + weekStartIso

    /** The stored preview for the week [Dates.nextWeekPreview] points at, if any. */
    suspend fun getNextWeek(): NextWeekPlan? = try {
        val (_, weekIso) = Dates.nextWeekPreview()
        getCache(previewKey(weekIso))?.let { NextWeekPlanner.fromJson(it.json) }
    } catch (_: Exception) {
        null
    }

    /**
     * The next-week preview, freshened at most every [maxAgeMs] (2 h default)
     * while the Thursday→Monday window is open. Outside the window it serves
     * whatever is stored and computes nothing.
     */
    suspend fun ensureNextWeek(maxAgeMs: Long = 7_200_000L): NextWeekPlan? {
        val (active, weekIso) = Dates.nextWeekPreview()
        if (!active) return getNextWeek()
        val cached = getCache(previewKey(weekIso))
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            NextWeekPlanner.fromJson(cached.json)?.let { return it }
        }
        return recomputeNextWeek() ?: cached?.let { NextWeekPlanner.fromJson(it.json) }
    }

    /** Rebuilds the preview from live data and stores it under its week key. */
    suspend fun recomputeNextWeek(): NextWeekPlan? {
        val (_, weekIso) = Dates.nextWeekPreview()
        return try {
            val held = heldMap()
            val plan = NextWeekPlanner(market, news).build(
                weekStart = weekIso,
                // Next week's reference buying power is the invested book at
                // cost; with no book the split is percentages only.
                investable = held.values.sum(),
                held = held,
                sectorTrends = sectorTrendsCached().ifEmpty { null },
                pulse = getMarketPulse(),
                flow = getMoneyFlow()
            )
            if (plan != null) {
                putCache(previewKey(weekIso), NextWeekPlanner.toJson(plan))
            }
            plan
        } catch (_: Exception) {
            null
        }
    }

    // ---- weekly worker entry ------------------------------------------------

    /** Monday-morning refresh: the pulse and the flow report start the week fresh. */
    suspend fun recomputeWeekly() {
        try {
            recomputeMarketPulse()
        } catch (_: Exception) {
            // best-effort
        }
        try {
            recomputeMoneyFlow()
        } catch (_: Exception) {
            // best-effort
        }
    }

    // ---- cache plumbing -----------------------------------------------------

    private suspend fun getCache(key: String): CacheEntity? = try {
        cacheDao.get(key)
    } catch (_: Exception) {
        null
    }

    private suspend fun putCache(key: String, json: String) {
        try {
            cacheDao.put(CacheEntity(key = key, json = json, updatedAt = System.currentTimeMillis()))
        } catch (_: Exception) {
            // cache write failure is non-fatal
        }
    }
}
