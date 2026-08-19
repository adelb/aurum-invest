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
        fundamentals: Fundamentals? = null,
        heldShares: Double? = null,
        heldAvgCost: Double? = null
    ) = StudyInputs(
        symbol = "TST", name = "Test Corp", quote = null,
        candles = candles, spy = spy,
        fundamentals = fundamentals, news = emptyList(), vix = 18.0,
        heldShares = heldShares, heldAvgCost = heldAvgCost
    )

    // ---- the projection -----------------------------------------------------

    @Test
    fun steadyGrowthProjectsItsOwnMeasuredForwardReturnAtEveryHorizon() {
        val closes = compound(400)
        val ps = StockStudyEngine.projectAll(candles(closes), closes.last())
        // 400 bars leave forward room for all five horizons.
        assertEquals(StockStudyEngine.HORIZONS.size, ps.size)
        for (p in ps) {
            // Every analog window did the same thing — the whole band
            // collapses onto the one measured outcome of that horizon.
            val expected = (1.002.pow(p.horizonSessions) - 1.0) * 100.0
            assertEquals(p.label, expected, p.medianPct, expected * 0.05 + 0.3)
            assertEquals(p.medianPct, p.q1Pct, expected * 0.05 + 0.5)
            assertEquals(p.medianPct, p.p90Pct, expected * 0.05 + 0.5)
            assertEquals(100.0, p.upSharePct, 1e-6)
        }
        // Longer horizons compound further out.
        assertTrue(ps.last().medianPct > ps.first().medianPct)
    }

    @Test
    fun thinHistoryRefusesToProject() {
        val closes = compound(100)   // under MIN_HISTORY_BARS
        assertTrue(StockStudyEngine.projectAll(candles(closes), closes.last()).isEmpty())
        val study = StockStudyEngine.study(inputs(candles(closes)))
        assertTrue(study.projections.isEmpty())
        assertTrue(study.notes.any { it.contains("projection", ignoreCase = true) })
    }

    @Test
    fun mediumHistoryProjectsUnconditionedAndSaysSo() {
        // Enough to measure, not enough to condition on regimes.
        val closes = compound(200)
        val ps = StockStudyEngine.projectAll(candles(closes), closes.last())
        assertTrue(ps.isNotEmpty())
        for (p in ps) {
            assertFalse(p.conditioned)
            assertTrue(p.basis.contains("every window"))
        }
    }

    @Test
    fun youngListingSkipsTheLongHorizonsAndSaysSo() {
        // 300 bars: 1 week through 6 months measurable; a 1-year forward
        // window has no room at all.
        val closes = compound(300)
        val study = StockStudyEngine.study(inputs(candles(closes)))
        val labels = study.projections.map { it.label }
        assertTrue("1 week" in labels)
        assertTrue("1 month" in labels)
        assertTrue("1 year" !in labels)
        assertTrue(study.notes.any { it.contains("1 year") && it.contains("not claimed") })
    }

    @Test
    fun veryYoungListingGetsAVolImpliedBandNotSilence() {
        // ~23 sessions, like a fresh NASDAQ listing (SKHY the day it moved).
        // The closes wobble so the daily σ is real.
        val closes = (0 until 23).map { 150.0 + (it % 5) }
        val ps = StockStudyEngine.projectAll(candles(closes), closes.last())
        assertTrue(ps.isNotEmpty())
        // Only horizons the history can back: 1 week and 1 month, no more.
        assertTrue(ps.all { it.horizonSessions <= 22 })
        for (p in ps) {
            assertTrue(p.volImplied)
            assertEquals(0, p.analogCount)
            // Centered on today, symmetric, wider at the longer horizon.
            assertEquals(0.0, p.medianPct, 1e-9)
            assertEquals(closes.last(), p.medianPrice, 0.01)
            assertEquals(-p.q1Pct, p.q3Pct, 0.11)
            assertTrue(p.basis.contains("too young"))
        }
        assertTrue(ps.last().q3Pct > ps.first().q3Pct)
        // And the study says why, in words.
        val study = StockStudyEngine.study(inputs(candles(closes)))
        assertTrue(study.notes.any { it.contains("too young") })
    }

    @Test
    fun projectionPricesScaleFromTheGivenPrice() {
        val closes = compound(400)
        val p1 = StockStudyEngine.projectAll(candles(closes), 100.0)
            .first { it.horizonSessions == 21 }
        val p2 = StockStudyEngine.projectAll(candles(closes), 200.0)
            .first { it.horizonSessions == 21 }
        assertEquals(p1.medianPct, p2.medianPct, 1e-9)
        assertEquals(p1.medianPrice * 2.0, p2.medianPrice, 0.1)
    }

    // ---- the portfolio tie-in -----------------------------------------------

    @Test
    fun heldSharesRideEveryProjectedLevel() {
        val closes = compound(400)
        val study = StockStudyEngine.study(
            inputs(candles(closes), heldShares = 10.0, heldAvgCost = 50.0)
        )
        val held = study.held!!
        assertEquals(10.0, held.shares, 1e-9)
        assertEquals(10.0 * closes.last(), held.marketValue, 0.5)
        assertEquals(10.0 * (closes.last() - 50.0), held.unrealizedPl, 0.5)
        // Not held = no fabricated position.
        assertNull(StockStudyEngine.study(inputs(candles(closes))).held)
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
