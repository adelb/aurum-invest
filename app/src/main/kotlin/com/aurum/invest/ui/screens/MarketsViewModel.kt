package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.Universes
import com.aurum.invest.data.model.AssetClass
import com.aurum.invest.data.model.Quote
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One quoted instrument row in the Markets screen. */
data class MarketRow(
    val symbol: String,
    val name: String,
    val quote: Quote?
)

/** Rows grouped by asset class, in the display order we want on-screen. */
data class MarketsState(
    val metals: List<MarketRow> = emptyList(),
    val fx: List<MarketRow> = emptyList(),
    val indices: List<MarketRow> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false
)

class MarketsViewModel(app: Application) : AndroidViewModel(app) {

    private val market = (app as AurumApp).container.market

    private val _state = MutableStateFlow(MarketsState())
    val state: StateFlow<MarketsState> = _state.asStateFlow()

    private val forceFresh = AtomicBoolean(false)

    init {
        // Seed the sections with skeleton rows immediately so the screen looks
        // structured while the first fetch completes.
        _state.update {
            it.copy(
                metals = Universes.METALS.map { (s, n) -> MarketRow(s, n, null) },
                fx = Universes.FX.map { (s, n) -> MarketRow(s, n, null) },
                indices = Universes.INDICES.map { (s, n) -> MarketRow(s, n, null) }
            )
        }
        load()
    }

    fun refresh() {
        forceFresh.set(true)
        _state.update { it.copy(refreshing = true) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val fresh = forceFresh.getAndSet(false)
            val maxAge = if (fresh) 0L else 60_000L
            val quotes = fetchQuotes(Universes.ALL_NON_EQUITY.map { it.first }, maxAge)
            _state.update { current ->
                current.copy(
                    metals = hydrate(current.metals, quotes),
                    fx = hydrate(current.fx, quotes),
                    indices = hydrate(current.indices, quotes),
                    loading = false,
                    refreshing = false
                )
            }
        }
    }

    private fun hydrate(rows: List<MarketRow>, quotes: Map<String, Quote>): List<MarketRow> =
        rows.map { r -> r.copy(quote = quotes[r.symbol] ?: r.quote) }

    private suspend fun fetchQuotes(symbols: List<String>, maxAge: Long): Map<String, Quote> =
        coroutineScope {
            symbols.map { s -> async { s to market.getQuote(s, maxAge) } }
                .awaitAll()
                .mapNotNull { (s, q) -> q?.let { s to it } }
                .toMap()
        }

    @Suppress("unused") // kept for future filters (buttons per class, etc.)
    fun rowsOf(cls: AssetClass): List<MarketRow> = when (cls) {
        AssetClass.METAL -> _state.value.metals
        AssetClass.FX -> _state.value.fx
        AssetClass.INDEX -> _state.value.indices
        AssetClass.EQUITY -> emptyList()
    }
}
