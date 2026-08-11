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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One holding as shown on the dashboard. */
data class HoldingRow(
    val view: PositionView,
    val spark: List<Double>,
    val advice: Advice?,
    val ext: ExtendedHours? = null
)

data class DashboardState(
    val loading: Boolean = true,
    val summary: PortfolioSummary? = null,
    val holdings: List<HoldingRow> = emptyList(),
    /** The book sliced by sector — shared math with Picks and Wealth. */
    val book: BookContext = BookContext.EMPTY
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val refreshTick = MutableStateFlow(0)
    private val forceFresh = AtomicBoolean(false)

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
    }

    /** Re-runs the whole pipeline with fresh quotes (bypasses the quote cache). */
    fun refresh() {
        forceFresh.set(true)
        refreshTick.update { it + 1 }
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
                    HoldingRow(view = view, spark = spark, advice = advice, ext = ext)
                }
            }.awaitAll()
            rowsList to sectorsD.await()
        }

        _state.value = DashboardState(
            loading = false,
            summary = summary,
            holdings = rows.sortedByDescending { it.view.marketValue },
            book = PortfolioLens.build(openViews, sectors)
        )
    }
}
