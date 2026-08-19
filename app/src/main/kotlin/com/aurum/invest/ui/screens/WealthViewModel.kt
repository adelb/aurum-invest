package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.DeploymentPlan
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.WealthReport
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
            }
        }
        // A wallet change (top-up, first setup) re-sizes everything at once.
        viewModelScope.launch {
            container.wallet.total.collectLatest {
                refreshStrategy()
                refreshDeployment()
                refreshReport()
            }
        }
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
        val symbols = (pulse.bestYesterday.map { it.symbol } +
            pulse.nextDay.map { it.symbol }).distinct()
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
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }
}
