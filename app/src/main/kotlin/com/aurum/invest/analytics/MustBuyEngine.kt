package com.aurum.invest.analytics

import java.util.Locale

/**
 * The Must-Buy engine: every stock currently nominated by the app's engines —
 * the five pick lists and the next-session scan — put through every read the
 * app can actually measure, then ranked by WEIGHTED points, not a flat count.
 *
 * Two point components, both laid open on the card:
 *  - CHECK POINTS: eight weighted checks. The green-zone entry vs SPY carries
 *    the heaviest weight ([WEIGHT_GREEN]) — the race against the index is the
 *    bar a buy must clear before anything else matters.
 *  - BACKING POINTS: one point per nominating engine, SCALED BY THAT ENGINE'S
 *    OWN GRADED RECORD from the self-scoring ledger — the measured Wilson 95%
 *    floor of its win rate against the 50% coin flip, clamped to
 *    [MULT_FLOOR]–[MULT_CEIL]. The most accurate engine backs hardest; an
 *    engine without [EngineRecord.MIN_GRADED] graded calls backs at ×1.0 —
 *    unproven is neutral, never boosted and never punished.
 *
 * Integrity rules (house law):
 *  - pure function over assembled facts; no I/O, never throws
 *  - fixed public bars: at least [MIN_MEASURED_SHARE] of the check weight
 *    must be measurable, at least [MIN_EARNED_SHARE] of the measurable weight
 *    must be earned, and not one measured negative — a bearish board, a lost
 *    SPY race, or concentration excludes a name outright
 *  - an unmeasured check counts for NOTHING and shrinks what the row can
 *    claim; a listing that can't be raced against SPY cannot take a seat
 *  - at most [SEATS] rows and never padded
 */

enum class CheckState { PASS, FAIL, UNMEASURED }

/** One measured check on one candidate — the label, the outcome, the weight. */
data class MustBuyCheck(
    val label: String,
    val state: CheckState,
    val detail: String,
    val weight: Double
)

/** One nominating engine's contribution, scaled by its own graded record. */
data class SourceBacking(
    val source: String,
    /** [NOMINATION_BASE] × [multiplier]. */
    val points: Double,
    val multiplier: Double,
    /** True when a graded record set the multiplier; false = neutral ×1.0. */
    val measured: Boolean,
    val gradedCalls: Int
)

/** Every fact the caller could assemble about one nominated stock. */
data class MustBuyCandidate(
    val symbol: String,
    val name: String,
    val price: Double,
    val dayChangePct: Double?,
    /** Which engines nominated it (Daily, Entries, Power, Weekly, Under $25, Next session). */
    val sources: List<String>,
    /** Best 0-100 pick score across the lists that carry one; null when none does. */
    val bestScore: Double?,
    /** Technique-board read stored with the pick; null = never read for this name. */
    val techDirection: String? = null,
    val techBullish: Int = 0,
    val techTotal: Int = 0,
    /** The Beat-SPY buy horizon's outcome; nulls = the race could not be run. */
    val spyVerdict: TechniqueVerdict? = null,
    val spyGreen: Boolean? = null,
    val spyBeatSharePct: Double? = null,
    val spyEdgeEntry: Double? = null,
    /** False when the earnings lookup failed — unknown is never earnings-free. */
    val earningsKnown: Boolean = false,
    /** Days until the next known report; null with known=true = none scheduled. */
    val earningsInDays: Int? = null,
    /** The pick against the live book; null = no factual note applies. */
    val noteKind: NoteKind? = null,
    /** Analyst consensus 1 (Strong Buy) .. 5 (Sell); null = unrated. */
    val analystRating: Double? = null,
    /** Entry-scan reward-to-risk; null when no entry pick carries it. */
    val rewardRisk: Double? = null,
    /** Newest related headline's sentiment; null = no headline was read. */
    val headlineSentiment: Int? = null
)

