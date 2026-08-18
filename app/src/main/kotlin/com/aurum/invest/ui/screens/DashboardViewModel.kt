package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.AdviceEngine
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.Advice
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.PortfolioSummary
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.PositionView
import com.aurum.invest.data.repo.PortfolioRepository
import com.aurum.invest.data.repo.WalletState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One holding as shown on the dashboard. */
data class HoldingRow(
    val view: PositionView,
    val spark: List<Double>,
    val advice: Advice?,
    val ext: ExtendedHours? = null,
    /** User's own profit target for this position, in percent; null when unset. */
    val targetPct: Double? = null
) {
    /** The price this position must reach to realise [targetPct]. */
    val targetPrice: Double?
        get() = targetPct?.let {
            com.aurum.invest.data.repo.TargetsRepository.sellPriceFor(view.position.avgCost, it)
        }

    /** How far the live price still is from the target, in percent. */
    val distanceToTargetPct: Double?
        get() {
            val target = targetPrice ?: return null
            val price = view.quote?.price ?: return null
            if (price <= 0.0) return null
            return (target - price) / price * 100.0
        }

    /** True once the live price has reached the target. */
    val targetReached: Boolean
        get() {
            val target = targetPrice ?: return false
            val price = view.quote?.price ?: return false
            return price >= target
        }
}

