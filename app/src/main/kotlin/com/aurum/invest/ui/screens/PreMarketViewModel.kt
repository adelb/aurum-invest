package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.IntradayPick
import com.aurum.invest.analytics.PreMarketPick
import com.aurum.invest.analytics.PreMarketPicker
import com.aurum.invest.core.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PreMarketState(
    val rows: List<PreMarketPick> = emptyList(),
    val targetPct: Double = PreMarketPicker.DEFAULT_TARGET_PCT,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val dateLabel: String = Dates.todayLabel(),
    /** Which US session is live right now — drives the header's honesty line. */
    val session: Dates.MarketSession = Dates.marketSessionNow(),
    val minutesToOpen: Long = Dates.minutesToUsOpen(),
    /** When the shown prints were read. */
    val asOf: Long = 0L,
    // Open-session (intraday 2%) scan — loaded when its toggle is first shown.
    val intradayRows: List<IntradayPick> = emptyList(),
    val intradayLoading: Boolean = false,
    val intradayRefreshing: Boolean = false,
    /** When the intraday prints were read. */
    val intradayAsOf: Long = 0L
) {
    /** True when the rows describe actual pre-market prints. */
    val livePreMarket: Boolean get() = rows.isNotEmpty() && rows.first().livePreMarket
}

/**
 * Drives the pre-market list. The profit target is user-owned: changing it
 * re-runs the scan, because the whole ranking is an answer to that number.
 */
class PreMarketViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val picks = container.picks
    private val targets = container.targets

    private val _state = MutableStateFlow(PreMarketState())
    val state: StateFlow<PreMarketState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * Refreshes whenever the screen is shown. Pre-market prices move minute
     * to minute, so opening the app at 9:00 AM ET must not serve a list
     * computed at 5:00 AM — [PicksRepository.ensurePreMarket] recomputes once
     * the stored set ages past two minutes.
     */
    fun onShown() = load()

    private fun load() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    session = Dates.marketSessionNow(),
                    minutesToOpen = Dates.minutesToUsOpen(),
                    dateLabel = Dates.todayLabel()
                )
            }
            val saved = targets.getTarget(DAILY_TARGET_KEY)
                ?: PreMarketPicker.DEFAULT_TARGET_PCT
            _state.update { it.copy(targetPct = saved) }
            val rows = picks.ensurePreMarket(saved)
            _state.update {
                it.copy(
                    rows = rows,
                    loading = false,
                    asOf = rows.firstOrNull()?.asOf ?: it.asOf
                )
            }
        }
    }

    /** Rescan with fresh pre-market prints. */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    refreshing = true,
                    session = Dates.marketSessionNow(),
                    minutesToOpen = Dates.minutesToUsOpen()
                )
            }
            try {
                val rows = picks.recomputePreMarket(_state.value.targetPct)
                _state.update {
                    it.copy(
                        rows = rows.ifEmpty { it.rows },
                        loading = false,
                        asOf = rows.firstOrNull()?.asOf ?: it.asOf
                    )
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /**
     * Loads (or freshens) the open-session scan. Called when its toggle is
     * shown — like the pre-market list, a set older than two minutes is
     * recomputed, because the question is what can still move from NOW.
     */
    fun ensureIntraday() {
        if (_state.value.intradayRefreshing) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    intradayLoading = it.intradayRows.isEmpty(),
                    session = Dates.marketSessionNow(),
                    minutesToOpen = Dates.minutesToUsOpen()
                )
            }
            val rows = picks.ensureIntraday(_state.value.targetPct)
            _state.update {
                it.copy(
                    intradayRows = rows,
                    intradayLoading = false,
                    intradayAsOf = rows.firstOrNull()?.asOf ?: it.intradayAsOf
                )
            }
        }
    }

    /** Rescan the open session from fresh prints. */
    fun refreshIntraday() {
        if (_state.value.intradayRefreshing) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    intradayRefreshing = true,
                    session = Dates.marketSessionNow(),
                    minutesToOpen = Dates.minutesToUsOpen()
                )
            }
            try {
                val rows = picks.recomputeIntraday(_state.value.targetPct)
                _state.update {
                    it.copy(
                        intradayRows = rows.ifEmpty { it.intradayRows },
                        intradayLoading = false,
                        intradayAsOf = rows.firstOrNull()?.asOf ?: it.intradayAsOf
                    )
                }
            } finally {
                _state.update { it.copy(intradayRefreshing = false) }
            }
        }
    }

    /** Sets the daily profit goal and re-runs BOTH scans against it. */
    fun setTarget(pct: Double) {
        if (pct <= 0.0 || pct == _state.value.targetPct) return
        viewModelScope.launch {
            targets.setTarget(DAILY_TARGET_KEY, pct)
            _state.update {
                it.copy(
                    targetPct = pct,
                    loading = true,
                    rows = emptyList(),
                    intradayRows = emptyList(),
                    intradayLoading = true
                )
            }
            val rows = picks.ensurePreMarket(pct)
            _state.update {
                it.copy(
                    rows = rows,
                    loading = false,
                    asOf = rows.firstOrNull()?.asOf ?: it.asOf
                )
            }
            val intraday = picks.ensureIntraday(pct)
            _state.update {
                it.copy(
                    intradayRows = intraday,
                    intradayLoading = false,
                    intradayAsOf = intraday.firstOrNull()?.asOf ?: it.intradayAsOf
                )
            }
        }
    }

    companion object {
        /** Stored like a per-symbol target, under a reserved pseudo-symbol. */
        private const val DAILY_TARGET_KEY = "__DAILY_TARGET__"
    }
}
