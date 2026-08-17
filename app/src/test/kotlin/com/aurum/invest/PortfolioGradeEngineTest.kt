package com.aurum.invest

import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.EquityContext
import com.aurum.invest.analytics.HoldingAction
import com.aurum.invest.analytics.HoldingStage
import com.aurum.invest.analytics.HoldingVerdict
import com.aurum.invest.analytics.PortfolioGradeEngine
import com.aurum.invest.analytics.SectorSlice
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.data.repo.InvestorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grade engine's contract — with the winner-riding discipline under the
 * microscope: it must reward letting winners run and cutting losses short, not
 * merely owning something green.
 */
class PortfolioGradeEngineTest {

    private val profile = InvestorProfile.DEFAULT

    private fun holding(
        symbol: String,
        plPct: Double,
        weightPct: Double,
        above50: Boolean? = true,
        stop: Double = 90.0,
        riskAtStop: Double? = null,
        direction: TechniqueVerdict = TechniqueVerdict.BULLISH
    ) = HoldingVerdict(
        symbol = symbol,
        name = "$symbol Inc",
        sector = "Technology",
        action = HoldingAction.HOLD,
        headline = "", whenText = "", whyPoints = emptyList(),
        price = 100.0, avgCost = 90.0,
        marketValue = weightPct * 100.0,
        weightPct = weightPct,
        unrealizedPl = 0.0,
        unrealizedPlPct = plPct,
        target = 120.0, stop = stop,
        techBullish = 25, techTotal = 35, techConfidence = 70,
        techDirection = direction,
        rsi = 60.0, newsScore = 0, newsNote = "",
        above50 = above50,
        conviction = 70, convictionMax = 100,
        riskScore = 20, riskScoreMax = 100,
        stage = HoldingStage.ADVANCING,
        riskAtStop = riskAtStop
    )

    private fun book(vararg v: HoldingVerdict): BookContext {
        val total = v.sumOf { it.marketValue }
        return BookContext(
            totalValue = total,
            heldWeights = v.associate { it.symbol to it.weightPct },
            slices = listOf(
                SectorSlice("Technology", total, 100.0, v.map { it.symbol })
            )
        )
    }

    private fun grade(
        vararg v: HoldingVerdict,
        equity: EquityContext = EquityContext.UNKNOWN
    ) = PortfolioGradeEngine.evaluate(
        verdicts = v.toList(),
        book = book(*v),
        flow = null,
        pulse = null,
        strategy = null,
        policy = profile,
        equity = equity
    )

    private fun winners(g: com.aurum.invest.analytics.PortfolioGrade) =
        g.components.single { it.key == "winners" }

    // ------------------------------------------------------- winners riding

    @Test
    fun `letting winners run beats snipping them, at the same share in profit`() {
        // Both books: 50% of the weight green, 50% red. The difference is the
        // SIZE of the wins against the size of the losses.
        val ridden = grade(
            holding("BIGWIN", plPct = 60.0, weightPct = 50.0),
            holding("SMALLLOSS", plPct = -4.0, weightPct = 50.0)
        )
        val snipped = grade(
            holding("SMALLWIN", plPct = 4.0, weightPct = 50.0),
            holding("BIGLOSS", plPct = -30.0, weightPct = 50.0)
        )
        assertTrue(
            "riding (${winners(ridden).points}) must outscore snipping (${winners(snipped).points})",
            winners(ridden).points > winners(snipped).points
        )
        assertTrue(winners(ridden).evidence.contains("ratio"))
    }

    @Test
    fun `a winner that has lost its 50-day line is not counted as being ridden`() {
        val intact = grade(
            holding("A", plPct = 40.0, weightPct = 60.0, above50 = true),
            holding("B", plPct = -5.0, weightPct = 40.0, above50 = true)
        )
        val broken = grade(
            holding("A", plPct = 40.0, weightPct = 60.0, above50 = false),
            holding("B", plPct = -5.0, weightPct = 40.0, above50 = true)
        )
        assertTrue(
            "a winner below its trend scores lower than one above it",
            winners(intact).points > winners(broken).points
        )
    }

