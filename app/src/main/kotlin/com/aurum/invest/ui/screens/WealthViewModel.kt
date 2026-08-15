package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.MoneyFlowReport
import com.aurum.invest.analytics.NextSessionReport
import com.aurum.invest.analytics.NextWeekPlan
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.PortfolioReview
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.core.Dates
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WealthState(
    /** The portfolio-evaluation engine's answer; null while computing or with no book. */
    val review: PortfolioReview? = null,
    val reviewLoading: Boolean = true,
    /** The user's book by sector — same math as the dashboard allocation. */
    val book: BookContext = BookContext.EMPTY,
    /** True once the first positions read landed (so "no book" is a fact, not a race). */
    val bookLoaded: Boolean = false,
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
    /** True while a pull-to-refresh recompute is in flight. */
    val refreshing: Boolean = false
)

class WealthViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val wealth = container.wealth

    private val _state = MutableStateFlow(WealthState())
    val state: StateFlow<WealthState> = _state.asStateFlow()

    private var strategyJob: Job? = null
    private var reviewJob: Job? = null

    init {
        // The money-flow report first — the strategy and review lean on it.
        viewModelScope.launch {
            val flow = wealth.getMoneyFlow()
            _state.update { it.copy(flow = flow, flowLoading = false) }
        }
        viewModelScope.launch {
            val pulse = wealth.getMarketPulse()
            _state.update { it.copy(pulse = pulse, pulseLoading = false) }
        }
        viewModelScope.launch {
            val ns = wealth.getNextSession()
            _state.update { it.copy(nextSession = ns, nextSessionLoading = false) }
        }
        // The user's book, kept live so the review survives new trades.
        viewModelScope.launch {
            container.portfolio.observePositions().collectLatest { positions ->
                val open = positions.filter { PortfolioRepository.isOpen(it) }
                if (open.isEmpty()) {
                    _state.update {
                        it.copy(
                            book = BookContext.EMPTY, bookLoaded = true,
                            review = null, reviewLoading = false
                        )
                    }
                    refreshStrategy()
                    return@collectLatest
                }
                val quotes = container.market.getQuotes(open.map { it.symbol })
                val views = open.map { PortfolioRepository.toView(it, quotes[it.symbol]) }
                val sectors = container.market.getSectors(open.map { it.symbol })
                _state.update {
                    it.copy(book = PortfolioLens.build(views, sectors), bookLoaded = true)
                }
                refreshStrategy()
                refreshReview()
            }
        }
        // The Thursday→Monday next-week preview, on its own clock.
        viewModelScope.launch {
            val (active, _) = Dates.nextWeekPreview()
            _state.update { it.copy(previewWindowActive = active, previewLoading = active) }
            val preview = wealth.ensureNextWeek()
            _state.update { it.copy(preview = preview, previewLoading = false) }
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

    /** Re-runs every engine from live data. */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            _state.update { it.copy(flowLoading = true, pulseLoading = true) }
            val flow = wealth.recomputeMoneyFlow()
            _state.update { it.copy(flow = flow ?: it.flow, flowLoading = false) }
            val pulse = wealth.recomputeMarketPulse()
            _state.update { it.copy(pulse = pulse ?: it.pulse, pulseLoading = false) }
            refreshStrategy()

            _state.update { it.copy(reviewLoading = true) }
            val review = wealth.recomputePortfolioReview()
            _state.update { it.copy(review = review ?: it.review, reviewLoading = false) }

            _state.update { it.copy(nextSessionLoading = true) }
            val ns = wealth.recomputeNextSession()
            _state.update { it.copy(nextSession = ns ?: it.nextSession, nextSessionLoading = false) }

            val (active, _) = Dates.nextWeekPreview()
            if (active) {
                _state.update { it.copy(previewWindowActive = true, previewLoading = true) }
                val preview = wealth.recomputeNextWeek()
                _state.update { it.copy(preview = preview ?: it.preview, previewLoading = false) }
            }
            _state.update { it.copy(refreshing = false) }
        }
    }
}
