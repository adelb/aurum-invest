package com.aurum.invest

import com.aurum.invest.data.remote.pickEarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Catalysts card must never announce a date that has already passed as
 * "Next earnings". Yahoo's `earningsDate` array is not a next-date field: it
 * keeps serving the last report until the next is scheduled, and holds TWO
 * entries when the date is only an estimated window.
 */
class EarningsDateTest {

    private val day = 86_400_000L
    private val today = 1_800_000_000_000L   // fixed "today start"; no clock reads

    @Test
    fun `a single future date is the next one`() {
        val w = pickEarnings(listOf(today + 10 * day), today)
        assertEquals(today + 10 * day, w.next)
        assertNull(w.nextEnd)
        assertNull(w.last)
    }

    @Test
    fun `a lone past date is never served as next`() {
        // The regression: element 0 was taken blindly, so a company that had
        // just reported showed its LAST earnings under "Next earnings".
        val w = pickEarnings(listOf(today - 3 * day), today)
        assertNull(w.next)
        assertEquals(today - 3 * day, w.last)
    }

    @Test
    fun `the past date is skipped in favour of the real upcoming one`() {
        val w = pickEarnings(listOf(today - 80 * day, today + 12 * day), today)
        assertEquals(today + 12 * day, w.next)
        assertEquals(today - 80 * day, w.last)
        assertNull(w.nextEnd)
    }

    @Test
    fun `two future dates are one estimated window, not two events`() {
        val w = pickEarnings(listOf(today + 20 * day, today + 24 * day), today)
        assertEquals(today + 20 * day, w.next)
        assertEquals(today + 24 * day, w.nextEnd)
    }

    @Test
    fun `today counts as upcoming — a company reports after the close`() {
        val w = pickEarnings(listOf(today), today)
        assertEquals(today, w.next)
        assertNull(w.last)
    }

    @Test
    fun `unsorted input still resolves the nearest future and latest past`() {
        val w = pickEarnings(
            listOf(today + 40 * day, today - 5 * day, today + 9 * day, today - 95 * day),
            today
        )
        assertEquals(today + 9 * day, w.next)
        assertEquals(today + 40 * day, w.nextEnd)
        assertEquals(today - 5 * day, w.last)
    }

    @Test
    fun `zero and duplicate timestamps are discarded`() {
        val w = pickEarnings(listOf(0L, today + 7 * day, today + 7 * day, 0L), today)
        assertEquals(today + 7 * day, w.next)
        assertNull(w.nextEnd)   // the duplicate must not read as a window
    }

    @Test
    fun `an empty array reports nothing rather than a fabricated date`() {
        val w = pickEarnings(emptyList(), today)
        assertNull(w.next)
        assertNull(w.nextEnd)
        assertNull(w.last)
    }
}
