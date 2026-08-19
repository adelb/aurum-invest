package com.aurum.invest

import com.aurum.invest.analytics.LedgerTrade
import com.aurum.invest.analytics.PerformanceEngine
import com.aurum.invest.data.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The performance engine: the TWR chain and its flow-immunity, the SPY
 * shadow, split re-basing against adjusted candles, the coverage gate, and
 * the XIRR solver. These are the verdict's load-bearing equations — each is
 * locked against hand-computed truth.
 */
class PerformanceEngineTest {

    private val dayMs = 86_400_000L
    private val base = 1_700_000_000_000L

    private fun day(i: Int) = base + i * dayMs

    /** Daily candles from a list of closes, one per day starting at [base]. */
    private fun candles(closes: List<Double>): List<Candle> =
        closes.mapIndexed { i, c ->
            Candle(ts = day(i), open = c, high = c, low = c, close = c, volume = 1_000_000L)
        }

    private fun linear(days: Int, from: Double, to: Double): List<Double> =
        (0 until days).map { from + (to - from) * it / (days - 1) }

    private fun buy(dayIdx: Int, symbol: String, shares: Double, price: Double) =
        LedgerTrade(day(dayIdx) + 3_600_000L, symbol, "BUY", shares, price, 0.0)

    private fun sell(dayIdx: Int, symbol: String, shares: Double, price: Double) =
        LedgerTrade(day(dayIdx) + 3_600_000L, symbol, "SELL", shares, price, 0.0)

    // ---- the chain ----------------------------------------------------------

    @Test
    fun singleRideMeasuresTwrAgainstFlatSpy() {
        val n = 40
        val report = PerformanceEngine.evaluate(
            trades = listOf(buy(0, "AAA", 10.0, 100.0)),
            candles = mapOf("AAA" to candles(linear(n, 100.0, 150.0))),
            spy = candles(List(n) { 400.0 }),
            now = day(n)
        )!!
        assertEquals(50.0, report.twrPct, 0.5)
        assertEquals(0.0, report.spyTwrPct, 0.5)
        assertEquals(report.twrPct, report.edgePct, 1e-6)
        assertEquals(1_500.0, report.bookNow, 1.0)
        assertEquals(1_000.0, report.investedIn, 1e-6)
        // The shadow held the same opening dollars in a flat SPY.
        assertEquals(1_000.0, report.spyAltNow, 1.0)
        assertEquals(100.0, report.coveragePct, 1e-6)
        // A monotone ride never draws down.
        assertTrue(report.maxDrawdownPct < 1.0)
    }

    @Test
    fun twrIgnoresFlowTimingButMwrDoesNot() {
        // The price path doubles: 100 -> 150 by day 10, -> 200 by day 20.
        val closes = linear(11, 100.0, 150.0) + linear(10, 155.0, 200.0)
        val spy = candles(List(closes.size) { 400.0 })
        val a = PerformanceEngine.evaluate(
            trades = listOf(buy(0, "AAA", 10.0, 100.0)),
            candles = mapOf("AAA" to candles(closes)),
            spy = spy,
            now = day(closes.size) + 200L * dayMs   // age the span for the solver
        )!!
        val b = PerformanceEngine.evaluate(
            trades = listOf(
                buy(0, "AAA", 10.0, 100.0),
                // Big money arrives late, at day 10's higher price.
                buy(10, "AAA", 100.0, 150.0)
            ),
            candles = mapOf("AAA" to candles(closes)),
            spy = spy,
            now = day(closes.size) + 200L * dayMs
        )!!
        // Time-weighted: identical — the late deposit must not change it.
        assertEquals(a.twrPct, b.twrPct, 0.5)
        // Money-weighted: B's dollars mostly rode only the second leg.
        assertNotNull(a.mwrAnnPct)
        assertNotNull(b.mwrAnnPct)
        assertTrue(b.mwrAnnPct!! < a.mwrAnnPct!!)
    }

    @Test
    fun sellsFlowOutWithoutDistortingTheReturn() {
        val closes = linear(11, 100.0, 150.0) + linear(10, 155.0, 200.0)
        val report = PerformanceEngine.evaluate(
            trades = listOf(
                buy(0, "AAA", 10.0, 100.0),
                sell(10, "AAA", 5.0, 150.0)
            ),
            candles = mapOf("AAA" to candles(closes)),
            spy = candles(List(closes.size) { 400.0 }),
            now = day(closes.size)
        )!!
        // The name doubled; selling half along the way is a flow, not a loss.
        assertEquals(100.0, report.twrPct, 1.5)
        assertEquals(750.0, report.takenOut, 1e-6)
        assertEquals(1_000.0, report.bookNow, 1.0)
    }

