package com.aurum.invest.analytics

import java.util.Locale

/** One call the app's engines made, with its forward outcomes once scored. */
data class EngineCall(
    val kind: String,          // ADD / TRIM / EXIT / PICK
    val symbol: String,
    val ts: Long,
    val refPrice: Double,
    /** Price move over the 5 / 20 sessions AFTER the call, percent; null until scored. */
    val fwd5Pct: Double?,
    val fwd20Pct: Double?
)

/** One kind of call, graded. */
data class RecordBucket(
    val kind: String,
    val label: String,
    /** What "right" means for this kind — printed, never implied. */
    val winMeaning: String,
    val total: Int,            // every call logged
    val graded: Int,           // calls old enough to have a 20-session outcome
    val wins: Int,
    /** null under [EngineRecord.MIN_GRADED] graded calls — no rate on a handful. */
    val winRatePct: Double?,
    val wilsonLowerPct: Double?,
    val avgFwd20Pct: Double?,  // average forward move across graded calls
    val note: String
)

/** The engines' own report card — every call logged, scored, and graded. */
data class EngineRecordReport(
    val computedAt: Long,
    val buckets: List<RecordBucket>,
    val headline: String,
    val caveat: String
)

/**
 * The self-scoring ledger's grader: every ADD / TRIM / EXIT the wealth engine
 * issues and every next-session pick is logged with the price it was made at,
 * scored against what the name actually did 5 and 20 sessions later, and
 * graded here. An advisor whose calls aren't scored is asking for trust
 * instead of earning it — house law applies to the house.
 *
 * Integrity rules:
 *  - pure function; no I/O, never throws
 *  - "right" is defined per kind and printed: a buy-side call wins when the
 *    name ROSE over the next 20 sessions; a sell-side call wins when it FELL
 *  - below [MIN_GRADED] graded calls a bucket shows its count, never a rate —
 *    the Wilson floor guards the rest
 */
object EngineRecord {

    /** Below this many graded calls, no win rate is claimed. */
    const val MIN_GRADED = 10

    const val KIND_ADD = "ADD"
    const val KIND_TRIM = "TRIM"
    const val KIND_EXIT = "EXIT"
    const val KIND_PICK = "PICK"

    // Each pick list is its own engine and earns its own graded record —
    // the must-buy backing weights read these buckets, so accuracy is
    // measured per engine, never assumed.
    const val KIND_DAILY = "DAILY"
    const val KIND_ENTRY = "ENTRY"
    const val KIND_POWER = "POWER"
    const val KIND_WEEKLY = "WEEKLY"
    const val KIND_BUDGET = "BUDGET"

    private data class KindSpec(
        val kind: String,
        val label: String,
        val winMeaning: String,
        val winIsUp: Boolean
    )

    private val SPECS = listOf(
        KindSpec(
            KIND_PICK, "Next-session picks",
            "a pick wins when the name closed higher 20 sessions on", true
        ),
        KindSpec(
            KIND_ADD, "Add calls",
            "an add wins when the name rose over the next 20 sessions", true
        ),
        KindSpec(
            KIND_TRIM, "Trim calls",
            "a trim wins when the name fell after it — risk that never bit still cost nothing",
            false
        ),
        KindSpec(
            KIND_EXIT, "Exit calls",
            "an exit wins when the name kept falling — money the call saved", false
        ),
        // The pick lists, graded on the same 20-session clock whatever each
        // list's own horizon — the yardstick a buy is ultimately judged by.
        KindSpec(
            KIND_DAILY, "Daily picks",
            "a daily pick wins when the name closed higher 20 sessions on", true
        ),
        KindSpec(
            KIND_ENTRY, "Entry picks",
            "an entry pick wins when the name closed higher 20 sessions on", true
        ),
        KindSpec(
            KIND_POWER, "Power-hour picks",
            "a power pick wins when the name closed higher 20 sessions on", true
        ),
        KindSpec(
            KIND_WEEKLY, "Weekly picks",
            "a weekly pick wins when the name closed higher 20 sessions on", true
        ),
        KindSpec(
            KIND_BUDGET, "Under-$25 picks",
            "an under-$25 pick wins when the name closed higher 20 sessions on", true
        )
    )

    fun grade(calls: List<EngineCall>, now: Long = System.currentTimeMillis()): EngineRecordReport {
        val caveat = "Every call the engines make is logged at its live price and scored " +
            "against what actually happened next. Nothing is deleted, nothing is regraded."
        val byKind = calls.groupBy { it.kind }
        val buckets = ArrayList<RecordBucket>()
        for (spec in SPECS) {
            val all = byKind[spec.kind].orEmpty()
            if (all.isEmpty()) continue
            val graded = all.filter { it.fwd20Pct != null }
            val wins = graded.count { c ->
                val fwd = c.fwd20Pct ?: return@count false
                if (spec.winIsUp) fwd > 0.0 else fwd < 0.0
            }
            val rate = if (graded.size >= MIN_GRADED) wins * 100.0 / graded.size else null
            val wilson =
                if (graded.size >= MIN_GRADED) {
                    WealthEngine.wilsonLower(wins, graded.size) * 100.0
                } else null
            val avgFwd = graded.mapNotNull { it.fwd20Pct }
                .takeIf { it.isNotEmpty() }?.average()
            val note = when {
                graded.isEmpty() ->
                    "${all.size} logged — none old enough to grade yet (20 sessions must pass)."
                graded.size < MIN_GRADED -> String.format(
                    Locale.US,
                    "%d of %d graded — no rate is claimed under %d; the calls stand on record.",
                    graded.size, all.size, MIN_GRADED
                )
                else -> String.format(
                    Locale.US,
                    "%d of %d graded calls were right — at least %.0f%% with 95%% confidence.",
                    wins, graded.size, wilson ?: 0.0
                )
            }
            buckets += RecordBucket(
                kind = spec.kind,
                label = spec.label,
                winMeaning = spec.winMeaning,
                total = all.size,
                graded = graded.size,
                wins = wins,
                winRatePct = rate?.let { round1(it) },
                wilsonLowerPct = wilson?.let { round1(it) },
                avgFwd20Pct = avgFwd?.let { round1(it) },
                note = note
            )
        }
        val gradedTotal = buckets.sumOf { it.graded }
        val headline = when {
            calls.isEmpty() ->
                "No calls logged yet — the record starts with the next engine run."
            gradedTotal == 0 -> String.format(
                Locale.US,
                "%d call%s logged, none old enough to grade — outcomes land after 20 sessions.",
                calls.size, if (calls.size == 1) "" else "s"
            )
            else -> {
                val rated = buckets.filter { it.winRatePct != null }
                if (rated.isEmpty()) String.format(
                    Locale.US,
                    "%d call%s graded so far — rates appear at %d per kind.",
                    gradedTotal, if (gradedTotal == 1) "" else "s", MIN_GRADED
                ) else String.format(
                    Locale.US,
                    "%d graded call%s on record — the rates below are measured, not promised.",
                    gradedTotal, if (gradedTotal == 1) "" else "s"
                )
            }
        }
        return EngineRecordReport(
            computedAt = now,
            buckets = buckets,
            headline = headline,
            caveat = caveat
        )
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
