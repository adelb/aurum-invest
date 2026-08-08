package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.AdviceEngine
import com.aurum.invest.analytics.GoldCorrelation
import com.aurum.invest.data.model.Advice
import com.aurum.invest.data.model.GoldRelation
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

data class DetailState(
    val loading: Boolean = true,
    val symbol: String = "",
    val quote: Quote? = null,
    val intradayCloses: List<Double> = emptyList(),
    val dailyCloses: List<Double> = emptyList(),
    val position: Position? = null,
    val view: PositionView? = null,
    val isHeld: Boolean = false,
    val advice: Advice? = null,
    val gold: GoldRelation? = null,
    val news: List<NewsItem> = emptyList(),
    val watched: Boolean = false,
    val pinned: Boolean = false
)

class PositionDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private var symbol: String = ""
    private val symKey: String get() = symbol.trim().uppercase()
    private var loadJob: Job? = null

    /** Idempotent entry point: sets the symbol and loads (once) for it. */
    fun start(symbol: String) {
        val cleaned = symbol.trim()
        if (cleaned.isEmpty()) return
        if (this.symbol.equals(cleaned, ignoreCase = true) && loadJob != null) return
        this.symbol = cleaned
        _state.value = DetailState(loading = true, symbol = cleaned.uppercase())
        reload()
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
                    val dailyD = async { container.market.getDailyCandles(symKey, 120) }
                    val goldD = async { container.market.getGoldCandles() }
                    val positionsD = async { container.portfolio.positionsNow() }
                    val watchD = async {
                        container.watch.getAll().firstOrNull { it.symbol.equals(symKey, ignoreCase = true) }
                    }

                    val quote = quoteD.await()
                    val intraday = intradayD.await()
                    val daily = dailyD.await()

                    val news = runCatching { container.news.getNews(symKey, daily) }
                        .getOrDefault(emptyList())
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
                        intradayCloses = intraday.map { it.close },
                        dailyCloses = daily.map { it.close },
                        position = position,
                        view = view,
                        isHeld = position != null,
                        advice = advice,
                        gold = gold,
                        news = news,
                        watched = watchItem != null,
                        pinned = watchItem?.pinned == true
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
