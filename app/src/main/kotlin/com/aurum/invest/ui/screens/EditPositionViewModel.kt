package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.TradeSide
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditPositionState(
    val symbol: String = "",
    val loading: Boolean = true,
    val trades: List<TransactionEntity> = emptyList(),
    /** The position as the ledger currently computes it — updates live while editing. */
    val position: Position? = null
)

/**
 * Edits the ledger rows behind one holding. Positions, P/L and reports are
 * all derived from these rows, so correcting a trade here corrects the whole
 * app — no separate "adjustment" concept and no drift.
 */
class EditPositionViewModel(app: Application) : AndroidViewModel(app) {

    private val portfolio = (app as AurumApp).container.portfolio

    private val _state = MutableStateFlow(EditPositionState())
    val state: StateFlow<EditPositionState> = _state.asStateFlow()

    private var symbol: String = ""
    private var job: Job? = null

    fun start(symbol: String) {
        val cleaned = symbol.trim().uppercase()
        if (cleaned.isEmpty() || (cleaned == this.symbol && job != null)) return
        this.symbol = cleaned
        _state.value = EditPositionState(symbol = cleaned, loading = true)
        job?.cancel()
        job = viewModelScope.launch {
            portfolio.observeTransactionsFor(cleaned).collectLatest { trades ->
                val position = portfolio.positionsNow()
                    .firstOrNull { it.symbol.equals(cleaned, ignoreCase = true) }
                _state.update {
                    it.copy(loading = false, trades = trades, position = position)
                }
            }
        }
    }

    fun updateTrade(
        tx: TransactionEntity,
        side: TradeSide,
        shares: Double,
        price: Double,
        fees: Double,
        ts: Long,
        plOverride: Double? = tx.plOverride
    ) {
        if (shares <= 0.0 || price <= 0.0) return
        viewModelScope.launch {
            runCatching {
                portfolio.updateTransaction(tx, side, shares, price, fees, ts, plOverride)
            }
        }
    }

    fun deleteTrade(tx: TransactionEntity) {
        viewModelScope.launch { runCatching { portfolio.deleteTransaction(tx) } }
    }

    companion object {
        /** Weighted-average cost of the open position, for the header. */
        fun isOpen(position: Position?): Boolean =
            position != null && PortfolioRepository.isOpen(position)
    }
}
