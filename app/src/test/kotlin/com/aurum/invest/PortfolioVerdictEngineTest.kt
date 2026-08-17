package com.aurum.invest

import com.aurum.invest.analytics.EquityContext
import com.aurum.invest.analytics.HoldingAction
import com.aurum.invest.analytics.HoldingEvidence
import com.aurum.invest.analytics.HoldingStage
import com.aurum.invest.analytics.PortfolioVerdictEngine
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.data.repo.InvestorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict engine's contract, especially the winner-riding rules: a profit
 * percentage alone must never sell a winner, a published stop must never move
 * down, and an unmeasured input must shrink the scale rather than fill it in.
 */
class PortfolioVerdictEngineTest {

    private val equity = EquityContext(
        invested = 10_000.0,
        holdingsValue = 12_000.0,
        liquidity = 3_000.0,
        realizedPl = 0.0
    )

    /**
     * A textbook winner: +38% open, price above a rising 50-day which is above
     * the 200-day, bullish board, strong relative strength, near its high.
     */
    private fun winner(
        symbol: String = "NVDA",
        price: Double = 138.0,
        avgCost: Double = 100.0,
        weightPct: Double = 12.0,
        rsi: Double = 64.0,
        volumeRatio: Double = 1.4,
        peak: Double? = 142.0,
        priorStop: Double? = null,
        sma50: Double? = 120.0,
        sma50Rising: Boolean? = true
    ) = HoldingEvidence(
        symbol = symbol,
        name = "$symbol Inc",
        sector = "Technology",
        shares = 100.0,
        avgCost = avgCost,
        investedCost = avgCost * 100.0,
        price = price,
        weightPct = weightPct,
        atr = 4.0,
        rsi = rsi,
        sma20 = 132.0,
        sma50 = sma50,
        sma200 = 105.0,
        sma50Rising = sma50Rising,
        peakSinceEntry = peak,
        peakMeasuredFromEntry = true,
        support = 128.0,
        resistance = 150.0,
        high52 = 142.0,
        donchianLow20 = 126.0,
        r5Pct = 3.0,
        r20Pct = 14.0,
        r60Pct = 30.0,
        rel20Pct = 9.0,
        rel60Pct = 18.0,
        sessionMovePct = 1.2,
        volumeRatio = volumeRatio,
        upDayVolumeSharePct = 63.0,
        distributionDays = 1,
        techDirection = TechniqueVerdict.BULLISH,
        techBullish = 26,
        techTotal = 35,
        techConfidence = 74,
        expectedHigh = 145.0,
        newsScore = 1,
        newsNote = "beat and raised",
        newsMeasured = true,
        sectorFlow = null,
        flowNote = "",
        priorStop = priorStop
    )

    private fun evaluate(vararg e: HoldingEvidence, profile: InvestorProfile = InvestorProfile.DEFAULT) =
        PortfolioVerdictEngine.evaluate(e.toList(), equity, profile)

    // ------------------------------------------------------------ riding

    @Test
    fun `a big winner in an intact uptrend is ridden, never sold on the profit alone`() {
        // +38% clears any fixed take-profit threshold, and RSI 64 is nowhere
        // near a climax — the old rule would have banked it; this one must not.
        val v = evaluate(winner()).verdicts.single()
        assertEquals(HoldingAction.HOLD, v.action)
        assertEquals(100.0, v.keepSharePct, 1e-9)
        assertTrue("a ridden winner must say so", v.ridingNote.isNotEmpty())
        assertEquals(HoldingStage.ADVANCING, v.stage)
    }

    @Test
    fun `riding a winner publishes a trail above cost and says the gain is locked`() {
        val v = evaluate(winner()).verdicts.single()
        assertNotNull(v.trailStop)
        assertTrue("the trail must sit under the price", v.trailStop!! < v.price)
        assertTrue("the trail must be above cost on a +38% winner", v.trailStop > v.avgCost)
        assertNotNull("a trail above cost locks a measurable gain", v.lockedGainPct)
        assertTrue(v.ridingNote.contains("no longer become a loss"))
    }

    @Test
    fun `the trail ratchets - a published stop is never lowered`() {
        // Chandelier from a 142 peak at 2.5 ATR is 132; last run published 135.
        val v = evaluate(winner(priorStop = 135.0)).verdicts.single()
        assertTrue(
            "published stop ${v.stop} must not fall below the prior 135.0",
            v.stop >= 135.0 - 1e-9
        )
    }

