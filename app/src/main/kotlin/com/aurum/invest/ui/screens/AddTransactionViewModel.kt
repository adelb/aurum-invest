package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.TradeSide
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.round

/** Which of the two synced quantity fields the user edited last; price edits recompute the other. */
enum class AddTxLastEdited { SHARES, AMOUNT }

/** Form state for the Add-trade screen. Numeric fields are raw text; validation is derived. */
data class AddTxState(
    val side: TradeSide = TradeSide.BUY,
    val symbol: String = "",
    val shares: String = "",
    val price: String = "",
    val amount: String = "",
    val fees: String = "",
    val suggestions: List<Pair<String, String>> = emptyList(),
    val saving: Boolean = false,
    val held: Position? = null,
    val sellAll: Boolean = false,
    val lastEdited: AddTxLastEdited = AddTxLastEdited.SHARES
) {
    val sharesVal: Double? get() = shares.trim().toDoubleOrNull()
    val priceVal: Double? get() = price.trim().toDoubleOrNull()
    val amountVal: Double? get() = amount.trim().toDoubleOrNull()
    val feesVal: Double get() = fees.trim().toDoubleOrNull() ?: 0.0

    private val feesOk: Boolean
        get() = fees.isBlank() || (fees.trim().toDoubleOrNull()?.let { it >= 0.0 } == true)

    val valid: Boolean
        get() = symbol.isNotBlank() &&
            (sharesVal ?: 0.0) > 0.0 &&
            (priceVal ?: 0.0) > 0.0 &&
            feesOk

    /** Buy: cost incl. fees. Sell: net proceeds after fees. */
    val total: Double
        get() {
            val sh = sharesVal ?: return 0.0
            val pr = priceVal ?: return 0.0
            return if (side == TradeSide.BUY) sh * pr + feesVal else (sh * pr - feesVal).coerceAtLeast(0.0)
        }
}

class AddTransactionViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val market = container.market
    private val portfolio = container.portfolio

    private val _state = MutableStateFlow(AddTxState())
    val state: StateFlow<AddTxState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var heldJob: Job? = null
    private var prefillApplied = false

    // Plain (comma-free) formatters so field text stays round-trippable through toDoubleOrNull.
    private val shares4 = DecimalFormat("0.####", DecimalFormatSymbols(Locale.US))
    private val shares8 = DecimalFormat("0.########", DecimalFormatSymbols(Locale.US))
    private val amount2 = DecimalFormat("0.##", DecimalFormatSymbols(Locale.US))

    /** Apply the navigation prefill exactly once (never clobbers user typing). */
    fun applyPrefill(symbol: String?, side: String?) {
        if (prefillApplied) return
        prefillApplied = true
        val prefSide = if (side?.trim()?.uppercase() == TradeSide.SELL.name) TradeSide.SELL else TradeSide.BUY
        _state.update { st ->
            if (!symbol.isNullOrBlank()) st.copy(side = prefSide, symbol = symbol.trim().uppercase())
            else st.copy(side = prefSide)
        }
        val sym = _state.value.symbol
        if (sym.isNotBlank()) refreshHeld(sym)
    }

    fun setSide(side: TradeSide) {
        _state.update { st ->
            val next = if (side == TradeSide.BUY) {
                st.copy(side = side, sellAll = false)
            } else {
                st.copy(side = side)
            }
            // The fee direction flips with the side — keep the fields in sync.
            when {
                next.sellAll -> syncFromShares(next)
                next.lastEdited == AddTxLastEdited.AMOUNT -> syncFromAmount(next)
                else -> syncFromShares(next)
            }
        }
    }

    fun onSymbolChange(value: String) {
        val cleaned = value.uppercase().filter { !it.isWhitespace() }
        _state.update { it.copy(symbol = cleaned, held = null, sellAll = false) }
        refreshHeld(cleaned)
        searchJob?.cancel()
        if (cleaned.length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            val results = market.search(cleaned)
            _state.update { it.copy(suggestions = results) }
        }
    }

    fun pickSuggestion(symbol: String) {
        searchJob?.cancel()
        val sym = symbol.uppercase()
        _state.update { it.copy(symbol = sym, suggestions = emptyList(), held = null, sellAll = false) }
        refreshHeld(sym)
    }

    fun onSharesChange(value: String) {
        if (_state.value.sellAll) return
        _state.update {
            syncFromShares(it.copy(shares = sanitizeDecimal(value), lastEdited = AddTxLastEdited.SHARES))
        }
    }

    fun onAmountChange(value: String) {
        if (_state.value.sellAll) return
        _state.update {
            syncFromAmount(it.copy(amount = sanitizeDecimal(value), lastEdited = AddTxLastEdited.AMOUNT))
        }
    }

    fun onPriceChange(value: String) {
        _state.update { st ->
            val next = st.copy(price = sanitizeDecimal(value))
            when {
                next.sellAll -> syncFromShares(next)
                next.lastEdited == AddTxLastEdited.AMOUNT -> syncFromAmount(next)
                else -> syncFromShares(next)
            }
        }
    }

    fun onFeesChange(value: String) {
        _state.update { st ->
            val next = st.copy(fees = sanitizeDecimal(value))
            // Fees change what the full amount buys/nets — re-sync whichever
            // field the user did not type in.
            when {
                next.sellAll -> syncFromShares(next)
                next.lastEdited == AddTxLastEdited.AMOUNT -> syncFromAmount(next)
                else -> syncFromShares(next)
            }
        }
    }

    /** ON: shares pinned to the full held quantity (fields lock); OFF: editable again. */
    fun setSellAll(on: Boolean) {
        _state.update { st ->
            val held = st.held
            if (on && held != null) {
                syncFromShares(
                    st.copy(
                        sellAll = true,
                        shares = shares8.format(held.shares),
                        lastEdited = AddTxLastEdited.SHARES
                    )
                )
            } else {
                st.copy(sellAll = false)
            }
        }
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (!s.valid || s.saving) return
        val shares = s.sharesVal ?: return
        val price = s.priceVal ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                portfolio.addTransaction(
                    symbol = s.symbol.trim().uppercase(),
                    side = s.side,
                    shares = shares,
                    price = price,
                    fees = s.feesVal,
                    source = "MANUAL"
                )
                onDone()
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    /** Load the open position (if any) for [symbol]; drops stale results if the symbol moved on. */
    private fun refreshHeld(symbol: String) {
        heldJob?.cancel()
        val sym = symbol.trim().uppercase()
        if (sym.isBlank()) return
        heldJob = viewModelScope.launch {
            val match = portfolio.positionsNow()
                .filter { PortfolioRepository.isOpen(it) }
                .firstOrNull { it.symbol == sym }
            _state.update { st ->
                if (st.symbol.trim().uppercase() != sym) st
                else if (match == null) st.copy(held = null, sellAll = false)
                else st.copy(held = match)
            }
        }
    }

    /**
     * Amount is the FULL money that moves, fees included: what leaves the
     * account on a buy (shares*price + fees), what lands in it on a sell
     * (shares*price - fees).
     */
    private fun syncFromShares(st: AddTxState): AddTxState {
        val sh = st.shares.trim().toDoubleOrNull()
        val pr = st.price.trim().toDoubleOrNull()
        if (sh == null || pr == null || pr <= 0.0) return st
        val full = if (st.side == TradeSide.BUY) {
            sh * pr + st.feesVal
        } else {
            (sh * pr - st.feesVal).coerceAtLeast(0.0)
        }
        return st.copy(amount = amount2.format(full))
    }

    /**
     * Shares follow from the full amount with fees deducted first: a buy of
     * "$1,000 with $5 fees" purchases $995 of stock; a sell that nets $1,000
     * after $5 fees must sell $1,005 of stock.
     */
    private fun syncFromAmount(st: AddTxState): AddTxState {
        val am = st.amount.trim().toDoubleOrNull()
        val pr = st.price.trim().toDoubleOrNull()
        if (am == null || pr == null || pr <= 0.0) return st
        val stockValue = if (st.side == TradeSide.BUY) am - st.feesVal else am + st.feesVal
        val sh = (round(stockValue / pr * 10_000.0) / 10_000.0).coerceAtLeast(0.0)
        return st.copy(shares = shares4.format(sh))
    }

    private fun sanitizeDecimal(value: String): String {
        val filtered = value.filter { it.isDigit() || it == '.' }
        // keep at most one decimal point
        val firstDot = filtered.indexOf('.')
        return if (firstDot < 0) filtered
        else filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "")
    }
}
