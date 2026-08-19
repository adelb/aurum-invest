package com.aurum.invest

import com.aurum.invest.analytics.Indicators
import com.aurum.invest.data.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden vectors for the indicator math every engine builds on (C7). */
class IndicatorsTest {

    @Test
    fun `sma of a known window`() {
        assertEquals(20.0, Indicators.sma(listOf(10.0, 20.0, 30.0), 3)!!, 1e-9)
        assertEquals(25.0, Indicators.sma(listOf(10.0, 20.0, 30.0), 2)!!, 1e-9)
        assertNull(Indicators.sma(listOf(10.0), 2))
    }

    @Test
    fun `rsi extremes behave`() {
        val allUp = (1..30).map { it.toDouble() }
        assertEquals(100.0, Indicators.rsi(allUp, 14)!!, 1e-9)
        val allDown = (30 downTo 1).map { it.toDouble() }
        assertEquals(0.0, Indicators.rsi(allDown, 14)!!, 1e-9)
        val flat = List(30) { 50.0 }
        assertEquals(50.0, Indicators.rsi(flat, 14)!!, 1e-9)
    }

    @Test
    fun `rsi known vector`() {
        // Classic Wilder example series (14-period), expected first RSI ≈ 70.46.
        val closes = listOf(
            44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42,
            45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28
        )
        val rsi = Indicators.rsi(closes, 14)!!
        assertEquals(70.46, rsi, 0.5)
    }

    @Test
    fun `atr on constant range`() {
        val candles = (1..20).map {
            Candle(ts = it * 1000L, open = 100.0, high = 102.0, low = 98.0, close = 100.0, volume = 1L)
        }
        // Every true range is 4 (high-low), so ATR = 4.
        assertEquals(4.0, Indicators.atr(candles, 14)!!, 1e-9)
    }

    @Test
    fun `pearson of identical series is one`() {
        val a = listOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.0, Indicators.pearson(a, a)!!, 1e-9)
        val inverse = listOf(4.0, 3.0, 2.0, 1.0)
        assertEquals(-1.0, Indicators.pearson(a, inverse)!!, 1e-9)
    }

    @Test
    fun `daily returns are fractional`() {
        val r = Indicators.dailyReturns(listOf(100.0, 110.0, 99.0))
        assertEquals(0.10, r[0], 1e-9)
        assertTrue(r[1] < 0.0)
    }
}
