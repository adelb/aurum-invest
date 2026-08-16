package com.aurum.invest

import com.aurum.invest.analytics.BuyPlanEngine
import com.aurum.invest.analytics.Techniques
import com.aurum.invest.data.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Account-aware plan sizing (C5): the budget must derive from equity and the
 * risk policy when the account is known, and be labeled a default otherwise.
 */
class BuyPlanTest {

    /** Deterministic up-trending series long enough for the 200-day average. */
    private fun candles(n: Int = 260): List<Candle> {
        var price = 100.0
        val out = ArrayList<Candle>(n)
        for (i in 0 until n) {
            // Deterministic wobble: rises with a repeating dip pattern.
            val drift = 0.15
            val wobble = when (i % 5) {
                0 -> -0.8; 1 -> 0.6; 2 -> -0.3; 3 -> 0.9; else -> 0.2
            }
            price = (price + drift + wobble).coerceAtLeast(5.0)
            out.add(
                Candle(
                    ts = 1_600_000_000_000L + i * 86_400_000L,
                    open = price - 0.3,
                    high = price + 1.0,
                    low = price - 1.2,
                    close = price,
                    volume = 1_000_000L
                )
            )
        }
        return out
    }

    @Test
    fun `unknown account falls back to a labeled default budget`() {
        val cs = candles()
        val analysis = Techniques.analyze("TEST", cs)!!
        val plan = BuyPlanEngine.build("TEST", cs, analysis, cs.last().close)
        assertEquals(BuyPlanEngine.DEFAULT_BUDGET, plan.budget, 1e-9)
        assertTrue(plan.budgetBasis.contains("NOT sized by the 2% account rule"))
        assertEquals(null, plan.accountEquity)
    }

    @Test
    fun `known account caps the stop-out loss at the risk policy`() {
        val cs = candles()
        val analysis = Techniques.analyze("TEST", cs)!!
        val equity = 50_000.0
        val riskPct = 2.0
        val plan = BuyPlanEngine.build(
            "TEST", cs, analysis, cs.last().close,
            accountEquity = equity,
            riskPerTradePct = riskPct,
            maxPositionPct = 100.0
        )
        assertNotNull(plan.accountEquity)
        // A full stop-out must lose at most ~2% of the account (small rounding slack).
        assertTrue(
            "risk ${plan.riskDollars} exceeds ${equity * riskPct / 100.0}",
            plan.riskDollars <= equity * riskPct / 100.0 * 1.02
        )
        // And riskPct is now a share of the ACCOUNT.
        assertTrue(plan.riskPct <= riskPct * 1.02)
    }

    @Test
    fun `position cap binds when risk allows a bigger order`() {
        val cs = candles()
        val analysis = Techniques.analyze("TEST", cs)!!
        val equity = 50_000.0
        val plan = BuyPlanEngine.build(
            "TEST", cs, analysis, cs.last().close,
            accountEquity = equity,
            riskPerTradePct = 5.0,
            maxPositionPct = 10.0
        )
        assertTrue(plan.budget <= equity * 0.10 * 1.001)
    }

    @Test
    fun `cash cap binds the order size`() {
        val cs = candles()
        val analysis = Techniques.analyze("TEST", cs)!!
        val plan = BuyPlanEngine.build(
            "TEST", cs, analysis, cs.last().close,
            accountEquity = 100_000.0,
            riskPerTradePct = 3.0,
            maxPositionPct = 50.0,
            cashAvailable = 1_500.0
        )
        assertTrue(plan.budget <= 1_500.0 * 1.001)
    }

    @Test
    fun `a fully deployed wallet is told the plan needs a sale to fund it`() {
        val cs = candles()
        val analysis = Techniques.analyze("TEST", cs)!!
        val plan = BuyPlanEngine.build(
            "TEST", cs, analysis, cs.last().close,
            accountEquity = 20_000.0,
            cashAvailable = 0.0
        )
        assertTrue(plan.budgetBasis.contains("no uninvested cash"))
        assertTrue(plan.budgetBasis.contains("selling something first"))
    }

    @Test
    fun `unknown cash is not reported as an empty balance`() {
        val cs = candles()
        val analysis = Techniques.analyze("TEST", cs)!!
        val plan = BuyPlanEngine.build(
            "TEST", cs, analysis, cs.last().close,
            accountEquity = 20_000.0,
            cashAvailable = null
        )
        assertTrue(!plan.budgetBasis.contains("no uninvested cash"))
    }

    @Test
    fun `stop sits below every planned entry`() {
        val cs = candles()
        val analysis = Techniques.analyze("TEST", cs)!!
        val plan = BuyPlanEngine.build("TEST", cs, analysis, cs.last().close)
        val lowestEntry = plan.tranches.filter { it.shares > 0.0 }.minOf { it.price }
        assertTrue(plan.stop < lowestEntry)
        assertTrue(plan.stop > 0.0)
    }

    @Test
    fun `risk scales linearly with budget`() {
        val cs = candles()
        val analysis = Techniques.analyze("TEST", cs)!!
        val price = cs.last().close
        val a = BuyPlanEngine.build("TEST", cs, analysis, price, fallbackBudget = 1000.0)
        val b = BuyPlanEngine.build("TEST", cs, analysis, price, fallbackBudget = 2000.0)
        assertTrue(abs(b.riskDollars - 2 * a.riskDollars) < a.riskDollars * 0.05 + 1.0)
    }
}
