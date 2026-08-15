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
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.PositionView
import com.aurum.invest.data.model.Quote
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        private const val PULSE_KEY = "marketpulse:v2"
        private const val TRENDS_KEY = "sectortrends:v2"
        private const val FLOW_KEY = "moneyflow:v2"
        private const val REVIEW_KEY = "portfolioreview:v2"
        private const val NEXT_SESSION_KEY = "nextsession:v2"
        private const val NS_NOTIFIED_PREFIX = "nextsession:notified:"
        private const val PREVIEW_KEY_PREFIX = "wealthplan:next:v2:"

        /** Stable identity for every ledger input that can change portfolio advice. */
        fun portfolioFingerprint(open: List<Position>): String =
            if (open.isEmpty()) {
                "empty"
            } else {
                open.sortedBy { it.symbol }.joinToString("|") {
                    "${it.symbol}:${it.shares.toBits()}:${it.avgCost.toBits()}:" +
                        "${it.investedCost.toBits()}:${it.realizedPl.toBits()}"
                }
            }
    }

    private data class BookSnapshot(
        val open: List<Position>,
        val held: Map<String, Double>,
        val fingerprint: String,
        val portfolioAvailable: Boolean
    )

    private suspend fun bookSnapshot(): BookSnapshot? = try {
        val open = portfolio.positionsNow().filter { PortfolioRepository.isOpen(it) }
        BookSnapshot(
            open = open,
            held = open.associate { it.symbol to it.shares * it.avgCost },
            fingerprint = portfolioFingerprint(open),
            portfolioAvailable = true
        )
    } catch (_: Exception) {
        null
    }

    /**
     * Market-wide engines still run when Room cannot read the ledger. Their
     * output is explicitly marked as lacking portfolio context rather than
     * silently suppressing scans or alerts.
     */
    private suspend fun marketBookSnapshot(): BookSnapshot =
        bookSnapshot() ?: BookSnapshot(
            open = emptyList(),
            held = emptyMap(),
            fingerprint = "portfolio-unavailable",
            portfolioAvailable = false
        )

    /** Open positions as symbol -> cost dollars, so engines can size around the book. */
    suspend fun heldMap(): Map<String, Double> = bookSnapshot()?.held.orEmpty()

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
        val snapshot = bookSnapshot() ?: return null
        if (snapshot.open.isEmpty()) return null
        val fp = snapshot.fingerprint
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
            val snapshot = bookSnapshot() ?: return null
            val open = snapshot.open
            if (open.isEmpty()) return null
            val quotes = market.getQuotes(open.map { it.symbol })
            val views = pricedViews(open, quotes)
            // The advisor answers for the whole book or not at all. A partial
            // review could tell the user to rebalance around a missing holding.
            if (views.size != open.size) return null
            val sectors = market.getSectors(open.map { it.symbol })
            val flow = getMoneyFlow()
            val book = com.aurum.invest.analytics.PortfolioLens.build(views, sectors)
            val strategy =
                if (flow == null) null
                else try {
                    SectorStrategy(market, news).build(sectorTrendsCached(), book, 0.0, flow)
                } catch (_: Exception) {
                    null
                }
            val review = PortfolioAdvisor(market, news).review(views, sectors, flow, strategy)
            if (review != null) {
                putCache(
                    REVIEW_KEY,
                    JSONObject().apply {
                        put("fp", snapshot.fingerprint)
                        put("review", PortfolioReview.toJson(review))
                    }.toString()
                )
            }
            review
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun pricedViews(
        positions: List<Position>,
        quotes: Map<String, Quote>
    ): List<PositionView> = coroutineScope {
        positions.map { position ->
            async {
                val quote = quotes[position.symbol]?.takeIf { it.price > 0.0 } ?: try {
                    val candles = market.getDailyCandles(position.symbol, 7)
                    val latest = candles.lastOrNull() ?: return@async null
                    if (latest.close <= 0.0) return@async null
                    Quote(
                        symbol = position.symbol,
                        price = latest.close,
                        prevClose = candles.getOrNull(candles.lastIndex - 1)?.close
                            ?.takeIf { it > 0.0 } ?: latest.close,
                        shortName = position.symbol,
                        fetchedAt = latest.ts,
                        lite = true
                    )
                } catch (_: Exception) {
                    null
                } ?: return@async null
                PortfolioRepository.toView(position, quote)
            }
        }.awaitAll().filterNotNull()
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
            val flow = getMoneyFlow() ?: return null
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
        val snapshot = marketBookSnapshot()
        val cached = getCache(NEXT_SESSION_KEY)
        val now = System.currentTimeMillis()
        val base =
            if (cached != null && now - cached.updatedAt <= maxAgeMs) {
                NextSessionReport.fromJson(cached.json)
            } else {
                recomputeNextSessionBase()
                    ?: cached?.let { NextSessionReport.fromJson(it.json) }
            }
        return base?.let { withPortfolioContext(it, snapshot) }
    }

    suspend fun recomputeNextSession(): NextSessionReport? {
        val snapshot = marketBookSnapshot()
        val base = recomputeNextSessionBase() ?: return null
        return withPortfolioContext(base, snapshot)
    }

    private fun withPortfolioContext(
        report: NextSessionReport,
        snapshot: BookSnapshot
    ): NextSessionReport {
        val tagged = NextSessionEngine.withPortfolio(report, snapshot.held)
        return if (snapshot.portfolioAvailable) {
            tagged
        } else {
            tagged.copy(
                notes = tagged.notes +
                    "Portfolio context is temporarily unavailable; held-position tags are omitted."
            )
        }
    }

    private suspend fun recomputeNextSessionBase(): NextSessionReport? {
        return try {
            val report = NextSessionEngine(market).compute()
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

    private fun previewCacheJson(fingerprint: String, plan: NextWeekPlan): String =
        JSONObject().apply {
            put("fp", fingerprint)
            put("plan", NextWeekPlanner.toJson(plan))
        }.toString()

    private fun previewFromCache(json: String, fingerprint: String): NextWeekPlan? = try {
        val root = JSONObject(json)
        if (root.optString("fp") != fingerprint) null
        else NextWeekPlanner.fromJson(root.optString("plan"))
    } catch (_: Exception) {
        null
    }

    /** The stored preview for the week [Dates.nextWeekPreview] points at, if any. */
    suspend fun getNextWeek(): NextWeekPlan? = try {
        val snapshot = marketBookSnapshot()
        val (_, weekIso) = Dates.nextWeekPreview()
        val cached = getCache(previewKey(weekIso))?.let {
            previewFromCache(it.json, snapshot.fingerprint)
        }
        cached ?: if (!snapshot.portfolioAvailable) recomputeNextWeek() else null
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
        val snapshot = marketBookSnapshot()
        val cached = getCache(previewKey(weekIso))
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            previewFromCache(cached.json, snapshot.fingerprint)?.let { return it }
        }
        return recomputeNextWeek()
            ?: cached?.let { previewFromCache(it.json, snapshot.fingerprint) }
    }

    /** Rebuilds the preview from live data and stores it under its week key. */
    suspend fun recomputeNextWeek(): NextWeekPlan? {
        val (_, weekIso) = Dates.nextWeekPreview()
        return try {
            val snapshot = marketBookSnapshot()
            val held = snapshot.held
            val base = NextWeekPlanner(market, news).build(
                weekStart = weekIso,
                // Next week's reference buying power is the invested book at
                // cost; with no book the split is percentages only.
                investable = held.values.sum(),
                held = held,
                sectorTrends = sectorTrendsCached().ifEmpty { null },
                pulse = getMarketPulse(),
                flow = getMoneyFlow()
            )
            val plan =
                if (base != null && !snapshot.portfolioAvailable) {
                    base.copy(
                        portfolioNote =
                            "Portfolio context is temporarily unavailable; this is a market-only " +
                                "plan with percentage allocations and no held-position overlay."
                    )
                } else {
                    base
                }
            // Do not let a transient ledger outage overwrite the last
            // portfolio-aware plan. The degraded market-only result is still
            // returned to this caller.
            if (plan != null && snapshot.portfolioAvailable) {
                putCache(previewKey(weekIso), previewCacheJson(snapshot.fingerprint, plan))
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
