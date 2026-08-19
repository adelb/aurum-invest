package com.aurum.invest

import com.aurum.invest.analytics.AllocationMove
import com.aurum.invest.analytics.AllocationPlanEngine
import com.aurum.invest.analytics.EquityContext
import com.aurum.invest.analytics.HoldingAction
import com.aurum.invest.analytics.HoldingStage
import com.aurum.invest.analytics.HoldingVerdict
import com.aurum.invest.analytics.LiquidityCandidate
import com.aurum.invest.analytics.PortfolioAdvisor
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.data.repo.InvestorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allocation engine's contract: it may never spend money it does not have,
 * never exceed the profile's caps, never contradict a holding's own verdict,
 * and never quietly claim a percentage of an account it cannot see.
 */
class AllocationPlanEngineTest {

    private val profile = InvestorProfile.DEFAULT

    private fun verdict(
        symbol: String,
        marketValue: Double,
        price: Double = 100.0,
        action: HoldingAction = HoldingAction.HOLD,
        keepSharePct: Double = 100.0,
        conviction: Int = 80,
        convictionMax: Int = 100,
        riskScore: Int = 20,
        riskScoreMax: Int = 100,
        stage: HoldingStage = HoldingStage.ADVANCING,
        stop: Double = 90.0,
        sector: String = "Technology"
    ) = HoldingVerdict(
        symbol = symbol,
        name = "$symbol Inc",
        sector = sector,
        action = action,
        headline = "",
        whenText = "",
        whyPoints = emptyList(),
        price = price,
        avgCost = 80.0,
        marketValue = marketValue,
        weightPct = 0.0,
        unrealizedPl = 0.0,
        unrealizedPlPct = 10.0,
        target = 120.0,
        stop = stop,
        techBullish = 25,
        techTotal = 35,
        techConfidence = 70,
        techDirection = TechniqueVerdict.BULLISH,
        rsi = 60.0,
        newsScore = 0,
        newsNote = "",
        conviction = conviction,
        convictionMax = convictionMax,
        riskScore = riskScore,
        riskScoreMax = riskScoreMax,
        stage = stage,
        keepSharePct = keepSharePct
    )

    private fun candidate(symbol: String, sector: String = "Healthcare") = LiquidityCandidate(
        symbol = symbol,
        name = "$symbol Inc",
        sector = sector,
        price = 50.0,
        entryScore = 85.0,
        volumeRatio = 1.6,
        dayChangePct = 1.0,
        rsi = 58.0,
        techDirection = TechniqueVerdict.BULLISH,
        techConfidence = 75,
        newsScore = 1,
        newsHeadline = "",
        analystRating = 1.8,
        fiftyDayAvg = 47.0,
        twoHundredDayAvg = 42.0,
        marketCap = 5_000_000_000.0,
        historyNote = "",
        reason = "test fixture"
    )

    private fun equity(holdings: Double, cash: Double?) =
        EquityContext(invested = holdings * 0.8, holdingsValue = holdings, liquidity = cash)

    // ------------------------------------------------------------- the verdict

    @Test
    fun `an exit verdict zeroes the target and the freed cash is reported`() {
        val plan = AllocationPlanEngine.build(
            verdicts = listOf(
                verdict("BAD", 2_000.0, action = HoldingAction.SELL, keepSharePct = 0.0),
                verdict("GOOD", 3_000.0)
            ),
            equity = equity(5_000.0, 1_000.0),
            moneyFlow = null, sectorTrends = emptyList(), candidates = emptyList(),
            profile = profile
        )
        val bad = plan.targets.single { it.symbol == "BAD" }
        assertEquals(AllocationMove.EXIT, bad.move)
        assertEquals(0.0, bad.targetValue, 1e-9)
        assertTrue("the exit's dollars must show up as freed", plan.freedCash >= 2_000.0 - 1e-9)
    }

