package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.WealthPlan
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WealthState(
    val loading: Boolean = true,
    val computing: Boolean = false,
    /** Null until the user provides the base amount + 4-month target. */
    val baseAmount: Double? = null,
    val targetProfit: Double? = null,
    val plan: WealthPlan? = null,
    /** True while the setup form is showing (first run or user editing). */
    val editing: Boolean = false,
    /** Whole-market rating; null while loading or when the market is unreachable. */
    val pulse: MarketRating? = null,
    val pulseLoading: Boolean = true,
    /** The user's book by sector — same math as the dashboard allocation. */
    val book: BookContext = BookContext.EMPTY,
    /** Sector classification for the pulse's suggested symbols. */
    val pulseSectors: Map<String, String> = emptyMap()
)

class WealthViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val wealth = container.wealth

    private val _state = MutableStateFlow(WealthState())
    val state: StateFlow<WealthState> = _state.asStateFlow()

    init {
        // The market pulse is independent of the user's plan inputs.
        viewModelScope.launch {
            val pulse = wealth.getMarketPulse()
            _state.update { it.copy(pulse = pulse, pulseLoading = false) }
            classifyPulse(pulse)
        }
        // The user's book, kept live so exposure reads survive new trades.
        viewModelScope.launch {
            container.portfolio.observePositions().collectLatest { positions ->
                val open = positions.filter { PortfolioRepository.isOpen(it) }
                if (open.isEmpty()) {
                    _state.update { it.copy(book = BookContext.EMPTY) }
                    return@collectLatest
                }
                val quotes = container.market.getQuotes(open.map { it.symbol })
                val views = open.map { PortfolioRepository.toView(it, quotes[it.symbol]) }
                val sectors = container.market.getSectors(open.map { it.symbol })
                _state.update { it.copy(book = PortfolioLens.build(views, sectors)) }
            }
        }
        viewModelScope.launch {
            val inputs = wealth.getInputs()
            if (inputs == null) {
                _state.update { it.copy(loading = false, editing = true) }
                return@launch
            }
            _state.update {
                it.copy(baseAmount = inputs.first, targetProfit = inputs.second)
            }
            // Serve the stored plan instantly, then freshen if the week rolled.
            val stored = wealth.getPlan()
            if (stored != null) {
                _state.update { it.copy(loading = false, plan = stored) }
            }
            val fresh = wealth.ensurePlan()
            _state.update { it.copy(loading = false, plan = fresh ?: stored) }
        }
    }

    /** Saves the inputs and builds the first plan (or rebuilds after an edit). */
    fun save(base: Double, target: Double) {
        if (base <= 0.0 || target <= 0.0 || _state.value.computing) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    computing = true,
                    editing = false,
                    baseAmount = base,
                    targetProfit = target
                )
            }
            wealth.setInputs(base, target)
            val plan = wealth.recompute()
            _state.update { it.copy(computing = false, loading = false, plan = plan) }
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

    /** Re-runs this week's full market scan — the plan AND the market pulse. */
    fun refresh() {
        if (_state.value.computing) return
        viewModelScope.launch {
            _state.update { it.copy(pulseLoading = true) }
            val pulse = wealth.recomputeMarketPulse()
            _state.update { it.copy(pulse = pulse ?: it.pulse, pulseLoading = false) }
            classifyPulse(pulse)
        }
        viewModelScope.launch {
            _state.update { it.copy(computing = true) }
            val plan = wealth.recompute()
            _state.update { it.copy(computing = false, plan = plan ?: it.plan) }
        }
    }

    fun startEditing() {
        _state.update { it.copy(editing = true) }
    }

    fun cancelEditing() {
        if (_state.value.plan != null) {
            _state.update { it.copy(editing = false) }
        }
    }
}
