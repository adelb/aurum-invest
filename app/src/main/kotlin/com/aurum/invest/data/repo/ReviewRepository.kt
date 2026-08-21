package com.aurum.invest.data.repo

import com.aurum.invest.analytics.BoardHoldingInput
import com.aurum.invest.analytics.PortfolioBoardEngine
import com.aurum.invest.analytics.PortfolioBoardReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The portfolio review's data layer: every open position, its live quote,
 * two-plus years of dailies, sector and news tone are gathered, then the
 * whole book runs through [PortfolioBoardEngine] TWICE — first ungraded, so
 * the screen paints in seconds, then holding by holding as each stock's
 * measured 1-year technique record lands (cached six hours in
 * [EvaluationStore], shared with the analysis screen). The flow emits after
 * every step so the caller can show honest progress instead of a spinner.
 */
class ReviewRepository(
    private val market: MarketRepository,
    private val portfolio: PortfolioRepository,
    private val news: NewsRepository,
    private val evaluations: EvaluationStore
) {

    companion object {
        /**
         * Two years of dailies per holding: the last 252 sessions feed the
         * 1-year integrity replay, and the sessions before them give every
         * replayed day a real indicator warm-up. Same depth as the analysis
         * screen, so both share one cached series and one evaluation.
         */
        private const val CANDLE_DAYS = 550
    }

    /** One emission of the progressive review. */
    data class Progress(
        val total: Int,
        /** Holdings whose measured 1-year grading has finished. */
        val graded: Int,
        /** The symbol being graded right now; null when grading is done. */
        val grading: String?,
        val review: PortfolioBoardReview
    )

    fun reviewFlow(): Flow<Progress> = flow {
        val open = runCatching { portfolio.positionsNow() }
            .getOrDefault(emptyList())
            .filter { PortfolioRepository.isOpen(it) }
        if (open.isEmpty()) {
            emit(Progress(0, 0, null, PortfolioBoardEngine.review(emptyList())))
            return@flow
        }

        // ---- pass 1: quotes, candles, sectors, news — the ungraded board ---
        val symbols = open.map { it.symbol }
        val quotes = runCatching { market.getQuotes(symbols) }.getOrDefault(emptyMap())
        val sectors = runCatching { market.getSectors(symbols) }.getOrDefault(emptyMap())
        val inputs = coroutineScope {
            open.map { position ->
                async {
                    val candles = runCatching {
                        market.getDailyCandles(position.symbol, CANDLE_DAYS)
                    }.getOrDefault(emptyList())
                    val newsScore = runCatching {
                        news.getNews(position.symbol, candles)
                            .sumOf { it.sentiment }.coerceIn(-2, 2)
                    }.getOrDefault(0)
                    BoardHoldingInput(
                        position = position,
                        quote = quotes[position.symbol],
                        candles = candles,
                        evaluation = null,
                        newsScore = newsScore,
                        sector = sectors[position.symbol]
                    )
                }
            }.map { it.await() }
        }.toMutableList()
        emit(
            Progress(
                total = inputs.size,
                graded = 0,
                grading = inputs.firstOrNull()?.position?.symbol,
                review = PortfolioBoardEngine.review(inputs)
            )
        )

        // ---- pass 2: the measured record, one holding at a time -----------
        for (i in inputs.indices) {
            val input = inputs[i]
            val evaluation = runCatching {
                evaluations.get(input.position.symbol, input.candles)
            }.getOrNull()
            if (evaluation != null) {
                inputs[i] = input.copy(evaluation = evaluation)
            }
            emit(
                Progress(
                    total = inputs.size,
                    graded = i + 1,
                    grading = inputs.getOrNull(i + 1)?.position?.symbol,
                    review = PortfolioBoardEngine.review(inputs)
                )
            )
        }
    }.flowOn(Dispatchers.Default)
}
