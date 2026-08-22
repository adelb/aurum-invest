package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.BeatSpyEngine
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.DailyPick
import com.aurum.invest.data.model.EntryPick
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.PowerPick
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.model.WeeklyPick
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One weekly pick enriched with the live quote for the since-pick delta. */
data class PickRow(
    val pick: WeeklyPick,
    val quote: Quote?,
    val sincePickPct: Double?
)

/**
 * One current pick whose price sits in the GREEN entry zone of the Beat-SPY
 * race: at or under the soft-quartile edge, where even a soft (Q1) measured
 * buy-horizon outcome beats SPY's median over the same days.
 */
data class SpyGreenRow(
    val symbol: String,
    val name: String,
    /** The raced price — live quote when available, else the last close. */
    val price: Double,
    val edgeEntry: Double,
    val breakevenEntry: Double,
    /** How much higher the price could sit and still be green, percent. */
    val headroomPct: Double,
    val beatSharePct: Double,
    val medianEdgePct: Double,
    val stockMedianPct: Double,
    val spyMedianPct: Double,
    val analogCount: Int,
    val horizonLabel: String,
    val verdict: TechniqueVerdict,
    /** Which pick lists carried the symbol (Daily, Entries, Power, Weekly, Under $25). */
    val sources: List<String>
)

data class PicksState(
    val rows: List<PickRow> = emptyList(),
    val budgetRows: List<PickRow> = emptyList(),
    val weekLabel: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    // Daily picks — same-day 3-10%+ candidates, off on Saturdays.
    val dailyRows: List<DailyPick> = emptyList(),
    val dailyLabel: String = "",
    val dailyLoading: Boolean = true,
    val dailyRefreshing: Boolean = false,
    val saturday: Boolean = false,
    // Best entries — market-wide scan for stocks at a good entry price now.
    val entryRows: List<EntryPick> = emptyList(),
    val entryLoading: Boolean = true,
    val entryRefreshing: Boolean = false,
    // Power hour — buy in the last 90 min of the session for next-day strength.
    val powerRows: List<PowerPick> = emptyList(),
    val powerLoading: Boolean = true,
    val powerRefreshing: Boolean = false,
    // SPY tab — every current pick raced against the index, kept only when
    // today's price sits in the green entry zone of the Beat-SPY race.
    val spyRows: List<SpyGreenRow> = emptyList(),
    val spyLoading: Boolean = true,
    val spyRefreshing: Boolean = false,
    val spyScanned: Int = 0,
    val spyTotal: Int = 0,
    /** Picks that could not be raced — too young a listing or missing history. */
    val spyUnraced: Int = 0,
    // The user's book + sector classification of visible picks, so every
    // suggestion can be read against what is actually held.
    val book: BookContext = BookContext.EMPTY,
    val pickSectors: Map<String, String> = emptyMap(),
    // Live pre/post-market read per visible symbol, so every pick card can
    // carry the same extended-hours chips the portfolio shows.
    val extHours: Map<String, ExtendedHours> = emptyMap()
)

class PicksViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val TAB_DAILY = "daily"
        const val TAB_ENTRIES = "entries"
        const val TAB_POWER = "power"
        const val TAB_WEEKLY = "weekly"
        const val TAB_SPY = "spy"

        /** Five years of dailies per race — the long horizons' analog pool. */
        private const val BEAT_SPY_DAYS = 1825
    }

    private val container = (app as AurumApp).container
    private val picks = container.picks
    private val market = container.market

    /** Tabs whose scan has already been kicked off this session. */
    private val started = java.util.Collections.synchronizedSet(HashSet<String>())

    private val _state = MutableStateFlow(
        PicksState(
            weekLabel = Dates.weekStartLabel(Dates.currentWeekStartIso()),
            dailyLabel = Dates.todayLabel(),
            saturday = Dates.isSaturday(),
            dailyLoading = !Dates.isSaturday()
        )
    )
    val state: StateFlow<PicksState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            picks.observeCurrentWeek().collectLatest { list ->
                if (list.isEmpty()) {
                    _state.update { it.copy(rows = emptyList()) }
                    return@collectLatest
                }
                // Show the picks immediately, then enrich with live quotes.
                _state.update { st ->
                    val known = st.rows.associateBy { it.pick.symbol }
                    st.copy(
                        rows = list.map { p -> known[p.symbol]?.copy(pick = p) ?: PickRow(p, null, null) },
                        loading = false
                    )
                }
                val quotes = market.getQuotes(list.map { it.symbol })
                _state.update { st ->
                    st.copy(
                        rows = list.map { p ->
                            val q = quotes[p.symbol]
                            val since = if (q != null && p.priceAtPick > 0.0) {
                                (q.price - p.priceAtPick) / p.priceAtPick * 100.0
                            } else null
                            PickRow(pick = p, quote = q, sincePickPct = since)
                        },
                        loading = false
                    )
                }
            }
        }
        viewModelScope.launch {
            picks.observeBudgetWeek().collectLatest { list ->
                if (list.isEmpty()) {
                    _state.update { it.copy(budgetRows = emptyList()) }
                    return@collectLatest
                }
                _state.update { st ->
                    val known = st.budgetRows.associateBy { it.pick.symbol }
                    st.copy(
                        budgetRows = list.map { p ->
                            known[p.symbol]?.copy(pick = p) ?: PickRow(p, null, null)
                        }
                    )
                }
                val quotes = market.getQuotes(list.map { it.symbol })
                _state.update { st ->
                    st.copy(
                        budgetRows = list.map { p ->
                            val q = quotes[p.symbol]
                            val since = if (q != null && p.priceAtPick > 0.0) {
                                (q.price - p.priceAtPick) / p.priceAtPick * 100.0
                            } else null
                            PickRow(pick = p, quote = q, sincePickPct = since)
                        }
                    )
                }
            }
        }
        // NOTE: the four market-wide scans are NOT started here. Each one
        // sweeps hundreds of symbols, and firing all four on open buried the
        // app in requests (and tripped Yahoo's throttling). They load when
        // their tab is actually shown — see [ensureTab].
        // The user's book, kept live so pick notes stay accurate after trades.
        viewModelScope.launch {
            container.portfolio.observePositions().collectLatest { positions ->
                val open = positions.filter { PortfolioRepository.isOpen(it) }
                if (open.isEmpty()) {
                    _state.update { it.copy(book = BookContext.EMPTY) }
                    return@collectLatest
                }
                val quotes = market.getQuotes(open.map { it.symbol })
                val views = open.map { PortfolioRepository.toView(it, quotes[it.symbol]) }
                val sectors = market.getSectors(open.map { it.symbol })
                _state.update { it.copy(book = PortfolioLens.build(views, sectors)) }
            }
        }
        // Classify whichever picks are on screen; cached lookups make this cheap.
        viewModelScope.launch {
            _state
                .map { st ->
                    (st.rows.map { it.pick.symbol } +
                        st.budgetRows.map { it.pick.symbol } +
                        st.dailyRows.map { it.symbol } +
                        st.entryRows.map { it.symbol } +
                        st.powerRows.map { it.symbol } +
                        st.spyRows.map { it.symbol }).toSet()
                }
                .distinctUntilChanged()
                .collect { symbols ->
                    // Pre/post-market prints for every visible pick — repo
                    // caching (5 min) keeps repeat visits cheap.
                    launch { loadExtHours(symbols) }
                    val missing = symbols - _state.value.pickSectors.keys
                    if (missing.isEmpty()) return@collect
                    val fetched = market.getSectors(missing.toList())
                    if (fetched.isNotEmpty()) {
                        _state.update { it.copy(pickSectors = it.pickSectors + fetched) }
                    }
                }
        }
    }

    /**
     * Extended-hours reads for [symbols], chunked so a full board (~45 names)
     * never bursts Yahoo. Failures are simply absent; entries refresh through
     * the repo's short cache.
     */
    private suspend fun loadExtHours(symbols: Collection<String>, maxAgeMs: Long = 300_000L) {
        val wanted = symbols.toSet()
        if (wanted.isEmpty()) return
        for (chunk in wanted.chunked(8)) {
            val fetched = coroutineScope {
                chunk.map { sym ->
                    async {
                        val ext = runCatching {
                            market.getExtendedHours(sym, maxAgeMs = maxAgeMs)
                        }.getOrNull()
                        ext?.let { sym to it }
                    }
                }.awaitAll()
            }.filterNotNull()
            if (fetched.isNotEmpty()) {
                _state.update { it.copy(extHours = it.extHours + fetched) }
            }
        }
    }

    /**
     * Loads the data for [tab] the first time it is shown. Each scan runs at
     * most once per session; already-loaded tabs return immediately.
     */
    fun ensureTab(tab: String) {
        if (!started.add(tab)) return
        when (tab) {
            TAB_DAILY -> viewModelScope.launch {
                if (Dates.isSaturday()) {
                    _state.update { it.copy(dailyLoading = false) }
                    return@launch
                }
                val daily = picks.ensureDaily()
                _state.update { it.copy(dailyRows = daily, dailyLoading = false) }
            }
            TAB_ENTRIES -> viewModelScope.launch {
                val entries = picks.ensureEntries()
                _state.update { it.copy(entryRows = entries, entryLoading = false) }
            }
            TAB_POWER -> viewModelScope.launch {
                val power = picks.ensurePower()
                _state.update { it.copy(powerRows = power, powerLoading = false) }
            }
            TAB_WEEKLY -> viewModelScope.launch {
                picks.ensureCurrentWeek()
                _state.update { it.copy(loading = false) }
                picks.ensureBudgetWeek()
            }
            TAB_SPY -> viewModelScope.launch { scanSpy() }
        }
    }

    /** Re-race the current picks against SPY from fresh quotes. */
    fun refreshSpy() {
        if (_state.value.spyRefreshing || _state.value.spyLoading) return
        viewModelScope.launch {
            _state.update { it.copy(spyRefreshing = true) }
            try {
                scanSpy(quoteMaxAgeMs = 60_000L)
            } finally {
                _state.update { it.copy(spyRefreshing = false) }
            }
        }
    }

    /**
     * The SPY tab's scan: every stock in today's pick lists (computed once
     * when a list is absent, served stored otherwise) raced against the index
     * over five years of paired windows, and kept only when today's price
     * sits in the GREEN entry zone — at or under the soft-quartile edge.
     * Candle fetches go out in small chunks so a ~45-name board never bursts
     * Yahoo; results land progressively as each chunk finishes.
     */
    private suspend fun scanSpy(quoteMaxAgeMs: Long = 300_000L) {
        _state.update {
            it.copy(
                spyRows = emptyList(), spyScanned = 0, spyTotal = 0,
                spyUnraced = 0, spyLoading = true
            )
        }
        try {
            // The stocks now in the picks. Sequential on purpose: each ensure
            // can be a market-wide sweep, and firing them together buried the
            // app in requests before (see the note in init).
            val daily = if (Dates.isSaturday()) emptyList() else picks.ensureDaily()
            val entries = picks.ensureEntries()
            val power = picks.ensurePower()
            val weekly = picks.ensureCurrentWeek()
            val budget = picks.ensureBudgetWeek()

            val sources = LinkedHashMap<String, Pair<String, MutableList<String>>>()
            fun add(symbol: String, name: String, source: String) {
                val key = symbol.trim().uppercase()
                if (key.isEmpty() || key == "SPY") return
                val cur = sources.getOrPut(key) { name to mutableListOf() }
                if (cur.first.isBlank() && name.isNotBlank()) sources[key] = name to cur.second
                if (source !in cur.second) cur.second.add(source)
            }
            daily.forEach { add(it.symbol, it.name, "Daily") }
            entries.forEach { add(it.symbol, it.name, "Entries") }
            power.forEach { add(it.symbol, it.name, "Power") }
            weekly.forEach { add(it.symbol, it.name, "Weekly") }
            budget.forEach { add(it.symbol, it.name, "Under $25") }

            val symbols = sources.keys.toList()
            _state.update { it.copy(spyTotal = symbols.size) }
            if (symbols.isEmpty()) return

            // The index side of every race, fetched once.
            val spyCandles = runCatching {
                market.getDailyCandles("SPY", BEAT_SPY_DAYS)
            }.getOrDefault(emptyList())
            if (spyCandles.isEmpty()) {
                _state.update {
                    it.copy(spyScanned = symbols.size, spyUnraced = symbols.size)
                }
                return
            }
            val spyQuote = runCatching {
                market.getQuote("SPY", maxAgeMs = quoteMaxAgeMs)
            }.getOrNull()
            val quotes = runCatching {
                market.getQuotes(symbols, maxAgeMs = quoteMaxAgeMs)
            }.getOrDefault(emptyMap())

            val green = ArrayList<SpyGreenRow>()
            var scanned = 0
            var unraced = 0
            for (chunk in symbols.chunked(4)) {
                val raced = coroutineScope {
                    chunk.map { sym ->
                        async {
                            val stock = runCatching {
                                market.getDailyCandles(sym, BEAT_SPY_DAYS)
                            }.getOrDefault(emptyList())
                            sym to withContext(Dispatchers.Default) {
                                runCatching {
                                    BeatSpyEngine.build(
                                        sym, stock, spyCandles,
                                        quotes[sym]?.price, spyQuote?.price
                                    )
                                }.getOrNull()
                            }
                        }
                    }.awaitAll()
                }
                for ((sym, report) in raced) {
                    val h = report?.let { BeatSpyEngine.buyHorizon(it) }
                    if (report == null || h == null) {
                        unraced++
                        continue
                    }
                    if (!BeatSpyEngine.inGreenZone(report.price, h)) continue
                    val (name, from) = sources[sym] ?: ("" to mutableListOf<String>())
                    green += SpyGreenRow(
                        symbol = sym,
                        name = name,
                        price = report.price,
                        edgeEntry = h.edgeEntry,
                        breakevenEntry = h.breakevenEntry,
                        headroomPct = (h.edgeEntry - report.price) / report.price * 100.0,
                        beatSharePct = h.beatSharePct,
                        medianEdgePct = h.medianEdgePct,
                        stockMedianPct = h.stockMedianPct,
                        spyMedianPct = h.spyMedianPct,
                        analogCount = h.analogCount,
                        horizonLabel = h.label,
                        verdict = h.verdict,
                        sources = from.toList()
                    )
                }
                scanned += chunk.size
                // Deepest in the green first — the most room to still be right.
                val sorted = green.sortedWith(
                    compareByDescending<SpyGreenRow> { it.headroomPct }
                        .thenByDescending { it.beatSharePct }
                )
                _state.update {
                    it.copy(spyRows = sorted, spyScanned = scanned, spyUnraced = unraced)
                }
            }
        } finally {
            _state.update { it.copy(spyLoading = false) }
        }
    }

    /** Re-run the power-hour scan from fresh screener + candle data. */
    fun refreshPower() {
        if (_state.value.powerRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(powerRefreshing = true) }
            try {
                val power = picks.recomputePower()
                _state.update { it.copy(powerRows = power, powerLoading = false) }
                loadExtHours(power.map { it.symbol }, maxAgeMs = 60_000L)
            } finally {
                _state.update { it.copy(powerRefreshing = false) }
            }
        }
    }

    /** Re-run the market-wide best-entry scan from fresh screener + candle data. */
    fun refreshEntries() {
        if (_state.value.entryRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(entryRefreshing = true) }
            try {
                val entries = picks.recomputeEntries()
                _state.update { it.copy(entryRows = entries, entryLoading = false) }
                loadExtHours(entries.map { it.symbol }, maxAgeMs = 60_000L)
            } finally {
                _state.update { it.copy(entryRefreshing = false) }
            }
        }
    }

    /** Recompute today's daily picks from fresh quotes, extended hours, and news. */
    fun refreshDaily() {
        if (_state.value.dailyRefreshing || _state.value.saturday) return
        viewModelScope.launch {
            _state.update { it.copy(dailyRefreshing = true) }
            try {
                val daily = picks.recomputeDaily()
                _state.update { it.copy(dailyRows = daily, dailyLoading = false) }
                loadExtHours(daily.map { it.symbol }, maxAgeMs = 60_000L)
            } finally {
                _state.update { it.copy(dailyRefreshing = false) }
            }
        }
    }

    /** Recompute this week's picks (both lists) from fresh market data. */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                picks.recompute()
                picks.recomputeBudget()
                loadExtHours(
                    (_state.value.rows + _state.value.budgetRows).map { it.pick.symbol },
                    maxAgeMs = 60_000L
                )
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }
}