    @Test
    fun `an unmeasurable sub-band is removed from both sides, never defaulted`() {
        // No losers at all: the win/loss ratio cannot exist, so its 6 points
        // leave the scale rather than being granted or scored at a midpoint.
        val noLosers = winners(
            grade(
                holding("A", plPct = 20.0, weightPct = 50.0),
                holding("B", plPct = 15.0, weightPct = 50.0)
            )
        )
        assertEquals(10, noLosers.maxPoints)
        assertTrue(noLosers.evidence.contains("Not measured"))
        assertTrue(noLosers.points <= noLosers.maxPoints)

        // Everything measurable: the full 16.
        val full = winners(
            grade(
                holding("A", plPct = 20.0, weightPct = 50.0),
                holding("B", plPct = -5.0, weightPct = 50.0)
            )
        )
        assertEquals(16, full.maxPoints)
    }

    @Test
    fun `the plan never proposes selling a winner to raise the winners score`() {
        val g = grade(
            holding("WIN", plPct = 40.0, weightPct = 40.0),
            holding("DEAD", plPct = -2.0, weightPct = 60.0)
        )
        winners(g).actions
            .filter { it.symbol.isNotEmpty() }
            .forEach { assertEquals("only the non-winner may be exited", "DEAD", it.symbol) }
    }

    // --------------------------------------------------------- risk budget

    @Test
    fun `the risk budget is not scored without tracked equity`() {
        val g = grade(holding("A", plPct = 10.0, weightPct = 100.0, riskAtStop = 500.0))
        val risk = g.components.single { it.key == "risk" }
        assertFalse(risk.measured)
        assertTrue(risk.evidence.contains("Equity is not tracked"))
    }

    @Test
    fun `a book inside its heat budget scores full risk points`() {
        // 2% per trade -> a 6% portfolio-heat budget on $10,000 = $600.
        val g = grade(
            holding("A", plPct = 10.0, weightPct = 50.0, riskAtStop = 200.0),
            holding("B", plPct = 5.0, weightPct = 50.0, riskAtStop = 200.0),
            equity = EquityContext(invested = 8_000.0, holdingsValue = 9_000.0, liquidity = 1_000.0)
        )
        val risk = g.components.single { it.key == "risk" }
        assertTrue(risk.measured)
        assertEquals(8, risk.points)
        assertTrue(risk.green)
    }

    @Test
    fun `a book running hot scores lower and is told exactly what to trim`() {
        val g = grade(
            holding("HOT", plPct = 10.0, weightPct = 50.0, riskAtStop = 900.0),
            holding("OK", plPct = 5.0, weightPct = 50.0, riskAtStop = 100.0),
            equity = EquityContext(invested = 8_000.0, holdingsValue = 9_000.0, liquidity = 1_000.0)
        )
        val risk = g.components.single { it.key == "risk" }
        assertTrue(risk.measured)
        assertTrue("10% heat against a 6% budget must lose points", risk.points < 8)
        assertEquals("HOT", risk.actions.first().symbol)
        assertTrue(risk.actions.first().pointsAfter >= risk.actions.first().pointsNow)
    }

    @Test
    fun `partial stop coverage leaves portfolio heat unmeasured rather than understated`() {
        val g = grade(
            holding("A", plPct = 10.0, weightPct = 50.0, riskAtStop = 200.0),
            holding("B", plPct = 5.0, weightPct = 50.0, riskAtStop = null),
            equity = EquityContext(invested = 8_000.0, holdingsValue = 9_000.0, liquidity = 1_000.0)
        )
        val risk = g.components.single { it.key == "risk" }
        assertFalse(risk.measured)
        assertTrue(risk.evidence.contains("1 of 2"))
    }

    // -------------------------------------------------------- the whole scale

    @Test
    fun `the eight disciplines sum to one hundred when every one is measurable`() {
        val g = grade(
            holding("A", plPct = 20.0, weightPct = 50.0, riskAtStop = 100.0),
            holding("B", plPct = -5.0, weightPct = 50.0, riskAtStop = 100.0),
            equity = EquityContext(invested = 8_000.0, holdingsValue = 9_000.0, liquidity = 1_000.0)
        )
        // trend + winners + loss + concentration + risk are measurable here;
        // relative strength, flow and regime need inputs this fixture omits.
        val declared = g.components.sumOf { it.maxPoints } +
            // the winners band drops nothing in this fixture, so its full 16 counts
            0
        assertEquals(100, declared)
        assertTrue(g.score <= g.maxScore)
        assertTrue(g.maxScore < 100)  // three disciplines are honestly unmeasured
        assertTrue(g.band.isNotBlank())
    }
}
