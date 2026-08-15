package com.aurum.invest.analytics

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * One graded discipline: the rule it comes from, the points earned on a FIXED
 * scale, and the measured evidence behind them.
 */
data class GradeComponent(
    val key: String,
    val label: String,
    val principle: String,     // the elite-investor rule being applied, named
    val points: Int,
    val maxPoints: Int,
    val measured: Boolean,     // false -> excluded from the score, said out loud
    val evidence: String       // the concrete numbers behind the points
)

/**
 * The whole book graded against the published rules of elite investors.
 * [score] is earned points over [maxScore] measurable points — when every
 * discipline is measurable, maxScore is exactly 100.
 */
data class PortfolioGrade(
    val score: Int,
    val maxScore: Int,
    val band: String,
    val components: List<GradeComponent>,
    val suggestion: String
) {
    companion object {
        fun toJson(g: PortfolioGrade): JSONObject = JSONObject().apply {
            put("score", g.score)
            put("max", g.maxScore)
            put("band", g.band)
            put("suggestion", g.suggestion)
            put("components", JSONArray().apply {
                g.components.forEach { c ->
                    put(JSONObject().apply {
                        put("key", c.key); put("label", c.label)
                        put("principle", c.principle)
                        put("points", c.points); put("max", c.maxPoints)
                        put("measured", c.measured); put("evidence", c.evidence)
                    })
                }
            })
        }

        fun fromJson(o: JSONObject): PortfolioGrade? = try {
            val components = ArrayList<GradeComponent>()
            o.optJSONArray("components")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    components.add(
                        GradeComponent(
                            key = c.getString("key"),
                            label = c.optString("label", ""),
                            principle = c.optString("principle", ""),
                            points = c.optInt("points", 0),
                            maxPoints = c.optInt("max", 0),
                            measured = c.optBoolean("measured", true),
                            evidence = c.optString("evidence", "")
                        )
                    )
                }
            }
            PortfolioGrade(
                score = o.optInt("score", 0),
                maxScore = o.optInt("max", 100),
                band = o.optString("band", ""),
                components = components,
                suggestion = o.optString("suggestion", "")
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Grades the verified book against seven measurable disciplines, each drawn
 * from the published playbook of a well-known investor or trader. This is a
 * comparison against their RULES — their actual holdings are not public in
 * real time, and pretending otherwise would be a guess.
 *
 * Integrity rules:
 *  - every component sits on a FIXED point scale, so 72 means the same thing
 *    next month
 *  - a discipline that cannot be measured (no SPY baseline, no sector map,
 *    listing too young) is excluded from BOTH sides of the score and labeled
 *    "not measured" — never defaulted to a neutral number
 *  - every point total carries the concrete numbers that produced it
 */
object PortfolioGrader {

    /** Bands over the earned share of measurable points. */
    private fun band(ratioPct: Int): String = when {
        ratioPct >= 85 -> "Elite discipline"
        ratioPct >= 70 -> "Professional"
        ratioPct >= 55 -> "Solid, with gaps"
        ratioPct >= 40 -> "Needs work"
        else -> "At risk"
    }

    fun grade(
        verdicts: List<HoldingVerdict>,
        book: BookContext,
        flow: MoneyFlowReport?,
        pulse: MarketRating?
    ): PortfolioGrade {
        val components = listOf(
            concentration(verdicts, book),
            lossDiscipline(verdicts),
            winners(verdicts),
            trend(verdicts),
            relativeStrength(verdicts),
            flowAlignment(verdicts),
            regimeFit(verdicts, pulse)
        )
        val measured = components.filter { it.measured }
        val score = measured.sumOf { it.points }
        val maxScore = measured.sumOf { it.maxPoints }
        val ratioPct = if (maxScore > 0) score * 100 / maxScore else 0
        return PortfolioGrade(
            score = score,
            maxScore = maxScore,
            band = band(ratioPct),
            components = components,
            suggestion = suggestion(components, verdicts, pulse)
        )
    }

    // ------------------------------------------------------------ components

    /** Buffett concentrates with conviction; Dalio caps what one bet can do. */
    private fun concentration(verdicts: List<HoldingVerdict>, book: BookContext): GradeComponent {
        val top = verdicts.maxBy { it.weightPct }
        val topSector = book.slices.firstOrNull { it.sector != PortfolioLens.UNCLASSIFIED }
        val posDeduction = when {
            top.weightPct > 45.0 -> 14
            top.weightPct > PortfolioAdvisor.POSITION_TRIM_PCT -> 8
            else -> 0
        }
        val sectorDeduction = when {
            topSector == null -> 0
            topSector.weightPct >= 50.0 -> 8
            topSector.weightPct >= PortfolioAdvisor.SECTOR_OVERWEIGHT_PCT -> 4
            else -> 0
        }
        val points = (20 - posDeduction - sectorDeduction).coerceAtLeast(0)
        val evidence = String.format(
            Locale.US,
            "Largest position %s at %.0f%% of the book%s.",
            top.symbol, top.weightPct,
            topSector?.let {
                String.format(Locale.US, "; largest sector %s at %.0f%%", it.sector, it.weightPct)
            } ?: ""
        )
        return GradeComponent(
            key = "concentration",
            label = "Concentration control",
            principle = "Buffett concentrates with conviction — but no single bet may sink the book (position cap 30%, sector cap 35%).",
            points = points, maxPoints = 20, measured = true, evidence = evidence
        )
    }

    /** O'Neil: cut every loss at 7-8%, no exceptions. */
    private fun lossDiscipline(verdicts: List<HoldingVerdict>): GradeComponent {
        val lossShare = verdicts.filter { it.unrealizedPlPct <= -8.0 }.sumOf { it.weightPct }
        val points = (15.0 * (1.0 - (lossShare / 30.0).coerceIn(0.0, 1.0))).roundToInt()
        val evidence =
            if (lossShare <= 0.0) "No position is more than 8% underwater."
            else String.format(
                Locale.US, "%.0f%% of the book is more than 8%% underwater.", lossShare
            )
        return GradeComponent(
            key = "loss",
            label = "Loss discipline",
            principle = "O'Neil's rule: cut every loss at 7-8% — small losses never become big ones.",
            points = points, maxPoints = 15, measured = true, evidence = evidence
        )
    }

    /** Livermore: the big money is made sitting tight on winning positions. */
    private fun winners(verdicts: List<HoldingVerdict>): GradeComponent {
        val profitShare = verdicts.filter { it.unrealizedPlPct > 0.0 }.sumOf { it.weightPct }
        val points = (15.0 * (profitShare / 60.0).coerceIn(0.0, 1.0)).roundToInt()
        return GradeComponent(
            key = "winners",
            label = "Winners riding",
            principle = "Livermore: the big money is in sitting tight on winners — elite books stay weighted toward what is working.",
            points = points, maxPoints = 15, measured = true,
            evidence = String.format(Locale.US, "%.0f%% of the book is in profit.", profitShare)
        )
    }

    /** Weinstein: own stocks trading above their own 50-day line. */
    private fun trend(verdicts: List<HoldingVerdict>): GradeComponent {
        val measurable = verdicts.filter { it.above50 != null }
        val mw = measurable.sumOf { it.weightPct }
        if (mw <= 0.0) {
            return GradeComponent(
                key = "trend", label = "Trend alignment",
                principle = "Weinstein's stage rule: own stocks above their own 50-day average.",
                points = 0, maxPoints = 15, measured = false,
                evidence = "No holding has enough history for a 50-day read yet."
            )
        }
        val aboveShare = measurable.filter { it.above50 == true }.sumOf { it.weightPct } / mw
        return GradeComponent(
            key = "trend",
            label = "Trend alignment",
            principle = "Weinstein's stage rule: own stocks above their own 50-day average.",
            points = (15.0 * aboveShare).roundToInt(), maxPoints = 15, measured = true,
            evidence = String.format(
                Locale.US, "%.0f%% of the measurable book trades above its 50-day average.",
                aboveShare * 100.0
            )
        )
    }

    /** O'Neil again: relative strength — beat the index or own the index. */
    private fun relativeStrength(verdicts: List<HoldingVerdict>): GradeComponent {
        val measurable = verdicts.filter { it.rel20Pct != null }
        val mw = measurable.sumOf { it.weightPct }
        if (mw <= 0.0) {
            return GradeComponent(
                key = "rs", label = "Relative strength",
                principle = "O'Neil: hold what beats the S&P 500 — lagging the index is a cost.",
                points = 0, maxPoints = 15, measured = false,
                evidence = "No measured S&P 500 baseline this run."
            )
        }
        val weighted = measurable.sumOf { it.rel20Pct!! * it.weightPct } / mw
        val points = (15.0 * ((weighted + 5.0) / 10.0).coerceIn(0.0, 1.0)).roundToInt()
        return GradeComponent(
            key = "rs",
            label = "Relative strength",
            principle = "O'Neil: hold what beats the S&P 500 — lagging the index is a cost.",
            points = points, maxPoints = 15, measured = true,
            evidence = String.format(
                Locale.US, "The book %s the S&P 500 by %.1fpp over 20 days, weight-averaged.",
                if (weighted >= 0) "beats" else "lags", abs(weighted)
            )
        )
    }

    /** Follow the institutional money — hold inflow sectors, avoid outflows. */
    private fun flowAlignment(verdicts: List<HoldingVerdict>): GradeComponent {
        val mapped = verdicts.filter { it.flowVerdictName.isNotEmpty() }
        val mw = mapped.sumOf { it.weightPct }
        if (mw <= 0.0) {
            return GradeComponent(
                key = "flow", label = "Money-flow alignment",
                principle = "Minervini/institutional playbook: keep the book where the money is measurably flowing in.",
                points = 0, maxPoints = 10, measured = false,
                evidence = "No holding maps to a measured sector flow this run."
            )
        }
        val inflow = mapped.filter { it.flowVerdictName == FlowVerdict.INFLOW.name }.sumOf { it.weightPct }
        val outflow = mapped.filter { it.flowVerdictName == FlowVerdict.OUTFLOW.name }.sumOf { it.weightPct }
        val align = ((inflow - outflow) / mw).coerceIn(-1.0, 1.0)
        return GradeComponent(
            key = "flow",
            label = "Money-flow alignment",
            principle = "Minervini/institutional playbook: keep the book where the money is measurably flowing in.",
            points = (10.0 * (align + 1.0) / 2.0).roundToInt(), maxPoints = 10, measured = true,
            evidence = String.format(
                Locale.US,
                "Of the flow-mapped book, %.0f%% sits in inflow sectors and %.0f%% in outflow sectors.",
                inflow / mw * 100.0, outflow / mw * 100.0
            )
        )
    }

    /** Livermore: trade with the tape — fight the market and it collects. */
    private fun regimeFit(verdicts: List<HoldingVerdict>, pulse: MarketRating?): GradeComponent {
        if (pulse == null) {
            return GradeComponent(
                key = "regime", label = "Regime fit",
                principle = "Livermore: trade with the tape, never against it.",
                points = 0, maxPoints = 10, measured = false,
                evidence = "The market pulse is unavailable this run."
            )
        }
        val bearShare = (verdicts.filter { it.techDirection == TechniqueVerdict.BEARISH }
            .sumOf { it.weightPct } / 100.0).coerceIn(0.0, 1.0)
        val multiplier = when (pulse.call) {
            MarketCall.INVEST -> 1.0
            MarketCall.SELECTIVE -> 1.5
            MarketCall.DEFENSIVE -> 2.0
        }
        val points = (10.0 * (1.0 - (bearShare * multiplier).coerceIn(0.0, 1.0))).roundToInt()
        return GradeComponent(
            key = "regime",
            label = "Regime fit",
            principle = "Livermore: trade with the tape, never against it.",
            points = points, maxPoints = 10, measured = true,
            evidence = String.format(
                Locale.US,
                "Market pulse %d/100 (%s); %.0f%% of the book fights a bearish board.",
                pulse.score, pulse.call.name.lowercase(Locale.US), bearShare * 100.0
            )
        )
    }

    // ------------------------------------------------------------ suggestion

    /** The weakest measured discipline becomes the one concrete next step. */
    private fun suggestion(
        components: List<GradeComponent>,
        verdicts: List<HoldingVerdict>,
        pulse: MarketRating?
    ): String {
        val weakest = components
            .filter { it.measured && it.maxPoints > 0 }
            .minByOrNull { it.points.toDouble() / it.maxPoints }
            ?: return "Nothing could be measured this run — pull down to retry."
        if (weakest.points.toDouble() / weakest.maxPoints >= 0.8) {
            return "The book follows the elite rulebook on every measured discipline — " +
                "keep the routine and re-check after the next session."
        }
        return when (weakest.key) {
            "concentration" -> {
                val top = verdicts.maxBy { it.weightPct }
                String.format(
                    Locale.US,
                    "Reduce concentration first: %s alone is %.0f%% of the book. " +
                        "Work it back toward the %.0f%% cap — the allocation plan below shows the exact weights.",
                    top.symbol, top.weightPct, PortfolioAdvisor.POSITION_CAP_PCT
                )
            }
            "loss" -> {
                val worst = verdicts.filter { it.unrealizedPlPct <= -8.0 }
                    .minByOrNull { it.unrealizedPlPct }
                if (worst == null) "Tighten the loss rule: no position may sit more than 8% underwater."
                else String.format(
                    Locale.US,
                    "Apply the 8%% loss rule first: %s is down %.1f%% — its card above says exactly when to act.",
                    worst.symbol, -worst.unrealizedPlPct
                )
            }
            "winners" -> "Too little of the book is winning. Rotate losers toward the leaders " +
                "the next-session and sector cards flag — elite books stay weighted toward what works."
            "trend" -> {
                val below = verdicts.filter { it.above50 == false }.maxByOrNull { it.weightPct }
                if (below == null) "Keep the book above its 50-day lines."
                else String.format(
                    Locale.US,
                    "Fix trend alignment first: %s (%.0f%% of the book) trades below its 50-day average — " +
                        "elite books own stocks in uptrends.",
                    below.symbol, below.weightPct
                )
            }
            "rs" -> "The book lags the S&P 500 over 20 days. Before adding anything, demand it beat " +
                "the index — otherwise the index is the better position."
            "flow" -> "Too much of the book sits where money is measurably leaving. The money-flow " +
                "card shows which sectors capital is entering — rebalance toward them."
            "regime" -> String.format(
                Locale.US,
                "The tape is %s while part of the book reads bearish — reduce risk first; " +
                    "add only when the board and the tape agree.",
                pulse?.call?.name?.lowercase(Locale.US) ?: "weak"
            )
            else -> "Address the weakest discipline above — its evidence line names the numbers."
        }
    }
}
