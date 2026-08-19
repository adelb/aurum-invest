package com.aurum.invest

import com.aurum.invest.analytics.HoldingMove
import com.aurum.invest.analytics.WealthEngine
import com.aurum.invest.analytics.WealthInputs
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.repo.InvestorProfile
import com.aurum.invest.data.repo.PortfolioRepository
import com.aurum.invest.data.repo.WalletState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The wealth engine's equations and honesty rules: Herfindahl concentration,
 * the Wilson lower bound, the current-book replay (volatility, beta, Sharpe,
 * drawdown), the VIX cash bands, the chandelier trail, and the fail-closed
 * coverage gate.
 */
class WealthEngineTest {

    private val dayMs = 86_400_000L

    /** [days] daily candles ending at [endPrice], walking from [startPrice]. */
    private fun trend(days: Int, startPrice: Double, endPrice: Double): List<Candle> {
        val step = (endPrice - startPrice) / (days - 1)
        return (0 until days).map { i ->
            val close = startPrice + step * i
            Candle(
                ts = 1_700_000_000_000L + i * dayMs,
                open = close * 0.995,
                high = close * 1.01,
                low = close * 0.99,
                close = close,
                volume = 1_000_000L
            )
        }
    }

    private fun view(symbol: String, shares: Double, avg: Double, price: Double) =
        PortfolioRepository.toView(
            Position(symbol = symbol, shares = shares, avgCost = avg,
                investedCost = shares * avg, realizedPl = 0.0),
            Quote(symbol = symbol, price = price, prevClose = price)
        )

    private fun inputs(
        views: List<com.aurum.invest.data.model.PositionView>,
        candles: Map<String, List<Candle>>,
        wallet: WalletState? = WalletState.of(
            total = 20_000.0, configured = true,
            invested = views.sumOf { it.position.investedCost },
            realizedPl = 0.0, unrealizedPl = views.sumOf { it.unrealizedPl },
            holdingsValue = views.sumOf { it.marketValue }
        ),
        vix: Double? = 18.0,
        profile: InvestorProfile = InvestorProfile.DEFAULT,
        sells: List<Double> = listOf(50.0, -20.0, 80.0, 10.0, -5.0, 120.0)
    ) = WealthInputs(
        wallet = wallet,
        views = views,
        candles = candles,
        sectors = views.associate { it.position.symbol to "Technology" },
        spy = trend(365, 400.0, 440.0),
        vix = vix,
        profile = profile,
        sellOutcomes = sells
    )

    // ---- equations ----------------------------------------------------------

    @Test
    fun hhiOfEqualWeightsIsOneOverN() {
        assertEquals(0.25, WealthEngine.hhi(listOf(1.0, 1.0, 1.0, 1.0)), 1e-9)
        assertEquals(1.0, WealthEngine.hhi(listOf(5.0)), 1e-9)
        // Dollar scale must not matter — only the shape of the weights.
        assertEquals(
            WealthEngine.hhi(listOf(1.0, 2.0, 3.0)),
            WealthEngine.hhi(listOf(100.0, 200.0, 300.0)),
            1e-9
        )
    }

    @Test
    fun wilsonLowerBoundIsConservative() {
        // 7 wins of 10: the point estimate is 70%, the honest floor ~39.7%.
        val lower = WealthEngine.wilsonLower(7, 10)
        assertTrue(abs(lower - 0.397) < 0.02)
        // More evidence tightens the bound toward the point estimate.
        assertTrue(WealthEngine.wilsonLower(70, 100) > lower)
        assertEquals(0.0, WealthEngine.wilsonLower(0, 0), 1e-9)
    }

    @Test
    fun cashBandsWidenWithFear() {
        assertTrue(WealthEngine.cashBandFor(12.0).endInclusive < WealthEngine.cashBandFor(30.0).start + 20.0)
        assertEquals(5.0..15.0, WealthEngine.cashBandFor(12.0))
        assertEquals(10.0..25.0, WealthEngine.cashBandFor(20.0))
        assertEquals(20.0..40.0, WealthEngine.cashBandFor(32.0))
        assertEquals(5.0..25.0, WealthEngine.cashBandFor(null))
    }