/** One seated stock: the rank, both point components, and every check laid open. */
data class MustBuyRow(
    val rank: Int,
    val symbol: String,
    val name: String,
    val price: Double,
    val dayChangePct: Double?,
    /** Check points earned / measurable check weight (of [MustBuyEngine.CHECK_WEIGHT_TOTAL]). */
    val checkPoints: Double,
    val checkMax: Double,
    /** Nominating engines' record-scaled contribution. */
    val backingPoints: Double,
    val totalPoints: Double,
    val passed: Int,
    val measured: Int,
    val checks: List<MustBuyCheck>,
    val backing: List<SourceBacking>,
    val sources: List<String>,
    val beatSharePct: Double?,
    val edgeEntry: Double?
)

data class MustBuyReport(
    val computedAt: Long,
    /** Candidates scanned — every stock the engines nominated. */
    val scanned: Int,
    /** Candidates excluded outright on a measured negative. */
    val disqualified: Int,
    val rows: List<MustBuyRow>,
    val notes: List<String>,
    val caveat: String
)

object MustBuyEngine {

    /** The list never grows past this, and is never padded up to it. */
    const val SEATS = 10

    /** The eight measured checks every candidate faces. */
    const val CHECK_COUNT = 8

    /** Fixed check weights, printed with the card so the scale never drifts. */
    const val WEIGHT_GREEN = 3.0
    const val WEIGHT_BEAT = 2.0
    const val WEIGHT_BOARD = 2.0
    const val WEIGHT_EARNINGS = 1.0
    const val WEIGHT_BOOK = 1.0
    const val WEIGHT_ANALYST = 1.0
    const val WEIGHT_SCORE = 1.0
    const val WEIGHT_HEADLINE = 1.0
    const val CHECK_WEIGHT_TOTAL = WEIGHT_GREEN + WEIGHT_BEAT + WEIGHT_BOARD +
        WEIGHT_EARNINGS + WEIGHT_BOOK + WEIGHT_ANALYST + WEIGHT_SCORE + WEIGHT_HEADLINE

    /** At least this share of the check weight must be measurable to seat. */
    const val MIN_MEASURED_SHARE = 0.6

    /** At least this share of the measurable weight must be earned to seat. */
    const val MIN_EARNED_SHARE = 0.5

    /** A report inside this window is an unhedgeable coin flip — the check fails. */
    const val EARNINGS_CLEAR_DAYS = 7

    /** Fixed bars, printed with the checks. */
    const val SCORE_BAR = 70.0
    const val ANALYST_BUY_BAR = 2.5

    /** Each nomination's base contribution before the record scales it. */
    const val NOMINATION_BASE = 1.0

    /** The record multiplier's clamp — even a perfect record backs at most ×2. */
    const val MULT_FLOOR = 0.5
    const val MULT_CEIL = 2.0

    /** Which graded-record bucket speaks for each nominating engine. */
    val SOURCE_KINDS = mapOf(
        "Daily" to EngineRecord.KIND_DAILY,
        "Entries" to EngineRecord.KIND_ENTRY,
        "Power" to EngineRecord.KIND_POWER,
        "Weekly" to EngineRecord.KIND_WEEKLY,
        "Under $25" to EngineRecord.KIND_BUDGET,
        "Next session" to EngineRecord.KIND_PICK
    )

