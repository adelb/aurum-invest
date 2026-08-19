package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.core.Dates
import com.aurum.invest.analytics.UPick
import com.aurum.invest.analytics.EntryPicker
import com.aurum.invest.data.model.EntryPick
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.PowerPick
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.model.ScanCoverage
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
    // U-pattern picks — the standalone dip-then-rise engine's daily list.
    val uRows: List<UPick> = emptyList(),
    val uLabel: String = "",
    val uLoading: Boolean = true,
    val uRefreshing: Boolean = false,
    // Best entries — market-wide scan for stocks at a good entry price now.
    val entryRows: List<EntryPick> = emptyList(),
    val entryLoading: Boolean = true,
    val entryRefreshing: Boolean = false,
    // Power hour — buy in the last 90 min of the session for next-day strength.
    val powerRows: List<PowerPick> = emptyList(),
    val powerLoading: Boolean = true,
    val powerRefreshing: Boolean = false,
    // The user's book + sector classification of visible picks, so every
    // suggestion can be read against what is actually held.
    val book: BookContext = BookContext.EMPTY,
    val pickSectors: Map<String, String> = emptyMap(),
    // Live pre/post-market read per visible symbol, so every pick card can
    // carry the same extended-hours chips the portfolio shows.
    val extHours: Map<String, ExtendedHours> = emptyMap(),
    /**
     * Universe health of the last market-wide scan (H4): these lists come
     * from Yahoo's predefined screens, NOT "the whole US market", and an
     * empty list only means "no setup" when the screens were reachable.
     */
    val coverage: ScanCoverage? = null
)

class PicksViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val TAB_U = "upattern"
        const val TAB_ENTRIES = "entries"
        const val TAB_POWER = "power"
        const val TAB_WEEKLY = "weekly"
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
            uLabel = Dates.todayLabel()
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
                        st.uRows.map { it.symbol } +
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

    /** Re-reads the screener-universe health after a market-wide scan ran. */
    private suspend fun refreshCoverage() {
        runCatching {
            val cov = market.screenerCoverage(EntryPicker.MARKET_SCREENS)
            _state.update { it.copy(coverage = cov) }
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
            TAB_U -> viewModelScope.launch {
                try {
                    val rows = picks.ensureUPattern()
                    _state.update { it.copy(uRows = rows, uLoading = false) }
                    refreshCoverage()
                } finally {
                    inFlight.remove(tab)
                }
            }
            TAB_ENTRIES -> viewModelScope.launch {
                try {
                    val entries = picks.ensureEntries()
                    _state.update { it.copy(entryRows = entries, entryLoading = false) }
                    refreshCoverage()
                } finally {
                    inFlight.remove(tab)
                }
            }
            TAB_POWER -> viewModelScope.launch {
                try {
                    val power = picks.ensurePower()
                    _state.update { it.copy(powerRows = power, powerLoading = false) }
                    refreshCoverage()
                } finally {
                    inFlight.remove(tab)
                }
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
                refreshCoverage()
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
                refreshCoverage()
                loadExtHours(entries.map { it.symbol }, maxAgeMs = 60_000L)
            } finally {
                _state.update { it.copy(entryRefreshing = false) }
            }
        }
    }

    /**
     * Pull-to-refresh on the U tab: cheap live re-read when a list exists
     * (states move in minutes; fingerprints don't), full market rescan when
     * the day has no list yet.
     */
    fun refreshU() {
        if (_state.value.uRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(uRefreshing = true) }
            try {
                val rows =
                    if (_state.value.uRows.isEmpty()) picks.recomputeUPattern()
                    else picks.refreshULive()
                _state.update { it.copy(uRows = rows, uLoading = false) }
                refreshCoverage()
            } finally {
                _state.update { it.copy(uRefreshing = false) }
            }
        }
    }

    /** The explicit full rescan — screens, fingerprints, gates, live read. */
    fun rescanU() {
        if (_state.value.uRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(uRefreshing = true) }
            try {
                val rows = picks.recomputeUPattern()
                _state.update { it.copy(uRows = rows, uLoading = false) }
                refreshCoverage()
            } finally {
                _state.update { it.copy(uRefreshing = false) }
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
