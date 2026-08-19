package com.aurum.invest

import com.aurum.invest.analytics.PortfolioPerformanceEngine
import com.aurum.invest.analytics.TechniqueEvaluator
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
import com.aurum.invest.data.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Statistical honesty gates (C4) and the equity-curve reconstruction (H2). */
class EvaluatorAndPerformanceTest {

    // ---- trust gates --------------------------------------------------------

    @Test
    fun `too few independent samples never earn trust`() {
        assertTrue(!TechniqueEvaluator.isTrustworthy(independentSignals = 5, independentHitRate = 100, baseRatePct = 40))
    }

    @Test
    fun `riding the base rate never earns trust`() {
        // 62% hit rate sounds good — but the stock itself rose 60% of windows.
        assertTrue(!TechniqueEvaluator.isTrustworthy(independentSignals = 20, independentHitRate = 62, baseRatePct = 60))
    }

    @Test
    fun `real edge over base earns trust`() {
        assertTrue(TechniqueEvaluator.isTrustworthy(independentSignals = 20, independentHitRate = 70, baseRatePct = 55))
    }

    @Test
    fun `wilson interval brackets the point estimate and respects n`() {
        val (low1, high1) = TechniqueEvaluator.wilson95(7, 10)
        val (low2, high2) = TechniqueEvaluator.wilson95(70, 100)
        assertTrue(low1 < 70.0 && high1 > 70.0)
        assertTrue(low2 < 70.0 && high2 > 70.0)
        // More evidence -> tighter interval.
        assertTrue(high2 - low2 < high1 - low1)
        val (l0, h0) = TechniqueEvaluator.wilson95(0, 0)
        assertEquals(0.0, l0, 1e-9)
        assertEquals(100.0, h0, 1e-9)
    }

    // ---- equity curve -------------------------------------------------------

    private fun day(i: Int): Long = 1_600_000_000_000L + i * 86_400_000L

    private fun candleSeries(prices: List<Double>): List<Candle> =
        prices.mapIndexed { i, p ->
            Candle(ts = day(i), open = p, high = p, low = p, close = p, volume = 1L)
        }

    @Test
    fun `twr matches a simple one-position path`() {
        val n = 40
        // Stock rises 1% every day; SPY is flat.
        val stock = candleSeries(List(n) { 100.0 * Math.pow(1.01, it.toDouble()) })
        val spy = candleSeries(List(n) { 400.0 })
        val ledger = listOf(
            TransactionEntity(
                id = 1, symbol = "AAA", side = TxSide.BUY, shares = 10.0,
                price = 100.0, ts = day(0)
            )
        )
        val perf = PortfolioPerformanceEngine.compute(
            ordered = ledger,
            cashEvents = emptyList(),
            candlesBySymbol = mapOf("AAA" to stock),
            benchmark = spy,
            cashTracked = false
        )
        assertNotNull(perf)
        // ~1% a day compounded across the measured days; SPY flat at 0%.
        assertTrue("twr=${perf!!.twrPct}", perf.twrPct > 20.0)
        assertEquals(0.0, perf.benchmarkTwrPct, 0.5)
        assertEquals(0.0, perf.maxDrawdownPct, 1e-6)
        assertTrue(perf.tradingDays >= PortfolioPerformanceEngine.MIN_DAYS)
    }

    @Test
    fun `mid-window buys are flows not returns`() {
        val n = 40
        val stock = candleSeries(List(n) { 100.0 }) // flat price
        val spy = candleSeries(List(n) { 400.0 })
        val ledger = listOf(
            TransactionEntity(
                id = 1, symbol = "AAA", side = TxSide.BUY, shares = 10.0,
                price = 100.0, ts = day(0)
            ),
            // Doubling the position mid-window must NOT look like a +100% day.
            TransactionEntity(
                id = 2, symbol = "AAA", side = TxSide.BUY, shares = 10.0,
                price = 100.0, ts = day(20)
            )
        )
        val perf = PortfolioPerformanceEngine.compute(
            ordered = ledger,
            cashEvents = emptyList(),
            candlesBySymbol = mapOf("AAA" to stock),
            benchmark = spy,
            cashTracked = false
        )
        assertNotNull(perf)
        assertEquals(0.0, perf!!.twrPct, 0.5)
    }

    @Test
    fun `drawdown is measured on the equity path`() {
        val n = 40
        // 100 -> 120 -> 90 -> 110: a real drawdown of 25% off the 120 peak.
        val prices = ArrayList<Double>()
        for (i in 0 until n) {
            prices.add(
                when {
                    i < 10 -> 100.0 + i * 2.0        // to 118
                    i < 20 -> 120.0 - (i - 10) * 3.0 // to 93
                    else -> 90.0 + (i - 20) * 1.0
                }
            )
        }
        val stock = candleSeries(prices)
        val spy = candleSeries(List(n) { 400.0 })
        val ledger = listOf(
            TransactionEntity(
                id = 1, symbol = "AAA", side = TxSide.BUY, shares = 10.0,
                price = 100.0, ts = day(0)
            )
        )
        val perf = PortfolioPerformanceEngine.compute(
            ordered = ledger,
            cashEvents = emptyList(),
            candlesBySymbol = mapOf("AAA" to stock),
            benchmark = spy,
            cashTracked = false
        )
        assertNotNull(perf)
        assertTrue("dd=${perf!!.maxDrawdownPct}", perf.maxDrawdownPct < -15.0)
    }
}
