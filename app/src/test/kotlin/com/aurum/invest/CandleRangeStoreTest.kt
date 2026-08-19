package com.aurum.invest

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.repo.MarketRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9 superset-range candle store: one canonical series per symbol, sliced to
 * the exact Yahoo bucket a direct fetch would have returned. These rules are
 * what lets a 60-day caller ride a cached 365-day series with ZERO requests
 * without ever seeing fewer bars than the old per-range cache gave it.
 */
class CandleRangeStoreTest {

    private val dayMs = 86_400_000L

    private fun daily(days: Int, asOf: Long): List<Candle> =
        (days - 1 downTo 0).map { back ->
            val ts = asOf - back * dayMs
            Candle(ts = ts, open = 1.0, high = 2.0, low = 0.5, close = 1.5, volume = 100L)
        }

    // ---- bucketDays: must mirror YahooClient.fetchDailyCandles' range map ----

    @Test
    fun bucketsMatchTheYahooRangeMap() {
        assertEquals(7, MarketRepository.bucketDays(5))
        assertEquals(7, MarketRepository.bucketDays(7))
        assertEquals(30, MarketRepository.bucketDays(30))
        assertEquals(95, MarketRepository.bucketDays(60))
        assertEquals(95, MarketRepository.bucketDays(90))
        assertEquals(190, MarketRepository.bucketDays(120))
        assertEquals(190, MarketRepository.bucketDays(180))
        assertEquals(400, MarketRepository.bucketDays(210))
        assertEquals(400, MarketRepository.bucketDays(365))
        assertEquals(400, MarketRepository.bucketDays(400))
        assertEquals(730, MarketRepository.bucketDays(500))
    }

    // ---- sliceToBucket -------------------------------------------------------

    @Test
    fun shallowRequestIsSlicedFromTheDeepSeries() {
        val asOf = 1_700_000_000_000L
        val yearSeries = daily(400, asOf)
        val sliced = MarketRepository.sliceToBucket(yearSeries, 60, asOf)
        // A 60-day request maps to Yahoo's 3mo bucket = 95 calendar days,
        // inclusive of the bar sitting exactly on the cutoff.
        assertEquals(96, sliced.size)
        // The slice is the TAIL: its last bar is the series' last bar.
        assertEquals(yearSeries.last().ts, sliced.last().ts)
        // And every kept bar is inside the bucket window.
        assertTrue(sliced.all { it.ts >= asOf - 95 * dayMs })
    }

    @Test
    fun requestAtTheStoredDepthKeepsEveryBar() {
        val asOf = 1_700_000_000_000L
        val series = daily(95, asOf)
        val sliced = MarketRepository.sliceToBucket(series, 90, asOf)
        assertEquals(series.size, sliced.size)
    }

    @Test
    fun staleSliceIsJudgedAgainstFetchTimeNotNow() {
        // A series fetched 10 days ago, sliced for a 7-day bucket: the window
        // hangs from WHEN THE BARS WERE READ. Judged against "now" instead,
        // every bar would fall outside the window and the stale fallback would
        // serve nothing — which is exactly the bug this rule prevents.
        val fetchedAt = 1_700_000_000_000L
        val series = daily(30, fetchedAt)
        val sliced = MarketRepository.sliceToBucket(series, 5, fetchedAt)
        assertEquals(7 + 1, sliced.size) // 7 calendar days inclusive of both ends
        assertEquals(series.last().ts, sliced.last().ts)
    }

    @Test
    fun emptySeriesStaysEmpty() {
        assertTrue(MarketRepository.sliceToBucket(emptyList(), 60, 0L).isEmpty())
    }
}
