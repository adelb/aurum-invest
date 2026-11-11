package com.aurum.invest

import com.aurum.invest.analytics.Techniques
import com.aurum.invest.analytics.TurnEngine
import com.aurum.invest.analytics.TurnPick
import com.aurum.invest.analytics.TurnPicker
import com.aurum.invest.analytics.TurnScan
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn read: MACD deep under zero closing on its signal while the close
 * sits above a still-falling HMA(20) — the session before the confirmation.
 */
class TurnEngineTest {

    // ---- the rule, on the readings the idea came from -----------------------

    /** COHR, 9 Nov 2026: close 225.19, HMA 193.42 still falling, MACD -22.34 vs -22.12. */
    @Test
    fun theOriginalReadingIsATurn() {
        assertTrue(
            TurnEngine.isTurn(
                price = 225.19, macd = -22.34, signal = -22.12,
                prevHistogram = -1.10, hma = 193.42, prevHma = 194.80
            )
        )
    }

    /** The confirmed state — MACD already above its signal, HMA rising — is past the turn. */
    @Test
    fun aCrossThatAlreadyHappenedIsNotATurn() {
        assertFalse(
            TurnEngine.isTurn(
                price = 134.34, macd = -5.38, signal = -7.10,
                prevHistogram = 1.20, hma = 121.41, prevHma = 119.90
            )
        )
    }

    @Test
    fun aRisingHullMeansTheTurnIsAlreadyConfirmed() {
        assertFalse(
            TurnEngine.isTurn(
                price = 225.19, macd = -22.34, signal = -22.12,
                prevHistogram = -1.10, hma = 193.42, prevHma = 192.00
            )
        )
    }

    @Test
    fun aCloseUnderTheHullIsStillFalling() {
        assertFalse(
            TurnEngine.isTurn(
                price = 190.00, macd = -22.34, signal = -22.12,
                prevHistogram = -1.10, hma = 193.42, prevHma = 194.80
            )
        )
    }

    @Test
    fun aWideningGapIsNotClosing() {
        assertFalse(
            TurnEngine.isTurn(
                price = 225.19, macd = -22.34, signal = -22.12,
                prevHistogram = -0.05, hma = 193.42, prevHma = 194.80
            )
        )
    }

    @Test
    fun aGapWiderThanTheBarIsTooEarly() {
        // 0.30% of 225.19 is 0.676; a 1.00 gap is not "a hair below".
        assertFalse(
            TurnEngine.isTurn(
                price = 225.19, macd = -23.12, signal = -22.12,
                prevHistogram = -1.50, hma = 193.42, prevHma = 194.80
            )
        )
    }

    @Test
    fun aShallowMacdIsNoWashout() {
        // -1.00 on 225.19 is 0.44% of price — under the 1% depth bar.
        assertFalse(
            TurnEngine.isTurn(
                price = 225.19, macd = -1.00, signal = -0.90,
                prevHistogram = -0.30, hma = 193.42, prevHma = 194.80
            )
        )
    }

    // ---- the read, on whole series ------------------------------------------

    @Test
    fun aSteadyClimbIsNeverATurn() {
        assertNull(TurnEngine.read((0 until 200).map { 100.0 * 1.004.pow(it) }))
    }

    @Test
    fun aSteadyFallIsNeverATurn() {
        // Close under a falling HMA, gap not closing: the fall is still on.
        assertNull(TurnEngine.read((0 until 200).map { 300.0 * 0.994.pow(it) }))
    }

    @Test
    fun tooLittleHistoryIsNotRead() {
        assertNull(TurnEngine.read((0 until TurnEngine.MIN_BARS - 1).map { 100.0 - it * 0.5 }))
    }

    /**
     * The shape the idea came from: a long slide, a pause, a last sharp leg
     * down that keeps the HMA falling, then one strong day up. The day before
     * the pop the close is still under the HMA; on the pop the read fires, and
     * it quotes the board's own MACD and Hull lines.
     */
    @Test
    fun aPopOffAWashoutIsTheTurn() {
        val closes = ArrayList<Double>()
        var p = 300.0
        repeat(80) { p *= 0.990; closes.add(p) }
        repeat(10) { p *= 0.999; closes.add(p) }
        repeat(5) { p *= 0.970; closes.add(p) }
        assertNull(TurnEngine.read(closes))

        closes.add(p * 1.08)
        val read = TurnEngine.read(closes)
        assertNotNull(read)
        read!!
        val n = closes.size
        val (macd, signal, hist) = Techniques.macdLines(closes)
        val hull = Techniques.hullLine(closes)
        assertEquals(macd[n - 1]!!, read.macd, 1e-9)
        assertEquals(signal[n - 1]!!, read.signal, 1e-9)
        assertEquals(hull[n - 1]!!, read.hma, 1e-9)
        assertTrue(read.macd < read.signal)
        assertTrue(read.macdPct <= -TurnEngine.MIN_DEPTH_PCT)
        assertTrue(read.gapPct < 0.0 && read.gapPct >= -TurnEngine.MAX_GAP_PCT)
        assertTrue(hist[n - 1]!! > hist[n - 2]!!)
        assertTrue(read.price > read.hma)
        assertTrue(hull[n - 1]!! <= hull[n - 2]!!)
        assertEquals(1, read.histRisingDays)
        assertEquals(8.0, read.dayChangePct, 1e-6)
        assertTrue(read.offHighPct < 0.0 && read.offLowPct > 0.0)
    }

    // ---- storage ------------------------------------------------------------

    @Test
    fun aScanSurvivesTheCache() {
        val pick = TurnPick(
            date = "2026-11-11", rank = 1, symbol = "TST", name = "Test Corp",
            price = 225.19, dayChangePct = 4.9, macd = -22.34, signal = -22.12,
            histogram = -0.22, macdPct = -9.92, gapPct = -0.098, hma = 193.42,
            hmaDistPct = 16.4, histRisingDays = 3, offHighPct = -38.9, offLowPct = 26.4,
            reason = "why"
        )
        val back = TurnPicker.fromJson(TurnPicker.toJson(TurnScan(listOf(pick), 412)))
        assertEquals(412, back.scanned)
        assertEquals(listOf(pick), back.picks)
    }

    @Test
    fun anEmptyScanKeepsItsCountAndGarbageReadsAsNothing() {
        val back = TurnPicker.fromJson(TurnPicker.toJson(TurnScan(emptyList(), 300)))
        assertEquals(300, back.scanned)
        assertTrue(back.picks.isEmpty())
        assertEquals(TurnScan.EMPTY, TurnPicker.fromJson("not json"))
    }

    @Test
    fun theReasonQuotesTheReadingItself() {
        val read = TurnEngine.Read(
            price = 225.19, dayChangePct = 4.9, macd = -22.34, signal = -22.12,
            histogram = -0.22, macdPct = -9.92, gapPct = -0.098, hma = 193.42,
            hmaDistPct = 16.4, histRisingDays = 1, offHighPct = -38.9, offLowPct = 26.4
        )
        val reason = TurnPicker.reasonFor(read)
        assertNotNull(reason)
        assertTrue(reason.contains("MACD -22.34 sits 0.22 under its signal -22.12"))
        assertTrue(reason.contains("for 1 session."))
        assertTrue(reason.contains("16.4% above an HMA(20) of 193.42"))
        assertTrue(reason.contains("38.9% off the 3-month high, 26.4% off the 20-day low"))
    }
}