    fun build(
        candidates: List<MustBuyCandidate>,
        record: EngineRecordReport? = null,
        now: Long = System.currentTimeMillis()
    ): MustBuyReport {
        val caveat = "Ten seats, eight weighted checks (the SPY green zone weighs " +
            "heaviest), three vetoes, and backing scaled by each engine's own graded " +
            "record — the strongest convergence of what the app can measure today, " +
            "not a promise about tomorrow. Decision support, not financial advice."

        data class Judged(
            val c: MustBuyCandidate,
            val checks: List<MustBuyCheck>,
            val backing: List<SourceBacking>,
            val checkPoints: Double,
            val checkMax: Double,
            val backingPoints: Double
        )

        var disqualified = 0
        var unraced = 0
        val judged = ArrayList<Judged>(candidates.size)
        for (c in candidates) {
            if (c.spyGreen == null) unraced++
            if (vetoes(c).isNotEmpty()) {
                disqualified++
                continue
            }
            val checks = checks(c)
            val backing = backing(c.sources, record)
            judged += Judged(
                c = c,
                checks = checks,
                backing = backing,
                checkPoints = checks.filter { it.state == CheckState.PASS }
                    .sumOf { it.weight },
                checkMax = checks.filter { it.state != CheckState.UNMEASURED }
                    .sumOf { it.weight },
                backingPoints = backing.sumOf { it.points }
            )
        }

        val seated = judged
            .filter { j ->
                j.checkMax >= MIN_MEASURED_SHARE * CHECK_WEIGHT_TOTAL &&
                    j.checkPoints >= MIN_EARNED_SHARE * j.checkMax
            }
            .sortedWith(
                compareByDescending<Judged> { it.checkPoints + it.backingPoints }
                    .thenByDescending { it.c.spyBeatSharePct ?: -1.0 }
                    .thenByDescending { it.c.bestScore ?: -1.0 }
                    .thenBy { it.c.symbol }
            )
            .take(SEATS)

        val rows = seated.mapIndexed { i, j ->
            MustBuyRow(
                rank = i + 1,
                symbol = j.c.symbol,
                name = j.c.name,
                price = j.c.price,
                dayChangePct = j.c.dayChangePct,
                checkPoints = round1(j.checkPoints),
                checkMax = round1(j.checkMax),
                backingPoints = round1(j.backingPoints),
                totalPoints = round1(j.checkPoints + j.backingPoints),
                passed = j.checks.count { it.state == CheckState.PASS },
                measured = j.checks.count { it.state != CheckState.UNMEASURED },
                checks = j.checks,
                backing = j.backing,
                sources = j.c.sources,
                beatSharePct = j.c.spyBeatSharePct,
                edgeEntry = j.c.spyEdgeEntry
            )
        }

        val notes = ArrayList<String>()
        if (rows.size < SEATS) {
            notes += String.format(
                Locale.US,
                "Only %d of the ten seats %s filled today — %d candidates were " +
                    "scanned and the bar (%.0f%%+ of the check weight measurable, " +
                    "half of it earned, no measured negative) does not bend to fill " +
                    "chairs.",
                rows.size, if (rows.size == 1) "is" else "are",
                candidates.size, MIN_MEASURED_SHARE * 100.0
            )
        }
        if (disqualified > 0) {
            notes += String.format(
                Locale.US,
                "%d candidate%s excluded on a measured negative — a bearish technique " +
                    "board, a lost SPY race, or a buy that would deepen concentration.",
                disqualified, if (disqualified == 1) " was" else "s were"
            )
        }
        if (unraced > 0) {
            notes += String.format(
                Locale.US,
                "%d candidate%s could not be raced against SPY — without the race the " +
                    "measurable weight falls under the %.0f%% bar, so an unraced " +
                    "listing cannot take a seat.",
                unraced, if (unraced == 1) "" else "s", MIN_MEASURED_SHARE * 100.0
            )
        }
        val anyMeasuredBacking = judged.any { j -> j.backing.any { it.measured } }
        notes += if (anyMeasuredBacking) {
            "Backing scales with each engine's own graded record — the Wilson 95% " +
                "floor of its win rate against the 50% coin flip, ×0.5 to ×2.0. " +
                "The most accurate engine backs hardest."
        } else {
            "No nominating engine holds ${EngineRecord.MIN_GRADED} graded calls yet — " +
                "every engine backs at ×1.0 until its own record earns more. " +
                "Every pick list now logs into the ledger; weights turn measured " +
                "as outcomes land."
        }

        return MustBuyReport(
            computedAt = now,
            scanned = candidates.size,
            disqualified = disqualified,
            rows = rows,
            notes = notes,
            caveat = caveat
        )
    }