    @Test
    fun replayMeasuresReturnVolatilityBetaAndDrawdown() {
        // One holding whose candles ARE the benchmark: beta must be ~1 and
        // the book's return must match SPY's over the same window.
        val series = trend(365, 400.0, 440.0)
        val v = view("SPYX", 10.0, 400.0, 440.0)
        val risk = WealthEngine.replayCurrentBook(
            listOf(v), mapOf("SPYX" to series), series
        )!!
        assertEquals(100.0, risk.measuredValuePct, 1e-6)
        assertNotNull(risk.beta)
        assertTrue(abs(risk.beta!! - 1.0) < 0.05)
        assertEquals(risk.returnPct, risk.benchmarkReturnPct!!, 0.2)
        // A monotone uptrend never draws down.
        assertTrue(risk.maxDrawdownPct < 1.0)
        assertTrue(risk.volatilityPct >= 0.0)
    }

    // ---- holding moves ------------------------------------------------------

    @Test
    fun capBreachDemandsATrimNeverAProfitRule() {
        // One winner grown to 60% of a two-stock book (cap 22%): TRIM — and
        // the reason is the CAP, not the gain.
        val big = view("BIG", 60.0, 50.0, 100.0)     // $6,000
        val small = view("SML", 40.0, 90.0, 100.0)   // $4,000
        val candles = mapOf(
            "BIG" to trend(365, 50.0, 100.0),
            "SML" to trend(365, 90.0, 100.0)
        )
        val report = WealthEngine.evaluate(inputs(listOf(big, small), candles))
        val bigEval = report.holdings.first { it.symbol == "BIG" }
        assertEquals(HoldingMove.TRIM, bigEval.move)
        assertTrue(bigEval.reasons.first().contains("position cap"))
        // And the trim lands in the management plan with the dollar excess.
        val trim = report.actions.first { it.symbol == "BIG" }
        assertNotNull(trim.amount)
        assertTrue(trim.amount!! > 0.0)
    }

    @Test
    fun uptrendHoldingCarriesARatchetingTrailBelowThePrice() {
        val v = view("UP", 10.0, 50.0, 100.0)
        val report = WealthEngine.evaluate(
            inputs(listOf(v), mapOf("UP" to trend(365, 50.0, 100.0)))
        )
        val eval = report.holdings.single()
        val stop = eval.trailStop
        assertNotNull(stop)
        assertTrue(stop!! < 100.0)
        assertTrue(stop > 0.0)
        // The design law: a 100% gain alone must never read as a sell —
        // winners are ridden behind the trail. A single-holding book IS over
        // any position cap, so a TRIM may fire — but only citing the CAP,
        // never the profit; and an intact uptrend is never an EXIT.
        assertTrue(eval.move != HoldingMove.EXIT)
        if (eval.move == HoldingMove.TRIM) {
            assertTrue(eval.reasons.first().contains("position cap"))
        }
    }

    @Test
    fun brokenDowntrendIsAnExit() {
        // A year sliding 100 -> 40: below the 200-day, weak RSI, bearish board.
        val v = view("DN", 10.0, 90.0, 40.0)
        val report = WealthEngine.evaluate(
            inputs(listOf(v), mapOf("DN" to trend(365, 100.0, 40.0)))
        )
        val eval = report.holdings.single()
        assertEquals(HoldingMove.EXIT, eval.move)
        assertTrue(report.actions.any { it.symbol == "DN" })
    }

    // ---- honesty gates ------------------------------------------------------

    @Test
    fun scoreIsWithheldWhenTooLittleIsMeasured() {
        // No candles, no wallet, no VIX, 2 sells: almost nothing measurable.
        val v = view("X", 1.0, 100.0, 100.0)
        val report = WealthEngine.evaluate(
            inputs(
                listOf(v), candles = mapOf("X" to emptyList()),
                wallet = null, vix = null, sells = listOf(1.0, -1.0)
            ).copy(spy = emptyList())
        )
        assertNull(report.healthScore)
        assertTrue(report.coveragePct < WealthEngine.MIN_COVERAGE_PCT)
        // The refusal explains itself.
        assertTrue(report.notes.any { it.contains("wallet", ignoreCase = true) })
    }

    @Test
    fun fullyMeasuredBookEarnsAScoreAndStopsAction() {
        val a = view("AAA", 20.0, 80.0, 100.0)
        val b = view("BBB", 30.0, 60.0, 70.0)
        val report = WealthEngine.evaluate(
            inputs(
                listOf(a, b),
                mapOf("AAA" to trend(365, 80.0, 100.0), "BBB" to trend(365, 60.0, 70.0))
            )
        )
        assertNotNull(report.healthScore)
        assertTrue(report.coveragePct >= WealthEngine.MIN_COVERAGE_PCT)
        assertEquals(6, report.disciplines.size)
        // Standing the trails is always on the plan for surviving holdings.
        assertTrue(report.actions.any { it.headline.contains("trailing stop", ignoreCase = true) })
    }
}
