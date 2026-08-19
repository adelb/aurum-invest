package com.aurum.invest

import com.aurum.invest.analytics.EngineCall
import com.aurum.invest.analytics.EngineRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engines' report card: what "right" means per kind of call, the
 * minimum-evidence gate before any rate is claimed, and the Wilson floor.
 */
class EngineRecordTest {

    private fun call(
        kind: String,
        fwd20: Double?,
        fwd5: Double? = fwd20,
        symbol: String = "AAA"
    ) = EngineCall(
        kind = kind, symbol = symbol, ts = 1_700_000_000_000L,
        refPrice = 100.0, fwd5Pct = fwd5, fwd20Pct = fwd20
    )

    @Test
    fun buySideCallsWinWhenTheNameRose() {
        // 12 graded PICKs, 9 rose.
        val calls = List(9) { call(EngineRecord.KIND_PICK, fwd20 = 5.0) } +
            List(3) { call(EngineRecord.KIND_PICK, fwd20 = -2.0) }
        val bucket = EngineRecord.grade(calls).buckets.single()
        assertEquals(12, bucket.graded)
        assertEquals(9, bucket.wins)
        assertEquals(75.0, bucket.winRatePct!!, 1e-6)
        // The floor is the honest number: well under the shown 75%.
        assertNotNull(bucket.wilsonLowerPct)
        assertTrue(bucket.wilsonLowerPct!! < 75.0)
        assertTrue(bucket.wilsonLowerPct!! > 40.0)
    }

    @Test
    fun sellSideCallsWinWhenTheNameFell() {
        val calls = List(10) { call(EngineRecord.KIND_EXIT, fwd20 = -8.0) } +
            List(2) { call(EngineRecord.KIND_EXIT, fwd20 = 4.0) }
        val bucket = EngineRecord.grade(calls).buckets.single()
        assertEquals(10, bucket.wins)
        // The definition is printed, never implied.
        assertTrue(bucket.winMeaning.contains("fell", ignoreCase = true) ||
            bucket.winMeaning.contains("falling", ignoreCase = true))
    }

    @Test
    fun noRateIsClaimedUnderTheEvidenceBar() {
        // 6 graded calls, all wins — still no rate: 6 < MIN_GRADED.
        val calls = List(6) { call(EngineRecord.KIND_ADD, fwd20 = 10.0) } +
            List(4) { call(EngineRecord.KIND_ADD, fwd20 = null) }
        val bucket = EngineRecord.grade(calls).buckets.single()
        assertEquals(10, bucket.total)
        assertEquals(6, bucket.graded)
        assertNull(bucket.winRatePct)
        assertNull(bucket.wilsonLowerPct)
        assertTrue(bucket.note.contains("${EngineRecord.MIN_GRADED}"))
    }

    @Test
    fun ungradedCallsStandOnRecordWithoutAnOutcome() {
        val calls = List(3) { call(EngineRecord.KIND_TRIM, fwd20 = null, fwd5 = null) }
        val report = EngineRecord.grade(calls)
        val bucket = report.buckets.single()
        assertEquals(3, bucket.total)
        assertEquals(0, bucket.graded)
        assertTrue(bucket.note.contains("none old enough"))
        assertTrue(report.headline.contains("none old enough"))
    }

    @Test
    fun emptyRecordSaysSoInsteadOfPretending() {
        val report = EngineRecord.grade(emptyList())
        assertTrue(report.buckets.isEmpty())
        assertTrue(report.headline.contains("No calls logged yet"))
    }
}
