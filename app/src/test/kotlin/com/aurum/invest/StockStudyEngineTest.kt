package com.aurum.invest

import com.aurum.invest.analytics.StockStudyEngine
import com.aurum.invest.analytics.StudyInputs
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.Fundamentals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The stock study engine: the analog projection's percentile math and honesty
 * gates, the graded factors' scaling, and the fail-closed grade coverage.
 */
class StockStudyEngineTest {

    private val dayMs = 86_400_000L

    private fun candles(closes: List<Double>): List<Candle> =
        closes.mapIndexed { i, c ->
            Candle(
                ts = 1_600_000_000_000L + i * dayMs,
                open = c * 0.995, high = c * 1.01, low = c * 0.99,
                close = c, volume = 1_000_000L
            )
        }

    /** Steady compound growth: every 21-session forward return is identical. */
    private fun compound(days: Int, dailyRate: Double = 1.002): List<Double> =
        (0 until days).map { 100.0 * dailyRate.pow(it) }

    private fun inputs(
        candles: List<Candle>,
        spy: List<Candle> = this.candles(compound(candles.size, 1.0005)),
        fundamentals: Fundamentals? = null
    ) = StudyInputs(
        symbol = "TST", name = "Test Corp", quote = null,
        candles = candles, spy = spy,
        fundamentals = fundamentals, news = emptyList(), vix = 18.0
    )

    // ---- the projection -----------------------------------------------------

    @Test
    fun steadyGrowthProjectsItsOwnMeasuredForwardReturn() {
        val closes = compound(400)
        val p = StockStudyEngine.project(candles(closes), closes.last())!!
        val expected = (1.002.pow(21) - 1.0) * 100.0   // ≈ +4.3%
        // Every analog month did the same thing — the whole band collapses
        // onto the one measured outcome.
        assertEquals(expected, p.medianPct, 0.3)
        assertEquals(p.medianPct, p.q1Pct, 0.5)
        assertEquals(p.medianPct, p.p90Pct, 0.5)
        assertEquals(100.0, p.upSharePct, 1e-6)
        assertEquals(closes.last() * (1.0 + expected / 100.0), p.medianPrice, 2.0)
        assertTrue(p.analogCount >= StockStudyEngine.MIN_ANALOGS)
    }

    @Test
    fun thinHistoryRefusesToProject() {
        val closes = compound(100)   // under MIN_HISTORY_BARS
        assertNull(StockStudyEngine.project(candles(closes), closes.last()))
        val study = StockStudyEngine.study(inputs(candles(closes)))
        assertNull(study.projection)
        assertTrue(study.notes.any { it.contains("projection", ignoreCase = true) })
    }

    @Test
    fun mediumHistoryProjectsUnconditionedAndSaysSo() {
        // Enough to measure, not enough to condition on regimes.
        val closes = compound(200)
        val p = StockStudyEngine.project(candles(closes), closes.last())!!
        assertFalse(p.conditioned)
        assertTrue(p.basis.contains("every month"))
    }

    @Test
    fun projectionPricesScaleFromTheGivenPrice() {
        val closes = compound(400)
        val p1 = StockStudyEngine.project(candles(closes), 100.0)!!
        val p2 = StockStudyEngine.project(candles(closes), 200.0)!!
        assertEquals(p1.medianPct, p2.medianPct, 1e-9)
        assertEquals(p1.medianPrice * 2.0, p2.medianPrice, 0.1)
    }

    // ---- factors ------------------------------------------------------------

    @Test
    fun strongFundamentalsOutscoreWeakOnes() {
        val closes = compound(300)
        val strong = StockStudyEngine.study(
            inputs(
                candles(closes),
                fundamentals = Fundamentals(
                    symbol = "TST", debtToEquity = 20.0, profitMargins = 0.25,
                    revenueGrowth = 0.20, freeCashflow = 1e9, forwardPE = 12.0
                )
            )
        ).factors.first { it.key == "fundamentals" }
        val weak = StockStudyEngine.study(
            inputs(
                candles(closes),
                fundamentals = Fundamentals(
                    symbol = "TST", debtToEquity = 300.0, profitMargins = -0.10,
                    revenueGrowth = -0.20, freeCashflow = -1e9, forwardPE = 60.0
                )
            )
        ).factors.first { it.key == "fundamentals" }
        assertEquals(25, strong.score)
        assertTrue(weak.score!! <= 2)
    }

    @Test
    fun partialFundamentalsAreGradedOnTheMeasuredPartsOnly() {
        val closes = compound(300)
        val factor = StockStudyEngine.study(
            inputs(
                candles(closes),
                // Only two of five components reported — both strong.
                fundamentals = Fundamentals(
                    symbol = "TST", debtToEquity = 20.0, profitMargins = 0.25
                )
            )
        ).factors.first { it.key == "fundamentals" }
        // Scaled to the measured universe: full marks, not 10/25.
        assertEquals(25, factor.score)
        assertTrue(factor.detail.contains("2 measured of 5"))
    }

    @Test
    fun missingFundamentalsAndNewsAreUnmeasuredNeverZero() {
        val closes = compound(300)
        val study = StockStudyEngine.study(inputs(candles(closes)))
        assertNull(study.factors.first { it.key == "fundamentals" }.score)
        assertNull(study.factors.first { it.key == "news" }.score)
        // The five measured factors (trend, momentum, board, volume, risk)
        // are 65 of 100 — coverage above the bar, so a grade IS claimed.
        assertNotNull(study.grade)
        assertEquals(65.0, study.coveragePct, 1.0)
    }

    @Test
    fun gradeIsWithheldWhenTooLittleIsMeasured() {
        // 40 bars: no projection, no board depth, no 3-month momentum, no
        // volume base, no volatility window — almost nothing measurable.
        val closes = compound(40)
        val study = StockStudyEngine.study(
            inputs(candles(closes), spy = emptyList<Candle>().let { candles(emptyList()) })
        )
        assertNull(study.grade)
        assertTrue(study.notes.any { it.contains("grade", ignoreCase = true) })
    }

    @Test
    fun momentumIsUnmeasuredWithoutTheBenchmark() {
        val closes = compound(300)
        val study = StockStudyEngine.study(
            inputs(candles(closes), spy = candles(emptyList()))
        )
        assertNull(study.factors.first { it.key == "momentum" }.score)
    }

    // ---- helpers ------------------------------------------------------------

    @Test
    fun spanReturnMeasuresExactlyNSessionsBack() {
        val closes = (0..100).map { 100.0 + it }   // +1 per session
        val r = StockStudyEngine.spanReturn(closes, 21)!!
        assertEquals((200.0 - 179.0) / 179.0 * 100.0, r, 0.1)
        assertNull(StockStudyEngine.spanReturn(closes.take(10), 21))
    }

    @Test
    fun rollingIndicatorsMatchTheirDefinitions() {
        val flat = List(60) { 50.0 }
        val sma = StockStudyEngine.rollingSma(flat, 20)
        assertNull(sma[18])
        assertEquals(50.0, sma[19]!!, 1e-9)
        assertEquals(50.0, sma[59]!!, 1e-9)
        // A series that only gains pegs Wilder RSI at 100.
        val rising = compound(60)
        val rsi = StockStudyEngine.rollingRsi(rising, 14)
        assertEquals(100.0, rsi[59]!!, 1e-6)
    }
}
