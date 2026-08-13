package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.MarketIdea
import com.aurum.invest.analytics.MarketsIdeas
import com.aurum.invest.analytics.NewsSentiment
import com.aurum.invest.analytics.Universes
import com.aurum.invest.data.model.AssetClass
import com.aurum.invest.data.model.NewsItem
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.remote.NewsClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One quoted instrument row in the Markets screen. */
data class MarketRow(
    val symbol: String,
    val name: String,
    val quote: Quote?
)

/** Trade ideas + news attached to the currently-expanded row. */
data class MarketDetail(
    val symbol: String,
    val ideas: List<MarketIdea> = emptyList(),
    val news: List<NewsItem> = emptyList(),
    val loading: Boolean = true
)

/** Rows grouped by asset class, plus optional expansion detail. */
data class MarketsState(
    val metals: List<MarketRow> = emptyList(),
    val fx: List<MarketRow> = emptyList(),
    val indices: List<MarketRow> = emptyList(),
    val expandedSymbol: String? = null,
    val detail: MarketDetail? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false
)

class MarketsViewModel(app: Application) : AndroidViewModel(app) {

    private val market = (app as AurumApp).container.market
    private val yahoo = (app).container.yahoo
    private val newsClient = NewsClient()

    private val _state = MutableStateFlow(MarketsState())
    val state: StateFlow<MarketsState> = _state.asStateFlow()

    private val forceFresh = AtomicBoolean(false)

    init {
        _state.update {
            it.copy(
                metals = Universes.METALS.map { (s, n) -> MarketRow(s, n, null) },
                fx = Universes.FX.map { (s, n) -> MarketRow(s, n, null) },
                indices = Universes.INDICES.map { (s, n) -> MarketRow(s, n, null) }
            )
        }
        load()
    }

    fun refresh() {
        forceFresh.set(true)
        _state.update { it.copy(refreshing = true) }
        load()
    }

    /**
     * Toggle expansion for [symbol]. Loads ideas + news lazily so the
     * Markets screen doesn't fire a 3 × 25 candle-fetch storm on open.
     */
    fun toggleExpanded(symbol: String) {
        val current = _state.value.expandedSymbol
        if (current == symbol) {
            _state.update { it.copy(expandedSymbol = null, detail = null) }
            return
        }
        _state.update {
            it.copy(
                expandedSymbol = symbol,
                detail = MarketDetail(symbol = symbol, loading = true)
            )
        }
        loadDetail(symbol)
    }

    private fun loadDetail(symbol: String) {
        viewModelScope.launch {
            val quote = market.getQuote(symbol, 30_000L)
            val price = quote?.price ?: 0.0
            val ideas = if (price > 0.0) computeIdeas(symbol, price) else emptyList()
            val news = fetchNewsFor(symbol)

            // Only commit if the user hasn't collapsed / switched rows.
            _state.update {
                if (it.expandedSymbol != symbol) it
                else it.copy(detail = MarketDetail(symbol, ideas, news, loading = false))
            }
        }
    }

    private suspend fun computeIdeas(symbol: String, price: Double): List<MarketIdea> = coroutineScope {
        MarketIdea.Horizon.values().map { h ->
            async {
                val (range, interval) = MarketsIdeas.yahooRangeInterval(h)
                val candles = try {
                    yahoo.fetchRangeCandles(symbol, range, interval)
                } catch (_: Exception) {
                    emptyList()
                }
                MarketsIdeas.compute(h, price, candles)
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchNewsFor(symbol: String): List<NewsItem> {
        val query = Universes.newsQueryFor(symbol) ?: return emptyList()
        val raw = try {
            newsClient.fetchQuery(query, windowDays = 5)
        } catch (_: Exception) {
            emptyList()
        }
        // Take top 5, tag sentiment via the existing headline scorer.
        return raw.take(5).map { r ->
            NewsItem(
                id = r.link.hashCode().toString(),
                symbol = symbol,
                title = r.title,
                source = r.source,
                url = r.link,
                publishedAt = r.publishedAt,
                sentiment = NewsSentiment.score(r.title),
                priceImpactPct = null
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            val fresh = forceFresh.getAndSet(false)
            val maxAge = if (fresh) 0L else 60_000L
            val quotes = fetchQuotes(Universes.ALL_NON_EQUITY.map { it.first }, maxAge)
            _state.update { current ->
                current.copy(
                    metals = hydrate(current.metals, quotes),
                    fx = hydrate(current.fx, quotes),
                    indices = hydrate(current.indices, quotes),
                    loading = false,
                    refreshing = false
                )
            }
        }
    }

    private fun hydrate(rows: List<MarketRow>, quotes: Map<String, Quote>): List<MarketRow> =
        rows.map { r -> r.copy(quote = quotes[r.symbol] ?: r.quote) }

    private suspend fun fetchQuotes(symbols: List<String>, maxAge: Long): Map<String, Quote> =
        coroutineScope {
            symbols.map { s -> async { s to market.getQuote(s, maxAge) } }
                .awaitAll()
                .mapNotNull { (s, q) -> q?.let { s to it } }
                .toMap()
        }

    @Suppress("unused")
    fun rowsOf(cls: AssetClass): List<MarketRow> = when (cls) {
        AssetClass.METAL -> _state.value.metals
        AssetClass.FX -> _state.value.fx
        AssetClass.INDEX -> _state.value.indices
        AssetClass.EQUITY -> emptyList()
    }
}
