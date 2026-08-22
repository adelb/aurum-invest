package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.BeatSpyEngine
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.DeploymentPlan
import com.aurum.invest.analytics.EngineRecordReport
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.MustBuyCandidate
import com.aurum.invest.analytics.MustBuyEngine
import com.aurum.invest.analytics.MustBuyReport
import com.aurum.invest.analytics.NextSessionReport
import com.aurum.invest.analytics.PerformanceReport
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.WealthReport
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.core.Dates
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Wealth tab's state: the wealth engine's full evaluation, the market
 * pulse, the book, the weekly sector strategy, and the liquidity deployment.
 * No goal inputs, no target profits — the engine reads what IS (the ledger,
 * the wallet, the market) and answers what to do.
 */
data class WealthState(
    val loading: Boolean = true,
    /** Whole-market rating; null while loading or when the market is unreachable. */
    val pulse: MarketRating? = null,
    val pulseLoading: Boolean = true,
    /** The user's book by sector — same math as the dashboard allocation. */
    val book: BookContext = BookContext.EMPTY,
    /** Sector classification for the pulse's suggested symbols. */
    val pulseSectors: Map<String, String> = emptyMap(),
    /** This week's sector gaps + deployment split for this book. */
    val strategy: WeeklyStrategy? = null,
    val strategyLoading: Boolean = true,
    /** The wallet's uninvested cash; null until the user states a total. */
    val liquidity: Double? = null,
    /** The liquidity-management answer: sectors, stocks, dollars, reserve. */
    val deployPlan: DeploymentPlan? = null,
    val deployLoading: Boolean = true,
    /** The wealth engine's evaluation: health, holdings, risk, actions. */
    val report: WealthReport? = null,
    val reportLoading: Boolean = true,
    /** The next-session engine's measured picks with analog follow-through. */
    val nextSession: NextSessionReport? = null,
    val nextSessionLoading: Boolean = true,
    /** The verdict: the user's ACTUAL trading measured against SPY. */
    val performance: PerformanceReport? = null,
    val performanceLoading: Boolean = true,
    /** The engines' own graded call record. */
    val record: EngineRecordReport? = null,
    /** The must-buy convergence: today's picks through every measured check. */
    val mustBuy: MustBuyReport? = null,
    val mustBuyLoading: Boolean = true,
    val mustBuyScanned: Int = 0,
    val mustBuyTotal: Int = 0,
    /** True while a pull-to-refresh recompute is in flight. */
    val refreshing: Boolean = false
)

class WealthViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val wealth = container.wealth

    private val _state = MutableStateFlow(WealthState())
    val state: StateFlow<WealthState> = _state.asStateFlow()

    private var strategyJob: Job? = null
    private var deployJob: Job? = null
    private var reportJob: Job? = null
    private var nextSessionJob: Job? = null
    private var performanceJob: Job? = null
    private var recordJob: Job? = null
    private var mustBuyJob: Job? = null

    private companion object {
        /** Five years of dailies per SPY race — the long horizons' analog pool. */
        const val BEAT_SPY_DAYS = 1825
        const val DAY_MS = 86_400_000L
    }

    init {
        viewModelScope.launch {
            val pulse = wealth.getMarketPulse()
            _state.update { it.copy(pulse = pulse, pulseLoading = false, loading = false) }
            classifyPulse(pulse)
        }
        // The user's book, kept live so every read survives new trades.
        viewModelScope.launch {
            container.portfolio.observePositions().collectLatest { positions ->
                val open = positions.filter { PortfolioRepository.isOpen(it) }
                if (open.isEmpty()) {
                    _state.update { it.copy(book = BookContext.EMPTY, loading = false) }
                } else {
                    val quotes = container.market.getQuotes(open.map { it.symbol })
                    val views = open.map { PortfolioRepository.toView(it, quotes[it.symbol]) }
                    val sectors = container.market.getSectors(open.map { it.symbol })
                    _state.update {
                        it.copy(book = PortfolioLens.build(views, sectors), loading = false)
                    }
                }
                refreshStrategy()
                refreshDeployment()
                refreshReport()
                refreshPerformance()
            }
        }
        refreshNextSession()
        refreshRecord()
        refreshMustBuy()
        // A wallet change (top-up, first setup) re-sizes everything at once.
        viewModelScope.launch {
            container.wallet.total.collectLatest {
                refreshStrategy()
                refreshDeployment()
                refreshReport()
            }
        }
    }

    /** The next-session scan — cache-served between the 20-minute recomputes. */
    private fun refreshNextSession(force: Boolean = false) {
        nextSessionJob?.cancel()
        nextSessionJob = viewModelScope.launch {
            _state.update { it.copy(nextSessionLoading = true) }
            val ns =
                if (force) wealth.recomputeNextSession(container.portfolio)
                else wealth.getNextSession(container.portfolio)
            _state.update {
                it.copy(nextSession = ns ?: it.nextSession, nextSessionLoading = false)
            }
        }
    }

    /**
     * The verdict — cache-served while the ledger is unchanged; the cached
     * fingerprint makes a new trade recompute it automatically.
     */
    private fun refreshPerformance(force: Boolean = false) {
        performanceJob?.cancel()
        performanceJob = viewModelScope.launch {
            _state.update { it.copy(performanceLoading = true) }
            val perf =
                if (force) wealth.recomputePerformance(container.portfolio)
                else wealth.getPerformance(container.portfolio)
            _state.update {
                it.copy(performance = perf ?: it.performance, performanceLoading = false)
            }
        }
    }

    /** Scores every call whose sessions have played out, then grades the record. */
    private fun refreshRecord() {
        recordJob?.cancel()
        recordJob = viewModelScope.launch {
            runCatching { container.record.scorePending() }
            val record = runCatching { container.record.getRecord() }.getOrNull()
            if (record != null) _state.update { it.copy(record = record) }
        }
    }

    /**
     * The must-buy convergence: every stock in today's pick lists put through
     * every measured check the app has — the scans, the technique board, the
     * Beat-SPY race, earnings proximity, analyst consensus, the news read, and
     * the fit against the live book — then ranked by [MustBuyEngine]. Runs
     * entirely off the main thread (the candle stores parse on the caller's
     * dispatcher), with candle fetches chunked so the board never bursts Yahoo.
     */
    private fun refreshMustBuy(quoteMaxAgeMs: Long = 300_000L) {
        mustBuyJob?.cancel()
        mustBuyJob = viewModelScope.launch {
            _state.update {
                it.copy(mustBuyLoading = true, mustBuyScanned = 0, mustBuyTotal = 0)
            }
            try {
                withContext(Dispatchers.Default) { runMustBuyScan(quoteMaxAgeMs) }
            } finally {
                _state.update { it.copy(mustBuyLoading = false) }
            }
        }
    }

    /** One nominated stock's facts, merged across every list that carries it. */
    private class Nominee(var name: String) {
        val sources = ArrayList<String>()
        var bestScore: Double? = null
        var pickPrice: Double = 0.0
        var techDirection: String? = null
        var techBullish: Int = 0
        var techTotal: Int = 0
        var analystRating: Double? = null
        var rewardRisk: Double? = null
        var headlineSentiment: Int? = null

        fun nominate(source: String, score: Double, price: Double) {
            if (source !in sources) sources.add(source)
            bestScore = maxOf(bestScore ?: score, score)
            if (price > 0.0) pickPrice = price
        }

        fun tech(direction: String, bullish: Int, total: Int) {
            // The deepest board read wins — more techniques voting, more signal.
            if (total > techTotal) {
                techDirection = direction
                techBullish = bullish
                techTotal = total
            }
        }
    }

    private suspend fun runMustBuyScan(quoteMaxAgeMs: Long) {
        val picks = container.picks
        // Sequential on purpose — each ensure can be a market-wide sweep.
        val daily = if (Dates.isSaturday()) emptyList() else picks.ensureDaily()
        val entries = picks.ensureEntries()
        val power = picks.ensurePower()
        val weekly = picks.ensureCurrentWeek()
        val budget = picks.ensureBudgetWeek()

        val nominees = LinkedHashMap<String, Nominee>()
        fun of(symbol: String, name: String): Nominee? {
            val key = symbol.trim().uppercase()
            if (key.isEmpty() || key == "SPY") return null
            val n = nominees.getOrPut(key) { Nominee(name) }
            if (n.name.isBlank() && name.isNotBlank()) n.name = name
            return n
        }
        for (p in daily) {
            of(p.symbol, p.name)?.apply {
                nominate("Daily", p.score, p.price)
                tech(p.techDirection, p.techBullish, p.techTotal)
                if (p.headline.isNotBlank()) headlineSentiment = p.headlineSentiment
            }
        }
        for (p in entries) {
            of(p.symbol, p.name)?.apply {
                nominate("Entries", p.score, p.price)
                tech(p.techDirection, p.techBullish, p.techTotal)
                if (p.analystRating != null) analystRating = p.analystRating
                rewardRisk = p.rewardRisk
            }
        }
        for (p in power) {
            of(p.symbol, p.name)?.apply {
                nominate("Power", p.score, p.price)
                tech(p.techDirection, p.techBullish, p.techTotal)
            }
        }
        for (p in weekly) of(p.symbol, p.name)?.nominate("Weekly", p.score, p.priceAtPick)
        for (p in budget) of(p.symbol, p.name)?.nominate("Under $25", p.score, p.priceAtPick)
        // The next-session engine's picks are nominations too — the one
        // engine whose record is already graded (KIND_PICK in the ledger).
        val nextSession = runCatching {
            wealth.getNextSession(container.portfolio)
        }.getOrNull()
        for (p in nextSession?.picks.orEmpty()) {
            of(p.symbol, p.name)?.nominate("Next session", p.score.toDouble(), p.price)
        }

        // Each engine's own graded record — the backing weights read it.
        val engineRecord = runCatching { container.record.getRecord() }.getOrNull()

        val symbols = nominees.keys.toList()
        _state.update { it.copy(mustBuyTotal = symbols.size) }
        if (symbols.isEmpty()) {
            _state.update {
                it.copy(mustBuy = MustBuyEngine.build(emptyList(), engineRecord))
            }
            return
        }

        // Shared facts, batched: live quotes, earnings dates, sectors.
        val quotes = runCatching {
            container.market.getQuotes(symbols, maxAgeMs = quoteMaxAgeMs)
        }.getOrDefault(emptyMap())
        val earnings = runCatching {
            container.market.getEarningsDates(symbols)
        }.getOrDefault(emptyMap())
        val sectors = runCatching {
            container.market.getSectors(symbols)
        }.getOrDefault(emptyMap())

        // The index side of every race, fetched once.
        val spyCandles = runCatching {
            container.market.getDailyCandles("SPY", BEAT_SPY_DAYS)
        }.getOrDefault(emptyList())
        val spyQuote = runCatching { container.market.getQuote("SPY") }.getOrNull()

        val now = System.currentTimeMillis()
        val candidates = ArrayList<MustBuyCandidate>(symbols.size)
        for (chunk in symbols.chunked(4)) {
            val raced = coroutineScope {
                chunk.map { sym ->
                    async {
                        if (spyCandles.isEmpty()) return@async sym to null
                        val stock = runCatching {
                            container.market.getDailyCandles(sym, BEAT_SPY_DAYS)
                        }.getOrDefault(emptyList())
                        sym to runCatching {
                            BeatSpyEngine.build(
                                sym, stock, spyCandles,
                                quotes[sym]?.price, spyQuote?.price
                            )
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            for ((sym, report) in raced) {
                val n = nominees[sym] ?: continue
                val quote = quotes[sym]
                val price = quote?.price?.takeIf { it > 0.0 } ?: n.pickPrice
                val dayChange = quote?.let { q ->
                    if (q.prevClose > 0.0) (q.price - q.prevClose) / q.prevClose * 100.0
                    else null
                }
                val horizon = report?.let { BeatSpyEngine.buyHorizon(it) }
                val info = earnings[sym]
                val earningsDays = info?.nextTs
                    ?.takeIf { it >= now - DAY_MS }
                    ?.let { ((it - now).coerceAtLeast(0L) / DAY_MS).toInt() }
                candidates += MustBuyCandidate(
                    symbol = sym,
                    name = n.name,
                    price = price,
                    dayChangePct = dayChange,
                    sources = n.sources.toList(),
                    bestScore = n.bestScore,
                    techDirection = n.techDirection,
                    techBullish = n.techBullish,
                    techTotal = n.techTotal,
                    spyVerdict = horizon?.verdict,
                    spyGreen = if (report != null && horizon != null) {
                        BeatSpyEngine.inGreenZone(report.price, horizon)
                    } else null,
                    spyBeatSharePct = horizon?.beatSharePct,
                    spyEdgeEntry = horizon?.edgeEntry,
                    earningsKnown = info != null,
                    earningsInDays = earningsDays,
                    noteKind = PortfolioLens.pickNote(
                        sym, sectors[sym], _state.value.book
                    )?.kind,
                    analystRating = n.analystRating,
                    rewardRisk = n.rewardRisk,
                    headlineSentiment = n.headlineSentiment
                )
            }
            _state.update {
                it.copy(
                    mustBuyScanned = (it.mustBuyScanned + chunk.size)
                        .coerceAtMost(symbols.size)
                )
            }
        }

        _state.update { it.copy(mustBuy = MustBuyEngine.build(candidates, engineRecord)) }
    }

    /** The wealth engine's full evaluation — health, holdings, risk, actions. */
    private fun refreshReport() {
        reportJob?.cancel()
        reportJob = viewModelScope.launch {
            _state.update { it.copy(reportLoading = true) }
            val report = wealth.getWealthReport(container.wallet, container.portfolio)
            _state.update { it.copy(report = report ?: it.report, reportLoading = false) }
        }
    }

    /** The week's sector split, sized from the wallet's real uninvested cash. */
    private fun refreshStrategy() {
        strategyJob?.cancel()
        strategyJob = viewModelScope.launch {
            _state.update { it.copy(strategyLoading = true) }
            val liquidity = runCatching { container.wallet.liquidityNow() }.getOrNull()
            val strategy = wealth.getStrategy(_state.value.book, liquidity ?: 0.0)
            _state.update {
                it.copy(
                    strategy = strategy ?: it.strategy,
                    strategyLoading = false,
                    liquidity = liquidity
                )
            }
        }
    }

    /** The liquidity-management answer, rebuilt from the wallet and the book. */
    private fun refreshDeployment() {
        deployJob?.cancel()
        deployJob = viewModelScope.launch {
            _state.update { it.copy(deployLoading = true) }
            val liquidity = runCatching { container.wallet.liquidityNow() }.getOrNull()
            val plan = wealth.getDeploymentPlan(liquidity ?: 0.0, _state.value.book)
            _state.update {
                it.copy(
                    liquidity = liquidity,
                    deployPlan = plan ?: it.deployPlan,
                    deployLoading = false
                )
            }
        }
    }

    /** Sector lookups for the pulse's suggested stocks (cached 30 days). */
    private suspend fun classifyPulse(pulse: MarketRating?) {
        if (pulse == null) return
        val symbols = pulse.bestYesterday.map { it.symbol }.distinct()
        if (symbols.isEmpty()) return
        val sectors = container.market.getSectors(symbols)
        if (sectors.isNotEmpty()) {
            _state.update { it.copy(pulseSectors = it.pulseSectors + sectors) }
        }
    }

    /** Re-runs every engine from live data. */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                _state.update { it.copy(pulseLoading = true) }
                val pulse = wealth.recomputeMarketPulse()
                _state.update { it.copy(pulse = pulse ?: it.pulse, pulseLoading = false) }
                classifyPulse(pulse)
                refreshStrategy()
                refreshDeployment()
                refreshReport()
                refreshNextSession(force = true)
                refreshPerformance(force = true)
                refreshRecord()
                refreshMustBuy(quoteMaxAgeMs = 60_000L)
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }
}