    @Test
    fun splitRebasedTradesReplayAgainstAdjustedCandles() {
        // Yahoo's candles are split-adjusted retroactively: a 2:1 split at
        // day 10 shows the pre-split 100->120 ride as 50->60. The ledger
        // holds raw shares; the engine must re-base them or halve the value.
        val closes = linear(11, 50.0, 60.0) + linear(10, 60.5, 66.0)
        val report = PerformanceEngine.evaluate(
            trades = listOf(
                buy(0, "AAA", 10.0, 100.0),
                LedgerTrade(day(10), "AAA", "SPLIT", 2.0, 0.0, 0.0)
            ),
            candles = mapOf("AAA" to candles(closes)),
            spy = candles(List(closes.size) { 400.0 }),
            now = day(closes.size)
        )!!
        // 10 raw shares -> 20 current-unit shares at $66 = $1,320 on $1,000.
        assertEquals(1_320.0, report.bookNow, 2.0)
        assertEquals(32.0, report.twrPct, 1.0)
    }

    // ---- honesty gates ------------------------------------------------------

    @Test
    fun unpricedSymbolIsDroppedAndNamed() {
        val n = 30
        val report = PerformanceEngine.evaluate(
            trades = listOf(
                buy(0, "AAA", 10.0, 100.0),     // priced: ~70% of the book
                buy(0, "BBB", 4.0, 100.0)       // no candles: ~30%
            ),
            candles = mapOf("AAA" to candles(List(n) { 100.0 })),
            spy = candles(List(n) { 400.0 }),
            quotes = mapOf("BBB" to 107.0),
            now = day(n)
        )!!
        assertTrue("BBB" in report.droppedSymbols)
        assertEquals(70.0, report.coveragePct, 2.0)
        assertTrue(report.notes.any { it.contains("BBB") })
    }

    @Test
    fun verdictIsWithheldWhenTheUnmeasuredPartDominates() {
        val n = 30
        val report = PerformanceEngine.evaluate(
            trades = listOf(
                buy(0, "AAA", 2.0, 100.0),      // priced: ~20%
                buy(0, "BBB", 8.0, 100.0)       // unpriced: ~80%
            ),
            candles = mapOf("AAA" to candles(List(n) { 100.0 })),
            spy = candles(List(n) { 400.0 }),
            quotes = mapOf("BBB" to 100.0),
            now = day(n)
        )
        assertNull(report)
    }

    @Test
    fun shortSpansRefuseToAnnualize() {
        val n = 30    // well under MIN_ANNUALIZE_DAYS
        val report = PerformanceEngine.evaluate(
            trades = listOf(buy(0, "AAA", 10.0, 100.0)),
            candles = mapOf("AAA" to candles(linear(n, 100.0, 110.0))),
            spy = candles(List(n) { 400.0 }),
            now = day(n)
        )!!
        assertNull(report.twrAnnPct)
        assertTrue(report.notes.any { it.contains("annualized", ignoreCase = true) })
    }

    @Test
    fun curveIndexesBothSeriesFrom100() {
        val n = 40
        val report = PerformanceEngine.evaluate(
            trades = listOf(buy(0, "AAA", 10.0, 100.0)),
            candles = mapOf("AAA" to candles(linear(n, 100.0, 150.0))),
            spy = candles(linear(n, 400.0, 440.0)),
            now = day(n)
        )!!
        assertEquals(100.0, report.curve.first().book, 1e-6)
        assertEquals(100.0, report.curve.first().spy, 1e-6)
        assertEquals(150.0, report.curve.last().book, 1.0)
        assertEquals(110.0, report.curve.last().spy, 1.0)
    }

    // ---- the solver ---------------------------------------------------------

    @Test
    fun xirrSolvesAKnownAnnualRate() {
        val rate = PerformanceEngine.xirr(
            listOf(
                base to -1_000.0,
                base + 365 * dayMs to 1_100.0
            )
        )
        assertNotNull(rate)
        assertTrue(abs(rate!! - 0.10) < 0.005)
    }

    @Test
    fun xirrRefusesOneSidedFlows() {
        assertNull(PerformanceEngine.xirr(listOf(base to -100.0, base + dayMs to -50.0)))
        assertNull(PerformanceEngine.xirr(listOf(base to 100.0)))
    }

    @Test
    fun splitAdjustmentPreservesEveryDollar() {
        val adjusted = PerformanceEngine.adjustForSplits(
            listOf(
                buy(0, "AAA", 10.0, 100.0),
                LedgerTrade(day(5), "AAA", "SPLIT", 4.0, 0.0, 0.0),
                sell(10, "AAA", 40.0, 30.0)
            )
        )
        // The SPLIT row is consumed; quantities re-base, dollars hold.
        assertEquals(2, adjusted.size)
        val first = adjusted.first()
        assertEquals(40.0, first.shares, 1e-9)
        assertEquals(25.0, first.price, 1e-9)
        assertEquals(1_000.0, first.shares * first.price, 1e-6)
        // The post-split sell is already in current units — untouched.
        assertEquals(40.0, adjusted.last().shares, 1e-9)
        assertEquals(30.0, adjusted.last().price, 1e-9)
    }
}
