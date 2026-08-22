package com.aurum.invest.analytics

import java.util.Locale

/**
 * The Must-Buy engine: every stock currently in the pick lists, put through
 * every read the app can actually measure — the pick scans, the technique
 * board, the Beat-SPY race, earnings proximity, analyst consensus, the news
 * read, and the fit against the user's own book — and ranked by how much of
 * that evidence lines up green at once.
 *
 * Integrity rules (house law):
 *  - pure function over assembled facts; no I/O, never throws
 *  - a fixed public bar: [MIN_PASSES] of the nine checks, and not one
 *    measured negative — a bearish board, a lost SPY race, or concentration
 *    excludes a name outright, whatever else it scores
 *  - an unmeasured check counts for NOTHING: it neither passes nor fails,
 *    and the row prints how much was actually measured
 *  - at most [SEATS] rows and never padded: fewer qualifiers means fewer
 *    rows, said plainly
 */

enum class CheckState { PASS, FAIL, UNMEASURED }

/** One measured check on one candidate — the label, the outcome, the number. */
data class MustBuyCheck(
    val label: String,
    val state: CheckState,
    val detail: String
)

/** Every fact the caller could assemble about one nominated stock. */
data class MustBuyCandidate(
    val symbol: String,
    val name: String,
    val price: Double,
    val dayChangePct: Double?,
    /** Which pick lists nominated it (Daily, Entries, Power, Weekly, Under $25). */
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

/** One seated stock: the rank, the tally, and every check laid open. */
data class MustBuyRow(
    val rank: Int,
    val symbol: String,
    val name: String,
    val price: Double,
    val dayChangePct: Double?,
    /** Checks passed / checks that could be measured, of [MustBuyEngine.CHECK_COUNT]. */
    val passed: Int,
    val measured: Int,
    val checks: List<MustBuyCheck>,
    val sources: List<String>,
    val beatSharePct: Double?,
    val edgeEntry: Double?
)

data class MustBuyReport(
    val computedAt: Long,
    /** Candidates scanned — every stock the pick lists carried. */
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

    /** Checks a candidate must pass to take a seat. */
    const val MIN_PASSES = 5

    /** The nine measured checks every candidate faces. */
    const val CHECK_COUNT = 9

    /** A report inside this window is an unhedgeable coin flip — the check fails. */
    const val EARNINGS_CLEAR_DAYS = 7

    /** Fixed bars, printed with the checks so the scale never drifts. */
    const val SCORE_BAR = 70.0
    const val ANALYST_BUY_BAR = 2.5

    fun build(
        candidates: List<MustBuyCandidate>,
        now: Long = System.currentTimeMillis()
    ): MustBuyReport {
        val caveat = "Ten seats, nine measured checks, three vetoes — the strongest " +
            "convergence of what the app can measure today, not a promise about " +
            "tomorrow. Decision support, not financial advice."

        data class Judged(val c: MustBuyCandidate, val checks: List<MustBuyCheck>)

        var disqualified = 0
        var unraced = 0
        val judged = ArrayList<Judged>(candidates.size)
        for (c in candidates) {
            if (c.spyGreen == null) unraced++
            if (vetoes(c).isNotEmpty()) {
                disqualified++
                continue
            }
            judged += Judged(c, checks(c))
        }

        val seated = judged
            .map { j ->
                val passed = j.checks.count { it.state == CheckState.PASS }
                val measured = j.checks.count { it.state != CheckState.UNMEASURED }
                Triple(j, passed, measured)
            }
            .filter { (_, passed, _) -> passed >= MIN_PASSES }
            .sortedWith(
                compareByDescending<Triple<Judged, Int, Int>> { it.second }
                    .thenByDescending { it.first.c.spyBeatSharePct ?: -1.0 }
                    .thenByDescending { it.first.c.bestScore ?: -1.0 }
                    .thenBy { it.first.c.symbol }
            )
            .take(SEATS)

        val rows = seated.mapIndexed { i, (j, passed, measured) ->
            MustBuyRow(
                rank = i + 1,
                symbol = j.c.symbol,
                name = j.c.name,
                price = j.c.price,
                dayChangePct = j.c.dayChangePct,
                passed = passed,
                measured = measured,
                checks = j.checks,
                sources = j.c.sources,
                beatSharePct = j.c.spyBeatSharePct,
                edgeEntry = j.c.spyEdgeEntry
            )
        }

        val notes = ArrayList<String>()
        if (rows.size < SEATS) {
            notes += String.format(
                Locale.US,
                "Only %d of the ten seats %s filled today — %d candidates were scanned " +
                    "and the bar (%d of %d checks, no measured negative) does not bend " +
                    "to fill chairs.",
                rows.size, if (rows.size == 1) "is" else "are",
                candidates.size, MIN_PASSES, CHECK_COUNT
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
                "%d candidate%s could not be raced against SPY — unmeasured checks " +
                    "count for nothing, so young listings start behind, never ahead.",
                unraced, if (unraced == 1) "" else "s"
            )
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
     * green checks buys past a red one.
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

    /** The nine checks, in the order the card prints them. */
    fun checks(c: MustBuyCandidate): List<MustBuyCheck> {
        val out = ArrayList<MustBuyCheck>(CHECK_COUNT)

        out += MustBuyCheck(
            label = "Nominated by more than one scan",
            state = if (c.sources.size >= 2) CheckState.PASS else CheckState.FAIL,
            detail = if (c.sources.isEmpty()) "no list carries it"
            else c.sources.joinToString(" · ")
        )

        out += when {
            c.techDirection == null || c.techTotal <= 0 -> MustBuyCheck(
                "Technique board bullish", CheckState.UNMEASURED,
                "the board was not read for this pick"
            )
            c.techDirection == "BULLISH" -> MustBuyCheck(
                "Technique board bullish", CheckState.PASS,
                "${c.techBullish} of ${c.techTotal} techniques"
            )
            else -> MustBuyCheck(
                "Technique board bullish", CheckState.FAIL,
                "the board reads ${c.techDirection.lowercase(Locale.US)}"
            )
        }

        out += when (c.spyGreen) {
            null -> MustBuyCheck(
                "Green-zone entry vs SPY", CheckState.UNMEASURED,
                "the race could not be run"
            )
            true -> MustBuyCheck(
                "Green-zone entry vs SPY", CheckState.PASS,
                "price at or under " + money(c.spyEdgeEntry)
            )
            false -> MustBuyCheck(
                "Green-zone entry vs SPY", CheckState.FAIL,
                "price above the soft-quartile edge of " + money(c.spyEdgeEntry)
            )
        }

        out += when {
            c.spyBeatSharePct == null -> MustBuyCheck(
                "Beats SPY over the month", CheckState.UNMEASURED,
                "the race could not be run"
            )
            c.spyBeatSharePct >= BeatSpyEngine.BEAT_BAR_PCT -> MustBuyCheck(
                "Beats SPY over the month", CheckState.PASS,
                String.format(Locale.US, "%.0f%% beat share", c.spyBeatSharePct)
            )
            else -> MustBuyCheck(
                "Beats SPY over the month", CheckState.FAIL,
                String.format(
                    Locale.US, "%.0f%% beat share, under the %.0f%% bar",
                    c.spyBeatSharePct, BeatSpyEngine.BEAT_BAR_PCT
                )
            )
        }

        out += when {
            !c.earningsKnown -> MustBuyCheck(
                "No earnings inside $EARNINGS_CLEAR_DAYS days", CheckState.UNMEASURED,
                "no report date could be read — unknown is not earnings-free"
            )
            c.earningsInDays == null -> MustBuyCheck(
                "No earnings inside $EARNINGS_CLEAR_DAYS days", CheckState.PASS,
                "no upcoming report is scheduled"
            )
            c.earningsInDays <= EARNINGS_CLEAR_DAYS -> MustBuyCheck(
                "No earnings inside $EARNINGS_CLEAR_DAYS days", CheckState.FAIL,
                if (c.earningsInDays <= 0) "reports today"
                else "reports in ${c.earningsInDays} day" +
                    (if (c.earningsInDays == 1) "" else "s")
            )
            else -> MustBuyCheck(
                "No earnings inside $EARNINGS_CLEAR_DAYS days", CheckState.PASS,
                "next report in ${c.earningsInDays} days"
            )
        }

        out += when (c.noteKind) {
            NoteKind.DIVERSIFIES -> MustBuyCheck(
                "Fits the book", CheckState.PASS, "adds a sector the book lacks"
            )
            NoteKind.HELD -> MustBuyCheck(
                "Fits the book", CheckState.PASS, "already held — adding, not opening"
            )
            NoteKind.CONCENTRATION -> MustBuyCheck(
                "Fits the book", CheckState.FAIL, "would deepen concentration"
            )
            null -> MustBuyCheck(
                "Fits the book", CheckState.PASS, "no conflict with current holdings"
            )
        }

        out += when {
            c.analystRating == null -> MustBuyCheck(
                "Analysts rate it a buy", CheckState.UNMEASURED,
                "no consensus rating is carried"
            )
            c.analystRating <= ANALYST_BUY_BAR -> MustBuyCheck(
                "Analysts rate it a buy", CheckState.PASS,
                String.format(Locale.US, "consensus %.1f of 5 (1 = strong buy)", c.analystRating)
            )
            else -> MustBuyCheck(
                "Analysts rate it a buy", CheckState.FAIL,
                String.format(Locale.US, "consensus %.1f of 5, past the %.1f bar",
                    c.analystRating, ANALYST_BUY_BAR)
            )
        }

        out += when {
            c.bestScore == null -> MustBuyCheck(
                "Pick score ${SCORE_BAR.toInt()}+", CheckState.UNMEASURED,
                "no scored list carries it"
            )
            c.bestScore >= SCORE_BAR -> MustBuyCheck(
                "Pick score ${SCORE_BAR.toInt()}+", CheckState.PASS,
                String.format(Locale.US, "best score %.0f of 100", c.bestScore)
            )
            else -> MustBuyCheck(
                "Pick score ${SCORE_BAR.toInt()}+", CheckState.FAIL,
                String.format(Locale.US, "best score %.0f of 100", c.bestScore)
            )
        }

        out += when {
            c.headlineSentiment == null -> MustBuyCheck(
                "Positive headline", CheckState.UNMEASURED, "no related headline was read"
            )
            c.headlineSentiment > 0 -> MustBuyCheck(
                "Positive headline", CheckState.PASS, "newest headline reads positive"
            )
            c.headlineSentiment < 0 -> MustBuyCheck(
                "Positive headline", CheckState.FAIL, "newest headline reads negative"
            )
            else -> MustBuyCheck(
                "Positive headline", CheckState.FAIL, "newest headline reads neutral"
            )
        }

        return out
    }

    private fun money(v: Double?): String =
        if (v == null || v <= 0.0) "an unmeasured level"
        else String.format(Locale.US, "$%,.2f", v)
}
