package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.AdviceEngine
import com.aurum.invest.analytics.GoldCorrelation
import com.aurum.invest.analytics.StockCatalog
import com.aurum.invest.data.db.WatchItemEntity
import com.aurum.invest.data.model.Advice
import com.aurum.invest.data.model.GoldLink
import com.aurum.invest.data.model.Quote
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

/** One row of the sector browse / tag search list. */
data class BrowseRow(
    val symbol: String,
    val name: String,
    val quote: Quote?,
    val spark: List<Double>,
    /** Return over the last 10 trading days (~2 weeks); null when history is short. */
    val twoWeekPct: Double?,
    /** True for the sector's best 2-week performers — the gold-border highlight. */
    val top: Boolean
)

data class StocksState(
    // Watchlist mode.
    val rows: List<WatchRow> = emptyList(),
    val suggestions: List<Pair<String, String>> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    // Search mode — by tag.
    val tagQuery: String = "",
    val tagRows: List<BrowseRow> = emptyList(),
    val tagSearching: Boolean = false,
    // Search mode — by sector.
    val selectedSector: String = StockCatalog.SECTORS.first().name,
    val sectorRows: List<BrowseRow> = emptyList(),
    val sectorLoading: Boolean = false,
    // Live watch membership so every row can show/toggle its star.
    val watchedSymbols: Set<String> = emptySet()
)

class StocksViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val market = container.market
    private val watch = container.watch

    private val _state = MutableStateFlow(StocksState())
    val state: StateFlow<StocksState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var tagJob: Job? = null
    private var sectorJob: Job? = null
    private val refreshTick = MutableStateFlow(0)
    private val forceFresh = AtomicBoolean(false)

    companion object {
        /** How many of a list's best 2-week performers get the gold highlight. */
        private const val TOP_HIGHLIGHTS = 3

        /** Trading days behind the "2 weeks" read. */
        private const val TWO_WEEK_BARS = 10
    }

    init {
        viewModelScope.launch {
            combine(watch.observeAll(), refreshTick) { items, _ -> items }
                .collectLatest { items ->
                    _state.update { st ->
                        st.copy(watchedSymbols = items.map { it.symbol }.toSet())
                    }
                    loadRows(items)
                }
        }
    }

    // ---- watchlist mode (unchanged behavior from the old Watchlist tab) ----

    /** Pull-to-refresh: re-hydrates every row with cache-bypassing quotes. */
    fun refresh() {
        forceFresh.set(true)
        _state.update { it.copy(refreshing = true) }
        refreshTick.update { it + 1 }
    }

    private suspend fun loadRows(items: List<WatchItemEntity>) {
        val fresh = forceFresh.getAndSet(false)
        if (items.isEmpty()) {
            _state.update { it.copy(rows = emptyList(), loading = false, refreshing = false) }
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
        val quoteMaxAge = if (fresh) 0L else 60_000L
        // One batched request for every watched symbol instead of one call each.
        val quotes = market.getQuotes(items.map { it.symbol }, maxAgeMs = quoteMaxAge)
        val rows = coroutineScope {
            items.map { item ->
                async {
                    val quote = quotes[item.symbol]
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
        _state.update { it.copy(rows = rows, loading = false, refreshing = false) }
    }

    /** Debounced ticker search for ADDING to the watchlist: >= 2 chars, 300 ms. */
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

    /** Star toggle on browse rows: watched -> removed, unwatched -> added. */
    fun toggleWatch(symbol: String, name: String) {
        viewModelScope.launch {
            if (_state.value.watchedSymbols.contains(symbol)) watch.remove(symbol)
            else watch.add(symbol, name)
        }
    }

    // ---- search mode: by tag ----------------------------------------------

    /** Debounced free search over Yahoo's whole symbol directory. */
    fun onTagQueryChange(query: String) {
        _state.update { it.copy(tagQuery = query) }
        tagJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(tagRows = emptyList(), tagSearching = false) }
            return
        }
        tagJob = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(tagSearching = true) }
            val matches = market.search(trimmed)
            val rows = hydrateBrowseRows(matches, sortByPerformance = false)
            _state.update { it.copy(tagRows = rows, tagSearching = false) }
        }
    }

    // ---- search mode: by sector -------------------------------------------

    /** Loads the initial sector the first time the search view opens. */
    fun ensureSector() {
        if (_state.value.sectorRows.isEmpty() && !_state.value.sectorLoading) {
            selectSector(_state.value.selectedSector, force = true)
        }
    }

    fun selectSector(name: String, force: Boolean = false) {
        if (!force && name == _state.value.selectedSector && _state.value.sectorRows.isNotEmpty()) return
        val sector = StockCatalog.SECTORS.firstOrNull { it.name == name } ?: return
        sectorJob?.cancel()
        _state.update { it.copy(selectedSector = name, sectorRows = emptyList(), sectorLoading = true) }
        sectorJob = viewModelScope.launch {
            val rows = hydrateBrowseRows(sector.stocks, sortByPerformance = true)
            _state.update { st ->
                if (st.selectedSector == name) st.copy(sectorRows = rows, sectorLoading = false)
                else st
            }
        }
    }

    /**
     * Quotes + 30-day candles for [pairs]; computes each name's 2-week move
     * and flags the top performers. Sector lists are sorted best-first so the
     * highlight reads instantly; tag results keep their search relevance.
     */
    private suspend fun hydrateBrowseRows(
        pairs: List<Pair<String, String>>,
        sortByPerformance: Boolean
    ): List<BrowseRow> {
        if (pairs.isEmpty()) return emptyList()
        val quotes = try {
            market.getQuotes(pairs.map { it.first })
        } catch (_: Exception) {
            emptyMap()
        }
        val rows = ArrayList<BrowseRow>(pairs.size)
        for (chunk in pairs.chunked(8)) {
            val part = coroutineScope {
                chunk.map { (symbol, name) ->
                    async {
                        val daily = try {
                            market.getDailyCandles(symbol, 30)
                        } catch (_: Exception) {
                            emptyList()
                        }
                        val closes = daily.map { it.close }
                        val spark = closes.takeLast(30)
                        val twoWeek = if (closes.size > TWO_WEEK_BARS) {
                            val base = closes[closes.size - 1 - TWO_WEEK_BARS]
                            if (base > 0.0) (closes.last() - base) / base * 100.0 else null
                        } else null
                        BrowseRow(
                            symbol = symbol,
                            name = name,
                            quote = quotes[symbol],
                            spark = spark,
                            twoWeekPct = twoWeek,
                            top = false
                        )
                    }
                }.awaitAll()
            }
            rows.addAll(part)
        }
        // The gold highlight goes only to genuine 2-week outperformers —
        // never to "the least bad" of a falling list.
        val topSymbols = rows
            .filter { (it.twoWeekPct ?: 0.0) > 0.0 }
            .sortedByDescending { it.twoWeekPct ?: 0.0 }
            .take(TOP_HIGHLIGHTS)
            .map { it.symbol }
            .toSet()
        val flagged = rows.map { it.copy(top = it.symbol in topSymbols) }
        return if (sortByPerformance) {
            flagged.sortedByDescending { it.twoWeekPct ?: Double.NEGATIVE_INFINITY }
        } else flagged
    }
}
