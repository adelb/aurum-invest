package com.aurum.invest

import com.aurum.invest.analytics.BeatSpyEngine
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.data.model.Candle
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Beat-the-SPY engine: date alignment, the paired-window race, the
 * entry-price levels, and the honesty gates for thin or unshared history.
 */
class BeatSpyEngineTest {

    private val dayMs = 86_400_000L
    private val base = 1_600_000_000_000L

    private fun candles(closes: List<Double>, startDay: Int = 0): List<Candle> =
        closes.mapIndexed { i, c ->
            Candle(
                ts = base + (startDay + i) * dayMs,
                open = c * 0.995, high = c * 1.01, low = c * 0.99,
                close = c, volume = 1_000_000L
            )
        }

    private fun compound(days: Int, dailyRate: Double): List<Double> =
        (0 until days).map { 100.0 * dailyRate.pow(it) }

    /** A drifting wobble — varying daily returns, identical for both sides. */
    private fun wobble(days: Int): List<Double> =
        (0 until days).map { 100.0 + 10.0 * sin(it / 5.0) + it * 0.05 }

    // ---- the race ----------------------------------------------------------

    @Test
    fun fasterCompounderWinsEveryWindowAndEveryHorizon() {
        val report = BeatSpyEngine.build(
            "TST", candles(compound(400, 1.003)), candles(compound(400, 1.0005))
        )
        assertNotNull(report)
        report!!
        assertEquals(400, report.alignedSessions)
        assertEquals(BeatSpyEngine.HORIZONS.size, report.horizons.size)
        for (h in report.horizons) {
            assertEquals(h.label, 100.0, h.beatSharePct, 1e-9)
            assertTrue(h.label, h.medianEdgePct > 0.0)
            assertEquals(h.label, TechniqueVerdict.BULLISH, h.verdict)
            // Every window compounds identically, so the stock's median beats
            // SPY's median from any entry up to the breakeven ABOVE the price.
            assertTrue(h.label, h.breakevenEntry > report.price)
        }
        assertEquals(TechniqueVerdict.BULLISH, report.verdict)
        assertTrue(report.verdictLine.contains("earns the ticket"))
    }

    @Test
    fun slowerCompounderHandsTheTicketToSpy() {
        val report = BeatSpyEngine.build(
            "TST", candles(compound(400, 1.0005)), candles(compound(400, 1.003))
        )!!
        val month = report.horizons.first { it.horizonSessions == 21 }
        assertEquals(0.0, month.beatSharePct, 1e-9)
        assertTrue(month.medianEdgePct < 0.0)
        assertEquals(TechniqueVerdict.BEARISH, report.verdict)
        assertTrue(report.verdictLine.contains("SPY keeps the ticket"))
        // Only an entry BELOW today's price would have matched SPY's median.
        assertTrue(month.breakevenEntry < report.price)
    }

    @Test
    fun identicalSeriesIsACoinFlipNotALoss() {
        val w = wobble(400)
        val report = BeatSpyEngine.build("TST", candles(w), candles(w))!!
        for (h in report.horizons) {
            // Never a strict beat, but never behind either — a tie is neutral.
            assertEquals(h.label, 0.0, h.beatSharePct, 1e-9)
            assertEquals(h.label, 0.0, h.medianEdgePct, 1e-9)
            assertEquals(h.label, TechniqueVerdict.NEUTRAL, h.verdict)
            // Equal medians: the breakeven entry IS today's price.
            assertEquals(h.label, report.price, h.breakevenEntry, 0.02)
        }
        assertEquals(TechniqueVerdict.NEUTRAL, report.verdict)
    }

    // ---- beta, correlation, capture ---------------------------------------

    @Test
    fun lockstepSeriesMeasuresBetaAndCaptureAtOneToOne() {
        val w = wobble(400)
        val report = BeatSpyEngine.build("TST", candles(w), candles(w))!!
        assertEquals(1.0, report.beta!!, 0.01)
        assertEquals(1.0, report.correlation!!, 0.01)
        assertEquals(100.0, report.upCapturePct!!, 0.5)
        assertEquals(100.0, report.downCapturePct!!, 0.5)
        // The realized head-to-head shares one clock and one answer: zero edge.
        for (span in report.spans) {
            if (span.stockPct != null && span.spyPct != null) {
                assertEquals(span.label, span.stockPct!!, span.spyPct!!, 1e-9)
            }
        }
    }

    // ---- honesty gates -----------------------------------------------------

