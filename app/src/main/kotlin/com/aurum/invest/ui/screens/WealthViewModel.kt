package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.LiquidityPlan
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.MoneyFlowReport
import com.aurum.invest.analytics.NextSessionReport
import com.aurum.invest.analytics.NextWeekPlan
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.PortfolioPerformance
import com.aurum.invest.analytics.PortfolioReview
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.core.Dates
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WealthState(
    /** The portfolio-evaluation engine's answer; null while computing or with no book. */
    val review: PortfolioReview? = null,
    val reviewLoading: Boolean = true,
    /** The user's book by sector — same math as the dashboard allocation. */
    val book: BookContext = BookContext.EMPTY,
    /** True once the first positions read landed (so "no book" is a fact, not a race). */
    val bookLoaded: Boolean = false,
    /**
     * True when the ledger holds open positions — distinct from [book], which
     * can be EMPTY on a quote outage even while positions exist. Only this
     * flag may drive the "no positions yet" message.
     */
    val hasPositions: Boolean = false,
    /** Whole-market rating; null while loading or when the market is unreachable. */
    val pulse: MarketRating? = null,
    val pulseLoading: Boolean = true,
    /** The standalone money-flow engine's sector report. */
    val flow: MoneyFlowReport? = null,
    val flowLoading: Boolean = true,
    /** This week's sector gaps for this book. */
    val strategy: WeeklyStrategy? = null,
    val strategyLoading: Boolean = true,
    /** The next-session engine's 10 picks with alert flags. */
    val nextSession: NextSessionReport? = null,
    val nextSessionLoading: Boolean = true,
    /** The Thursday→Monday next-week preview; null outside the window or before first build. */
    val preview: NextWeekPlan? = null,
    val previewLoading: Boolean = false,
    val previewWindowActive: Boolean = false,
    /** Reconstructed performance & risk (H2); null when too little could be measured. */
    val performance: PortfolioPerformance? = null,
    val performanceLoading: Boolean = false,
    /** How much of the uninvested wallet cash to deploy, and where. */
    val liquidityPlan: LiquidityPlan? = null,
    val liquidityPlanLoading: Boolean = true,
    /** True while a pull-to-refresh recompute is in flight. */
    val refreshing: Boolean = false
)

class WealthViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /**
         * The portfolio review re-runs on this cadence while the screen lives.
         * Quotes are the only network cost of a re-run — candles, news, flow
         * and sectors are all served from their own caches — so the verdicts
         * track the live tape without hammering the market API.
         */
        private const val LIVE_REVIEW_MS = 120_000L
    }

    private val container = (app as AurumApp).container
    private val wealth = container.wealth

    private val _state = MutableStateFlow(WealthState())
    val state: StateFlow<WealthState> = _state.asStateFlow()

    private var strategyJob: Job? = null
    private var reviewJob: Job? = null
    private var nextSessionJob: Job? = null
    private var previewJob: Job? = null
    private var performanceJob: Job? = null
    private var liquidityPlanJob: Job? = null

    init {
        // The live loop: keeps the review current between manual refreshes.
        // Paused whenever nothing collects the state (screen not visible).
        viewModelScope.launch {
            while (isActive) {
                delay(LIVE_REVIEW_MS)
                if (_state.subscriptionCount.value == 0) continue
                val s = _state.value
                if (s.refreshing || !s.hasPositions || reviewJob?.isActive == true) continue
                val fresh = wealth.getPortfolioReview(maxAgeMs = LIVE_REVIEW_MS)
                if (fresh != null) {
                    _state.update { it.copy(review = fresh, reviewLoading = false) }
                }
            }
        }
        // The money-flow report first — the strategy and review lean on it.
        viewModelScope.launch {
            val flow = wealth.getMoneyFlow()
            _state.update { it.copy(flow = flow, flowLoading = false) }
        }
        viewModelScope.launch {
            val pulse = wealth.getMarketPulse()
            _state.update { it.copy(pulse = pulse, pulseLoading = false) }
        }
        // The user's book, kept live so the review survives new trades.
        viewModelScope.launch {
            container.portfolio.observePositions().collectLatest { positions ->
                val open = positions.filter { PortfolioRepository.isOpen(it) }
                if (open.isEmpty()) {
                    _state.update {
                        it.copy(
                            book = BookContext.EMPTY, bookLoaded = true,
                            hasPositions = false,
                            review = null, reviewLoading = false,
                            strategy = null,
                            nextSession = null, preview = null
                        )
                    }
                    refreshStrategy()
                    refreshNextSession()
                    refreshPreview()
                    refreshLiquidityPlan()
                    return@collectLatest
                }
                val quotes = container.market.getQuotes(open.map { it.symbol })
                val views = open.map { PortfolioRepository.toView(it, quotes[it.symbol]) }
                val sectors = container.market.getSectors(open.map { it.symbol })
                _state.update {
                    it.copy(
                        book = PortfolioLens.build(views, sectors),
                        bookLoaded = true,
                        hasPositions = true,
                        // A ledger change invalidates every portfolio-aware
                        // output. Never leave the prior book's advice visible.
                        review = null,
                        reviewLoading = true,
                        strategy = null,
                        nextSession = null,
                        preview = null
                    )
                }
                refreshStrategy()
                refreshReview()
                refreshNextSession()
                refreshPreview()
                refreshPerformance()
                refreshLiquidityPlan()
            }
        }
    }

    /** The reconstructed equity curve + risk stats; heavy, so cache-backed. */
    private fun refreshPerformance() {
        performanceJob?.cancel()
        performanceJob = viewModelScope.launch {
            _state.update { it.copy(performanceLoading = true) }
            val perf = wealth.getPerformance()
            _state.update { it.copy(performance = perf ?: it.performance, performanceLoading = false) }
        }
    }

    /**
     * How much of the stated wallet's uninvested cash to deploy, and where.
     * Liquidity comes from [com.aurum.invest.data.repo.WalletRepository.liquidityNow]
     * — the one cash identity (total − invested + realized P/L) the dashboard
     * and the reports card also read — so a wallet top-up, a new buy, and the
     * proceeds of a sell all recompute this the same way a ledger change
     * recomputes the portfolio review.
     */
    private fun refreshLiquidityPlan() {
        liquidityPlanJob?.cancel()
        liquidityPlanJob = viewModelScope.launch {
            _state.update { it.copy(liquidityPlanLoading = true) }
            // Null wallet = unknown cash, which the engine reads as nothing to
            // deploy — never as free money.
            val liquidity = try {
                container.wallet.liquidityNow() ?: 0.0
            } catch (_: Exception) {
                0.0
            }
            val plan = wealth.getLiquidityPlan(liquidity, _state.value.book)
            _state.update {
                it.copy(liquidityPlan = plan ?: it.liquidityPlan, liquidityPlanLoading = false)
            }
        }
    }

    /** The portfolio review — fingerprinted, so a new trade recomputes it. */
    private fun refreshReview() {
        reviewJob?.cancel()
        reviewJob = viewModelScope.launch {
            _state.update { it.copy(reviewLoading = true) }
            val review = wealth.getPortfolioReview()
            _state.update { it.copy(review = review ?: it.review, reviewLoading = false) }
        }
    }

    /** This week's sector gaps for the current book. Cheap on repeat — inputs cached. */
    private fun refreshStrategy() {
        strategyJob?.cancel()
        strategyJob = viewModelScope.launch {
            _state.update { it.copy(strategyLoading = true) }
            val strategy = wealth.getStrategy(_state.value.book)
            _state.update {
                it.copy(strategy = strategy ?: it.strategy, strategyLoading = false)
            }
        }
    }

    /** Re-applies current holdings to the cached market scan without stale tags. */
    private fun refreshNextSession() {
        nextSessionJob?.cancel()
        nextSessionJob = viewModelScope.launch {
            _state.update { it.copy(nextSessionLoading = true) }
            val nextSession = wealth.getNextSession()
            _state.update {
                it.copy(nextSession = nextSession ?: it.nextSession, nextSessionLoading = false)
            }
        }
    }

    /** The preview cache is fingerprinted to the current positions and cost basis. */
    private fun refreshPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val (active, _) = Dates.nextWeekPreview()
            _state.update {
                it.copy(previewWindowActive = active, previewLoading = active)
            }
            val preview = wealth.ensureNextWeek()
            _state.update {
                it.copy(preview = preview ?: it.preview, previewLoading = false)
            }
        }
    }

    /** Re-runs every engine from live data. */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        strategyJob?.cancel()
        reviewJob?.cancel()
        nextSessionJob?.cancel()
        previewJob?.cancel()
        liquidityPlanJob?.cancel()
        viewModelScope.launch {
            try {
                _state.update { it.copy(flowLoading = true, pulseLoading = true) }
                val flow = wealth.recomputeMoneyFlow()
                _state.update { it.copy(flow = flow ?: it.flow, flowLoading = false) }
                val pulse = wealth.recomputeMarketPulse()
                _state.update { it.copy(pulse = pulse ?: it.pulse, pulseLoading = false) }

                _state.update { it.copy(strategyLoading = true) }
                val strategy = wealth.getStrategy(_state.value.book)
                _state.update {
                    it.copy(strategy = strategy ?: it.strategy, strategyLoading = false)
                }

                _state.update { it.copy(reviewLoading = true) }
                val review = wealth.recomputePortfolioReview()
                _state.update { it.copy(review = review ?: it.review, reviewLoading = false) }

                _state.update { it.copy(nextSessionLoading = true) }
                val ns = wealth.recomputeNextSession()
                _state.update {
                    it.copy(nextSession = ns ?: it.nextSession, nextSessionLoading = false)
                }

                val (active, _) = Dates.nextWeekPreview()
                if (active) {
                    _state.update { it.copy(previewWindowActive = true, previewLoading = true) }
                    val preview = wealth.recomputeNextWeek()
                    _state.update {
                        it.copy(preview = preview ?: it.preview, previewLoading = false)
                    }
                }

                _state.update { it.copy(liquidityPlanLoading = true) }
                val liquidity = try {
                    container.wallet.liquidityNow() ?: 0.0
                } catch (_: Exception) {
                    0.0
                }
                val plan = wealth.recomputeLiquidityPlan(liquidity, _state.value.book)
                _state.update {
                    it.copy(liquidityPlan = plan ?: it.liquidityPlan, liquidityPlanLoading = false)
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }
}
