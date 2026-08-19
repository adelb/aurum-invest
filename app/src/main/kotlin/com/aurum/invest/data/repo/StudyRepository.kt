package com.aurum.invest.data.repo

import com.aurum.invest.analytics.StockStudy
import com.aurum.invest.analytics.StockStudyEngine
import com.aurum.invest.analytics.StudyInputs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The stock study's data layer: gathers everything the engine reasons over —
 * two years of candles, the benchmark, the live quote, the fundamental
 * snapshot, the headlines, and the VIX — then runs the pure evaluation.
 * Every input rides its own cache (candles 6h, fundamentals 24h, news 30min,
 * quote 60s), so a re-study of the same name is nearly free. Null only when
 * even the price history could not be reached.
 */
class StudyRepository(
    private val market: MarketRepository,
    private val news: NewsRepository,
    private val wealth: WealthRepository
) {

    suspend fun getStudy(symbol: String, name: String): StockStudy? = try {
        coroutineScope {
            val sym = symbol.trim().uppercase()
            val candlesD = async {
                runCatching { market.getDailyCandles(sym, 730) }.getOrDefault(emptyList())
            }
            val spyD = async {
                runCatching { market.getDailyCandles("SPY", 730) }.getOrDefault(emptyList())
            }
            val quoteD = async { runCatching { market.getQuote(sym) }.getOrNull() }
            val fundD = async { runCatching { market.getFundamentals(sym) }.getOrNull() }
            val candles = candlesD.await()
            if (candles.isEmpty()) return@coroutineScope null
            val newsItems = runCatching { news.getNews(sym, candles) }.getOrDefault(emptyList())
            val vix = runCatching { wealth.getMarketPulse() }.getOrNull()?.vix
            val quote = quoteD.await()
            StockStudyEngine.study(
                StudyInputs(
                    symbol = sym,
                    name = name.ifBlank { quote?.shortName?.ifBlank { null } ?: sym },
                    quote = quote,
                    candles = candles,
                    spy = spyD.await(),
                    fundamentals = fundD.await(),
                    news = newsItems,
                    vix = vix
                )
            )
        }
    } catch (_: Exception) {
        null
    }
}