    @Test
    fun thinSharedHistoryRacesNothingAndSaysSo() {
        val report = BeatSpyEngine.build(
            "TST", candles(compound(100, 1.002)), candles(compound(100, 1.001))
        )!!
        assertTrue(report.horizons.isEmpty())
        assertEquals(TechniqueVerdict.NEUTRAL, report.verdict)
        assertTrue(report.notes.any { it.contains("share", ignoreCase = true) })
        assertTrue(report.verdictLine.contains("don't share enough history"))
    }

    @Test
    fun alignmentKeepsOnlySharedSessions() {
        val stock = candles(compound(400, 1.002))
        // SPY prints only every other day — half the sessions are shared.
        val spy = candles(compound(400, 1.001)).filterIndexed { i, _ -> i % 2 == 0 }
        val report = BeatSpyEngine.build("TST", stock, spy)!!
        assertEquals(200, report.alignedSessions)
    }

    @Test
    fun disjointCalendarsProduceNoReport() {
        val stock = candles(compound(200, 1.002))
        val spy = candles(compound(200, 1.001), startDay = 100_000)
        assertNull(BeatSpyEngine.build("TST", stock, spy))
    }

    @Test
    fun emptyInputsProduceNoReport() {
        assertNull(BeatSpyEngine.build("TST", emptyList(), emptyList()))
        assertNull(BeatSpyEngine.build("TST", candles(compound(50, 1.001)), emptyList()))
        assertNull(BeatSpyEngine.build("TST", emptyList(), candles(compound(50, 1.001))))
    }

    @Test
    fun garbageClosesNeverThrow() {
        val dirty = candles(compound(300, 1.002)).mapIndexed { i, c ->
            when {
                i % 17 == 0 -> c.copy(close = Double.NaN)
                i % 23 == 0 -> c.copy(close = 0.0)
                else -> c
            }
        }
        val report = BeatSpyEngine.build("TST", dirty, candles(compound(300, 1.001)))
        // Whatever the verdict, the engine holds: no exception, sane fields.
        if (report != null) {
            assertTrue(report.price > 0.0)
            assertTrue(report.alignedSessions > 0)
        }
    }

    // ---- the green entry zone ---------------------------------------------

    @Test
    fun buyHorizonPrefersTheMonthOverTheNearestRaced() {
        val report = BeatSpyEngine.build(
            "TST", candles(compound(400, 1.003)), candles(compound(400, 1.0005))
        )!!
        assertEquals(
            BeatSpyEngine.BUY_HORIZON_SESSIONS,
            BeatSpyEngine.buyHorizon(report)!!.horizonSessions
        )
    }

    @Test
    fun fasterCompounderSitsInTheGreenZoneToday() {
        // Every window compounds identically, so Q1 == median and the
        // soft-quartile edge lands above today's price: the buy is green now.
        val report = BeatSpyEngine.build(
            "TST", candles(compound(400, 1.003)), candles(compound(400, 1.0005))
        )!!
        val month = BeatSpyEngine.buyHorizon(report)!!
        assertTrue(month.edgeEntry > report.price)
        assertTrue(BeatSpyEngine.inGreenZone(report.price, month))
    }

    @Test
    fun slowerCompounderIsNeverGreenAtTodaysPrice() {
        val report = BeatSpyEngine.build(
            "TST", candles(compound(400, 1.0005)), candles(compound(400, 1.003))
        )!!
        val month = BeatSpyEngine.buyHorizon(report)!!
        assertTrue(month.edgeEntry < report.price)
        assertTrue(!BeatSpyEngine.inGreenZone(report.price, month))
    }

    @Test
    fun greenZoneRejectsUnpricedInputs() {
        val report = BeatSpyEngine.build(
            "TST", candles(compound(400, 1.003)), candles(compound(400, 1.0005))
        )!!
        val month = BeatSpyEngine.buyHorizon(report)!!
        assertTrue(!BeatSpyEngine.inGreenZone(0.0, month))
        assertTrue(!BeatSpyEngine.inGreenZone(report.price, month.copy(edgeEntry = 0.0)))
    }

    @Test
    fun livePriceAnchorsTheEntryLevels() {
        val report = BeatSpyEngine.build(
            "TST", candles(compound(400, 1.003)), candles(compound(400, 1.0005)),
            livePrice = 500.0
        )!!
        assertEquals(500.0, report.price, 1e-9)
        val month = report.horizons.first { it.horizonSessions == 21 }
        val ms = 1.003.pow(21) - 1.0
        val mb = 1.0005.pow(21) - 1.0
        assertEquals(500.0 * (1.0 + ms) / (1.0 + mb), month.breakevenEntry, 0.05)
    }
}
