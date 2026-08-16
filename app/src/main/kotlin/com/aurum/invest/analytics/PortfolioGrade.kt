package com.aurum.invest.analytics

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * A discipline is "in the green" only at or above this share of its points.
 * With whole points that means at least 17/20, 13/15, and 9/10 — anything
 * below carries an improvement plan.
 */
const val GRADE_GREEN_PCT = 85

/** What kind of move an improvement action is. */
enum class GradeActionKind { SELL, TRIM, ROTATE, BUY, REVIEW }

/**
 * One step of a discipline's improvement plan, with the arithmetic of what
 * the discipline scores once THIS step (and the steps before it) are
 * executed. Buy actions ALWAYS name the ticker in [buySymbol] — a buy
 * suggestion without a tag is not allowed.
 */
data class GradeAction(
    val kind: GradeActionKind,
    val title: String,            // "Sell PLUG", "Trim NVDA toward 22%", ...
    val detail: String,           // the measured reasoning, numbers included
    val symbol: String,           // the holding the action operates on; "" for general guidance
    val buySymbol: String = "",   // the explicit buy tag; "" when the action buys nothing
    val buyName: String = "",
    val buyEntry: Double = 0.0,   // the pick's measured good entry; 0 when none
    val movePct: Double = 0.0,    // share of the book the action moves
    val pointsNow: Int,           // the discipline's points before this step
    val pointsAfter: Int,         // the discipline's points once this step is executed
    val maxPoints: Int
)

/**
 * One graded discipline: the rule it comes from, the points earned on a FIXED
 * scale, the measured evidence, and the step-by-step plan that lifts it to
 * the green line (>= [GRADE_GREEN_PCT]% of its points: 17/20, 13/15, 9/10).
 * [projectedPoints] is where the discipline lands once EVERY listed step is
 * executed — checked against the green target, never assumed.
 */
