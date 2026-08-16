package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.AdviceEngine
import com.aurum.invest.analytics.GoldCorrelation
import com.aurum.invest.data.db.PriceAlertEntity
import com.aurum.invest.data.model.Advice
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.FeedStatus
import com.aurum.invest.data.model.FundamentalsFeed
import com.aurum.invest.data.model.GoldRelation
import com.aurum.invest.data.model.NewsFeed
import com.aurum.invest.data.model.NewsItem
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.PositionView
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.repo.PortfolioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One chart series: closes with index-aligned bar timestamps. */
data class ChartSeries(
    val closes: List<Double> = emptyList(),
    val timestamps: List<Long> = emptyList()
) {
    companion object {
        fun of(candles: List<Candle>): ChartSeries =
            ChartSeries(candles.map { it.close }, candles.map { it.ts })
    }
}

data class DetailState(
    val loading: Boolean = true,
    val symbol: String = "",
    val quote: Quote? = null,
    val chart1D: ChartSeries = ChartSeries(),
    val chart1W: ChartSeries = ChartSeries(),
    val chart1M: ChartSeries = ChartSeries(),
    val chart3M: ChartSeries = ChartSeries(),
    // Long-horizon views (M2), loaded lazily when their chip is tapped.
    val chart1Y: ChartSeries = ChartSeries(),
    val chart5Y: ChartSeries = ChartSeries(),
    val chartMax: ChartSeries = ChartSeries(),
    val ext: ExtendedHours? = null,
    val position: Position? = null,
    val view: PositionView? = null,
    val isHeld: Boolean = false,
    val advice: Advice? = null,
    val gold: GoldRelation? = null,
    val news: List<NewsItem> = emptyList(),
    /** FRESH = feed verified; STALE = old cache after a failed fetch; FAILED = unknown. */
    val newsStatus: FeedStatus = FeedStatus.FRESH,
    val newsAsOf: Long = 0L,
    val watched: Boolean = false,
    val pinned: Boolean = false,
    /** The user's price alerts on this symbol (active first). */
    val alerts: List<PriceAlertEntity> = emptyList(),
    /** Company research (profile, financials, valuation, analysts, catalysts). */
    val fundamentals: FundamentalsFeed? = null
)

class PositionDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private var symbol: String = ""
    private val symKey: String get() = symbol.trim().uppercase()
    private var loadJob: Job? = null
    private var alertsJob: Job? = null

    /** Idempotent entry point: sets the symbol and loads (once) for it. */
    fun start(symbol: String) {
        val cleaned = symbol.trim()
        if (cleaned.isEmpty()) return
        if (this.symbol.equals(cleaned, ignoreCase = true) && loadJob != null) return
        this.symbol = cleaned
        _state.value = DetailState(loading = true, symbol = cleaned.uppercase())
        reload()
        alertsJob?.cancel()
        alertsJob = viewModelScope.launch {
            runCatching {
                container.alerts.observeForSymbol(symKey).collect { list ->
                    _state.update { it.copy(alerts = list) }
                }
            }
        }
    }

    /** Lazily loads the long-horizon chart data the first time its chip is tapped. */
    fun ensureRange(range: String) {
        if (symbol.isBlank()) return
        val st = _state.value
        val needed = when (range) {
            "1Y" -> st.chart1Y.closes.isEmpty()
            "5Y" -> st.chart5Y.closes.isEmpty()
            "MAX" -> st.chartMax.closes.isEmpty()
            else -> false
        }
        if (!needed) return
        viewModelScope.launch {
            val candles = runCatching {
                when (range) {
                    "1Y" -> container.market.getRangeCandles(symKey, "1y", "1d", maxAgeMs = 21_600_000L)
                    "5Y" -> container.market.getRangeCandles(symKey, "5y", "1wk", maxAgeMs = 86_400_000L)
                    else -> container.market.getRangeCandles(symKey, "max", "1mo", maxAgeMs = 86_400_000L)
                }
            }.getOrDefault(emptyList())
            if (candles.isEmpty()) return@launch
            _state.update { s ->
                when (range) {
                    "1Y" -> s.copy(chart1Y = ChartSeries.of(candles))
                    "5Y" -> s.copy(chart5Y = ChartSeries.of(candles))
                    else -> s.copy(chartMax = ChartSeries.of(candles))
                }
            }
        }
    }

    fun addAlert(direction: String, threshold: Double, note: String = "") {
        if (threshold <= 0.0 || symbol.isBlank()) return
        viewModelScope.launch { container.alerts.add(symKey, direction, threshold, note) }
    }

    fun deleteAlert(alert: PriceAlertEntity) {
        viewModelScope.launch { container.alerts.delete(alert) }
    }

    fun refresh() = reload()

    private fun reload() {
        if (symbol.isBlank()) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                coroutineScope {
                    val quoteD = async { container.market.getQuote(symKey) }
                    val intradayD = async { container.market.getIntraday(symKey) }
                    val weekD = async { container.market.getRangeCandles(symKey, "5d", "30m") }
                    val monthD = async { container.market.getRangeCandles(symKey, "1mo", "60m") }
                    val dailyD = async { container.market.getDailyCandles(symKey, 120) }
                    val extD = async { container.market.getExtendedHours(symKey) }
                    val goldD = async { container.market.getGoldCandles() }
                    val fundamentalsD = async {
                        runCatching { container.fundamentals.getFundamentals(symKey) }.getOrNull()
                    }
                    val positionsD = async { container.portfolio.positionsNow() }
                    val watchD = async {
                        container.watch.getAll().firstOrNull { it.symbol.equals(symKey, ignoreCase = true) }
                    }

                    val quote = quoteD.await()
                    val intraday = intradayD.await()
                    val daily = dailyD.await()

                    val newsFeed = runCatching { container.news.getNewsFeed(symKey, daily) }
                        .getOrDefault(NewsFeed.FAILED)
                    val news = newsFeed.items
                    val newsScore = news.sumOf { it.sentiment }.coerceIn(-2, 2)

                    val position = positionsD.await()
                        .firstOrNull { it.symbol.equals(symKey, ignoreCase = true) && PortfolioRepository.isOpen(it) }
                    val view = position?.let { PortfolioRepository.toView(it, quote) }

                    val advice =
                        if (quote != null && daily.size >= 2) {
                            runCatching {
                                if (position != null) {
                                    AdviceEngine.sellAdvice(position, quote, daily, newsScore)
                                } else {
                                    AdviceEngine.buyAdvice(quote, daily, newsScore)
                                }
                            }.getOrNull()
                        } else null

                    val goldCandles = goldD.await()
                    val gold =
                        if (daily.isNotEmpty() && goldCandles.isNotEmpty()) {
                            runCatching { GoldCorrelation.relation(daily, goldCandles) }.getOrNull()
                        } else null

                    val watchItem = watchD.await()

                    _state.value = DetailState(
                        loading = false,
                        symbol = symKey,
                        quote = quote,
                        chart1D = ChartSeries.of(intraday),
                        chart1W = ChartSeries.of(weekD.await()),
                        chart1M = ChartSeries.of(monthD.await()),
                        chart3M = ChartSeries.of(daily),
                        ext = runCatching { extD.await() }.getOrNull(),
                        position = position,
                        view = view,
                        isHeld = position != null,
                        advice = advice,
                        gold = gold,
                        news = news,
                        newsStatus = newsFeed.status,
                        newsAsOf = newsFeed.asOf,
                        watched = watchItem != null,
                        pinned = watchItem?.pinned == true,
                        alerts = _state.value.alerts,
                        fundamentals = fundamentalsD.await()
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** Star: add to / remove from the watchlist. */
    fun toggleWatch() {
        if (symbol.isBlank()) return
        viewModelScope.launch {
            try {
                if (_state.value.watched) {
                    container.watch.remove(symKey)
                    _state.update { it.copy(watched = false, pinned = false) }
                } else {
                    container.watch.add(symKey, _state.value.quote?.shortName ?: "")
                    _state.update { it.copy(watched = true, pinned = false) }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Pin: pin/unpin on the watchlist (adds it first when not watched yet). */
    fun togglePin() {
        if (symbol.isBlank()) return
        viewModelScope.launch {
            try {
                if (!_state.value.watched) {
                    container.watch.add(symKey, _state.value.quote?.shortName ?: "")
                }
                val newPinned = !_state.value.pinned
                container.watch.setPinned(symKey, newPinned)
                _state.update { it.copy(watched = true, pinned = newPinned) }
            } catch (_: Exception) {
            }
        }
    }
}
