package com.aurum.invest

import com.aurum.invest.analytics.BoardAction
import com.aurum.invest.analytics.BoardHoldingInput
import com.aurum.invest.analytics.PortfolioBoardEngine
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.Quote
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The portfolio board engine: the decision table's ordering, the unmeasured
 * paths, the value-weighted temperature's coverage gate, and the wording of
 * the to-do list.
 */
class PortfolioBoardEngineTest {

    private val dayMs = 86_400_000L

    private fun candles(closes: List<Double>): List<Candle> =
        closes.mapIndexed { i, c ->
            Candle(
                ts = 1_600_000_000_000L + i * dayMs,
                open = c * 0.995, high = c * 1.01, low = c * 0.99,
                close = c, volume = 1_000_000L
            )
        }

    private fun compound(days: Int, dailyRate: Double): List<Double> =
        (0 until days).map { 100.0 * dailyRate.pow(it) }

    private fun flat(days: Int): List<Double> =
        (0 until days).map { 100.0 + 0.4 * sin(it / 3.0) }

    private fun position(symbol: String, shares: Double, avgCost: Double) =
        Position(symbol, shares, avgCost, shares * avgCost, 0.0)

    private fun quote(symbol: String, price: Double) =
        Quote(symbol = symbol, price = price, prevClose = price)

    private fun input(
        symbol: String,
        shares: Double,
        avgCost: Double,
        closes: List<Double>,
        sector: String? = null
    ): BoardHoldingInput {
        val cs = candles(closes)
        return BoardHoldingInput(
            position = position(symbol, shares, avgCost),
            quote = quote(symbol, cs.last().close),
            candles = cs,
            evaluation = null,
            sector = sector
        )
    }

    // ---- the empty and unmeasured paths ------------------------------------

    @Test
    fun emptyBookReadsNothing() {
        val review = PortfolioBoardEngine.review(emptyList())
        assertTrue(review.holdings.isEmpty())
        assertNull(review.boardTempPct)
        assertTrue(review.headline.contains("No open positions"))
        assertTrue(review.actions.isEmpty())
    }

    @Test
    fun unpricedHoldingIsWatchedNotJudged() {
        val review = PortfolioBoardEngine.review(
            listOf(
                BoardHoldingInput(
                    position = position("GHOST", 10.0, 50.0),
                    quote = null,
                    candles = emptyList(),
                    evaluation = null
                )
            )
        )
        val h = review.holdings.single()
        assertEquals(BoardAction.WATCH, h.action)
        assertTrue(!h.measured)
        assertEquals(0.0, h.marketValue, 1e-9)
        assertNull(review.boardTempPct)
        assertTrue(review.notes.any { it.contains("GHOST") })
    }

    @Test
    fun garbageClosesNeverThrow() {
        val dirty = flat(200).mapIndexed { i, c -> if (i % 13 == 0) Double.NaN else c }
        val review = PortfolioBoardEngine.review(
            listOf(input("DIRTY", 5.0, 100.0, dirty))
        )
        assertEquals(1, review.holdings.size)
    }

    // ---- the decision table ------------------------------------------------

    @Test
    fun stopBreachLeadsTheListAsExit() {
        // Bought at 200, now ~100 on a flat tape: far through the loss stop.
        val loser = input("LOSS", 10.0, 200.0, flat(260))
        val steady = input("EVEN", 10.0, 100.0, flat(260))
        val review = PortfolioBoardEngine.review(listOf(steady, loser))

        val lossReview = review.holdings.first { it.symbol == "LOSS" }
        assertEquals(BoardAction.EXIT, lossReview.action)
        // Urgency order: the exit call leads the holdings AND the to-do list.
        assertEquals("LOSS", review.holdings.first().symbol)
        assertTrue(review.actions.isNotEmpty())
        assertTrue(review.actions.first().startsWith("Exit LOSS"))
    }

    @Test
    fun parabolicWinnerIsTrimmedNotAbandoned() {
        // A relentless riser held from the bottom: deep profit, RSI pinned —
        // the advice engine banks at least part, so the board says TRIM.
        val closes = compound(260, 1.003)
        val winner = input("WIN", 10.0, closes.first(), closes)
        val review = PortfolioBoardEngine.review(listOf(winner))
        val h = review.holdings.single()
        assertEquals(BoardAction.TRIM, h.action)
        assertTrue(h.why.isNotEmpty())
        assertTrue(review.actions.first().startsWith("Trim WIN"))
    }

    // ---- weights and the coverage gate ------------------------------------

    @Test
    fun weightsSumToTheWholePricedBook() {
        val review = PortfolioBoardEngine.review(
            listOf(
                input("AAA", 10.0, 100.0, flat(260)),
                input("BBB", 30.0, 100.0, flat(260))
            )
        )
        assertEquals(100.0, review.holdings.sumOf { it.weightPct }, 0.3)
        val bbb = review.holdings.first { it.symbol == "BBB" }
        assertEquals(75.0, bbb.weightPct, 0.5)
    }

    @Test
    fun boardTempWithheldWhenUnderSixtyPercentMeasured() {
        // Half the book's value has a quote but no candles — no board there.
        val unread = BoardHoldingInput(
            position = position("BLIND", 10.0, 100.0),
            quote = quote("BLIND", 100.0),
            candles = emptyList(),
            evaluation = null
        )
        val read = input("SEEN", 10.0, 100.0, flat(260))
        val review = PortfolioBoardEngine.review(listOf(unread, read))
        assertEquals(50.0, review.measuredWeightPct, 1.0)
        assertNull(review.boardTempPct)
        assertTrue(review.notes.any { it.contains("temperature") })
    }

    @Test
    fun boardTempClaimedWhenCoverageHolds() {
        val review = PortfolioBoardEngine.review(
            listOf(
                input("AAA", 10.0, 100.0, flat(260)),
                input("BBB", 10.0, 100.0, compound(260, 1.002))
            )
        )
        assertEquals(100.0, review.measuredWeightPct, 0.5)
        val temp = review.boardTempPct
        assertTrue(temp != null && temp in 0..100)
    }

    @Test
    fun sectorConcentrationIsNamed() {
        val review = PortfolioBoardEngine.review(
            listOf(
                input("AAA", 90.0, 100.0, flat(260), sector = "Technology"),
                input("BBB", 10.0, 100.0, flat(260), sector = "Energy")
            )
        )
        assertTrue(review.notes.any { it.contains("Concentrated") && it.contains("Technology") })
    }
}