    @Test
    fun `a bank-half verdict and the plan agree on the same number`() {
        // A base large enough that neither the position cap (22% = $6,600) nor
        // the risk budget (2% of $30,000 over a $10 stop = $6,000) binds first,
        // so the number on the line can only have come from the verdict itself.
        val plan = AllocationPlanEngine.build(
            verdicts = listOf(
                verdict("WIN", 4_000.0, action = HoldingAction.TAKE_PROFIT, keepSharePct = 50.0)
            ),
            equity = equity(4_000.0, 26_000.0),
            moneyFlow = null, sectorTrends = emptyList(), candidates = emptyList(),
            profile = profile
        )
        val t = plan.targets.single()
        assertEquals(AllocationMove.REDUCE, t.move)
        assertEquals(2_000.0, t.targetValue, 0.01)
        assertEquals(-2_000.0, t.deltaValue, 0.01)
        assertTrue("the reduction must be attributed to the verdict", t.note.contains("own verdict"))
    }

    @Test
    fun `the risk budget can bind before the position cap and the line says which`() {
        // $4,000 in one name against a $4,000 base: 2% of equity over a $10
        // stop allows only $800 — tighter than the 22% cap's $880.
        val plan = AllocationPlanEngine.build(
            verdicts = listOf(verdict("HOT", 4_000.0, stop = 90.0)),
            equity = equity(4_000.0, 0.0),
            moneyFlow = null, sectorTrends = emptyList(), candidates = emptyList(),
            profile = profile
        )
        val t = plan.targets.single()
        assertEquals(AllocationMove.REDUCE, t.move)
        assertEquals(800.0, t.targetValue, 0.01)
        assertTrue("the binding rule must be named", t.cappedBy.contains("risk budget"))
        assertTrue("and it must not be blamed on the verdict", t.note.contains("risk control"))
    }

    // ----------------------------------------------------------------- the caps

    @Test
    fun `no target is ever above the profile's position cap`() {
        val base = 10_000.0
        val plan = AllocationPlanEngine.build(
            // One enormous position, cheap risk, top conviction — everything
            // that would tempt an engine to over-allocate.
            verdicts = listOf(verdict("BIG", 9_000.0, stop = 99.0, conviction = 100)),
            equity = equity(9_000.0, 1_000.0),
            moneyFlow = null, sectorTrends = emptyList(), candidates = emptyList(),
            profile = profile
        )
        val capValue = base * PortfolioAdvisor.positionCapPct(profile) / 100.0
        plan.targets.forEach {
            assertTrue(
                "${it.symbol} target ${it.targetValue} exceeds the ${capValue} cap",
                it.targetValue <= capValue + 0.01
            )
        }
    }

    @Test
    fun `a wide stop earns a smaller slot than a tight one at equal conviction`() {
        val plan = AllocationPlanEngine.build(
            verdicts = listOf(
                verdict("TIGHT", 200.0, stop = 98.0),   // 2% risk per share
                verdict("WIDE", 200.0, stop = 70.0)     // 30% risk per share
            ),
            equity = equity(400.0, 9_600.0),
            moneyFlow = null, sectorTrends = emptyList(), candidates = emptyList(),
            profile = profile
        )
        val tight = plan.targets.single { it.symbol == "TIGHT" }
        val wide = plan.targets.single { it.symbol == "WIDE" }
        assertTrue(
            "the tight-stopped name must earn the larger slot (${tight.targetValue} vs ${wide.targetValue})",
            tight.targetValue > wide.targetValue
        )
    }

    // ---------------------------------------------------------------- the money

    @Test
    fun `never deploys more than the freed capital and tracked cash allow`() {
        val cash = 1_000.0
        val plan = AllocationPlanEngine.build(
            verdicts = listOf(
                verdict("OUT", 2_000.0, action = HoldingAction.SELL, keepSharePct = 0.0),
                verdict("KEEP", 3_000.0)
            ),
            equity = equity(5_000.0, cash),
            moneyFlow = null, sectorTrends = emptyList(),
            candidates = listOf(candidate("AAA"), candidate("BBB"), candidate("CCC")),
            profile = profile
        )
        val spentOnAdds = plan.adds.sumOf { it.amount }
        val spentOnHeld = plan.targets.filter { it.deltaValue > 0.0 }.sumOf { it.deltaValue }
        val available = cash + plan.freedCash - plan.cashFloorValue
        assertTrue(
            "deployed ${spentOnAdds + spentOnHeld} exceeds the $available available",
            spentOnAdds + spentOnHeld <= available + 0.01
        )
    }