    /**
     * The measured negatives that exclude a candidate outright — no tally of
     * green points buys past a red veto.
     */
    fun vetoes(c: MustBuyCandidate): List<String> {
        val out = ArrayList<String>(3)
        if (c.spyVerdict == TechniqueVerdict.BEARISH) {
            out += "SPY keeps the ticket — the index won the measured race"
        }
        if (c.techDirection == "BEARISH" && c.techTotal > 0) {
            out += "the technique board reads bearish"
        }
        if (c.noteKind == NoteKind.CONCENTRATION) {
            out += "the buy would deepen an already concentrated position"
        }
        return out
    }

    /**
     * One nominating engine's contribution: [NOMINATION_BASE] scaled by the
     * engine's own graded record. The multiplier is the Wilson 95% lower
     * bound of its win rate over the 50% coin flip — an engine measured at
     * 75% backs ×1.5, at 40% backs ×0.8 — clamped to [MULT_FLOOR]..[MULT_CEIL].
     * No graded record (or under [EngineRecord.MIN_GRADED] calls) backs ×1.0.
     */
    fun backing(sources: List<String>, record: EngineRecordReport?): List<SourceBacking> =
        sources.map { source ->
            val bucket = record?.buckets?.firstOrNull { it.kind == SOURCE_KINDS[source] }
            val wilson = bucket?.wilsonLowerPct
            val multiplier =
                if (wilson != null) (wilson / 50.0).coerceIn(MULT_FLOOR, MULT_CEIL)
                else 1.0
            SourceBacking(
                source = source,
                points = round1(NOMINATION_BASE * multiplier),
                multiplier = round1(multiplier),
                measured = wilson != null,
                gradedCalls = bucket?.graded ?: 0
            )
        }

