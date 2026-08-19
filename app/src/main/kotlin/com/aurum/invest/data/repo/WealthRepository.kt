package com.aurum.invest.data.repo

import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.MarketPulse
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.SectorStrategy
import com.aurum.invest.analytics.SectorTrends
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * The Wealth section's data layer: the market pulse, the shared sector scan,
 * the weekly strategy, the deployment plan, and the wealth engine's full
 * evaluation — no goal inputs, no target profits. Never throws to callers.
 */
class WealthRepository(
    private val cacheDao: CacheDao,
    private val market: MarketRepository,
    private val news: NewsRepository,
    private val settings: SettingsRepository,
    /** Today's entry/power boards — optional context for the next-session scan. */
    private val picks: PicksRepository? = null,
    /** The self-scoring call ledger; every action and pick is logged through it. */
    private val record: RecordRepository? = null
) {

    companion object {
        // v2: the VIX read gained its 5-session drift.
        private const val PULSE_KEY = "marketpulse:v2"
        private const val NEXT_SESSION_KEY = "nextsession:v4"
        private const val PERFORMANCE_KEY = "performance:v1"
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

    // ---- shared sector scan -------------------------------------------------

    /**
     * One sector scan per half hour, shared by every consumer — the strategy,
     * the Stocks browse's rotation read, and the deployment plan. Cached in
     * the JSON store; the stale copy serves when the market is unreachable.
     */
    suspend fun sectorTrends(maxAgeMs: Long = 1_800_000L): List<com.aurum.invest.analytics.SectorTrend> {
        val key = "sectortrends:v1"
        val now = System.currentTimeMillis()
        val cached = try {
            cacheDao.get(key)
        } catch (_: Exception) {
            null
        }
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            try {
                val parsed = SectorTrends.fromJson(org.json.JSONArray(cached.json))
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
                        key = key,
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
                SectorTrends.fromJson(org.json.JSONArray(it.json))
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
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
            SectorStrategy(market, news).build(sectorTrends(), book, investable)
        } catch (_: Exception) {
            null
        }

    // ---- next session -------------------------------------------------------

    /**
     * The next-session report: measured picks with analog-history follow-
     * through, replayed against each name's own past days. Cached 20 minutes;
     * the stored copy serves as fallback when the market is unreachable.
     * Held-position tags come from the ledger at compute time.
     */
    suspend fun getNextSession(
        portfolio: PortfolioRepository,
        maxAgeMs: Long = 1_200_000L
    ): com.aurum.invest.analytics.NextSessionReport? {
        val cached = try {
            cacheDao.get(NEXT_SESSION_KEY)
        } catch (_: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            com.aurum.invest.analytics.NextSessionReport.fromJson(cached.json)?.let { return it }
        }
        return recomputeNextSession(portfolio)
            ?: cached?.let { com.aurum.invest.analytics.NextSessionReport.fromJson(it.json) }
    }

    /** Re-runs the full scan: screens, analog replays, board reads. */
    suspend fun recomputeNextSession(
        portfolio: PortfolioRepository
    ): com.aurum.invest.analytics.NextSessionReport? {
        return try {
            val held = runCatching {
                portfolio.positionsNow()
                    .filter { PortfolioRepository.isOpen(it) }
                    .associate { it.symbol to it.investedCost }
            }.getOrDefault(emptyMap())
            // STORED day boards only — reading them must never trigger the
            // market-wide scans from the Wealth tab; absent context is
            // reported by the engine, not silently faked.
            val dayScans = com.aurum.invest.analytics.DayScanContext.of(
                entries = runCatching { picks?.getEntries() ?: emptyList() }
                    .getOrDefault(emptyList()),
                power = runCatching { picks?.getPower() ?: emptyList() }
                    .getOrDefault(emptyList())
            )
            val report = com.aurum.invest.analytics.NextSessionEngine(market, news)
                .compute(held = held, dayScans = dayScans)
            if (report != null) {
                cacheDao.put(
                    CacheEntity(
                        key = NEXT_SESSION_KEY,
                        json = com.aurum.invest.analytics.NextSessionReport.toJson(report),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                // Every pick goes on the permanent record, priced as issued.
                runCatching { record?.logPicks(report) }
            }
            report
        } catch (_: Exception) {
            null
        }
    }

    // ---- the wealth engine --------------------------------------------------

    /**
     * Gathers everything [com.aurum.invest.analytics.WealthEngine] reasons
     * over — priced holdings, a year of candles each (served from the
     * superset store), sectors, the benchmark, the VIX, the wallet, the
     * profile, and the closed-sell record — then runs the pure evaluation.
     * Null only when even the ledger could not be read.
     */
    suspend fun getWealthReport(
        wallet: WalletRepository?,
        portfolio: PortfolioRepository
    ): com.aurum.invest.analytics.WealthReport? = try {
        coroutineScope {
            val ordered = portfolio.orderedTransactionsNow()
            val open = PortfolioRepository.computePositions(ordered)
                .filter { PortfolioRepository.isOpen(it) }
            val quotes = try {
                market.getQuotes(open.map { it.symbol })
            } catch (_: Exception) {
                emptyMap()
            }
            val views = open.map { PortfolioRepository.toView(it, quotes[it.symbol]) }
            val candles = open.map { p ->
                async {
                    p.symbol to runCatching { market.getDailyCandles(p.symbol, 365) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().toMap()
            val spy = runCatching { market.getDailyCandles("SPY", 365) }
                .getOrDefault(emptyList())
            val sectors = runCatching { market.getSectors(open.map { it.symbol }) }
                .getOrDefault(emptyMap())
            val earnings = runCatching { market.getEarningsDates(open.map { it.symbol }) }
                .getOrDefault(emptyMap())
            val pulse = runCatching { getMarketPulse() }.getOrNull()
            val profile = runCatching { settings.investorProfile.first() }
                .getOrDefault(InvestorProfile.DEFAULT)
            val walletState = wallet?.let { w ->
                val configured = runCatching { w.configured.first() }.getOrDefault(false)
                if (!configured) {
                    null
                } else {
                    val total = runCatching { w.total.first() }.getOrDefault(0.0)
                    val summary = PortfolioRepository.summarize(
                        views,
                        PortfolioRepository.computePositions(ordered)
                    )
                    WalletState.of(total, true, summary)
                }
            }
            // Every closed sell's realized outcome, chronological — the raw
            // material of the Wilson-graded trade record.
            val sellOutcomes = buildList {
                val running = HashMap<String, Pair<Double, Double>>() // shares, avg
                for (tx in ordered) {
                    val sym = tx.symbol.trim().uppercase()
                    val (shares, avg) = running[sym] ?: (0.0 to 0.0)
                    when {
                        tx.side == PortfolioRepository.SIDE_SPLIT ->
                            if (tx.shares > 0.0 && shares > 0.0) {
                                running[sym] = (shares * tx.shares) to (avg / tx.shares)
                            }
                        tx.side == com.aurum.invest.data.model.TradeSide.BUY.name -> {
                            val newShares = shares + tx.shares
                            val newAvg =
                                if (newShares > 0.0) {
                                    (shares * avg + tx.shares * tx.price + tx.fees) / newShares
                                } else avg
                            running[sym] = newShares to newAvg
                        }
                        else -> {
                            val qty = minOf(tx.shares, shares)
                            if (qty > 0.0) {
                                add(tx.plOverride ?: (qty * (tx.price - avg) - tx.fees))
                                running[sym] = (shares - qty) to avg
                            }
                        }
                    }
                }
            }
            val report = com.aurum.invest.analytics.WealthEngine.evaluate(
                com.aurum.invest.analytics.WealthInputs(
                    wallet = walletState,
                    views = views,
                    candles = candles,
                    sectors = sectors,
                    spy = spy,
                    vix = pulse?.vix,
                    profile = profile,
                    sellOutcomes = sellOutcomes,
                    earnings = earnings
                )
            )
            // The buy/sell-side actions go on the permanent record too.
            runCatching {
                record?.logActions(
                    report.actions,
                    views.mapNotNull { v ->
                        v.quote?.price?.let { v.position.symbol to it }
                    }.toMap()
                )
            }
            report
        }
    } catch (_: Exception) {
        null
    }

    // ---- liquidity deployment ----------------------------------------------

    /**
     * The liquidity-management answer for THIS wallet and THIS book: how much
     * of the uninvested cash to deploy, into which trendy sectors, into which
     * stocks, and how much into each — sized under the investor's own caps.
     * Pure math over the cached strategy scan; null only when even the scan
     * could not be attempted.
     */
    suspend fun getDeploymentPlan(
        liquidity: Double,
        book: BookContext
    ): com.aurum.invest.analytics.DeploymentPlan? = try {
        val strategy = getStrategy(book, liquidity)
        val profile = runCatching { settings.investorProfile.first() }
            .getOrDefault(InvestorProfile.DEFAULT)
        val marketNote = runCatching { getMarketPulse() }.getOrNull()?.headline ?: ""
        // Earnings dates for every candidate name — the blackout gate's input.
        val candidates = strategy?.gaps.orEmpty()
            .flatMap { gap -> gap.picks.map { it.symbol } }
            .distinct()
        val earnings = runCatching { market.getEarningsDates(candidates) }
            .getOrDefault(emptyMap())
        com.aurum.invest.analytics.LiquidityPlanner.build(
            liquidity = liquidity,
            book = book,
            strategy = strategy,
            profile = profile,
            marketNote = marketNote,
            earnings = earnings
        )
    } catch (_: Exception) {
        null
    }

    // ---- the performance verdict --------------------------------------------

    /**
     * The verdict on the user's ACTUAL trading — time-weighted return vs SPY,
     * the same-flows SPY alternative, and the whole-ledger money-weighted
     * rate. Served from cache while fresh AND while the ledger is unchanged
     * (its fingerprint rides inside the cached JSON, so a new trade recomputes
     * immediately); the stale copy serves when the market is unreachable.
     */
    suspend fun getPerformance(
        portfolio: PortfolioRepository,
        maxAgeMs: Long = 21_600_000L
    ): com.aurum.invest.analytics.PerformanceReport? = try {
        val ordered = portfolio.orderedTransactionsNow()
        if (ordered.isEmpty()) {
            null
        } else {
            val fp = ledgerFingerprint(ordered)
            val cached = try {
                cacheDao.get(PERFORMANCE_KEY)
            } catch (_: Exception) {
                null
            }
            val now = System.currentTimeMillis()
            val cachedReport = cached?.let { entry ->
                try {
                    val o = org.json.JSONObject(entry.json)
                    if (o.optString("fp") == fp) {
                        com.aurum.invest.analytics.PerformanceEngine.fromJson(
                            o.optString("report")
                        )
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
            if (cachedReport != null && now - cached.updatedAt <= maxAgeMs) {
                cachedReport
            } else {
                computePerformance(ordered, fp) ?: cachedReport
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Forces the full recompute (pull-to-refresh). */
    suspend fun recomputePerformance(
        portfolio: PortfolioRepository
    ): com.aurum.invest.analytics.PerformanceReport? = try {
        val ordered = portfolio.orderedTransactionsNow()
        if (ordered.isEmpty()) null
        else computePerformance(ordered, ledgerFingerprint(ordered))
    } catch (_: Exception) {
        null
    }

    private suspend fun computePerformance(
        ordered: List<com.aurum.invest.data.db.TransactionEntity>,
        fp: String
    ): com.aurum.invest.analytics.PerformanceReport? = try {
        coroutineScope {
            val trades = ordered.map {
                com.aurum.invest.analytics.LedgerTrade(
                    ts = it.ts,
                    symbol = it.symbol.trim().uppercase(),
                    side = it.side,
                    shares = it.shares,
                    price = it.price,
                    fees = it.fees
                )
            }
            val daysNeeded =
                ((System.currentTimeMillis() - ordered.minOf { it.ts }) / 86_400_000L)
                    .toInt() + 30
            val symbols = trades.map { it.symbol }.distinct()
            val candles = symbols.map { s ->
                async {
                    s to runCatching { market.getDailyCandles(s, daysNeeded) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().toMap()
            val spy = runCatching { market.getDailyCandles("SPY", daysNeeded) }
                .getOrDefault(emptyList())
            val open = PortfolioRepository.computePositions(ordered)
                .filter { PortfolioRepository.isOpen(it) }
            val quotes = runCatching { market.getQuotes(open.map { it.symbol }) }
                .getOrDefault(emptyMap())
                .mapValues { it.value.price }
            val report = com.aurum.invest.analytics.PerformanceEngine.evaluate(
                trades = trades,
                candles = candles,
                spy = spy,
                quotes = quotes
            )
            if (report != null) {
                cacheDao.put(
                    CacheEntity(
                        key = PERFORMANCE_KEY,
                        json = org.json.JSONObject().apply {
                            put("fp", fp)
                            put(
                                "report",
                                com.aurum.invest.analytics.PerformanceEngine.toJson(report)
                            )
                        }.toString(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            report
        }
    } catch (_: Exception) {
        null
    }

    /** Changes whenever any row is added, edited, or removed. */
    private fun ledgerFingerprint(
        ordered: List<com.aurum.invest.data.db.TransactionEntity>
    ): String {
        var hash = 1_125_899_906_842_597L
        for (tx in ordered) {
            hash = hash * 31 + tx.id
            hash = hash * 31 + tx.ts
            hash = hash * 31 + tx.side.hashCode()
            hash = hash * 31 + java.lang.Double.doubleToLongBits(tx.shares)
            hash = hash * 31 + java.lang.Double.doubleToLongBits(tx.price)
        }
        return "${ordered.size}:$hash"
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
