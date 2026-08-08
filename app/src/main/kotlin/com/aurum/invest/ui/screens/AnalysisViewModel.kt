package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.TechniqueAnalysis
import com.aurum.invest.analytics.Techniques
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalysisState(
    val symbol: String = "",
    val loading: Boolean = true,
    val analysis: TechniqueAnalysis? = null,
    val price: Double? = null
)

class AnalysisViewModel(app: Application) : AndroidViewModel(app) {

    private val market = (app as AurumApp).container.market

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    private var job: Job? = null

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
            _state.update { it.copy(loading = true) }
            val candles = try {
                market.getDailyCandles(sym, 180)
            } catch (_: Exception) {
                emptyList()
            }
            val quote = try {
                market.getQuote(sym)
            } catch (_: Exception) {
                null
            }
            val analysis = Techniques.analyze(sym, candles)
            _state.update {
                it.copy(
                    loading = false,
                    analysis = analysis,
                    price = quote?.price ?: candles.lastOrNull()?.close
                )
            }
        }
    }
}