    @Test
    fun `the cash floor survives the plan`() {
        val plan = AllocationPlanEngine.build(
            verdicts = listOf(verdict("KEEP", 5_000.0)),
            equity = equity(5_000.0, 5_000.0),
            moneyFlow = null, sectorTrends = emptyList(),
            candidates = List(8) { candidate("C$it") },
            profile = profile
        )
        assertTrue(plan.cashFloorPct > 0.0)
        assertTrue(
            "target cash ${plan.targetCashPct}% fell under the ${plan.cashFloorPct}% floor",
            plan.targetCashPct >= plan.cashFloorPct - 0.05
        )
        assertTrue(plan.cashNote.contains("tolerance"))
    }

    @Test
    fun `with no candidates the freed money stays cash and the plan says so`() {
        val plan = AllocationPlanEngine.build(
            verdicts = listOf(
                verdict("OUT", 4_000.0, action = HoldingAction.CUT_LOSS, keepSharePct = 0.0),
                verdict("KEEP", 1_000.0)
            ),
            equity = equity(5_000.0, 0.0),
            moneyFlow = null, sectorTrends = emptyList(), candidates = emptyList(),
            profile = profile
        )
        assertTrue(plan.adds.isEmpty())
        assertTrue(plan.notes.any { it.contains("No market candidate") })
    }

    @Test
    fun `an untracked wallet names the book as its base and claims no cash`() {
        val plan = AllocationPlanEngine.build(
            verdicts = listOf(verdict("KEEP", 5_000.0)),
            equity = equity(5_000.0, null),
            moneyFlow = null, sectorTrends = emptyList(), candidates = listOf(candidate("AAA")),
            profile = profile
        )
        assertTrue(!plan.baseIsEquity)
        assertEquals(5_000.0, plan.base, 0.01)
        assertTrue(plan.cashNote.contains("wallet total is not tracked"))
    }

    // ------------------------------------------------------------------ adding

    @Test
    fun `an under-sized advancing winner earns an add, a low-conviction name does not`() {
        val plan = AllocationPlanEngine.build(
            verdicts = listOf(
                verdict("STRONG", 300.0, conviction = 90),
                verdict("WEAK", 300.0, conviction = 45)
            ),
            equity = equity(600.0, 9_400.0),
            moneyFlow = null, sectorTrends = emptyList(), candidates = emptyList(),
            profile = profile
        )
        assertEquals(AllocationMove.ADD, plan.targets.single { it.symbol == "STRONG" }.move)
        val weak = plan.targets.single { it.symbol == "WEAK" }
        assertEquals(AllocationMove.HOLD, weak.move)
        assertTrue(weak.note.contains("below the"))
    }

    @Test
    fun `no add is proposed without a measured conviction`() {
        val plan = AllocationPlanEngine.build(
            verdicts = listOf(verdict("BLIND", 300.0, conviction = 0, convictionMax = 0)),
            equity = equity(300.0, 9_700.0),
            moneyFlow = null, sectorTrends = emptyList(), candidates = emptyList(),
            profile = profile
        )
        val t = plan.targets.single()
        assertEquals(AllocationMove.HOLD, t.move)
        assertTrue(t.note.contains("could not be measured"))
    }

    @Test
    fun `an empty book produces an honest empty plan rather than a throw`() {
        val plan = AllocationPlanEngine.build(
            verdicts = emptyList(),
            equity = EquityContext.UNKNOWN,
            moneyFlow = null, sectorTrends = emptyList(), candidates = emptyList(),
            profile = profile
        )
        assertTrue(plan.targets.isEmpty())
        assertTrue(plan.adds.isEmpty())
        assertTrue(plan.headline.isNotBlank())
    }
}