data class GradeComponent(
    val key: String,
    val label: String,
    val principle: String,        // the elite-investor rule being applied, named
    val points: Int,
    val maxPoints: Int,
    val measured: Boolean,        // false -> excluded from the score, said out loud
    val evidence: String,         // the concrete numbers behind the points
    val actions: List<GradeAction> = emptyList(),
    val projectedPoints: Int = points
) {
    /** In the green: nothing to fix for this discipline. */
    val green: Boolean
        get() = measured && maxPoints > 0 && points * 100 >= maxPoints * GRADE_GREEN_PCT

    /** True when executing the whole plan provably reaches the green line. */
    val planReachesGreen: Boolean
        get() = measured && maxPoints > 0 && projectedPoints * 100 >= maxPoints * GRADE_GREEN_PCT
}

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
                        put("projected", c.projectedPoints)
                        put("actions", JSONArray().apply {
                            c.actions.forEach { a ->
                                put(JSONObject().apply {
                                    put("kind", a.kind.name); put("title", a.title)
                                    put("detail", a.detail); put("symbol", a.symbol)
                                    put("buySymbol", a.buySymbol); put("buyName", a.buyName)
                                    put("buyEntry", a.buyEntry); put("movePct", a.movePct)
                                    put("now", a.pointsNow); put("after", a.pointsAfter)
                                    put("maxPts", a.maxPoints)
                                })
                            }
                        })
                    })
                }
            })
        }

        fun fromJson(o: JSONObject): PortfolioGrade? = try {
            val components = ArrayList<GradeComponent>()
            o.optJSONArray("components")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val actions = ArrayList<GradeAction>()
                    c.optJSONArray("actions")?.let { acts ->
                        for (j in 0 until acts.length()) {
                            val a = acts.optJSONObject(j) ?: continue
                            actions.add(
                                GradeAction(
                                    kind = runCatching {
                                        GradeActionKind.valueOf(a.optString("kind"))
                                    }.getOrDefault(GradeActionKind.REVIEW),
                                    title = a.optString("title", ""),
                                    detail = a.optString("detail", ""),
                                    symbol = a.optString("symbol", ""),
                                    buySymbol = a.optString("buySymbol", ""),
                                    buyName = a.optString("buyName", ""),
                                    buyEntry = a.optDouble("buyEntry", 0.0),
                                    movePct = a.optDouble("movePct", 0.0),
                                    pointsNow = a.optInt("now", 0),
                                    pointsAfter = a.optInt("after", 0),
                                    maxPoints = a.optInt("maxPts", 0)
                                )
                            )
                        }
                    }
                    val points = c.optInt("points", 0)
                    components.add(
                        GradeComponent(
                            key = c.getString("key"),
                            label = c.optString("label", ""),
                            principle = c.optString("principle", ""),
                            points = points,
                            maxPoints = c.optInt("max", 0),
                            measured = c.optBoolean("measured", true),
                            evidence = c.optString("evidence", ""),
                            actions = actions,
                            projectedPoints = c.optInt("projected", points)
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
 * The standalone portfolio-grade engine: scores the verified book on FIXED
 * scales against seven named elite-investor disciplines, then runs the
 * suggestion engine — a step-by-step simulation that keeps proposing the next
 * best measured move until the discipline provably reaches its green target
 * (17/20, 13/15, 9/10) or today's book and candidates are exhausted, in which
 * case the plan states exactly where it tops out.
 *
 * Awareness comes from the measured outputs of the other engines, never from
 * guesses: the market pulse (index moves, breadth, VIX), the money-flow
 * report (Chaikin/MFI/OBV volume equations per sector), the sector-gap
 * strategy (trending themes with 35-board-approved picks carrying momentum,
 * volume ratio, news tone, and a measured entry), and each holding's own
 * technical verdict and headline tone.
 *
 * Integrity rules:
 *  - every component sits on a FIXED point scale, so 72 means the same thing
 *    next month
 *  - a discipline that cannot be measured is excluded from BOTH sides of the
 *    score and labeled "not measured" — never defaulted
 *  - every step's points-after is arithmetic on today's measured numbers,
 *    chained through the steps before it — never a prediction of prices
 *  - a plan that cannot reach the green line with today's candidates says so
 *    in a REVIEW step instead of overpromising
 *  - a buy suggestion ALWAYS names the ticker, and only board-approved picks
 *    from measurably inflowing or trending themes qualify
 *  - the comparison is against elite investors' PUBLISHED rules; their live
 *    holdings are not public in real time and are never pretended at
 */
object PortfolioGradeEngine {

    private const val GREEN_PCT = GRADE_GREEN_PCT
    private const val MAX_STEPS = 4
    private const val MAX_CANDIDATES = 4

    /** The smallest whole-point score that clears the green line for [max]. */
    private fun greenTarget(max: Int): Int = (max * GREEN_PCT + 99) / 100

    /** A board-approved buy candidate with its theme's measured flow context. */
    private data class Candidate(
        val pick: SectorPick,
        val themeLabel: String,
        val flowScore: Int,
        val flowVerdict: FlowVerdict?,
        val rel20: Double?           // pick 20d return minus SPY's; null without a baseline
    )

    fun evaluate(
        verdicts: List<HoldingVerdict>,
        book: BookContext,
        flow: MoneyFlowReport?,
        pulse: MarketRating?,
        strategy: WeeklyStrategy?
    ): PortfolioGrade {
        val candidates = candidates(verdicts, flow, strategy)
        val components = listOf(
            concentration(verdicts, book, candidates),
            lossDiscipline(verdicts),
            winners(verdicts, candidates),
            trend(verdicts, candidates),
            relativeStrength(verdicts, candidates),
            flowAlignment(verdicts, candidates),
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
            suggestion = suggestion(components)
        )
    }

    private fun band(ratioPct: Int): String = when {
        ratioPct >= 85 -> "Elite discipline"
        ratioPct >= 70 -> "Professional"
        ratioPct >= 55 -> "Solid, with gaps"
        ratioPct >= 40 -> "Needs work"
        else -> "At risk"
    }

    // ------------------------------------------------------------ candidates

    /**
     * Board-approved buy candidates from the sector-gap engine, best first:
     * measurably inflowing themes lead, then flow score, then board votes.
     * Held symbols are excluded — the engine never suggests buying what the
     * book already owns.
     */
    private fun candidates(
        verdicts: List<HoldingVerdict>,
        flow: MoneyFlowReport?,
        strategy: WeeklyStrategy?
    ): List<Candidate> {
        val held = verdicts.map { it.symbol }.toSet()
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        val gaps = strategy?.gaps.orEmpty().sortedWith(
            compareByDescending<SectorGap> { it.flowVerdict == FlowVerdict.INFLOW }
                .thenByDescending { it.flowScore }
        )
        for (gap in gaps) {
            if (gap.flowVerdict == FlowVerdict.OUTFLOW) continue
            for (pick in gap.picks) {
                if (pick.symbol in held || !seen.add(pick.symbol)) continue
                out.add(
                    Candidate(
                        pick = pick,
                        themeLabel = gap.label,
                        flowScore = gap.flowScore,
                        flowVerdict = gap.flowVerdict,
                        rel20 = flow?.let { pick.r20Pct - it.spyR20Pct }
                    )
                )
            }
        }
        return out
            .sortedWith(
                compareByDescending<Candidate> { it.flowVerdict == FlowVerdict.INFLOW }
                    .thenByDescending { it.flowScore }
                    .thenByDescending {
                        if (it.pick.techTotal > 0) it.pick.techBullish * 100 / it.pick.techTotal else 0
                    }
            )
            .take(MAX_CANDIDATES)
    }

    private fun buyLine(c: Candidate): String {
        val board =
            if (c.pick.techTotal > 0) "${c.pick.techBullish} of ${c.pick.techTotal} techniques bullish"
            else "board-approved"
        val flowBit =
            if (c.flowScore >= 0) "${c.themeLabel} flow ${c.flowScore}/100" else c.themeLabel
        val volumeBit =
            if (c.pick.volumeRatio > 0.0) {
                String.format(Locale.US, ", %.1fx volume", c.pick.volumeRatio)
            } else ""
        val newsBit = if (c.pick.newsScore != 0) {
            ", news tone ${if (c.pick.newsScore > 0) "+" else ""}${c.pick.newsScore}"
        } else ""
        return "$flowBit; $board$volumeBit$newsBit."
    }

    /** The honest closing step when a plan cannot reach the green line today. */
    private fun topsOut(points: Int, max: Int, why: String) = GradeAction(
        kind = GradeActionKind.REVIEW,
        title = "Tops out at $points/$max today",
        detail = "The green line for this discipline is ${greenTarget(max)}/$max. $why",
        symbol = "",
        pointsNow = points,
        pointsAfter = points,
        maxPoints = max
    )

    // ------------------------------------------------------------ components

    /** Buffett concentrates with conviction; Dalio caps what one bet can do. */
    private fun concentration(
        verdicts: List<HoldingVerdict>,
        book: BookContext,
        candidates: List<Candidate>
    ): GradeComponent {
        val top = verdicts.maxBy { it.weightPct }
        val topSector = book.slices.firstOrNull { it.sector != PortfolioLens.UNCLASSIFIED }
        fun pointsFor(topPos: Double, sectorW: Double?): Int {
            val posDed = when {
                topPos > 45.0 -> 14
                topPos > PortfolioAdvisor.POSITION_TRIM_PCT -> 8
                else -> 0
            }
            val secDed = when {
                sectorW == null -> 0
                sectorW >= 50.0 -> 8
                sectorW >= PortfolioAdvisor.SECTOR_OVERWEIGHT_PCT -> 4
                else -> 0
            }
            return (20 - posDed - secDed).coerceAtLeast(0)
        }

        val points = pointsFor(top.weightPct, topSector?.weightPct)
        val target = greenTarget(20)
        val actions = ArrayList<GradeAction>()
        // Chained simulation state.
        var curPts = points
        var curTop = top.weightPct
        var curSector = topSector?.weightPct
        var base = 100.0
        if (points < target) {
            // Step 1 — trim the oversized position toward the cap.
            if (curTop > PortfolioAdvisor.POSITION_TRIM_PCT && curTop < base) {
                val sellPct = curTop - PortfolioAdvisor.POSITION_CAP_PCT
                val newBase = base - sellPct
                val newTop = PortfolioAdvisor.POSITION_CAP_PCT / newBase * 100.0
                val newSector = curSector?.let {
                    if (top.sector == topSector?.sector) {
                        ((it - sellPct) / newBase * 100.0).coerceAtLeast(0.0)
                    } else {
                        it / newBase * 100.0
                    }
                }
                val after = pointsFor(newTop, newSector)
                actions.add(
                    GradeAction(
                        kind = GradeActionKind.TRIM,
                        title = "Trim ${top.symbol} toward the ${fmt0(PortfolioAdvisor.POSITION_CAP_PCT)}% cap",
                        detail = String.format(
                            Locale.US,
                            "%s is %.0f%% of the book. Selling %.0f%% of the book's value leaves it " +
                                "at %.0f%% of the remaining book.",
                            top.symbol, curTop, sellPct, newTop
                        ),
                        symbol = top.symbol,
                        movePct = round1(sellPct),
                        pointsNow = curPts,
                        pointsAfter = after,
                        maxPoints = 20
                    )
                )
                curPts = after; curTop = newTop; curSector = newSector; base = newBase
            }
            // Step 2 — if the sector is still overweight, rotate its weakest
            // name into the best pick from a different theme.
            if (curPts < target && topSector != null && curSector != null &&
                curSector!! >= PortfolioAdvisor.SECTOR_OVERWEIGHT_PCT
            ) {
                val weakest = verdicts.filter { it.sector == topSector.sector }
                    .minByOrNull { it.techConfidence }
                val buy = candidates.firstOrNull { it.pick.symbol !in topSector.symbols }
                if (weakest != null && curSector!! < 100.0) {
                    val movePct = minOf(
                        weakest.weightPct,
                        curSector!! - PortfolioAdvisor.SECTOR_TARGET_PCT
                    )
                    val newSector = curSector!! - movePct
                    val newTop =
                        if (weakest.symbol == top.symbol) (curTop - movePct).coerceAtLeast(0.0)
                        else curTop
                    val after = pointsFor(newTop, newSector)
                    actions.add(
                        GradeAction(
                            kind = if (buy != null) GradeActionKind.ROTATE else GradeActionKind.SELL,
                            title = buy?.let { "Rotate ${weakest.symbol} into ${it.pick.symbol}" }
                                ?: "Reduce ${weakest.symbol} to cash",
                            detail = String.format(
                                Locale.US,
                                "%s still holds %.0f%% of the book; moving %.0f%% out of %s (its " +
                                    "weakest board read) brings the sector to %.0f%%. %s",
                                topSector.sector, curSector, movePct, weakest.symbol, newSector,
                                buy?.let { "Buy ${it.pick.symbol} — ${buyLine(it)}" }
                                    ?: "No candidate passes the board right now — hold the proceeds in cash."
                            ),
                            symbol = weakest.symbol,
                            buySymbol = buy?.pick?.symbol ?: "",
                            buyName = buy?.pick?.name ?: "",
                            buyEntry = buy?.pick?.entry ?: 0.0,
                            movePct = round1(movePct),
                            pointsNow = curPts,
                            pointsAfter = after,
                            maxPoints = 20
                        )
                    )
                    curPts = after; curSector = newSector
                }
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, 20,
                        "A single-position book cannot spread further without new names — " +
                            "add a second position from the picks the other cards flag."
                    )
                )
            }
        }
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
            points = points, maxPoints = 20, measured = true, evidence = evidence,
            actions = actions, projectedPoints = curPts
        )
    }

    /** O'Neil: cut every loss at 7-8%, no exceptions. */
    private fun lossDiscipline(verdicts: List<HoldingVerdict>): GradeComponent {
        fun pointsFor(lossShare: Double): Int =
            (15.0 * (1.0 - (lossShare / 30.0).coerceIn(0.0, 1.0))).roundToInt()

        val losers = verdicts.filter { it.unrealizedPlPct <= -8.0 }.sortedBy { it.unrealizedPlPct }
        val lossShare = losers.sumOf { it.weightPct }
        val points = pointsFor(lossShare)
        val target = greenTarget(15)
        val actions = ArrayList<GradeAction>()
        // Chained simulation: sell deep losers worst-first until green.
        var curPts = points
        var lossAbs = lossShare
        var sold = 0.0
        for (loser in losers) {
            if (curPts >= target || actions.size >= MAX_STEPS) break
            if (loser.weightPct >= 100.0 - sold) break
            val newSold = sold + loser.weightPct
            val newLossAbs = lossAbs - loser.weightPct
            val newShare = newLossAbs / (100.0 - newSold) * 100.0
            val after = pointsFor(newShare)
            actions.add(
                GradeAction(
                    kind = GradeActionKind.SELL,
                    title = "Sell ${loser.symbol} — the 8% rule fired",
                    detail = String.format(
                        Locale.US,
                        "%s is down %.1f%% and holds %.0f%% of the book. After this step the " +
                            "underwater share is %.0f%% of the remaining book.",
                        loser.symbol, -loser.unrealizedPlPct, loser.weightPct, newShare
                    ),
                    symbol = loser.symbol,
                    movePct = round1(loser.weightPct),
                    pointsNow = curPts,
                    pointsAfter = after,
                    maxPoints = 15
                )
            )
            curPts = after; lossAbs = newLossAbs; sold = newSold
        }
        if (points < target && curPts < target) {
            actions.add(
                topsOut(
                    curPts, 15,
                    "The remaining underwater weight sits in positions too large to exit in one " +
                        "step — keep applying the 8% rule as their cards direct."
                )
            )
        }
        val evidence =
            if (lossShare <= 0.0) "No position is more than 8% underwater."
            else String.format(
                Locale.US, "%.0f%% of the book is more than 8%% underwater.", lossShare
            )
        return GradeComponent(
            key = "loss",
            label = "Loss discipline",
            principle = "O'Neil's rule: cut every loss at 7-8% — small losses never become big ones.",
            points = points, maxPoints = 15, measured = true, evidence = evidence,
            actions = actions, projectedPoints = curPts
        )
    }

    /** Livermore: the big money is made sitting tight on winning positions. */
    private fun winners(
        verdicts: List<HoldingVerdict>,
        candidates: List<Candidate>
    ): GradeComponent {
        fun pointsFor(profitShare: Double): Int =
            (15.0 * (profitShare / 60.0).coerceIn(0.0, 1.0)).roundToInt()

        val profitShare = verdicts.filter { it.unrealizedPlPct > 0.0 }.sumOf { it.weightPct }
        val points = pointsFor(profitShare)
        val target = greenTarget(15)
        val actions = ArrayList<GradeAction>()
        // Chained simulation: exit the largest non-winners; the arithmetic
        // holds the proceeds in cash — a redeploy counts only once it is
        // actually in profit, and each step says so.
        var curPts = points
        var sold = 0.0
        if (points < target) {
            val nonWinners = verdicts.filter { it.unrealizedPlPct <= 0.0 }
                .sortedByDescending { it.weightPct }
            var buyIndex = 0
            for (holding in nonWinners) {
                if (curPts >= target || actions.size >= MAX_STEPS) break
                if (holding.weightPct >= 100.0 - sold) break
                val newSold = sold + holding.weightPct
                val newShare = (profitShare / (100.0 - newSold) * 100.0).coerceAtMost(100.0)
                val after = pointsFor(newShare)
                val buy = candidates.getOrNull(buyIndex)
                actions.add(
                    GradeAction(
                        kind = GradeActionKind.SELL,
                        title = "Exit ${holding.symbol} — the largest non-winner",
                        detail = String.format(
                            Locale.US,
                            "%s (%.0f%% of the book, %+.1f%%) is dead weight. With the proceeds " +
                                "held as cash the winning share of the remaining book becomes " +
                                "%.0f%%. %s",
                            holding.symbol, holding.weightPct, holding.unrealizedPlPct, newShare,
                            buy?.let {
                                "Redeploy option: buy ${it.pick.symbol} — ${buyLine(it)} " +
                                    "It counts toward this discipline only once it is in profit."
                            } ?: ""
                        ).trim(),
                        symbol = holding.symbol,
                        buySymbol = buy?.pick?.symbol ?: "",
                        buyName = buy?.pick?.name ?: "",
                        buyEntry = buy?.pick?.entry ?: 0.0,
                        movePct = round1(holding.weightPct),
                        pointsNow = curPts,
                        pointsAfter = after,
                        maxPoints = 15
                    )
                )
                curPts = after; sold = newSold
                if (buy != null) buyIndex++
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, 15,
                        if (profitShare <= 0.0) {
                            "No position is in profit today — these points arrive only as " +
                                "holdings move into the green, not from any sale."
                        } else {
                            "The winning share cannot be lifted further by exits alone — the " +
                                "rest comes as positions turn profitable."
                        }
                    )
                )
            }
        }
        return GradeComponent(
            key = "winners",
            label = "Winners riding",
            principle = "Livermore: the big money is in sitting tight on winners — elite books stay weighted toward what is working.",
            points = points, maxPoints = 15, measured = true,
            evidence = String.format(Locale.US, "%.0f%% of the book is in profit.", profitShare),
            actions = actions, projectedPoints = curPts
        )
    }

    /** Weinstein: own stocks trading above their own 50-day line. */
    private fun trend(
        verdicts: List<HoldingVerdict>,
        candidates: List<Candidate>
    ): GradeComponent {
        val principle = "Weinstein's stage rule: own stocks above their own 50-day average."
        val measurable = verdicts.filter { it.above50 != null }
        val mw = measurable.sumOf { it.weightPct }
        if (mw <= 0.0) {
            return GradeComponent(
                key = "trend", label = "Trend alignment", principle = principle,
                points = 0, maxPoints = 15, measured = false,
                evidence = "No holding has enough history for a 50-day read yet."
            )
        }
        fun pointsFor(aboveShare: Double): Int = (15.0 * aboveShare.coerceIn(0.0, 1.0)).roundToInt()
        val aboveW = measurable.filter { it.above50 == true }.sumOf { it.weightPct }
        val points = pointsFor(aboveW / mw)
        val target = greenTarget(15)
        val actions = ArrayList<GradeAction>()
        // Chained simulation: exit below-trend names largest-first. A rotated
        // buy joins the trend read only once its own 50-day is measurable, so
        // the arithmetic drops the sold weight from the measurable set.
        var curPts = points
        var curMw = mw
        if (points < target) {
            val below = measurable.filter { it.above50 == false }
                .sortedByDescending { it.weightPct }
            var buyIndex = 0
            for (holding in below) {
                if (curPts >= target || actions.size >= MAX_STEPS) break
                if (curMw - holding.weightPct <= 0.0) break
                val newMw = curMw - holding.weightPct
                val newShare = aboveW / newMw
                val after = pointsFor(newShare)
                val buy = candidates.getOrNull(buyIndex)
                actions.add(
                    GradeAction(
                        kind = if (buy != null) GradeActionKind.ROTATE else GradeActionKind.SELL,
                        title = buy?.let { "Rotate ${holding.symbol} into ${it.pick.symbol}" }
                            ?: "Exit ${holding.symbol} — below its 50-day line",
                        detail = String.format(
                            Locale.US,
                            "%s (%.0f%% of the book) trades below its 50-day average. After this " +
                                "step %.0f%% of the measurable book is above trend. %s",
                            holding.symbol, holding.weightPct,
                            (newShare * 100.0).coerceAtMost(100.0),
                            buy?.let { "Buy ${it.pick.symbol} — ${buyLine(it)}" } ?: ""
                        ).trim(),
                        symbol = holding.symbol,
                        buySymbol = buy?.pick?.symbol ?: "",
                        buyName = buy?.pick?.name ?: "",
                        buyEntry = buy?.pick?.entry ?: 0.0,
                        movePct = round1(holding.weightPct),
                        pointsNow = curPts,
                        pointsAfter = after,
                        maxPoints = 15
                    )
                )
                curPts = after; curMw = newMw
                if (buy != null) buyIndex++
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, 15,
                        "The remaining below-trend weight cannot be exited in these steps — " +
                            "re-check as holdings reclaim their 50-day lines."
                    )
                )
            }
        }
        return GradeComponent(
            key = "trend", label = "Trend alignment", principle = principle,
            points = points, maxPoints = 15, measured = true,
            evidence = String.format(
                Locale.US, "%.0f%% of the measurable book trades above its 50-day average.",
                aboveW / mw * 100.0
            ),
            actions = actions, projectedPoints = curPts
        )
    }

    /** O'Neil again: relative strength — beat the index or own the index. */
    private fun relativeStrength(
        verdicts: List<HoldingVerdict>,
        candidates: List<Candidate>
    ): GradeComponent {
        val principle = "O'Neil: hold what beats the S&P 500 — lagging the index is a cost."
        val measurable = verdicts.filter { it.rel20Pct != null }
        val mw = measurable.sumOf { it.weightPct }
        if (mw <= 0.0) {
            return GradeComponent(
                key = "rs", label = "Relative strength", principle = principle,
                points = 0, maxPoints = 15, measured = false,
                evidence = "No measured S&P 500 baseline this run."
            )
        }
        fun pointsFor(weighted: Double): Int =
            (15.0 * ((weighted + 5.0) / 10.0).coerceIn(0.0, 1.0)).roundToInt()

        var weightedSum = measurable.sumOf { it.rel20Pct!! * it.weightPct }
        val weighted = weightedSum / mw
        val points = pointsFor(weighted)
        val target = greenTarget(15)
        val actions = ArrayList<GradeAction>()
        // Chained simulation: swap the worst-contributing laggers into the
        // strongest measured candidates, one candidate per step.
        var curPts = points
        if (points < target) {
            val laggers = measurable.sortedBy { it.rel20Pct!! * it.weightPct }
            val buys = candidates.filter { it.rel20 != null }
                .sortedByDescending { it.rel20!! }
            var buyIndex = 0
            for (lagger in laggers) {
                if (curPts >= target || actions.size >= MAX_STEPS) break
                val buy = buys.getOrNull(buyIndex) ?: break
                if (buy.rel20!! <= lagger.rel20Pct!!) break
                val newSum = weightedSum - lagger.rel20Pct!! * lagger.weightPct +
                    buy.rel20 * lagger.weightPct
                val newWeighted = newSum / mw
                val after = pointsFor(newWeighted)
                actions.add(
                    GradeAction(
                        kind = GradeActionKind.ROTATE,
                        title = "Rotate ${lagger.symbol} into ${buy.pick.symbol}",
                        detail = String.format(
                            Locale.US,
                            "%s runs %+.1fpp vs the S&P 500 over 20 days; %s runs %+.1fpp as " +
                                "measured today. After this step the book's weighted relative " +
                                "strength is %+.1fpp — today's numbers, not a prediction. " +
                                "Buy %s — %s",
                            lagger.symbol, lagger.rel20Pct, buy.pick.symbol, buy.rel20,
                            newWeighted, buy.pick.symbol, buyLine(buy)
                        ),
                        symbol = lagger.symbol,
                        buySymbol = buy.pick.symbol,
                        buyName = buy.pick.name,
                        buyEntry = buy.pick.entry,
                        movePct = round1(lagger.weightPct),
                        pointsNow = curPts,
                        pointsAfter = after,
                        maxPoints = 15
                    )
                )
                curPts = after; weightedSum = newSum; buyIndex++
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, 15,
                        "Today's board-approved candidates cannot lift the weighted relative " +
                            "strength further — re-check when the sector scan surfaces stronger names."
                    )
                )
            }
        }
        return GradeComponent(
            key = "rs", label = "Relative strength", principle = principle,
            points = points, maxPoints = 15, measured = true,
            evidence = String.format(
                Locale.US, "The book %s the S&P 500 by %.1fpp over 20 days, weight-averaged.",
                if (weighted >= 0) "beats" else "lags", abs(weighted)
            ),
            actions = actions, projectedPoints = curPts
        )
    }

    /** Follow the institutional money — hold inflow sectors, avoid outflows. */
    private fun flowAlignment(
        verdicts: List<HoldingVerdict>,
        candidates: List<Candidate>
    ): GradeComponent {
        val principle =
            "Minervini/institutional playbook: keep the book where the money is measurably flowing in."
        val mapped = verdicts.filter { it.flowVerdictName.isNotEmpty() }
        val mw = mapped.sumOf { it.weightPct }
        if (mw <= 0.0) {
            return GradeComponent(
                key = "flow", label = "Money-flow alignment", principle = principle,
                points = 0, maxPoints = 10, measured = false,
                evidence = "No holding maps to a measured sector flow this run."
            )
        }
        fun pointsFor(inflowW: Double, outflowW: Double): Int =
            (10.0 * (((inflowW - outflowW) / mw).coerceIn(-1.0, 1.0) + 1.0) / 2.0).roundToInt()

        val inflowW0 = mapped.filter { it.flowVerdictName == FlowVerdict.INFLOW.name }
            .sumOf { it.weightPct }
        val outflowW0 = mapped.filter { it.flowVerdictName == FlowVerdict.OUTFLOW.name }
            .sumOf { it.weightPct }
        val points = pointsFor(inflowW0, outflowW0)
        val target = greenTarget(10)
        val actions = ArrayList<GradeAction>()
        // Chained simulation: move outflow-sector weight into inflowing
        // themes, largest outflow holding first.
        var curPts = points
        var inflowW = inflowW0
        var outflowW = outflowW0
        if (points < target) {
            val leavingList = mapped.filter { it.flowVerdictName == FlowVerdict.OUTFLOW.name }
                .sortedByDescending { it.weightPct }
            val inflowBuys = candidates.filter { it.flowVerdict == FlowVerdict.INFLOW }
            var buyIndex = 0
            for (leaving in leavingList) {
                if (curPts >= target || actions.size >= MAX_STEPS) break
                val buy = inflowBuys.getOrNull(buyIndex) ?: inflowBuys.lastOrNull()
                ?: candidates.firstOrNull()
                val buyIsInflow = buy?.flowVerdict == FlowVerdict.INFLOW
                val newInflow = if (buyIsInflow) inflowW + leaving.weightPct else inflowW
                val newOutflow = (outflowW - leaving.weightPct).coerceAtLeast(0.0)
                val after = pointsFor(newInflow, newOutflow)
                actions.add(
                    GradeAction(
                        kind = if (buy != null) GradeActionKind.ROTATE else GradeActionKind.SELL,
                        title = buy?.let { "Rotate ${leaving.symbol} into ${it.pick.symbol}" }
                            ?: "Reduce ${leaving.symbol} — its sector is bleeding money",
                        detail = String.format(
                            Locale.US,
                            "%s sits in a sector money is measurably leaving. Moving its %.0f%% " +
                                "weight%s leaves the inflow/outflow split at %.0f%%/%.0f%% of the " +
                                "mapped book. %s",
                            leaving.symbol, leaving.weightPct,
                            if (buyIsInflow) " into an inflowing theme" else " to cash",
                            newInflow / mw * 100.0, newOutflow / mw * 100.0,
                            buy?.let { "Buy ${it.pick.symbol} — ${buyLine(it)}" } ?: ""
                        ).trim(),
                        symbol = leaving.symbol,
                        buySymbol = buy?.pick?.symbol ?: "",
                        buyName = buy?.pick?.name ?: "",
                        buyEntry = buy?.pick?.entry ?: 0.0,
                        movePct = round1(leaving.weightPct),
                        pointsNow = curPts,
                        pointsAfter = after,
                        maxPoints = 10
                    )
                )
                curPts = after; inflowW = newInflow; outflowW = newOutflow
                if (buyIndex < inflowBuys.size - 1) buyIndex++
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, 10,
                        if (inflowBuys.isEmpty()) {
                            "No measurably inflowing theme has a board-approved pick today — " +
                                "hold the proceeds in cash until one appears."
                        } else {
                            "The neutral-sector weight holds the split below the line — " +
                                "re-check as the flow report updates."
                        }
                    )
                )
            }
        }
        return GradeComponent(
            key = "flow", label = "Money-flow alignment", principle = principle,
            points = points, maxPoints = 10, measured = true,
            evidence = String.format(
                Locale.US,
                "Of the flow-mapped book, %.0f%% sits in inflow sectors and %.0f%% in outflow sectors.",
                inflowW0 / mw * 100.0, outflowW0 / mw * 100.0
            ),
            actions = actions, projectedPoints = curPts
        )
    }

    /** Livermore: trade with the tape — fight the market and it collects. */
    private fun regimeFit(verdicts: List<HoldingVerdict>, pulse: MarketRating?): GradeComponent {
        val principle = "Livermore: trade with the tape, never against it."
        if (pulse == null) {
            return GradeComponent(
                key = "regime", label = "Regime fit", principle = principle,
                points = 0, maxPoints = 10, measured = false,
                evidence = "The market pulse is unavailable this run."
            )
        }
        val multiplier = when (pulse.call) {
            MarketCall.INVEST -> 1.0
            MarketCall.SELECTIVE -> 1.5
            MarketCall.DEFENSIVE -> 2.0
            // No honest regime read -> this component is unmeasured, not scored.
            MarketCall.INCOMPLETE -> return GradeComponent(
                key = "regime", label = "Regime fit", principle = principle,
                points = 0, maxPoints = 10, measured = false,
                evidence = "The market pulse had too little measured data for a call."
            )
        }
        fun pointsFor(bearShare: Double): Int =
            (10.0 * (1.0 - (bearShare * multiplier).coerceIn(0.0, 1.0))).roundToInt()

        val bearish = verdicts.filter { it.techDirection == TechniqueVerdict.BEARISH }
            .sortedByDescending { it.weightPct }
        val bearShare0 = (bearish.sumOf { it.weightPct } / 100.0).coerceIn(0.0, 1.0)
        val points = pointsFor(bearShare0)
        val target = greenTarget(10)
        val actions = ArrayList<GradeAction>()
        // Chained simulation: exit bearish-board names largest-first.
        var curPts = points
        var bearAbs = bearShare0 * 100.0
        var sold = 0.0
        if (points < target) {
            for (holding in bearish) {
                if (curPts >= target || actions.size >= MAX_STEPS) break
                if (holding.weightPct >= 100.0 - sold) break
                val newSold = sold + holding.weightPct
                val newBear = ((bearAbs - holding.weightPct) / (100.0 - newSold)).coerceIn(0.0, 1.0)
                val after = pointsFor(newBear)
                actions.add(
                    GradeAction(
                        kind = GradeActionKind.SELL,
                        title = "Reduce ${holding.symbol} — bearish board in a ${pulse.call.name.lowercase(Locale.US)} tape",
                        detail = String.format(
                            Locale.US,
                            "%s reads bearish (%d of %d techniques bullish) while the market " +
                                "pulse is %d/100 (%s). After this step %.0f%% of the book fights " +
                                "a bearish board.",
                            holding.symbol, holding.techBullish, holding.techTotal,
                            pulse.score, pulse.call.name.lowercase(Locale.US), newBear * 100.0
                        ),
                        symbol = holding.symbol,
                        movePct = round1(holding.weightPct),
                        pointsNow = curPts,
                        pointsAfter = after,
                        maxPoints = 10
                    )
                )
                curPts = after; bearAbs -= holding.weightPct; sold = newSold
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, 10,
                        "The remaining bearish weight cannot be exited in these steps — " +
                            "their holding cards say exactly when to act."
                    )
                )
            }
        }
        return GradeComponent(
            key = "regime", label = "Regime fit", principle = principle,
            points = points, maxPoints = 10, measured = true,
            evidence = String.format(
                Locale.US,
                "Market pulse %d/100 (%s); %.0f%% of the book fights a bearish board.",
                pulse.score, pulse.call.name.lowercase(Locale.US), bearShare0 * 100.0
            ),
            actions = actions, projectedPoints = curPts
        )
    }

    // ------------------------------------------------------------ suggestion

    /** The weakest measured discipline's first step becomes the headline move. */
    private fun suggestion(components: List<GradeComponent>): String {
        val weakest = components
            .filter { it.measured && it.maxPoints > 0 }
            .minByOrNull { it.points.toDouble() / it.maxPoints }
            ?: return "Nothing could be measured this run — pull down to retry."
        if (weakest.green) {
            return "Every measured discipline is in the green — keep the routine and " +
                "re-check after the next session."
        }
        val first = weakest.actions.firstOrNull { it.kind != GradeActionKind.REVIEW }
            ?: return "${weakest.label} is the weakest discipline — its evidence line names the numbers."
        val plan =
            if (weakest.planReachesGreen) {
                "the full plan reaches ${weakest.projectedPoints}/${weakest.maxPoints} — in the green"
            } else {
                "the full plan tops out at ${weakest.projectedPoints}/${weakest.maxPoints} today"
            }
        return "${weakest.label} first: ${first.title} " +
            "(${first.pointsNow} → ${first.pointsAfter}/${first.maxPoints}); $plan. " +
            "Open the row below for every step."
    }

    // ------------------------------------------------------------ helpers

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
}
