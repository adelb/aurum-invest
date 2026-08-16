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
import com.aurum.invest.analytics.PortfolioPerformance
import com.aurum.invest.analytics.PortfolioPerformanceEngine
import com.aurum.invest.analytics.PortfolioReview
import com.aurum.invest.analytics.SectorStrategy
import com.aurum.invest.analytics.SectorTrend
import com.aurum.invest.analytics.SectorTrends
import com.aurum.invest.analytics.UnverifiedHolding
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.PositionView
import com.aurum.invest.data.model.Quote
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
    private val portfolio: PortfolioRepository,
    /** Supplies the investor policy so every review is sized to its owner. */
    private val settings: SettingsRepository? = null,
    /** Immutable trail of emitted recommendations, for later outcome review. */
    private val adviceLog: AdviceLogRepository? = null,
    /** Cash ledger — part of the equity curve when the user tracks it. */
    private val cash: CashRepository? = null
) {

    companion object {
        private const val PULSE_KEY = "marketpulse:v2"
        private const val TRENDS_KEY = "sectortrends:v2"
        private const val FLOW_KEY = "moneyflow:v2"
        private const val REVIEW_KEY = "portfolioreview:v6"
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
     * the user no longer holds. Null when the book is empty or when nothing
     * at all could be measured (the fingerprint-matching stale copy, with its
     * honest computedAt stamp, is the last resort before that).
     */
    suspend fun getPortfolioReview(maxAgeMs: Long = 1_800_000L): PortfolioReview? {
        val snapshot = bookSnapshot() ?: return null
        if (snapshot.open.isEmpty()) return null
        // The policy is part of the review's identity: change your risk
        // profile and a cached review sized to the old one must not serve.
        val fp = snapshot.fingerprint + "|" + policyFingerprint()
        val cached = getCache(REVIEW_KEY)
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            cachedReview(cached.json, fp)?.let { return it }
        }
        return recomputePortfolioReview()
    }

    // ---- performance & risk (H2) -------------------------------------------

    /**
     * The reconstructed performance/risk read, cached 6 hours per exact
     * ledger. Null when the ledger is empty or too little could be measured.
     */
    suspend fun getPerformance(maxAgeMs: Long = 6L * 3_600_000L): PortfolioPerformance? {
        return try {
            val ordered = portfolio.orderedTransactionsNow()
            if (ordered.isEmpty()) return null
            val fp = portfolioFingerprint(
                PortfolioRepository.computePositions(ordered).filter { PortfolioRepository.isOpen(it) }
            ) + ":" + ordered.size
            val key = "perf:v1"
            val cached = getCache(key)
            val now = System.currentTimeMillis()
            if (cached != null && now - cached.updatedAt <= maxAgeMs) {
                try {
                    val o = JSONObject(cached.json)
                    if (o.optString("fp") == fp) {
                        PortfolioPerformance.fromJson(o.optString("perf"))?.let { return it }
                    }
                } catch (_: Exception) {
                }
            }

            val symbols = ordered.map { it.symbol.trim().uppercase() }.distinct()
            val candles = coroutineScope {
                symbols.chunked(4).flatMap { chunk ->
                    chunk.map { sym ->
                        async { sym to runCatching { market.getDailyCandles(sym, 400) }.getOrDefault(emptyList()) }
                    }.awaitAll()
                }
            }.toMap()
            val spy = market.getDailyCandles("SPY", 400)
            val cashEvents = cash?.let {
                runCatching { it.observeEvents().first() }.getOrDefault(emptyList())
            } ?: emptyList()
            val cashTracked = cashEvents.any { it.type == com.aurum.invest.data.db.CashType.DEPOSIT }

            val perf = PortfolioPerformanceEngine.compute(
                ordered = ordered,
                cashEvents = cashEvents,
                candlesBySymbol = candles,
                benchmark = spy,
                cashTracked = cashTracked
            ) ?: return null
            putCache(
                key,
                JSONObject().apply {
                    put("fp", fp)
                    put("perf", PortfolioPerformance.toJson(perf))
                }.toString()
            )
            perf
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun policyFingerprint(): String {
        val p = settings?.let { runCatching { it.investorProfile.first() }.getOrNull() }
            ?: InvestorProfile.DEFAULT
        return "${p.configured}:${p.horizon}:${p.riskTolerance}:${p.riskPerTradePct}:" +
            "${p.maxPositionPct}:${p.maxSectorPct}"
    }

    /** The stored review, only when it was computed from exactly this book. */
    private fun cachedReview(json: String, fingerprint: String): PortfolioReview? = try {
        val o = JSONObject(json)
        if (o.optString("fp") == fingerprint) {
            PortfolioReview.fromJson(o.optString("review"))
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    suspend fun recomputePortfolioReview(): PortfolioReview? {
        return try {
            val snapshot = bookSnapshot() ?: return null
            val open = snapshot.open
            if (open.isEmpty()) return null
            val quotes = market.getQuotes(open.map { it.symbol })
            val (views, unpriced) = pricedViews(open, quotes)
            val reviewFp = snapshot.fingerprint + "|" + policyFingerprint()
            if (views.isEmpty()) {
                // Nothing could be priced this run (network down?) — serve the
                // last review of exactly this book rather than a blank screen.
                return getCache(REVIEW_KEY)?.let { cachedReview(it.json, reviewFp) }
            }
            val sectors = market.getSectors(views.map { it.position.symbol })
            val flow = getMoneyFlow()
            val pulse = getMarketPulse()
            val book = com.aurum.invest.analytics.PortfolioLens.build(views, sectors)
            // The strategy provides the board-approved buy candidates that the
            // rebalance AND the grade engine's improvement actions draw from.
            val strategy =
                if (flow == null) null
                else try {
                    SectorStrategy(market, news).build(sectorTrendsCached(), book, 0.0, flow)
                } catch (_: Exception) {
                    null
                }
            val profile = settings?.let {
                runCatching { it.investorProfile.first() }.getOrNull()
            } ?: InvestorProfile.DEFAULT
            val review = PortfolioAdvisor(market, news, profile)
                .review(views, sectors, flow, strategy, pulse, unpriced)
            if (review != null) {
                putCache(
                    REVIEW_KEY,
                    JSONObject().apply {
                        put("fp", reviewFp)
                        put("review", PortfolioReview.toJson(review))
                    }.toString()
                )
                // Audit trail: every emitted verdict is recorded so its
                // outcome can be reviewed later. One row per symbol per day.
                review.verdicts.forEach { v ->
                    adviceLog?.log(
                        engine = "WEALTH",
                        symbol = v.symbol,
                        action = v.action.name,
                        priceAt = v.price,
                        score = v.techConfidence,
                        detail = v.headline
                    )
                }
            }
            review
                ?: getCache(REVIEW_KEY)?.let { cachedReview(it.json, reviewFp) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Prices every open position from the live quotes, falling back to the
     * latest daily close. Positions with no verifiable price at all come back
     * in the second list, named — never silently dropped.
     */
    private suspend fun pricedViews(
        positions: List<Position>,
        quotes: Map<String, Quote>
    ): Pair<List<PositionView>, List<UnverifiedHolding>> = coroutineScope {
        val results = positions.map { position ->
            async {
                val quote = quotes[position.symbol]?.takeIf { it.price > 0.0 } ?: try {
                    val candles = market.getDailyCandles(position.symbol, 7)
                    val latest = candles.lastOrNull()?.takeIf { it.close > 0.0 }
                    latest?.let {
                        Quote(
                            symbol = position.symbol,
                            price = it.close,
                            prevClose = candles.getOrNull(candles.lastIndex - 1)?.close
                                ?.takeIf { c -> c > 0.0 } ?: it.close,
                            shortName = position.symbol,
                            fetchedAt = it.ts,
                            lite = true
                        )
                    }
                } catch (_: Exception) {
                    null
                }
                if (quote == null) {
                    position to null
                } else {
                    position to PortfolioRepository.toView(position, quote)
                }
            }
        }.awaitAll()
        val views = results.mapNotNull { (_, view) -> view }
        val unpriced = results.filter { (_, view) -> view == null }.map { (position, _) ->
            UnverifiedHolding(
                symbol = position.symbol,
                shares = position.shares,
                reason = "no verifiable price from live quotes or recent daily candles"
            )
        }
        views to unpriced
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
