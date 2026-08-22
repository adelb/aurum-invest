package com.aurum.invest

import com.aurum.invest.analytics.CheckState
import com.aurum.invest.analytics.MustBuyCandidate
import com.aurum.invest.analytics.MustBuyEngine
import com.aurum.invest.analytics.NoteKind
import com.aurum.invest.analytics.TechniqueVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Must-Buy engine: the fixed bar, the three vetoes, the honesty of
 * unmeasured checks, and a list that is capped but never padded.
 */
class MustBuyEngineTest {

    /** A candidate with every check measured and green. */
    private fun strong(symbol: String = "AAA", beatShare: Double = 70.0) = MustBuyCandidate(
        symbol = symbol,
        name = "Strong Co",
        price = 100.0,
        dayChangePct = 1.0,
        sources = listOf("Daily", "Weekly"),
        bestScore = 85.0,
        techDirection = "BULLISH",
        techBullish = 28,
        techTotal = 35,
        spyVerdict = TechniqueVerdict.BULLISH,
        spyGreen = true,
        spyBeatSharePct = beatShare,
        spyEdgeEntry = 104.0,
        earningsKnown = true,
        earningsInDays = 30,
        noteKind = NoteKind.DIVERSIFIES,
        analystRating = 1.8,
        rewardRisk = 3.0,
        headlineSentiment = 1
    )

    // ---- the checks --------------------------------------------------------

    @Test
    fun aFullyGreenCandidatePassesAllNineChecks() {
        val checks = MustBuyEngine.checks(strong())
        assertEquals(MustBuyEngine.CHECK_COUNT, checks.size)
        assertTrue(checks.all { it.state == CheckState.PASS })
    }

    @Test
    fun unknownEarningsIsUnmeasuredNeverEarningsFree() {
        val checks = MustBuyEngine.checks(strong().copy(earningsKnown = false))
        val earnings = checks.first { it.label.contains("earnings") }
        assertEquals(CheckState.UNMEASURED, earnings.state)
    }

    @Test
    fun earningsInsideSevenDaysFailsTheCheck() {
        val checks = MustBuyEngine.checks(
            strong().copy(earningsInDays = MustBuyEngine.EARNINGS_CLEAR_DAYS)
        )
        assertEquals(
            CheckState.FAIL,
            checks.first { it.label.contains("earnings") }.state
        )
    }

    @Test
    fun anUnracedListingCarriesTwoUnmeasuredSpyChecks() {
        val checks = MustBuyEngine.checks(
            strong().copy(
                spyVerdict = null, spyGreen = null,
                spyBeatSharePct = null, spyEdgeEntry = null
            )
        )
        assertEquals(2, checks.count { it.state == CheckState.UNMEASURED })
    }

    // ---- the vetoes --------------------------------------------------------

    @Test
    fun aLostSpyRaceExcludesWhateverElseIsGreen() {
        val report = MustBuyEngine.build(
            listOf(strong().copy(spyVerdict = TechniqueVerdict.BEARISH))
        )
        assertTrue(report.rows.isEmpty())
        assertEquals(1, report.disqualified)
    }

    @Test
    fun aBearishBoardAndConcentrationEachExclude() {
        val report = MustBuyEngine.build(
            listOf(
                strong("AAA").copy(techDirection = "BEARISH"),
                strong("BBB").copy(noteKind = NoteKind.CONCENTRATION)
            )
        )
        assertTrue(report.rows.isEmpty())
        assertEquals(2, report.disqualified)
    }

    // ---- the bar and the seats ---------------------------------------------

    @Test
    fun theBarIsFivePassesNotFour() {
        // Strip green checks down: single source, weak score, no analyst,
        // no headline, above the green zone -> exactly 4 passes (board,
        // beat share, earnings, book) stays unseated; adding one more seats it.
        val four = strong("FOU").copy(
            sources = listOf("Daily"),
            bestScore = 40.0,
            analystRating = null,
            headlineSentiment = null,
            spyGreen = false
        )
        val five = four.copy(symbol = "FIV", spyGreen = true)
        val report = MustBuyEngine.build(listOf(four, five))
        assertEquals(listOf("FIV"), report.rows.map { it.symbol })
        val seated = report.rows.first()
        assertEquals(5, seated.passed)
    }

    @Test
    fun seatsAreCappedAtTenAndRankedByPasses() {
        val many = (0 until 12).map { i ->
            if (i == 0) strong("S%02d".format(i)).copy(headlineSentiment = 0)
            else strong("S%02d".format(i))
        }
        val report = MustBuyEngine.build(many)
        assertEquals(MustBuyEngine.SEATS, report.rows.size)
        // The 8-pass candidate ranks last of the seated full-passers' cohort.
        assertTrue(report.rows.first().passed >= report.rows.last().passed)
        assertEquals(1, report.rows.first().rank)
    }

    @Test
    fun equalPassesBreakTiesByBeatShare() {
        val report = MustBuyEngine.build(
            listOf(strong("LOW", beatShare = 60.0), strong("HIG", beatShare = 80.0))
        )
        assertEquals(listOf("HIG", "LOW"), report.rows.map { it.symbol })
    }

    @Test
    fun theListIsNeverPaddedAndSaysSo() {
        val report = MustBuyEngine.build(listOf(strong()))
        assertEquals(1, report.rows.size)
        assertTrue(report.notes.any { it.contains("does not bend") })
    }

    @Test
    fun emptyCandidatesProduceAnEmptyHonestReport() {
        val report = MustBuyEngine.build(emptyList())
        assertTrue(report.rows.isEmpty())
        assertEquals(0, report.scanned)
        assertTrue(report.notes.any { it.contains("ten seats") || it.contains("seats") })
    }
}
