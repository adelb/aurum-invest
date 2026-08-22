package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.BeatSpyEngine
import com.aurum.invest.analytics.BeatSpyReport
import com.aurum.invest.analytics.BuyPlan
import com.aurum.invest.analytics.BuyPlanEngine
import com.aurum.invest.analytics.TechniqueAnalysis
import com.aurum.invest.analytics.TechniqueEvaluation
import com.aurum.invest.analytics.TechniqueEvaluator
import com.aurum.invest.analytics.Techniques
import com.aurum.invest.data.model.FeedStatus
import com.aurum.invest.data.repo.InvestorProfile
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AnalysisState(
    val symbol: String = "",
    val loading: Boolean = true,
    val analysis: TechniqueAnalysis? = null,
    val price: Double? = null,
    val plan: BuyPlan? = null,
    /** Measured 1-year track record per technique; null while it computes. */
    val evaluation: TechniqueEvaluation? = null,
    val evaluationLoading: Boolean = false,
    /**
     * Provenance of the price history behind everything on this screen.
     * FAILED with an empty analysis means "couldn't load the data" — a
     * different diagnosis from "this symbol is listed too recently", and the
     * screen must say the right one.
     */
    val historyStatus: FeedStatus = FeedStatus.FRESH,
    val historyAsOf: Long = 0L,
    /** The stock raced against SPY; null while it computes or when unmeasurable. */
    val beatSpy: BeatSpyReport? = null,
    val beatSpyLoading: Boolean = false
)

class AnalysisViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val market = container.market
    private val evaluations = container.evaluations

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    private var job: Job? = null

    companion object {
        /**
         * Two years of dailies: the last 252 sessions feed the 1-year
         * integrity replay, and the sessions before them give every replayed
         * day a real indicator warm-up.
         */
        private const val CANDLE_DAYS = 550

        /** Five years of dailies for the SPY race — the long horizons' analog pool. */
        private const val BEAT_SPY_DAYS = 1825
    }

    fun start(symbol: String) {
        val sym = symbol.trim().uppercase()
        if (sym.isEmpty()) return
        if (_state.value.symbol == sym && _state.value.analysis != null) return
        _state.value = AnalysisState(symbol = sym)
        load(sym)
    }

    fun refresh() {
        val sym = _state.value.symbol
        if (sym.isNotEmpty()) load(sym)
    }

    private fun load(sym: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true, evaluation = null, evaluationLoading = false,
                    beatSpy = null, beatSpyLoading = false
                )
            }
            // Two years of dailies: the 200-day average, plus a full-year
            // integrity replay with honest warm-up for every replayed day.
            val candleFeed = try {
                market.getDailyCandlesFeed(sym, CANDLE_DAYS)
            } catch (_: Exception) {
                com.aurum.invest.data.model.CandleFeed(emptyList(), FeedStatus.FAILED, 0L)
            }
            val candles = candleFeed.candles
            val quote = try {
                market.getQuote(sym)
            } catch (_: Exception) {
                null
            }
            val analysis = Techniques.analyze(sym, candles)
            val price = quote?.price ?: candles.lastOrNull()?.close

            // Account-aware sizing: the plan derives its budget from equity
            // and the investor's risk policy instead of a fixed $3,000.
            val profile = runCatching { container.settings.investorProfile.first() }
                .getOrDefault(InvestorProfile.DEFAULT)
            // Holdings at market: 0.0 when nothing is open (a fact), null when
            // a holding could not be priced (unknown — cost basis is not equity).
            val equity = runCatching {
                val open = container.portfolio.positionsNow()
                    .filter { PortfolioRepository.isOpen(it) }
                if (open.isEmpty()) {
                    0.0
                } else {
                    val quotes = container.market.getQuotes(open.map { it.symbol })
                    val priced = open.mapNotNull { p -> quotes[p.symbol]?.let { p.shares * it.price } }
                    if (priced.size < open.size) null else priced.sum()
                }
            }.getOrNull()
            // Spendable cash: the stated wallet's liquidity — which already
            // carries the proceeds of every sell. Null when the wallet is not
            // configured: an unknown cash balance stays unknown.
            val available =
                runCatching { container.wallet.liquidityNow() }.getOrNull()?.coerceAtLeast(0.0)
            val totalEquity = when {
                equity != null && available != null -> equity + available
                else -> equity
            }

            val plan =
                if (analysis != null && price != null && price > 0.0) {
                    runCatching {
                        BuyPlanEngine.build(
                            sym, candles, analysis, price,
                            accountEquity = totalEquity,
                            riskPerTradePct = profile.riskPerTradePct,
                            maxPositionPct = profile.maxPositionPct,
                            cashAvailable = available,
                            policyNote = profile.label()
                        )
                    }.getOrNull()
                } else null
            _state.update {
                it.copy(
                    loading = false,
                    analysis = analysis,
                    price = price,
                    plan = plan,
                    historyStatus = candleFeed.status,
                    historyAsOf = candleFeed.asOf,
                    evaluationLoading =
                        analysis != null &&
                            candles.size >= TechniqueEvaluator.MIN_CANDLES_FOR_FULL_REPLAY,
                    beatSpyLoading = sym != "SPY"
                )
            }

            // The SPY race runs beside the integrity replay, never blocking it.
            if (sym != "SPY") {
                launch { loadBeatSpy(sym, price) }
            }

            // The integrity engine: replay the last year of sessions and grade
            // every technique against the real 5-day moves. Once graded, the
            // outlook is re-voted with each technique weighted by its own
            // measured hit rate on this stock, and the board reorders so the
            // top-ranked techniques lead.
            if (
                analysis == null ||
                candles.size < TechniqueEvaluator.MIN_CANDLES_FOR_FULL_REPLAY
            ) {
                return@launch
            }
            val evaluation = loadEvaluation(sym, candles)
            if (evaluation == null) {
                _state.update { st ->
                    if (st.symbol != sym) st else st.copy(evaluationLoading = false)
                }
                return@launch
            }
            val weighted = withContext(Dispatchers.Default) {
                runCatching { Techniques.analyze(sym, candles, evaluation.weights()) }.getOrNull()
            }
            _state.update { st ->
                // Ignore a result that raced a newer symbol load.
                if (st.symbol != sym) st
                else st.copy(
                    evaluation = evaluation,
                    analysis = weighted ?: st.analysis,
                    evaluationLoading = false
                )
            }
        }
    }

    /** Cached 12-month evaluation via the store shared with the portfolio review. */
    private suspend fun loadEvaluation(
        sym: String,
        candles: List<com.aurum.invest.data.model.Candle>
    ): TechniqueEvaluation? = evaluations.get(sym, candles)

    /**
     * The SPY race: five years of the stock and the index, date-aligned and
     * replayed as paired windows. Runs beside the main load; a result that
     * raced a newer symbol is dropped.
     */
    private suspend fun loadBeatSpy(sym: String, price: Double?) =
        // Whole race off the main thread: the five-year candle stores are
        // parsed from JSON on the caller's dispatcher, and two of them on
        // Main is a visible hitch every time the screen opens.
        withContext(Dispatchers.Default) {
            val stock = runCatching { market.getDailyCandles(sym, BEAT_SPY_DAYS) }
                .getOrDefault(emptyList())
            val spy = runCatching { market.getDailyCandles("SPY", BEAT_SPY_DAYS) }
                .getOrDefault(emptyList())
            val spyQuote = runCatching { market.getQuote("SPY") }.getOrNull()
            val report = runCatching {
                BeatSpyEngine.build(sym, stock, spy, price, spyQuote?.price)
            }.getOrNull()
            _state.update { st ->
                if (st.symbol != sym) st else st.copy(beatSpy = report, beatSpyLoading = false)
            }
        }
}
