package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.PeriodReport
import com.aurum.invest.analytics.ReportPeriod
import com.aurum.invest.analytics.ReportsEngine
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.model.TradeSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReportsState(
    val daily: List<PeriodReport> = emptyList(),
    val weekly: List<PeriodReport> = emptyList(),
    val monthly: List<PeriodReport> = emptyList(),
    val loading: Boolean = true
)

class ReportsViewModel(app: Application) : AndroidViewModel(app) {

    private val portfolio = (app as AurumApp).container.portfolio

    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    /** The ledger rows behind the current reports, keyed by id — edit source. */
    private var txById: Map<Long, TransactionEntity> = emptyMap()

    init {
        viewModelScope.launch {
            portfolio.observeTransactions().collectLatest { txs ->
                txById = txs.associateBy { it.id }
                val (daily, weekly, monthly) = withContext(Dispatchers.Default) {
                    Triple(
                        ReportsEngine.build(txs, ReportPeriod.DAY),
                        ReportsEngine.build(txs, ReportPeriod.WEEK),
                        ReportsEngine.build(txs, ReportPeriod.MONTH)
                    )
                }
                _state.value = ReportsState(
                    daily = daily,
                    weekly = weekly,
                    monthly = monthly,
                    loading = false
                )
            }
        }
    }

    /** The ledger row behind a report line; null when it no longer exists. */
    fun transaction(txId: Long): TransactionEntity? = txById[txId]

    /**
     * Corrects a trade shown in a report. The report is a VIEW of the ledger,
     * so the fix lands in the ledger itself — positions, P/L, and every
     * report period recompute automatically, and a changed date re-files the
     * trade under the day, week, and month it now belongs to.
     */
    fun updateTrade(
        tx: TransactionEntity,
        side: TradeSide,
        shares: Double,
        price: Double,
        fees: Double,
        ts: Long,
        plOverride: Double?
    ) {
        if (shares <= 0.0 || price <= 0.0) return
        viewModelScope.launch {
            runCatching {
                portfolio.updateTransaction(tx, side, shares, price, fees, ts, plOverride)
            }
        }
    }

    /** Removes a trade from the ledger; the reports recompute without it. */
    fun deleteTrade(tx: TransactionEntity) {
        viewModelScope.launch { runCatching { portfolio.deleteTransaction(tx) } }
    }
}
