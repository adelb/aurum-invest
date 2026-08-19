package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.AdviceEngine
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.BreakoutCall
import com.aurum.invest.analytics.BreakoutScout
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.SectorPulse
import com.aurum.invest.analytics.SectorRotation
import com.aurum.invest.analytics.StockCatalog
import com.aurum.invest.analytics.StockStudy
import com.aurum.invest.data.db.WatchItemEntity
import com.aurum.invest.data.repo.PortfolioRepository
import com.aurum.invest.data.model.Advice
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One fully-hydrated watchlist row. */
data class WatchRow(
    val symbol: String,
    val name: String,
    val pinned: Boolean,
    val quote: Quote?,
    val spark: List<Double>,
    val advice: Advice?
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
    val top: Boolean,
    /** This name's share of the user's book, null when not held. */
    val heldPct: Double? = null,
    /** The measured next-week breakout setup, null for the vast majority. */
    val breakout: BreakoutCall? = null
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
    /** Live rotation read per shelf name; empty until the trend scan lands. */
    val pulses: Map<String, SectorPulse> = emptyMap(),
    /** Next-week breakout calls for the selected sector, strongest first. */
    val breakouts: List<BreakoutCall> = emptyList(),
    val breakoutScanning: Boolean = false,
    /** The user's live book, so shelves can say what is already held. */
    val book: BookContext = BookContext.EMPTY,
    // Live watch membership so every row can show/toggle its star.
    val watchedSymbols: Set<String> = emptySet(),
    // Study mode — one name, fully evaluated, with the 1-month projection.
    val studyQuery: String = "",
    val studySuggestions: List<Pair<String, String>> = emptyList(),
    val studySearching: Boolean = false,
    val study: StockStudy? = null,
    val studyLoading: Boolean = false,
    val studyError: String = ""
)

class StocksViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val market = container.market
    private val watch = container.watch
    private val scout = BreakoutScout(container.market, container.news)

    private val _state = MutableStateFlow(StocksState())
    val state: StateFlow<StocksState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var tagJob: Job? = null
    private var sectorJob: Job? = null
    private var trendsJob: Job? = null
    private var studySearchJob: Job? = null
    private var studyJob: Job? = null
    private val refreshTick = MutableStateFlow(0)
    private val forceFresh = AtomicBoolean(false)

    companion object {
        /** How many of a list's best 2-week performers get the gold highlight. */
        private const val TOP_HIGHLIGHTS = 3

        /** Trading days behind the "2 weeks" read. */
        private const val TWO_WEEK_BARS = 10

        /** How often the live price ticker re-quotes the watchlist while it's on screen. */
        private const val LIVE_PRICE_TICK_MS = 15_000L
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
        // The user's book, kept live so shelf rows mark held names correctly
        // right after a trade lands.
        viewModelScope.launch {
            container.portfolio.observePositions().collectLatest { positions ->
                val open = positions.filter { PortfolioRepository.isOpen(it) }
                val book = if (open.isEmpty()) {
                    BookContext.EMPTY
                } else {
                    try {
                        val quotes = market.getQuotes(open.map { it.symbol })
                        val views = open.map { PortfolioRepository.toView(it, quotes[it.symbol]) }
                        val sectors = market.getSectors(open.map { it.symbol })
                        PortfolioLens.build(views, sectors)
                    } catch (_: Exception) {
                        BookContext.EMPTY
                    }
                }
                _state.update { st ->
                    st.copy(
                        book = book,
                        sectorRows = st.sectorRows.map { row ->
                            row.copy(heldPct = book.heldWeights[row.symbol])
                        }
                    )
                }
            }
        }
        // Live price ticker: re-quotes every watchlist row once a second
        // while this screen is actually on screen. Only the quote refreshes
        // on this cadence — sparklines/advice/gold-link stay from the last
        // full loadRows(), which would be far too expensive to redo every second.
        viewModelScope.launch {
            while (isActive) {
                delay(LIVE_PRICE_TICK_MS)
                if (_state.subscriptionCount.value == 0) continue
                runCatching { tickLiveQuotes() }
            }
        }
    }

    private suspend fun tickLiveQuotes() {
        val rows = _state.value.rows
        if (rows.isEmpty()) return
        val symbols = rows.map { it.symbol }.distinct()
        val quotes = market.getQuotes(symbols, maxAgeMs = LIVE_PRICE_TICK_MS - 100L)
        if (quotes.isEmpty()) return
        _state.update { st ->
            st.copy(rows = st.rows.map { row -> quotes[row.symbol]?.let { row.copy(quote = it) } ?: row })
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
                            advice = null
                        )
                },
                loading = st.rows.isEmpty()
            )
        }

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
                    WatchRow(
                        symbol = item.symbol,
                        name = item.name,
                        pinned = item.pinned,
                        quote = quote,
                        spark = spark,
                        advice = advice
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

    // ---- study mode --------------------------------------------------------

    /** Debounced search over the whole US directory — ticker or company name. */
    fun onStudyQueryChange(query: String) {
        _state.update { it.copy(studyQuery = query) }
        studySearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(studySuggestions = emptyList(), studySearching = false) }
            return
        }
        studySearchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(studySearching = true) }
            val results = market.search(trimmed)
            _state.update { it.copy(studySuggestions = results, studySearching = false) }
        }
    }

    /** Runs the full study for one picked name. */
    fun selectStudy(symbol: String, name: String) {
        studySearchJob?.cancel()
        studyJob?.cancel()
        _state.update {
            it.copy(
                studyQuery = "", studySuggestions = emptyList(),
                studyLoading = true, studyError = ""
            )
        }
        studyJob = viewModelScope.launch {
            val study = container.study.getStudy(symbol, name)
            _state.update {
                it.copy(
                    study = study ?: it.study,
                    studyLoading = false,
                    studyError = if (study == null) {
                        "Could not reach $symbol's price history — try again in a moment."
                    } else ""
                )
            }
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
        ensureTrends()
        if (_state.value.sectorRows.isEmpty() && !_state.value.sectorLoading) {
            selectSector(_state.value.selectedSector, force = true)
        }
    }

    /**
     * One rotation read per screen visit: the shared half-hour sector scan
     * (ETF momentum, volume, news tone) ranked across the catalog shelves.
     */
    private fun ensureTrends() {
        if (_state.value.pulses.isNotEmpty() || trendsJob?.isActive == true) return
        trendsJob = viewModelScope.launch {
            val trends = try {
                container.wealth.sectorTrends()
            } catch (_: Exception) {
                emptyList()
            }
            val pulses = SectorRotation.pulses(trends)
            if (pulses.isNotEmpty()) _state.update { it.copy(pulses = pulses) }
        }
    }

    fun selectSector(name: String, force: Boolean = false) {
        if (!force && name == _state.value.selectedSector && _state.value.sectorRows.isNotEmpty()) return
        val sector = StockCatalog.SECTORS.firstOrNull { it.name == name } ?: return
        sectorJob?.cancel()
        _state.update {
            it.copy(
                selectedSector = name,
                sectorRows = emptyList(),
                sectorLoading = true,
                breakouts = emptyList(),
                breakoutScanning = true
            )
        }
        sectorJob = viewModelScope.launch {
            val rows = hydrateBrowseRows(sector.stocks, sortByPerformance = true)
            _state.update { st ->
                if (st.selectedSector == name) st.copy(sectorRows = rows, sectorLoading = false)
                else st
            }
            // The forward look runs after the shelf is on screen: candles are
            // already cached from the hydrate, so only the finalists cost more.
            val calls = scout.scan(sector.stocks)
            _state.update { st ->
                if (st.selectedSector != name) st
                else st.copy(
                    breakouts = calls,
                    breakoutScanning = false,
                    sectorRows = st.sectorRows.map { row ->
                        row.copy(breakout = calls.firstOrNull { it.symbol == row.symbol })
                    }
                )
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
        // One batched read of the close lines for the whole shelf. This used to
        // be a request PER SYMBOL, eight at a time — a 22-name shelf cost 22
        // requests, and tapping through the catalogue cost hundreds in a couple
        // of minutes, which is what Yahoo's edge starts refusing outright. The
        // sparkline and the 2-week move only ever needed closes.
        val series = try {
            market.getCloseSeries(pairs.map { it.first }, rangeDays = 30)
        } catch (_: Exception) {
            emptyMap()
        }
        val rows = pairs.map { (symbol, name) ->
            val closes = series[symbol]?.map { it.close }.orEmpty()
            val twoWeek = if (closes.size > TWO_WEEK_BARS) {
                val base = closes[closes.size - 1 - TWO_WEEK_BARS]
                if (base > 0.0) (closes.last() - base) / base * 100.0 else null
            } else null
            BrowseRow(
                symbol = symbol,
                name = name,
                quote = quotes[symbol],
                spark = closes.takeLast(30),
                twoWeekPct = twoWeek,
                top = false,
                heldPct = _state.value.book.heldWeights[symbol]
            )
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