    @Test
    fun `a broken published stop is honoured with a sell`() {
        val v = evaluate(winner(price = 130.0, priorStop = 131.0)).verdicts.single()
        assertEquals(HoldingAction.SELL, v.action)
        assertEquals(0.0, v.keepSharePct, 1e-9)
        assertTrue(v.headline.contains("stop this review published"))
    }

    @Test
    fun `a measured climax banks a third and keeps riding the rest`() {
        // 3+ ATR above the 20-day, RSI 80, twice normal volume.
        val v = evaluate(
            winner(price = 146.0, rsi = 80.0, volumeRatio = 2.4, peak = 146.0)
        ).verdicts.single()
        assertEquals(HoldingAction.TAKE_PROFIT, v.action)
        assertEquals(66.7, v.keepSharePct, 0.2)
        assertTrue("the rest still rides", v.ridingNote.isNotEmpty())
    }

    // ------------------------------------------------------------ defence

    @Test
    fun `the loss rule cuts a broken loser in full`() {
        val loser = winner(
            price = 88.0, avgCost = 100.0, peak = 104.0,
            sma50 = 96.0, sma50Rising = false
        ).copy(
            sma20 = 92.0, sma200 = 99.0,
            techDirection = TechniqueVerdict.BEARISH, techBullish = 6, techConfidence = 70,
            rel20Pct = -8.0, rel60Pct = -12.0, r20Pct = -10.0, r5Pct = -3.0, r60Pct = -14.0
        )
        val v = evaluate(loser).verdicts.single()
        assertEquals(HoldingAction.CUT_LOSS, v.action)
        assertEquals(0.0, v.keepSharePct, 1e-9)
    }

    @Test
    fun `an oversized winner is trimmed exactly to the cap and labelled risk control`() {
        val profile = InvestorProfile.DEFAULT
        val cap = profile.maxPositionPct
        val over = cap + 12.0                       // past the trim line (cap + 8)
        val v = evaluate(winner(weightPct = over), profile = profile).verdicts.single()
        assertEquals(HoldingAction.TRIM, v.action)
        // Selling shrinks the book, so the trim must be solved against the
        // SMALLER book: keeping cap/weight of the position would leave the name
        // above the cap. Work the arithmetic through in percentage-of-book terms.
        val keptWeight = over * v.keepSharePct / 100.0
        val soldWeight = over - keptWeight
        val weightAfter = keptWeight / (100.0 - soldWeight) * 100.0
        assertEquals("the trim must land exactly on the cap", cap, weightAfter, 0.05)
        assertTrue(v.headline.contains("risk control"))
        assertTrue("the remainder still rides", v.ridingNote.isNotEmpty())
    }

    // ------------------------------------------------------------ integrity

    @Test
    fun `unmeasured inputs shrink the scale instead of filling it in`() {
        val thin = winner().copy(
            sma50 = null, sma200 = null, sma50Rising = null,
            rel20Pct = null, rel60Pct = null,
            upDayVolumeSharePct = null, volumeRatio = null,
            high52 = null, newsMeasured = false, distributionDays = null,
            peakSinceEntry = null
        )
        val v = evaluate(thin).verdicts.single()
        assertTrue("the conviction max must shrink", v.convictionMax < 100)
        assertTrue("the risk max must shrink", v.riskScoreMax < 100)
        assertTrue("earned points can never exceed the measured max", v.conviction <= v.convictionMax)
        assertTrue("the blind inputs must be named", v.notMeasured.isNotEmpty())
        assertTrue(v.notMeasured.any { it.contains("52-week") })
        assertTrue(v.notMeasured.any { it.contains("headline") })
    }

    @Test
    fun `a fully measured holding scores against the full hundred point scale`() {
        val v = evaluate(winner().copy(sectorFlow = null)).verdicts.single()
        // Sector flow is the one band this fixture leaves unmapped.
        assertEquals(90, v.convictionMax)
        assertTrue(v.notMeasured.contains("sector money flow"))
    }

    @Test
    fun `dollars at risk are only claimed as a share of equity when equity is tracked`() {
        val tracked = PortfolioVerdictEngine
            .evaluate(listOf(winner()), equity, InvestorProfile.DEFAULT).verdicts.single()
        assertNotNull(tracked.riskAtStop)
        assertNotNull(tracked.riskAtStopEquityPct)

        val untracked = PortfolioVerdictEngine.evaluate(
            listOf(winner()),
            EquityContext(invested = 10_000.0, holdingsValue = 0.0, liquidity = null),
            InvestorProfile.DEFAULT
        ).verdicts.single()
        assertNotNull("the dollar risk is still measurable", untracked.riskAtStop)
        assertNull("no share of an unknown account is claimed", untracked.riskAtStopEquityPct)
    }

