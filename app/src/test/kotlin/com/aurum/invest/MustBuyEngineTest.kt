package com.aurum.invest

import com.aurum.invest.analytics.CheckState
import com.aurum.invest.analytics.EngineRecord
import com.aurum.invest.analytics.EngineRecordReport
import com.aurum.invest.analytics.MustBuyCandidate
import com.aurum.invest.analytics.MustBuyEngine
import com.aurum.invest.analytics.NoteKind
import com.aurum.invest.analytics.RecordBucket
import com.aurum.invest.analytics.TechniqueVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Must-Buy engine v2: weighted checks with the SPY green zone heaviest,
 * backing scaled by each engine's own graded record, the fixed seating bar,
 * the three vetoes, and a list that is capped but never padded.
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

    private fun bucket(kind: String, wilson: Double?, graded: Int = 20) = RecordBucket(
        kind = kind, label = kind, winMeaning = "", total = graded, graded = graded,
        wins = graded / 2, winRatePct = wilson, wilsonLowerPct = wilson,
        avgFwd20Pct = null, note = ""
    )

    private fun record(vararg buckets: RecordBucket) =
        EngineRecordReport(0L, buckets.toList(), "", "")

    // ---- the weighted checks ----------------------------------------------

    @Test
    fun aFullyGreenCandidateEarnsTheWholeCheckWeight() {
        val row = MustBuyEngine.build(listOf(strong())).rows.single()
        assertEquals(MustBuyEngine.CHECK_COUNT, row.passed)
        assertEquals(MustBuyEngine.CHECK_WEIGHT_TOTAL, row.checkPoints, 1e-9)
        assertEquals(MustBuyEngine.CHECK_WEIGHT_TOTAL, row.checkMax, 1e-9)
        // Two nominating engines, both unproven -> x1.0 each.
        assertEquals(2.0, row.backingPoints, 1e-9)
    }

    @Test
    fun theGreenCheckOutweighsAOnePointCheck() {
        // A fails the 1-pt analyst check but holds the green zone; B holds the
        // analyst check but sits above the green zone. A must rank first.
        val a = strong("GRN").copy(analystRating = 4.0)
        val b = strong("NOG").copy(spyGreen = false)
        val report = MustBuyEngine.build(listOf(b, a))
        assertEquals(listOf("GRN", "NOG"), report.rows.map { it.symbol })
        val ga = report.rows.first { it.symbol == "GRN" }
        val gb = report.rows.first { it.symbol == "NOG" }
        assertEquals(
            MustBuyEngine.WEIGHT_GREEN - MustBuyEngine.WEIGHT_ANALYST,
            ga.checkPoints - gb.checkPoints, 1e-9
        )
    }

    @Test
    fun unknownEarningsIsUnmeasuredNeverEarningsFree() {
        val checks = MustBuyEngine.checks(strong().copy(earningsKnown = false))
        assertEquals(
            CheckState.UNMEASURED,
            checks.first { it.label.contains("earnings") }.state
        )
    }

    // ---- record-scaled backing --------------------------------------------

    @Test
    fun aMeasuredRecordScalesTheBackingByWilsonOverTheCoinFlip() {
        val rec = record(bucket(EngineRecord.KIND_DAILY, wilson = 75.0))
        val backing = MustBuyEngine.backing(listOf("Daily"), rec).single()
        assertEquals(1.5, backing.multiplier, 1e-9)
        assertEquals(1.5, backing.points, 1e-9)
        assertTrue(backing.measured)
        assertEquals(20, backing.gradedCalls)
    }

    @Test
    fun aPoorRecordBacksUnderOneAndClampsAtTheFloor() {
        val rec = record(
            bucket(EngineRecord.KIND_DAILY, wilson = 40.0),
            bucket(EngineRecord.KIND_WEEKLY, wilson = 10.0)
        )
        val backing = MustBuyEngine.backing(listOf("Daily", "Weekly"), rec)
        assertEquals(0.8, backing[0].multiplier, 1e-9)
        assertEquals(MustBuyEngine.MULT_FLOOR, backing[1].multiplier, 1e-9)
    }

    @Test
    fun anUnprovenEngineBacksNeutralNeverBoostedOrPunished() {
        // No bucket at all, and a bucket too thin to claim a rate (wilson null).
        val rec = record(bucket(EngineRecord.KIND_DAILY, wilson = null, graded = 4))
        val backing = MustBuyEngine.backing(listOf("Daily", "Weekly"), rec)
        for (b in backing) {
            assertEquals(1.0, b.multiplier, 1e-9)
            assertFalse(b.measured)
        }
    }

    @Test
    fun theMostAccurateEngineRanksItsNomineeFirst() {
        // Identical checks; only the nominating engine's graded record differs.
        val provenPick = strong("ACC").copy(sources = listOf("Daily"))
        val unprovenPick = strong("UNP").copy(sources = listOf("Weekly"))
        val rec = record(bucket(EngineRecord.KIND_DAILY, wilson = 75.0))
        val report = MustBuyEngine.build(listOf(unprovenPick, provenPick), rec)
        assertEquals(listOf("ACC", "UNP"), report.rows.map { it.symbol })
        assertEquals(0.5, report.rows[0].totalPoints - report.rows[1].totalPoints, 1e-9)
    }

    @Test
    fun nextSessionNominationsReadThePickBucket() {
        val rec = record(bucket(EngineRecord.KIND_PICK, wilson = 60.0))
        val backing = MustBuyEngine.backing(listOf("Next session"), rec).single()
        assertEquals(1.2, backing.multiplier, 1e-9)
        assertTrue(backing.measured)
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

    // ---- the seating bar ---------------------------------------------------

    @Test
    fun anUnracedListingCannotTakeASeat() {
        // Without the race, five of twelve weight points are unmeasurable —
        // under the 60% measurable bar however green the rest reads.
        val report = MustBuyEngine.build(
            listOf(
                strong().copy(
                    spyVerdict = null, spyGreen = null,
                    spyBeatSharePct = null, spyEdgeEntry = null
                )
            )
        )
        assertTrue(report.rows.isEmpty())
        assertTrue(report.notes.any { it.contains("cannot take a seat") })
    }

    @Test
    fun halfTheMeasurableWeightMustBeEarned() {
        // Everything measured; fails board, analyst, score, headline, and the
        // green zone -> 4.0 of 12.0 earned, under half. The same candidate
        // holding the green zone earns 7.0 and seats.
        val weak = strong("WEA").copy(
            techDirection = "NEUTRAL",
            analystRating = 4.0,
            bestScore = 40.0,
            headlineSentiment = 0,
            spyGreen = false
        )
        val seatedTwin = weak.copy(symbol = "SEA", spyGreen = true)
        val report = MustBuyEngine.build(listOf(weak, seatedTwin))
        assertEquals(listOf("SEA"), report.rows.map { it.symbol })
        assertEquals(7.0, report.rows.single().checkPoints, 1e-9)
    }

    // ---- seats, caps, honesty ----------------------------------------------

    @Test
    fun seatsAreCappedAtTenAndRankedByPoints() {
        val many = (0 until 12).map { i ->
            if (i == 0) strong("S%02d".format(i)).copy(headlineSentiment = 0)
            else strong("S%02d".format(i))
        }
        val report = MustBuyEngine.build(many)
        assertEquals(MustBuyEngine.SEATS, report.rows.size)
        assertTrue(report.rows.first().totalPoints >= report.rows.last().totalPoints)
        assertEquals(1, report.rows.first().rank)
    }

    @Test
    fun theListIsNeverPaddedAndSaysSo() {
        val report = MustBuyEngine.build(listOf(strong()))
        assertEquals(1, report.rows.size)
        assertTrue(report.notes.any { it.contains("does not bend") })
    }

    @Test
    fun anUnprovenLedgerIsNamedInTheNotes() {
        val report = MustBuyEngine.build(listOf(strong()))
        assertTrue(report.notes.any { it.contains("×1.0 until") })
    }

    @Test
    fun emptyCandidatesProduceAnEmptyHonestReport() {
        val report = MustBuyEngine.build(emptyList())
        assertTrue(report.rows.isEmpty())
        assertEquals(0, report.scanned)
        assertTrue(report.notes.any { it.contains("seats") })
    }
}
