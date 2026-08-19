package com.aurum.invest.data.repo

import com.aurum.invest.analytics.StockStudy
import com.aurum.invest.analytics.StockStudyEngine
import com.aurum.invest.analytics.StudyInputs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The stock study's data layer: gathers everything the engine reasons over —
 * five years of candles (the long horizons' analog pool), the benchmark, the
 * live quote, the fundamental snapshot, the headlines, the VIX, and the
 * ledger's own stake in the name — then runs the pure evaluation. Every
 * input rides its own cache (candles 6h, fundamentals 24h, news 30min,
 * quote 60s), so a re-study of the same name is nearly free. Null only when
 * even the price history could not be reached.
 */
class StudyRepository(
    private val market: MarketRepository,
    private val news: NewsRepository,
    private val wealth: WealthRepository,
    private val portfolio: PortfolioRepository
) {

    companion object {
        /** Five years of dailies — enough forward room for the 1-year analogs. */
        private const val HISTORY_DAYS = 1825
    }

    suspend fun getStudy(symbol: String, name: String): StockStudy? = try {
        coroutineScope {
            val sym = symbol.trim().uppercase()
            val candlesD = async {
                runCatching { market.getDailyCandles(sym, HISTORY_DAYS) }
                    .getOrDefault(emptyList())
            }
            val spyD = async {
                runCatching { market.getDailyCandles("SPY", HISTORY_DAYS) }
                    .getOrDefault(emptyList())
            }
            val quoteD = async { runCatching { market.getQuote(sym) }.getOrNull() }
            val fundD = async { runCatching { market.getFundamentals(sym) }.getOrNull() }
            // The ledger's stake in this name — the study is portfolio-aware.
            val heldD = async {
                runCatching {
                    portfolio.positionsNow()
                        .firstOrNull {
                            it.symbol == sym && PortfolioRepository.isOpen(it)
                        }
                }.getOrNull()
            }
            val candles = candlesD.await()
            if (candles.isEmpty()) return@coroutineScope null
            val newsItems = runCatching { news.getNews(sym, candles) }.getOrDefault(emptyList())
            val vix = runCatching { wealth.getMarketPulse() }.getOrNull()?.vix
            val quote = quoteD.await()
            val held = heldD.await()
            StockStudyEngine.study(
                StudyInputs(
                    symbol = sym,
                    name = name.ifBlank { quote?.shortName?.ifBlank { null } ?: sym },
                    quote = quote,
                    candles = candles,
                    spy = spyD.await(),
                    fundamentals = fundD.await(),
                    news = newsItems,
                    vix = vix,
                    heldShares = held?.shares,
                    heldAvgCost = held?.avgCost
                )
            )
        }
    } catch (_: Exception) {
        null
    }
}
