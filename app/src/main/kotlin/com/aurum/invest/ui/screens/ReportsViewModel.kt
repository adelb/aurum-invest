package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.PeriodReport
import com.aurum.invest.analytics.ReportPeriod
import com.aurum.invest.analytics.ReportsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReportsState(
    val weekly: List<PeriodReport> = emptyList(),
    val monthly: List<PeriodReport> = emptyList(),
    val loading: Boolean = true
)

class ReportsViewModel(app: Application) : AndroidViewModel(app) {

    private val portfolio = (app as AurumApp).container.portfolio

    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            portfolio.observeTransactions().collectLatest { txs ->
                val (weekly, monthly) = withContext(Dispatchers.Default) {
                    ReportsEngine.build(txs, ReportPeriod.WEEK) to
                        ReportsEngine.build(txs, ReportPeriod.MONTH)
                }
                _state.value = ReportsState(weekly = weekly, monthly = monthly, loading = false)
            }
        }
    }
}
