package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.PeriodReport
import com.aurum.invest.analytics.ReportPeriod
import com.aurum.invest.analytics.ReportsEngine
import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.model.TradeSide
import com.aurum.invest.data.repo.PortfolioRepository
import com.aurum.invest.data.repo.WalletState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReportsState(
    val daily: List<PeriodReport> = emptyList(),
    val weekly: List<PeriodReport> = emptyList(),
    val monthly: List<PeriodReport> = emptyList(),
    val yearly: List<PeriodReport> = emptyList(),
    val loading: Boolean = true,
    /**
     * The same derived wallet the dashboard shows — total, invested,
     * liquidity, realized/total P/L and net worth. Built from one
     * [com.aurum.invest.data.repo.WalletState], so the two screens cannot
     * quote different numbers for the same book.
     */
    val wallet: WalletState = WalletState.UNSET,
    /**
     * Open holdings with no live quote, carried at cost inside net worth.
     * Net worth is only exact when this is 0, and the card says so.
     */
    val unpricedCount: Int = 0
)

class ReportsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val portfolio = container.portfolio
    private val wallet = container.wallet

    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    /** The ledger rows behind the current reports, keyed by id — edit source. */
    private var txById: Map<Long, TransactionEntity> = emptyMap()

    init {
        viewModelScope.launch {
            combine(
                portfolio.observeTransactions(),
                portfolio.observePositions(),
                wallet.total,
                wallet.configured
            ) { txs, positions, walletTotal, walletConfigured ->
                Quad(txs, positions, walletTotal, walletConfigured)
            }.collectLatest { (txs, positions, walletTotal, walletConfigured) ->
                txById = txs.associateBy { it.id }
                // Net worth needs the holdings at market, so this screen prices
                // the open book the same way the dashboard does. Quotes are
                // cache-served; a failure leaves positions carried at cost and
                // [ReportsState.unpricedCount] makes the card say so.
                val open = positions.filter { PortfolioRepository.isOpen(it) }
                val quotes = runCatching { container.market.getQuotes(open.map { it.symbol }) }
                    .getOrDefault(emptyMap())
                val views = open.map { PortfolioRepository.toView(it, quotes[it.symbol]) }
                // Realized P/L over ALL positions, closed ones included — a
                // fully-sold winner is gone from the book but its proceeds are
                // sitting in cash, so it must still count toward liquidity.
                val summary = PortfolioRepository.summarize(views, positions)
                val walletState = WalletState.of(walletTotal, walletConfigured, summary)
                val (daily, weekly, monthly, yearly) = withContext(Dispatchers.Default) {
                    Quad(
                        ReportsEngine.build(txs, ReportPeriod.DAY),
                        ReportsEngine.build(txs, ReportPeriod.WEEK),
                        ReportsEngine.build(txs, ReportPeriod.MONTH),
                        ReportsEngine.build(txs, ReportPeriod.YEAR)
                    )
                }
                // The Daily tab only ever shows the current week's days — older
                // days are reached via the Weekly tab, which collapses them
                // under their week.
                val currentWeekStart = Dates.currentWeekStartIso()
                val dailyThisWeek = daily.filter { it.periodKey >= currentWeekStart }
                _state.value = ReportsState(
                    daily = dailyThisWeek,
                    weekly = weekly,
                    monthly = monthly,
                    yearly = yearly,
                    loading = false,
                    wallet = walletState,
                    unpricedCount = summary.unpricedCount
                )
            }
        }
    }

    /** Small local tuple to carry four combined values without extra allocations elsewhere. */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

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