data class DashboardState(
    val loading: Boolean = true,
    val summary: PortfolioSummary? = null,
    val holdings: List<HoldingRow> = emptyList(),
    /** The book sliced by sector — shared math with Picks and Wealth. */
    val book: BookContext = BookContext.EMPTY,
    /** The user's stated total wallet, derived invested/liquidity/P&L, and net worth. */
    val wallet: WalletState = WalletState.UNSET,
    /** Oldest fetch time among the quotes shown; 0 when nothing was priced. */
    val pricesAsOf: Long = 0L
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val refreshTick = MutableStateFlow(0)
    private val forceFresh = AtomicBoolean(false)

    /**
     * Every position ever traded — including fully closed ones — from the
     * last full [load]. The 1-second ticker reuses this so it can recompute
     * an honest cumulative realized P/L instead of only summing the realized
     * P/L of positions still open right now.
     */
    @Volatile
    private var lastAllPositions: List<Position> = emptyList()

    init {
        viewModelScope.launch {
            combine(container.portfolio.observePositions(), refreshTick) { positions, _ -> positions }
                .collectLatest { positions ->
                    try {
                        load(positions)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        _state.update { it.copy(loading = false) }
                    }
                }
        }
        // Wallet: the single user-stated total, kept in sync with the latest summary.
        viewModelScope.launch {
            runCatching {
                combine(
                    container.wallet.total,
                    container.wallet.configured,
                    _state.map { it.summary }.distinctUntilChanged()
                ) { total, configured, summary -> WalletState.of(total, configured, summary) }
                    .collectLatest { wallet -> _state.update { it.copy(wallet = wallet) } }
            }
        }
        // Live price ticker: re-prices every open holding on a timer while the
        // dashboard is actually on screen (subscriptionCount == 0 once the
        // composable stops collecting, e.g. backgrounded or navigated away).
        // Only quotes + the numbers derived from them refresh on this cadence
        // — sparklines, advice, and extended-hours stay from the last full
        // load(), which would be far too expensive to redo every second.
        viewModelScope.launch {
            while (isActive) {
                delay(LIVE_PRICE_TICK_MS)
                if (_state.subscriptionCount.value == 0) continue
                runCatching { tickLivePrices() }
            }
        }
    }

    private suspend fun tickLivePrices() {
        val current = _state.value
        val holdings = current.holdings
        if (holdings.isEmpty()) return
        val symbols = holdings.map { it.view.position.symbol }.distinct()

        // Just under the tick (rather than 0) so two screens ticking together
        // share one read instead of each firing its own, while still reading
        // "live" — the cache is always at least this fresh on this screen.
        val quotes = container.market.getQuotes(symbols, maxAgeMs = LIVE_PRICE_TICK_MS - 100L)
        if (quotes.isEmpty()) return

        val ordered = container.portfolio.orderedTransactionsNow()
        val dayStart = Dates.todayStartMs()
        val dayPl = PortfolioRepository.dayPlBySymbol(ordered, quotes, dayStart)

        val allPositions = lastAllPositions.ifEmpty { holdings.map { it.view.position } }
        val openViews = holdings.map { row ->
            val sym = row.view.position.symbol
            val fresh = quotes[sym] ?: row.view.quote
            PortfolioRepository.toView(row.view.position, fresh, dayPl[sym])
        }
        val summary = PortfolioRepository.summarize(openViews, allPositions, dayPl.values.sum())
        val newRows = holdings.mapIndexed { i, row -> row.copy(view = openViews[i]) }
        val pricesAsOf = quotes.values.map { it.fetchedAt }.filter { it > 0L }.minOrNull()
            ?: current.pricesAsOf

        _state.update {
            it.copy(
                holdings = newRows.sortedByDescending { r -> r.view.marketValue },
                summary = summary,
                pricesAsOf = pricesAsOf
            )
        }
    }

    companion object {
        /**
         * How often the live price ticker re-prices holdings while the
         * dashboard is visible.
         *
         * This was once a second, which was worse than pointless: the batch
         * quote series only moves once a minute, and asking Yahoo every second
         * from three live screens got the device throttled per IP. Throttled
         * reads fall back to the cached quote, so the figures then sat frozen
         * for as long as the throttle lasted while still presenting as live.
         */
        private const val LIVE_PRICE_TICK_MS = 15_000L
    }

    /** Re-runs the whole pipeline with fresh quotes (bypasses the quote cache). */
    fun refresh() {
        forceFresh.set(true)
        refreshTick.update { it + 1 }
    }

    /**
     * Sets (or clears, when [pct] is null) the profit target for [symbol].
     * The card then shows the exact price that target implies.
     */
    fun setTarget(symbol: String, pct: Double?) {
        viewModelScope.launch {
            runCatching {
                if (pct == null) container.targets.clearTarget(symbol)
                else container.targets.setTarget(symbol, pct)
            }
            refresh()
        }
    }

    /** Sets the user-stated total wallet — the single number the wallet card derives from. */
    fun setWalletTotal(amount: Double) {
        viewModelScope.launch {
            runCatching { container.wallet.setTotal(amount) }
        }
    }

    /**
     * Deletes every ledger row for [symbol] — used to clear a test position.
     * Only that symbol is touched; the positions flow re-emits automatically.
     */
    fun removeHolding(symbol: String) {
        viewModelScope.launch {
            runCatching { container.portfolio.removeSymbol(symbol) }
        }
    }

    private suspend fun load(allPositions: List<Position>) {
        _state.update { it.copy(loading = true) }
        lastAllPositions = allPositions
        val fresh = forceFresh.getAndSet(false)
        val quoteMaxAge = if (fresh) 0L else 60_000L

        val open = allPositions.filter { PortfolioRepository.isOpen(it) }

        // Quotes for every open symbol PLUS anything traded today, so the
        // "today" P/L also counts positions closed earlier in the session.
        val ordered = container.portfolio.orderedTransactionsNow()
        val dayStart = Dates.todayStartMs()
        val tradedToday = ordered.filter { it.ts >= dayStart }.map { it.symbol.trim().uppercase() }
        val quotes = container.market.getQuotes(
            (open.map { it.symbol } + tradedToday).distinct(),
            maxAgeMs = quoteMaxAge
        )

        // Day P/L that respects when shares were actually bought or sold today.
        val dayPl = PortfolioRepository.dayPlBySymbol(ordered, quotes, dayStart)
        val openViews = open.map {
            PortfolioRepository.toView(it, quotes[it.symbol], dayPl[it.symbol])
        }
        val summary = PortfolioRepository.summarize(openViews, allPositions, dayPl.values.sum())

        val targets = runCatching { container.targets.getTargets(open.map { it.symbol }) }
            .getOrDefault(emptyMap())

        val (rows, sectors) = coroutineScope {
            val sectorsD = async {
                runCatching { container.market.getSectors(open.map { it.symbol }) }
                    .getOrDefault(emptyMap())
            }
            val rowsList = openViews.map { view ->
                async {
                    val sym = view.position.symbol
                    val daily = try {
                        container.market.getDailyCandles(sym, 120)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val intraday = try {
                        container.market.getIntraday(sym)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val spark =
                        if (intraday.size >= 2) intraday.map { it.close }
                        else daily.takeLast(30).map { it.close }
                    val quote = view.quote
                    val advice =
                        if (quote != null && daily.size >= 2) {
                            runCatching {
                                AdviceEngine.sellAdvice(view.position, quote, daily, newsScore = 0)
                            }.getOrNull()
                        } else null
                    val ext = runCatching {
                        container.market.getExtendedHours(
                            sym,
                            maxAgeMs = if (fresh) 0L else 300_000L
                        )
                    }.getOrNull()
                    HoldingRow(
                        view = view,
                        spark = spark,
                        advice = advice,
                        ext = ext,
                        targetPct = targets[sym]
                    )
                }
            }.awaitAll()
            rowsList to sectorsD.await()
        }

        // Honest freshness: the OLDEST quote on screen sets the "as of" time.
        val pricesAsOf = quotes.values
            .map { it.fetchedAt }
            .filter { it > 0L }
            .minOrNull() ?: 0L

        _state.update {
            it.copy(
                loading = false,
                summary = summary,
                holdings = rows.sortedByDescending { r -> r.view.marketValue },
                book = PortfolioLens.build(openViews, sectors),
                pricesAsOf = pricesAsOf
            )
        }
    }
}