    /** The eight weighted checks, in the order the card prints them. */
    fun checks(c: MustBuyCandidate): List<MustBuyCheck> {
        val out = ArrayList<MustBuyCheck>(CHECK_COUNT)

        out += when (c.spyGreen) {
            null -> MustBuyCheck(
                "Green-zone entry vs SPY", CheckState.UNMEASURED,
                "the race could not be run", WEIGHT_GREEN
            )
            true -> MustBuyCheck(
                "Green-zone entry vs SPY", CheckState.PASS,
                "price at or under " + money(c.spyEdgeEntry), WEIGHT_GREEN
            )
            false -> MustBuyCheck(
                "Green-zone entry vs SPY", CheckState.FAIL,
                "price above the soft-quartile edge of " + money(c.spyEdgeEntry),
                WEIGHT_GREEN
            )
        }

        out += when {
            c.spyBeatSharePct == null -> MustBuyCheck(
                "Beats SPY over the month", CheckState.UNMEASURED,
                "the race could not be run", WEIGHT_BEAT
            )
            c.spyBeatSharePct >= BeatSpyEngine.BEAT_BAR_PCT -> MustBuyCheck(
                "Beats SPY over the month", CheckState.PASS,
                String.format(Locale.US, "%.0f%% beat share", c.spyBeatSharePct),
                WEIGHT_BEAT
            )
            else -> MustBuyCheck(
                "Beats SPY over the month", CheckState.FAIL,
                String.format(
                    Locale.US, "%.0f%% beat share, under the %.0f%% bar",
                    c.spyBeatSharePct, BeatSpyEngine.BEAT_BAR_PCT
                ),
                WEIGHT_BEAT
            )
        }

        out += when {
            c.techDirection == null || c.techTotal <= 0 -> MustBuyCheck(
                "Technique board bullish", CheckState.UNMEASURED,
                "the board was not read for this pick", WEIGHT_BOARD
            )
            c.techDirection == "BULLISH" -> MustBuyCheck(
                "Technique board bullish", CheckState.PASS,
                "${c.techBullish} of ${c.techTotal} techniques", WEIGHT_BOARD
            )
            else -> MustBuyCheck(
                "Technique board bullish", CheckState.FAIL,
                "the board reads ${c.techDirection.lowercase(Locale.US)}", WEIGHT_BOARD
            )
        }

        out += when {
            !c.earningsKnown -> MustBuyCheck(
                "No earnings inside $EARNINGS_CLEAR_DAYS days", CheckState.UNMEASURED,
                "no report date could be read — unknown is not earnings-free",
                WEIGHT_EARNINGS
            )
            c.earningsInDays == null -> MustBuyCheck(
                "No earnings inside $EARNINGS_CLEAR_DAYS days", CheckState.PASS,
                "no upcoming report is scheduled", WEIGHT_EARNINGS
            )
            c.earningsInDays <= EARNINGS_CLEAR_DAYS -> MustBuyCheck(
                "No earnings inside $EARNINGS_CLEAR_DAYS days", CheckState.FAIL,
                if (c.earningsInDays <= 0) "reports today"
                else "reports in ${c.earningsInDays} day" +
                    (if (c.earningsInDays == 1) "" else "s"),
                WEIGHT_EARNINGS
            )
            else -> MustBuyCheck(
                "No earnings inside $EARNINGS_CLEAR_DAYS days", CheckState.PASS,
                "next report in ${c.earningsInDays} days", WEIGHT_EARNINGS
            )
        }

        out += when (c.noteKind) {
            NoteKind.DIVERSIFIES -> MustBuyCheck(
                "Fits the book", CheckState.PASS, "adds a sector the book lacks",
                WEIGHT_BOOK
            )
            NoteKind.HELD -> MustBuyCheck(
                "Fits the book", CheckState.PASS, "already held — adding, not opening",
                WEIGHT_BOOK
            )
            NoteKind.CONCENTRATION -> MustBuyCheck(
                "Fits the book", CheckState.FAIL, "would deepen concentration",
                WEIGHT_BOOK
            )
            null -> MustBuyCheck(
                "Fits the book", CheckState.PASS, "no conflict with current holdings",
                WEIGHT_BOOK
            )
        }

        out += when {
            c.analystRating == null -> MustBuyCheck(
                "Analysts rate it a buy", CheckState.UNMEASURED,
                "no consensus rating is carried", WEIGHT_ANALYST
            )
            c.analystRating <= ANALYST_BUY_BAR -> MustBuyCheck(
                "Analysts rate it a buy", CheckState.PASS,
                String.format(
                    Locale.US, "consensus %.1f of 5 (1 = strong buy)", c.analystRating
                ),
                WEIGHT_ANALYST
            )
            else -> MustBuyCheck(
                "Analysts rate it a buy", CheckState.FAIL,
                String.format(
                    Locale.US, "consensus %.1f of 5, past the %.1f bar",
                    c.analystRating, ANALYST_BUY_BAR
                ),
                WEIGHT_ANALYST
            )
        }

        out += when {
            c.bestScore == null -> MustBuyCheck(
                "Pick score ${SCORE_BAR.toInt()}+", CheckState.UNMEASURED,
                "no scored list carries it", WEIGHT_SCORE
            )
            c.bestScore >= SCORE_BAR -> MustBuyCheck(
                "Pick score ${SCORE_BAR.toInt()}+", CheckState.PASS,
                String.format(Locale.US, "best score %.0f of 100", c.bestScore),
                WEIGHT_SCORE
            )
            else -> MustBuyCheck(
                "Pick score ${SCORE_BAR.toInt()}+", CheckState.FAIL,
                String.format(Locale.US, "best score %.0f of 100", c.bestScore),
                WEIGHT_SCORE
            )
        }

        out += when {
            c.headlineSentiment == null -> MustBuyCheck(
                "Positive headline", CheckState.UNMEASURED,
                "no related headline was read", WEIGHT_HEADLINE
            )
            c.headlineSentiment > 0 -> MustBuyCheck(
                "Positive headline", CheckState.PASS,
                "newest headline reads positive", WEIGHT_HEADLINE
            )
            c.headlineSentiment < 0 -> MustBuyCheck(
                "Positive headline", CheckState.FAIL,
                "newest headline reads negative", WEIGHT_HEADLINE
            )
            else -> MustBuyCheck(
                "Positive headline", CheckState.FAIL,
                "newest headline reads neutral", WEIGHT_HEADLINE
            )
        }

        return out
    }

    private fun money(v: Double?): String =
        if (v == null || v <= 0.0) "an unmeasured level"
        else String.format(Locale.US, "$%,.2f", v)

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
