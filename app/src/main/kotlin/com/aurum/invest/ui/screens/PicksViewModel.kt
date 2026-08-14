package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.RelationGroup
import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.DailyPick
import com.aurum.invest.data.model.EntryPick
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.PowerPick
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.model.WeeklyPick
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    // Relations — first-party links: who moves when the giants move.
    val relationRows: List<RelationGroup> = emptyList(),
    val relationLoading: Boolean = true,
    val relationRefreshing: Boolean = false,
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
        const val TAB_RELATION = "relation"
    }

    private val container = (app as AurumApp).container
    private val picks = container.picks
    private val market = container.market

    /** Tabs whose scan has already been kicked off this session. */
    private val started = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Tabs with a scan in flight right now — guards double-fire on re-show. */
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())

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
                        st.powerRows.map { it.symbol }).toSet()
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
     * Loads the data for [tab] whenever it is shown. The repos hold the
     * freshness rule (each list has a TTL), so re-showing a tab with a fresh
     * set returns instantly, while a set from hours ago quietly recomputes —
     * a power-hour list built at 3 AM must not be the 3:45 PM default read.
     * The weekly lists are Room-backed and genuinely once-per-session.
     */
    fun ensureTab(tab: String) {
        if (tab == TAB_WEEKLY) {
            if (!started.add(tab)) return
            viewModelScope.launch {
                picks.ensureCurrentWeek()
                _state.update { it.copy(loading = false) }
                picks.ensureBudgetWeek()
            }
            return
        }
        if (!inFlight.add(tab)) return
        when (tab) {
            TAB_DAILY -> viewModelScope.launch {
                try {
                    if (Dates.isSaturday()) {
                        _state.update { it.copy(dailyLoading = false) }
                        return@launch
                    }
                    val daily = picks.ensureDaily()
                    _state.update { it.copy(dailyRows = daily, dailyLoading = false) }
                } finally {
                    inFlight.remove(tab)
                }
            }
            TAB_ENTRIES -> viewModelScope.launch {
                try {
                    val entries = picks.ensureEntries()
                    _state.update { it.copy(entryRows = entries, entryLoading = false) }
                } finally {
                    inFlight.remove(tab)
                }
            }
            TAB_POWER -> viewModelScope.launch {
                try {
                    val power = picks.ensurePower()
                    _state.update { it.copy(powerRows = power, powerLoading = false) }
                } finally {
                    inFlight.remove(tab)
                }
            }
            TAB_RELATION -> viewModelScope.launch {
                try {
                    val groups = picks.ensureRelations()
                    _state.update { it.copy(relationRows = groups, relationLoading = false) }
                } finally {
                    inFlight.remove(tab)
                }
            }
        }
    }

    /** Re-reads the relation groups from fresh quotes and candles. */
    fun refreshRelations() {
        if (_state.value.relationRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(relationRefreshing = true) }
            try {
                val groups = picks.recomputeRelations()
                _state.update { it.copy(relationRows = groups, relationLoading = false) }
            } finally {
                _state.update { it.copy(relationRefreshing = false) }
            }
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
