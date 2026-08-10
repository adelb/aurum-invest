package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.DailyPick
import com.aurum.invest.data.model.EntryPick
import com.aurum.invest.data.model.PowerPick
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.model.WeeklyPick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One weekly pick enriched with the live quote for the since-pick delta. */
data class PickRow(
    val pick: WeeklyPick,
    val quote: Quote?,
    val sincePickPct: Double?
)

data class PicksState(
    val rows: List<PickRow> = emptyList(),
    val budgetRows: List<PickRow> = emptyList(),
    val weekLabel: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    // Daily picks — same-day 3-10%+ candidates, off on Saturdays.
    val dailyRows: List<DailyPick> = emptyList(),
    val dailyLabel: String = "",
    val dailyLoading: Boolean = true,
    val dailyRefreshing: Boolean = false,
    val saturday: Boolean = false,
    // Best entries — market-wide scan for stocks at a good entry price now.
    val entryRows: List<EntryPick> = emptyList(),
    val entryLoading: Boolean = true,
    val entryRefreshing: Boolean = false,
    // Power hour — buy in the last 90 min of the session for next-day strength.
    val powerRows: List<PowerPick> = emptyList(),
    val powerLoading: Boolean = true,
    val powerRefreshing: Boolean = false
)

class PicksViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val picks = container.picks
    private val market = container.market

    private val _state = MutableStateFlow(
        PicksState(
            weekLabel = Dates.weekStartLabel(Dates.currentWeekStartIso()),
            dailyLabel = Dates.todayLabel(),
            saturday = Dates.isSaturday(),
            dailyLoading = !Dates.isSaturday()
        )
    )
    val state: StateFlow<PicksState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            picks.observeCurrentWeek().collectLatest { list ->
                if (list.isEmpty()) {
                    _state.update { it.copy(rows = emptyList()) }
                    return@collectLatest
                }
                // Show the picks immediately, then enrich with live quotes.
                _state.update { st ->
                    val known = st.rows.associateBy { it.pick.symbol }
                    st.copy(
                        rows = list.map { p -> known[p.symbol]?.copy(pick = p) ?: PickRow(p, null, null) },
                        loading = false
                    )
                }
                val quotes = market.getQuotes(list.map { it.symbol })
                _state.update { st ->
                    st.copy(
                        rows = list.map { p ->
                            val q = quotes[p.symbol]
                            val since = if (q != null && p.priceAtPick > 0.0) {
                                (q.price - p.priceAtPick) / p.priceAtPick * 100.0
                            } else null
                            PickRow(pick = p, quote = q, sincePickPct = since)
                        },
                        loading = false
                    )
                }
            }
        }
        viewModelScope.launch {
            picks.observeBudgetWeek().collectLatest { list ->
                if (list.isEmpty()) {
                    _state.update { it.copy(budgetRows = emptyList()) }
                    return@collectLatest
                }
                _state.update { st ->
                    val known = st.budgetRows.associateBy { it.pick.symbol }
                    st.copy(
                        budgetRows = list.map { p ->
                            known[p.symbol]?.copy(pick = p) ?: PickRow(p, null, null)
                        }
                    )
                }
                val quotes = market.getQuotes(list.map { it.symbol })
                _state.update { st ->
                    st.copy(
                        budgetRows = list.map { p ->
                            val q = quotes[p.symbol]
                            val since = if (q != null && p.priceAtPick > 0.0) {
                                (q.price - p.priceAtPick) / p.priceAtPick * 100.0
                            } else null
                            PickRow(pick = p, quote = q, sincePickPct = since)
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            picks.ensureCurrentWeek()
            _state.update { it.copy(loading = false) }
        }
        viewModelScope.launch {
            picks.ensureBudgetWeek()
        }
        viewModelScope.launch {
            if (!Dates.isSaturday()) {
                val daily = picks.ensureDaily()
                _state.update { it.copy(dailyRows = daily, dailyLoading = false) }
            }
        }
        viewModelScope.launch {
            val entries = picks.ensureEntries()
            _state.update { it.copy(entryRows = entries, entryLoading = false) }
        }
        viewModelScope.launch {
            val power = picks.ensurePower()
            _state.update { it.copy(powerRows = power, powerLoading = false) }
        }
    }

    /** Re-run the power-hour scan from fresh screener + candle data. */
    fun refreshPower() {
        if (_state.value.powerRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(powerRefreshing = true) }
            try {
                val power = picks.recomputePower()
                _state.update { it.copy(powerRows = power, powerLoading = false) }
            } finally {
                _state.update { it.copy(powerRefreshing = false) }
            }
        }
    }

    /** Re-run the market-wide best-entry scan from fresh screener + candle data. */
    fun refreshEntries() {
        if (_state.value.entryRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(entryRefreshing = true) }
            try {
                val entries = picks.recomputeEntries()
                _state.update { it.copy(entryRows = entries, entryLoading = false) }
            } finally {
                _state.update { it.copy(entryRefreshing = false) }
            }
        }
    }

    /** Recompute today's daily picks from fresh quotes, extended hours, and news. */
    fun refreshDaily() {
        if (_state.value.dailyRefreshing || _state.value.saturday) return
        viewModelScope.launch {
            _state.update { it.copy(dailyRefreshing = true) }
            try {
                val daily = picks.recomputeDaily()
                _state.update { it.copy(dailyRows = daily, dailyLoading = false) }
            } finally {
                _state.update { it.copy(dailyRefreshing = false) }
            }
        }
    }

    /** Recompute this week's picks (both lists) from fresh market data. */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                picks.recompute()
                picks.recomputeBudget()
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }
}
