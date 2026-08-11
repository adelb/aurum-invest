package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
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
    /** Where in the trading day the user currently is, for the header note. */
    val session: Dates.PowerWindow = Dates.PowerWindow.BEFORE
)

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
        viewModelScope.launch {
            val saved = targets.getTarget(DAILY_TARGET_KEY)
                ?: PreMarketPicker.DEFAULT_TARGET_PCT
            _state.update { it.copy(targetPct = saved) }
            val stored = picks.ensurePreMarket(saved)
            _state.update { it.copy(rows = stored, loading = false) }
        }
    }

    /** Rescan with fresh pre-market prints. */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                val rows = picks.recomputePreMarket(_state.value.targetPct)
                _state.update { it.copy(rows = rows, loading = false) }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** Sets the daily profit goal and re-runs the scan against it. */
    fun setTarget(pct: Double) {
        if (pct <= 0.0 || pct == _state.value.targetPct) return
        viewModelScope.launch {
            targets.setTarget(DAILY_TARGET_KEY, pct)
            _state.update { it.copy(targetPct = pct, loading = true, rows = emptyList()) }
            val rows = picks.ensurePreMarket(pct)
            _state.update { it.copy(rows = rows, loading = false) }
        }
    }

    companion object {
        /** Stored like a per-symbol target, under a reserved pseudo-symbol. */
        private const val DAILY_TARGET_KEY = "__DAILY_TARGET__"
    }
}
