package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.AdviceEngine
import com.aurum.invest.analytics.GoldCorrelation
import com.aurum.invest.data.db.WatchItemEntity
import com.aurum.invest.data.model.Advice
import com.aurum.invest.data.model.GoldLink
import com.aurum.invest.data.model.Quote
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One fully-hydrated watchlist row. */
data class WatchRow(
    val symbol: String,
    val name: String,
    val pinned: Boolean,
    val quote: Quote?,
    val spark: List<Double>,
    val advice: Advice?,
    val goldLink: GoldLink?
)

data class WatchState(
    val rows: List<WatchRow> = emptyList(),
    val suggestions: List<Pair<String, String>> = emptyList(),
    val query: String = "",
    val loading: Boolean = true
)

class WatchlistViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val market = container.market
    private val watch = container.watch

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            watch.observeAll().collectLatest { items -> loadRows(items) }
        }
    }

    private suspend fun loadRows(items: List<WatchItemEntity>) {
        if (items.isEmpty()) {
            _state.update { it.copy(rows = emptyList(), loading = false) }
            return
        }
        // Seed placeholder rows immediately so new symbols and pin toggles feel instant;
        // any data already loaded for a symbol is kept while it refreshes.
        val existing = _state.value.rows.associateBy { it.symbol }
        _state.update { st ->
            st.copy(
                rows = items.map { item ->
                    existing[item.symbol]?.copy(pinned = item.pinned, name = item.name)
                        ?: WatchRow(
                            symbol = item.symbol,
                            name = item.name,
                            pinned = item.pinned,
                            quote = null,
                            spark = emptyList(),
                            advice = null,
                            goldLink = null
                        )
                },
                loading = st.rows.isEmpty()
            )
        }

        val goldCandles = if (items.any { it.pinned }) market.getGoldCandles() else emptyList()
        val rows = coroutineScope {
            items.map { item ->
                async {
                    val quote = market.getQuote(item.symbol)
                    val daily = market.getDailyCandles(item.symbol, 120)
                    val spark = daily.takeLast(30).map { c -> c.close }
                    val advice = if (quote != null && daily.isNotEmpty()) {
                        runCatching { AdviceEngine.buyAdvice(quote, daily) }.getOrNull()
                    } else null
                    val goldLink = if (item.pinned && daily.isNotEmpty() && goldCandles.isNotEmpty()) {
                        runCatching { GoldCorrelation.relation(daily, goldCandles).link }.getOrNull()
                    } else null
                    WatchRow(
                        symbol = item.symbol,
                        name = item.name,
                        pinned = item.pinned,
                        quote = quote,
                        spark = spark,
                        advice = advice,
                        goldLink = goldLink
                    )
                }
            }.awaitAll()
        }
        _state.update { it.copy(rows = rows, loading = false) }
    }

    /** Debounced ticker search: >= 2 chars, 300 ms after the last keystroke. */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            val results = market.search(trimmed)
            _state.update { it.copy(suggestions = results) }
        }
    }

    fun addSymbol(symbol: String, name: String) {
        searchJob?.cancel()
        viewModelScope.launch {
            watch.add(symbol, name)
            _state.update { it.copy(query = "", suggestions = emptyList()) }
        }
    }

    fun setPinned(symbol: String, pinned: Boolean) {
        viewModelScope.launch { watch.setPinned(symbol, pinned) }
    }

    fun remove(symbol: String) {
        viewModelScope.launch { watch.remove(symbol) }
    }
}
