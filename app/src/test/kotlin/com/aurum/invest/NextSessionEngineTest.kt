package com.aurum.invest

import com.aurum.invest.analytics.DayScanContext
import com.aurum.invest.analytics.NextSessionEngine
import com.aurum.invest.analytics.NextSessionPick
import com.aurum.invest.analytics.NextSessionReport
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.EntryPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of the next-session engine: VWAP, alert gates, scan context, JSON. */
class NextSessionEngineTest {

    private fun bar(ts: Long, price: Double, volume: Long) =
        Candle(ts = ts, open = price, high = price, low = price, close = price, volume = volume)

    @Test
    fun `session vwap weights by volume`() {
        val bars = listOf(
            bar(1L, 100.0, 100L),
            bar(2L, 110.0, 300L)
        )
        // (100*100 + 110*300) / 400 = 107.5
        assertEquals(107.5, NextSessionEngine.sessionVwap(bars)!!, 1e-9)
    }

    @Test
    fun `session vwap is null when no bar carries volume`() {
        val bars = listOf(bar(1L, 100.0, 0L), bar(2L, 110.0, 0L))
        assertNull(NextSessionEngine.sessionVwap(bars))
    }

    @Test
    fun `alert needs every gate at once`() {
        // All gates green.
        assertTrue(
            NextSessionEngine.qualifiesForAlert(
                score = 80, analogDays = 9, probUpPct = 70,
                direction = TechniqueVerdict.BULLISH, confidence = 65
            )
        )
        // One gate short each time.
        assertFalse(
            NextSessionEngine.qualifiesForAlert(77, 9, 70, TechniqueVerdict.BULLISH, 65)
        )
        assertFalse(
            NextSessionEngine.qualifiesForAlert(80, 7, 70, TechniqueVerdict.BULLISH, 65)
        )
        assertFalse(
            NextSessionEngine.qualifiesForAlert(80, 9, 64, TechniqueVerdict.BULLISH, 65)
        )
        assertFalse(
            NextSessionEngine.qualifiesForAlert(80, 9, 70, TechniqueVerdict.NEUTRAL, 65)
        )
        assertFalse(
            NextSessionEngine.qualifiesForAlert(80, 9, 70, TechniqueVerdict.BULLISH, 59)
        )
    }

    @Test
    fun `day scan context distinguishes empty from unavailable`() {
        assertFalse(DayScanContext.EMPTY.available)
        val withEntries = DayScanContext.of(
            entries = listOf(entryPick("AAPL")),
            power = emptyList()
        )
        assertTrue(withEntries.available)
        assertTrue("AAPL" in withEntries.entryBySymbol)
    }

    @Test
    fun `report json round-trips the v2 measured context`() {
        val pick = NextSessionPick(
            symbol = "AAPL", name = "Apple", price = 150.0, dayChangePct = 2.1,
            score = 81, probUpPct = 68, analogDays = 9, avgNextDayPct = 1.2,
            entry = 150.0, target = 153.0, stop = 146.5,
            expectedLowPct = -1.5, expectedHighPct = 2.8, rsi = 61.0,
            techBullish = 22, techTotal = 35, techConfidence = 66,
            volumeRatio = 1.8, closePosPct = 88.0, extNote = "", heldNote = "",
            alert = true, reason = "test",
            sector = "Technology", flowVerdict = "INFLOW", flowNote = "Money flow: Tech inflow 80/100",
            newsScore = 2, newsMeasured = true, newsNote = "Beat earnings — Reuters",
            rel20Pct = 3.4, aboveVwap = true, vwapDistPct = 0.8,
            breakout20 = true, riskReward = 1.4, scanNote = "Surfaced by today's entry scan (score 78)."
        )
        val unmeasured = pick.copy(
            symbol = "XYZ", flowVerdict = "", newsMeasured = false, newsScore = 0,
            rel20Pct = null, aboveVwap = null, vwapDistPct = null, riskReward = null
        )
        val report = NextSessionReport(
            computedAt = 42L, sessionNote = "note", picks = listOf(pick, unmeasured),
            headline = "h", notes = listOf("n1", "n2")
        )
        val back = NextSessionReport.fromJson(NextSessionReport.toJson(report))!!
        assertEquals(2, back.picks.size)

        val p = back.picks[0]
        assertEquals("INFLOW", p.flowVerdict)
        assertTrue(p.newsMeasured)
        assertEquals(2, p.newsScore)
        assertEquals(3.4, p.rel20Pct!!, 1e-9)
        assertEquals(true, p.aboveVwap)
        assertTrue(p.breakout20)
        assertEquals(1.4, p.riskReward!!, 1e-9)
        assertEquals("Surfaced by today's entry scan (score 78).", p.scanNote)

        // Unmeasured stays unmeasured — never resurrected as a number.
        val u = back.picks[1]
        assertEquals("", u.flowVerdict)
        assertFalse(u.newsMeasured)
        assertNull(u.rel20Pct)
        assertNull(u.aboveVwap)
        assertNull(u.riskReward)
    }

    private fun entryPick(symbol: String) = EntryPick(
        date = "2026-08-18", rank = 1, symbol = symbol, name = symbol,
        score = 78.0, price = 100.0, dayChangePct = 1.0, entryLimit = 99.0,
        target = 105.0, stop = 96.0, upsidePct = 5.0, riskPct = 4.0,
        rewardRisk = 1.25, rsi = 55.0, dipPct = 2.0, vs50DayPct = 3.0,
        techDirection = "BULLISH", techBullish = 20, techTotal = 35,
        techConfidence = 60, analystRating = 2.0, reason = ""
    )
}