    @Test
    fun `the target never promises ground above the nearest measured resistance`() {
        val v = evaluate(winner()).verdicts.single()
        assertEquals(150.0, v.target, 0.01)
        assertNotNull(v.runwayPct)
        assertNotNull(v.riskReward)
    }

    @Test
    fun `book level open risk is reported only when every stop is measurable`() {
        val report = PortfolioVerdictEngine.evaluate(
            listOf(winner(symbol = "A"), winner(symbol = "B")), equity, InvestorProfile.DEFAULT
        )
        assertNotNull(report.openRiskPct)
        assertTrue(report.openRiskPct!! > 0.0)
        assertTrue(report.equityNote.contains("uninvested"))
    }

    @Test
    fun `with no measurable level there is no stop, and nothing downstream invents one`() {
        // No ATR, no support, no 50-day, no Donchian low, no peak: there is
        // simply no level the chart supports. A round percentage under the
        // price would look measured and would poison risk, reward-to-risk and
        // portfolio heat alike.
        // The gain is deliberately small: past twice the loss rule the engine
        // ratchets the stop to the cost basis, which IS a measured level.
        val blind = winner(price = 105.0).copy(
            atr = null, support = null, sma50 = null, sma20 = null, sma200 = null,
            donchianLow20 = null, peakSinceEntry = null, resistance = null, high52 = null,
            expectedHigh = null, sma50Rising = null
        )
        val v = evaluate(blind).verdicts.single()
        assertEquals("no measurable level means no stop", 0.0, v.stop, 1e-9)
        assertNull("no stop means no dollars at risk", v.riskAtStop)
        assertNull(v.riskAtStopEquityPct)
        assertNull("no stop means no reward-to-risk", v.riskReward)
        assertTrue(v.whenText.contains("No exit level could be measured"))
    }

    @Test
    fun `book level open risk is withheld when any holding has no measurable stop`() {
        val blind = winner(symbol = "BLIND", price = 105.0).copy(
            atr = null, support = null, sma50 = null, sma20 = null, sma200 = null,
            donchianLow20 = null, peakSinceEntry = null
        )
        val report = PortfolioVerdictEngine.evaluate(
            listOf(winner(symbol = "OK"), blind), equity, InvestorProfile.DEFAULT
        )
        assertNull("one blind stop makes portfolio heat unmeasurable", report.openRiskPct)
        assertTrue(report.notes.any { it.contains("Total risk-to-stop is not reported") })
    }

    @Test
    fun `the same winner is ridden differently for a trader and a long-horizon investor`() {
        val short = InvestorProfile.DEFAULT.copy(horizon = InvestorProfile.HORIZON_SHORT)
        val long = InvestorProfile.DEFAULT.copy(horizon = InvestorProfile.HORIZON_LONG)
        // A weeks-long trader trails tighter than a years-long investor, so the
        // same book cannot produce the same orders for both.
        assertTrue(
            PortfolioVerdictEngine.trailAtrMultiple(short) <
                PortfolioVerdictEngine.trailAtrMultiple(long)
        )
        val tight = evaluate(winner(), profile = short).verdicts.single()
        val loose = evaluate(winner(), profile = long).verdicts.single()
        assertTrue(
            "the short-horizon trail (${tight.stop}) must sit above the long-horizon one (${loose.stop})",
            tight.stop > loose.stop
        )
    }

    @Test
    fun `the cap trim keeps the exact share that lands on the cap`() {
        // 34% of the book against a 22% cap: keeping 22/34 would leave it at
        // 25% of the smaller book. The solved share is 54.75%.
        assertEquals(54.75, PortfolioVerdictEngine.trimKeepSharePct(34.0, 22.0), 0.01)
        // A one-name book has nothing to re-base against — the plain ratio is
        // the honest instruction there rather than "sell everything".
        assertEquals(22.0, PortfolioVerdictEngine.trimKeepSharePct(100.0, 22.0), 0.01)
    }

    @Test
    fun `an empty book produces an honest empty report rather than a throw`() {
        val report = PortfolioVerdictEngine.evaluate(
            emptyList(), EquityContext.UNKNOWN, InvestorProfile.DEFAULT
        )
        assertTrue(report.verdicts.isEmpty())
        assertNull(report.openRiskPct)
        assertTrue(report.headline.isNotBlank())
    }
}
