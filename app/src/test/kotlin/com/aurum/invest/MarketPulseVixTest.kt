package com.aurum.invest

import com.aurum.invest.analytics.MarketCall
import com.aurum.invest.analytics.MarketPulse
import com.aurum.invest.analytics.MarketRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The VIX is a first-class Wealth figure: its level, its 5-session drift,
 * and its regime label must survive the cache round-trip, and the regime
 * bands must stay glued to the score's own vixPoints thresholds.
 */
class MarketPulseVixTest {

    private fun rating(vix: Double?, vix5d: Double?) = MarketRating(
        date = "2026-08-19",
        computedAt = 1_700_000_000_000L,
        score = 61,
        call = MarketCall.INVEST,
        headline = "h",
        advice = "a",
        reasons = listOf("r"),
        breadthAbove50Pct = 55.0,
        advancersPct = 52.0,
        scannedCount = 120,
        vix = vix,
        vixChange5d = vix5d,
        indexes = emptyList(),
        bestYesterday = emptyList(),
        nextDay = emptyList()
    )

    @Test
    fun vixAndDriftSurviveTheJsonRoundTrip() {
        val back = MarketPulse.fromJson(MarketPulse.toJson(rating(18.4, -1.3)))!!
        assertEquals(18.4, back.vix!!, 1e-9)
        assertEquals(-1.3, back.vixChange5d!!, 1e-9)
    }

    @Test
    fun missingVixStaysNullNeverDefaulted() {
        val back = MarketPulse.fromJson(MarketPulse.toJson(rating(null, null)))!!
        assertNull(back.vix)
        assertNull(back.vixChange5d)
    }

    @Test
    fun driftlessLevelIsStillCarried() {
        val back = MarketPulse.fromJson(MarketPulse.toJson(rating(22.0, null)))!!
        assertEquals(22.0, back.vix!!, 1e-9)
        assertNull(back.vixChange5d)
    }

    @Test
    fun regimeBandsMatchTheScoreThresholds() {
        assertEquals("Very calm", MarketPulse.vixRegime(11.0))
        assertEquals("Calm", MarketPulse.vixRegime(15.5))
        assertEquals("Normal", MarketPulse.vixRegime(18.0))
        assertEquals("Elevated", MarketPulse.vixRegime(22.0))
        assertEquals("Stressed", MarketPulse.vixRegime(27.0))
        assertEquals("Fear regime", MarketPulse.vixRegime(35.0))
        // Band edges belong to the higher-volatility side, same as vixPoints.
        assertEquals("Calm", MarketPulse.vixRegime(14.0))
        assertEquals("Normal", MarketPulse.vixRegime(17.0))
        assertEquals("Elevated", MarketPulse.vixRegime(20.0))
        assertEquals("Stressed", MarketPulse.vixRegime(25.0))
        assertEquals("Fear regime", MarketPulse.vixRegime(30.0))
    }
}
