package com.aurum.invest

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.repo.MarketRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Superset-range candle store: one canonical series per symbol, sliced to
 * the exact Yahoo bucket a direct fetch would have returned. This is what
 * lets a 60-day caller ride a cached year with ZERO requests without ever
 * seeing fewer bars than the per-range cache gave it.
 */
class CandleRangeStoreTest {

    private val dayMs = 86_400_000L

    private fun daily(days: Int, asOf: Long): List<Candle> =
        (days - 1 downTo 0).map { back ->
            val ts = asOf - back * dayMs
            Candle(ts = ts, open = 1.0, high = 2.0, low = 0.5, close = 1.5, volume = 100L)
        }

    @Test
    fun bucketsMatchTheYahooRangeMap() {
        assertEquals(7, MarketRepository.bucketDays(5))
        assertEquals(30, MarketRepository.bucketDays(30))
        assertEquals(95, MarketRepository.bucketDays(60))
        assertEquals(190, MarketRepository.bucketDays(120))
        assertEquals(400, MarketRepository.bucketDays(365))
        assertEquals(730, MarketRepository.bucketDays(550))
    }

    @Test
    fun shallowRequestIsSlicedFromTheDeepSeries() {
        val asOf = 1_700_000_000_000L
        val yearSeries = daily(400, asOf)
        val sliced = MarketRepository.sliceToBucket(yearSeries, 60, asOf)
        // A 60-day request maps to Yahoo's 3mo bucket = 95 calendar days,
        // inclusive of the bar sitting exactly on the cutoff.
        assertEquals(96, sliced.size)
        assertEquals(yearSeries.last().ts, sliced.last().ts)
        assertTrue(sliced.all { it.ts >= asOf - 95 * dayMs })
    }

    @Test
    fun requestAtTheStoredDepthKeepsEveryBar() {
        val asOf = 1_700_000_000_000L
        val series = daily(95, asOf)
        assertEquals(series.size, MarketRepository.sliceToBucket(series, 90, asOf).size)
    }

    @Test
    fun staleSliceIsJudgedAgainstFetchTimeNotNow() {
        // The window hangs from WHEN THE BARS WERE READ; judged against "now"
        // a stale series would slice to nothing and the fallback would starve.
        val fetchedAt = 1_700_000_000_000L
        val series = daily(30, fetchedAt)
        val sliced = MarketRepository.sliceToBucket(series, 5, fetchedAt)
        assertEquals(8, sliced.size)
        assertEquals(series.last().ts, sliced.last().ts)
    }

    @Test
    fun emptySeriesStaysEmpty() {
        assertTrue(MarketRepository.sliceToBucket(emptyList(), 60, 0L).isEmpty())
    }
}
