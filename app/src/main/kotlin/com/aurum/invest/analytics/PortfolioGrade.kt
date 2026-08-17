package com.aurum.invest.analytics

import com.aurum.invest.core.Fmt
import com.aurum.invest.data.repo.InvestorProfile
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

    // The eight disciplines' FIXED point scales. They sum to exactly 100 when
    // every one is measurable, so a score means the same thing next month.
    // Changing any of these changes what a score means — bump the review cache
    // key when you do.
    private const val MAX_CONCENTRATION = 18
    private const val MAX_LOSS = 14
    private const val MAX_WINNERS = 16
    private const val MAX_TREND = 14
    private const val MAX_RS = 14
    private const val MAX_FLOW = 8
    private const val MAX_REGIME = 8
    private const val MAX_RISK = 8

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
        strategy: WeeklyStrategy?,
        /** The SAME profile the advisor's verdicts were sized to — the grade must score the caps the advisor acts on. */
        policy: InvestorProfile = InvestorProfile.DEFAULT,
        /**
         * The money behind the book. Without it the risk-budget discipline is
         * excluded from the score and says so — a portfolio-heat figure with no
         * equity under it would be a fabrication.
         */
        equity: EquityContext = EquityContext.UNKNOWN
    ): PortfolioGrade {
        val candidates = candidates(verdicts, flow, strategy)
        // Share of the whole book the verdicts actually measured. Loss,
        // winner, and regime shares are computed within it — dividing by a
        // fictitious 100 would count every unverifiable holding as healthy.
        val measuredBase = verdicts.sumOf { it.weightPct }.coerceAtLeast(1e-9)
        val components = listOf(
            concentration(verdicts, book, candidates, policy),
            lossDiscipline(verdicts, policy, measuredBase),
            winners(verdicts, candidates, measuredBase, policy),
            trend(verdicts, candidates),
            relativeStrength(verdicts, candidates),
            flowAlignment(verdicts, candidates),
            regimeFit(verdicts, pulse, measuredBase),
            riskBudget(verdicts, equity, policy)
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
        candidates: List<Candidate>,
        policy: InvestorProfile
    ): GradeComponent {
        // The user's own caps, derived exactly as the advisor derives them —
        // the grade card and the holding cards must never disagree on what
        // "oversized" means.
        val positionCap = PortfolioAdvisor.positionCapPct(policy)
        val positionTrim = PortfolioAdvisor.positionTrimPct(policy)
        val sectorOverweight = PortfolioAdvisor.sectorOverweightPct(policy)
        val sectorTarget = PortfolioAdvisor.sectorTargetPct(policy)

        val top = verdicts.maxBy { it.weightPct }
        val topSector = book.slices.firstOrNull { it.sector != PortfolioLens.UNCLASSIFIED }
        fun pointsFor(topPos: Double, sectorW: Double?): Int {
            val posDed = when {
                topPos > positionTrim + 15.0 -> 13
                topPos > positionTrim -> 7
                else -> 0
            }
            val secDed = when {
                sectorW == null -> 0
                sectorW >= sectorOverweight + 15.0 -> 7
                sectorW >= sectorOverweight -> 4
                else -> 0
            }
            return (MAX_CONCENTRATION - posDed - secDed).coerceAtLeast(0)
        }

        val points = pointsFor(top.weightPct, topSector?.weightPct)
        val target = greenTarget(MAX_CONCENTRATION)
        val actions = ArrayList<GradeAction>()
        // Chained simulation state.
        var curPts = points
        var curTop = top.weightPct
        var curSector = topSector?.weightPct
        var base = 100.0
        if (points < target) {
            // Step 1 — trim the oversized position toward the cap.
            if (curTop > positionTrim && curTop < base) {
                val sellPct = curTop - positionCap
                val newBase = base - sellPct
                val newTop = positionCap / newBase * 100.0
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
                        title = "Trim ${top.symbol} toward your ${fmt0(positionCap)}% cap",
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
                        maxPoints = MAX_CONCENTRATION
                    )
                )
                curPts = after; curTop = newTop; curSector = newSector; base = newBase
            }
            // Step 2 — if the sector is still overweight, rotate its weakest
            // name into the best pick from a different theme.
            if (curPts < target && topSector != null && curSector != null &&
                curSector!! >= sectorOverweight
            ) {
                val weakest = verdicts.filter { it.sector == topSector.sector }
                    .minByOrNull { it.techConfidence }
                val buy = candidates.firstOrNull { it.pick.symbol !in topSector.symbols }
                if (weakest != null && curSector!! < 100.0) {
                    // Step 1 rescaled the book, so the weakest name's original
                    // whole-book weight must be re-based before mixing it with
                    // the current percentages.
                    val weakestW = weakest.weightPct / base * 100.0
                    val movePct = minOf(weakestW, curSector!! - sectorTarget)
                    val newSector: Double
                    val newTop: Double
                    if (buy != null) {
                        // Rotation: the book's size is unchanged.
                        newSector = (curSector!! - movePct).coerceAtLeast(0.0)
                        newTop =
                            if (weakest.symbol == top.symbol) (curTop - movePct).coerceAtLeast(0.0)
                            else curTop
                    } else {
                        // Sale to cash: the book shrinks, so every remaining
                        // weight — the top position included — scales UP.
                        val shrink = (1.0 - movePct / 100.0).coerceAtLeast(1e-9)
                        newSector = ((curSector!! - movePct) / shrink).coerceAtLeast(0.0)
                        newTop = (
                            if (weakest.symbol == top.symbol) (curTop - movePct).coerceAtLeast(0.0)
                            else curTop
                            ) / shrink
                    }
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
                            maxPoints = MAX_CONCENTRATION
                        )
                    )
                    curPts = after; curSector = newSector; curTop = newTop
                }
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, MAX_CONCENTRATION,
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
            principle = "Buffett concentrates with conviction — but no single bet may sink the book " +
                "(your position cap ${fmt0(positionCap)}%, sector cap ${fmt0(sectorOverweight)}%).",
            points = points, maxPoints = MAX_CONCENTRATION, measured = true, evidence = evidence,
            actions = actions, projectedPoints = curPts
        )
    }

    /** O'Neil: cut every loss fast, no exceptions — at the user's own threshold. */
    private fun lossDiscipline(
        verdicts: List<HoldingVerdict>,
        policy: InvestorProfile,
        measuredBase: Double
    ): GradeComponent {
        // The SAME loss rule the holding cards fire at — a conservative
        // profile's -6% and an aggressive one's -10% must grade differently.
        val cutLoss = PortfolioAdvisor.cutLossPct(policy)
        fun pointsFor(lossShare: Double): Int =
            (MAX_LOSS * (1.0 - (lossShare / 30.0).coerceIn(0.0, 1.0))).roundToInt()

        val losers = verdicts.filter { it.unrealizedPlPct <= cutLoss }.sortedBy { it.unrealizedPlPct }
        // Shares are of the MEASURED book — an unverifiable holding is not
        // silently counted as healthy weight in the denominator.
        val lossShare = losers.sumOf { it.weightPct } / measuredBase * 100.0
        val points = pointsFor(lossShare)
        val target = greenTarget(MAX_LOSS)
        val actions = ArrayList<GradeAction>()
        // Chained simulation: sell deep losers worst-first until green.
        var curPts = points
        var lossAbs = losers.sumOf { it.weightPct }
        var sold = 0.0
        for (loser in losers) {
            if (curPts >= target || actions.size >= MAX_STEPS) break
            if (loser.weightPct >= measuredBase - sold) break
            val newSold = sold + loser.weightPct
            val newLossAbs = lossAbs - loser.weightPct
            val newShare = newLossAbs / (measuredBase - newSold) * 100.0
            val after = pointsFor(newShare)
            actions.add(
                GradeAction(
                    kind = GradeActionKind.SELL,
                    title = "Sell ${loser.symbol} — your ${fmt0(-cutLoss)}% loss rule fired",
                    detail = String.format(
                        Locale.US,
                        "%s is down %.1f%% and holds %.0f%% of the book. After this step the " +
                            "underwater share is %.0f%% of the remaining measured book.",
                        loser.symbol, -loser.unrealizedPlPct, loser.weightPct, newShare
                    ),
                    symbol = loser.symbol,
                    movePct = round1(loser.weightPct),
                    pointsNow = curPts,
                    pointsAfter = after,
                    maxPoints = MAX_LOSS
                )
            )
            curPts = after; lossAbs = newLossAbs; sold = newSold
        }
        if (points < target && curPts < target) {
            actions.add(
                topsOut(
                    curPts, MAX_LOSS,
                    "The remaining underwater weight sits in positions too large to exit in one " +
                        "step — keep applying your ${fmt0(-cutLoss)}% rule as their cards direct."
                )
            )
        }
        val evidence =
            if (lossShare <= 0.0) "No position is more than ${fmt0(-cutLoss)}% underwater."
            else String.format(
                Locale.US, "%.0f%% of the measured book is more than %s%% underwater.",
                lossShare, fmt0(-cutLoss)
            )
        return GradeComponent(
            key = "loss",
            label = "Loss discipline",
            principle = "O'Neil's rule: cut every loss fast — your profile cuts at ${fmt0(-cutLoss)}%, " +
                "so small losses never become big ones.",
            points = points, maxPoints = MAX_LOSS, measured = true, evidence = evidence,
            actions = actions, projectedPoints = curPts
        )
    }

    /**
     * Livermore: the big money is made sitting tight on winners — and O'Neil's
     * other half, that the average win must dwarf the average loss.
     *
     * This discipline measures RIDING, not merely owning. Three sub-bands, each
     * dropped from BOTH sides of the scale when it cannot be measured:
     *
     *  a) 6 pts — the win/loss size ratio: average % gain across winners over
     *     average % loss across losers. This is the number that separates a
     *     book that lets winners run from one that snips gains and nurses
     *     losses. Needs at least one winner AND one loser to exist at all.
     *  b) 6 pts — how much of the measured book is working (4) and whether the
     *     three biggest slots are the winners (2). Elite books are weighted
     *     toward what is already working, not toward what needs to recover.
     *  c) 4 pts — the share of winning weight still holding its own 50-day
     *     line: a winner below its trend is not being ridden, it is being
     *     hoped over. Needs a measurable 50-day on at least one winner.
     */
    private fun winners(
        verdicts: List<HoldingVerdict>,
        candidates: List<Candidate>,
        measuredBase: Double,
        policy: InvestorProfile
    ): GradeComponent {
        val principle =
            "Livermore and O'Neil together: sit tight on winners, cut losers fast — the average " +
                "win must be worth several average losses (your loss rule cuts at " +
                "${fmt0(-PortfolioAdvisor.cutLossPct(policy))}%)."
        val holdings = verdicts.map {
            Rider(it.symbol, it.unrealizedPlPct, it.weightPct, it.above50)
        }
        if (holdings.isEmpty() || measuredBase <= 0.0) {
            return GradeComponent(
                key = "winners", label = "Winners riding", principle = principle,
                points = 0, maxPoints = MAX_WINNERS, measured = false,
                evidence = "No holding could be measured this run."
            )
        }

        val now = rideScore(holdings, measuredBase)
        if (now.max <= 0) {
            return GradeComponent(
                key = "winners", label = "Winners riding", principle = principle,
                points = 0, maxPoints = MAX_WINNERS, measured = false,
                evidence = "Nothing measurable about how this book rides its winners yet: " +
                    now.dropped.joinToString("; ") + "."
            )
        }
        val target = greenTarget(now.max)
        val actions = ArrayList<GradeAction>()

        // Chained simulation. At each step every legal exit is scored on the
        // SAME arithmetic and the best one is taken — no move is proposed
        // unless it provably raises the discipline.
        var live = holdings
        var liveBase = measuredBase
        var curPts = now.points
        var buyIndex = 0
        while (curPts < target && actions.size < MAX_STEPS && live.size > 1) {
            val best = live
                .mapNotNull { h ->
                    if (h.weightPct >= liveBase) return@mapNotNull null
                    val remaining = live.filter { it.symbol != h.symbol }
                    val newBase = liveBase - h.weightPct
                    if (newBase <= 0.0) return@mapNotNull null
                    val after = rideScore(remaining, newBase)
                    // Only an exit that lifts the score on today's numbers.
                    if (after.max <= 0 || after.points <= curPts) null
                    else Triple(h, after, newBase)
                }
                .maxByOrNull { it.second.points }
                ?: break
            val (holding, after, newBase) = best
            val buy = candidates.getOrNull(buyIndex)
            val why = when {
                holding.plPct <= 0.0 && holding.above50 == false ->
                    "a loser below its own 50-day line — the exact position the loss rule exists for"
                holding.plPct <= 0.0 -> "dead weight the winners are carrying"
                holding.above50 == false ->
                    "a winner that has already lost its 50-day line, so it is no longer being ridden"
                else -> "the position holding this discipline back on today's numbers"
            }
            actions.add(
                GradeAction(
                    kind = GradeActionKind.SELL,
                    title = "Exit ${holding.symbol} — $why",
                    detail = String.format(
                        Locale.US,
                        "%s is %+.1f%% on %.0f%% of the book. With the proceeds held as cash the " +
                            "discipline scores %d/%d: %s. %s",
                        holding.symbol, holding.plPct, holding.weightPct,
                        after.points, after.max, after.evidence,
                        buy?.let {
                            "Redeploy option: buy ${it.pick.symbol} — ${buyLine(it)} It counts " +
                                "toward this discipline only once it is in profit."
                        } ?: ""
                    ).trim(),
                    symbol = holding.symbol,
                    buySymbol = buy?.pick?.symbol ?: "",
                    buyName = buy?.pick?.name ?: "",
                    buyEntry = buy?.pick?.entry ?: 0.0,
                    movePct = round1(holding.weightPct),
                    pointsNow = curPts,
                    pointsAfter = after.points,
                    maxPoints = now.max
                )
            )
            curPts = after.points
            live = live.filter { it.symbol != holding.symbol }
            liveBase = newBase
            if (buy != null) buyIndex++
        }
        if (curPts < target) {
            actions.add(
                topsOut(
                    curPts, now.max,
                    "No further exit lifts this discipline on today's numbers — the rest arrives " +
                        "as winners run and the average win pulls away from the average loss. " +
                        "Selling a winner would lower this score, not raise it."
                )
            )
        }

        return GradeComponent(
            key = "winners", label = "Winners riding", principle = principle,
            points = now.points, maxPoints = now.max, measured = true,
            evidence = now.evidence, actions = actions, projectedPoints = curPts
        )
    }

    /** One holding reduced to what the winners-riding maths needs. */
    private data class Rider(
        val symbol: String,
        val plPct: Double,
        val weightPct: Double,
        val above50: Boolean?
    )

    private data class RideScore(
        val points: Int,
        val max: Int,
        val evidence: String,
        val dropped: List<String>
    )

    /**
     * The winners-riding arithmetic on today's numbers. Sub-bands that cannot
     * be measured are removed from BOTH sides — never scored at a midpoint.
     */
    private fun rideScore(list: List<Rider>, measuredBase: Double): RideScore {
        if (list.isEmpty() || measuredBase <= 0.0) {
            return RideScore(0, 0, "No measurable holdings.", listOf("every sub-band"))
        }
        var pts = 0.0
        var max = 0.0
        val dropped = ArrayList<String>()
        val facts = ArrayList<String>()

        val winners = list.filter { it.plPct > 0.0 }
        val losers = list.filter { it.plPct < 0.0 }

        // (a) the ratio that defines the discipline.
        val avgWin = if (winners.isNotEmpty()) winners.map { it.plPct }.average() else null
        val avgLoss = if (losers.isNotEmpty()) losers.map { -it.plPct }.average() else null
        if (avgWin != null && avgLoss != null && avgLoss > 1e-9) {
            max += 6.0
            val ratio = avgWin / avgLoss
            pts += when {
                ratio >= 3.0 -> 6.0
                ratio >= 2.0 -> 4.5
                ratio >= 1.5 -> 3.0
                ratio >= 1.0 -> 1.5
                else -> 0.0
            }
            facts += String.format(
                Locale.US,
                "average win %+.1f%% against average loss %.1f%% — a %.1f:1 ratio",
                avgWin, avgLoss, ratio
            )
        } else {
            dropped += if (winners.isEmpty()) "the win/loss ratio (no position is in profit)"
            else "the win/loss ratio (no position is underwater to compare against)"
        }

        // (b) how much of the book is working, and whether it leads.
        max += 6.0
        val profitShare = winners.sumOf { it.weightPct } / measuredBase * 100.0
        pts += 4.0 * (profitShare / 60.0).coerceIn(0.0, 1.0)
        val top3 = list.sortedByDescending { it.weightPct }.take(3)
        val top3W = top3.sumOf { it.weightPct }
        val top3WinW = top3.filter { it.plPct > 0.0 }.sumOf { it.weightPct }
        if (top3W > 1e-9) pts += 2.0 * (top3WinW / top3W).coerceIn(0.0, 1.0)
        facts += String.format(
            Locale.US,
            "%.0f%% of the measured book is in profit and %.0f%% of the three largest slots are winners",
            profitShare, if (top3W > 1e-9) top3WinW / top3W * 100.0 else 0.0
        )

        // (c) are the winners actually still being ridden?
        val ridable = winners.filter { it.above50 != null }
        val ridableW = ridable.sumOf { it.weightPct }
        if (ridableW > 1e-9) {
            max += 4.0
            val intact = ridable.filter { it.above50 == true }.sumOf { it.weightPct }
            pts += 4.0 * (intact / ridableW).coerceIn(0.0, 1.0)
            facts += String.format(
                Locale.US, "%.0f%% of the winning weight still holds its 50-day line",
                intact / ridableW * 100.0
            )
        } else {
            dropped += "whether the winners still hold their 50-day lines"
        }

        val evidence = facts.joinToString("; ").replaceFirstChar { it.uppercase() } +
            (if (dropped.isEmpty()) "." else ". Not measured: ${dropped.joinToString("; ")}.")
        return RideScore(pts.roundToInt(), max.roundToInt(), evidence, dropped)
    }

    /**
     * Position sizing before stock picking: every stop has a dollar cost, and
     * the sum of those costs is the portfolio's heat. A book can be right about
     * every stock and still be wrong about how much it has bet.
     */
    private fun riskBudget(
        verdicts: List<HoldingVerdict>,
        equity: EquityContext,
        policy: InvestorProfile
    ): GradeComponent {
        val budget = PortfolioVerdictEngine.riskBudgetPct(policy)
        val principle = String.format(
            Locale.US,
            "Risk before reward: your %.1f%% per trade allows about %.0f%% of equity at risk across " +
                "the whole book at once.",
            policy.riskPerTradePct, budget
        )
        fun unmeasured(why: String) = GradeComponent(
            key = "risk", label = "Risk budget", principle = principle,
            points = 0, maxPoints = MAX_RISK, measured = false, evidence = why
        )
        if (equity.equity <= 0.0) {
            return unmeasured(
                "Equity is not tracked this run, so no share of the account can be claimed. " +
                    "Set your wallet total and this discipline starts scoring."
            )
        }
        val risked = verdicts.mapNotNull { v -> v.riskAtStop?.let { v to it } }
        if (risked.size < verdicts.size || risked.isEmpty()) {
            return unmeasured(
                "${risked.size} of ${verdicts.size} holdings have a measurable stop — portfolio " +
                    "heat is only honest when every position's risk is known."
            )
        }
        fun pointsFor(heat: Double): Int = when {
            heat <= budget -> MAX_RISK
            heat <= budget * 1.5 -> 5
            heat <= budget * 2.0 -> 2
            else -> 0
        }

        val perTradeValue = equity.equity * policy.riskPerTradePct / 100.0
        var heatValue = risked.sumOf { it.second }
        var curPts = pointsFor(heatValue / equity.equity * 100.0)
        val points = curPts
        val target = greenTarget(MAX_RISK)
        val actions = ArrayList<GradeAction>()
        // Chained simulation: bring the heaviest risk contributors down to one
        // full per-trade unit each. That is arithmetic on today's stops, not a
        // forecast of prices.
        val heavy = risked.filter { it.second > perTradeValue * 1.05 }
            .sortedByDescending { it.second }
        for ((v, atRisk) in heavy) {
            if (curPts >= target || actions.size >= MAX_STEPS) break
            val release = atRisk - perTradeValue
            val newHeat = (heatValue - release).coerceAtLeast(0.0)
            val after = pointsFor(newHeat / equity.equity * 100.0)
            val trimPct = if (atRisk > 0.0) (release / atRisk * 100.0).coerceIn(0.0, 100.0) else 0.0
            actions.add(
                GradeAction(
                    kind = GradeActionKind.TRIM,
                    title = String.format(
                        Locale.US, "Trim %s by %.0f%% — its stop risks %s",
                        v.symbol, trimPct, AllocationMath.money(atRisk)
                    ),
                    detail = String.format(
                        Locale.US,
                        "%s risks %s to its %s stop, %.1f× your %s per-trade unit (%s). Cutting " +
                            "the position by %.0f%% brings the book's heat from %.1f%% to %.1f%% of equity.",
                        v.symbol, AllocationMath.money(atRisk), Fmt.money(v.stop),
                        atRisk / perTradeValue.coerceAtLeast(1e-9),
                        String.format(Locale.US, "%.1f%%", policy.riskPerTradePct),
                        AllocationMath.money(perTradeValue), trimPct,
                        heatValue / equity.equity * 100.0, newHeat / equity.equity * 100.0
                    ),
                    symbol = v.symbol,
                    movePct = round1(v.weightPct * trimPct / 100.0),
                    pointsNow = curPts,
                    pointsAfter = after,
                    maxPoints = MAX_RISK
                )
            )
            curPts = after
            heatValue = newHeat
        }
        if (curPts < target) {
            actions.add(
                topsOut(
                    curPts, MAX_RISK,
                    "The remaining heat is spread across positions already at or under one " +
                        "per-trade unit — lowering it further means holding fewer names, or " +
                        "waiting for the stops to trail up beneath the winners."
                )
            )
        }
        val heat0 = risked.sumOf { it.second } / equity.equity * 100.0
        return GradeComponent(
            key = "risk", label = "Risk budget", principle = principle,
            points = points, maxPoints = MAX_RISK, measured = true,
            evidence = String.format(
                Locale.US,
                "Every stop together risks %s — %.1f%% of %s equity against a %.0f%% budget.",
                AllocationMath.money(risked.sumOf { it.second }), heat0,
                AllocationMath.money(equity.equity), budget
            ),
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
                points = 0, maxPoints = MAX_TREND, measured = false,
                evidence = "No holding has enough history for a 50-day read yet."
            )
        }
        fun pointsFor(aboveShare: Double): Int =
            (MAX_TREND * aboveShare.coerceIn(0.0, 1.0)).roundToInt()
        val aboveW = measurable.filter { it.above50 == true }.sumOf { it.weightPct }
        val points = pointsFor(aboveW / mw)
        val target = greenTarget(MAX_TREND)
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
                        maxPoints = MAX_TREND
                    )
                )
                curPts = after; curMw = newMw
                if (buy != null) buyIndex++
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, MAX_TREND,
                        "The remaining below-trend weight cannot be exited in these steps — " +
                            "re-check as holdings reclaim their 50-day lines."
                    )
                )
            }
        }
        return GradeComponent(
            key = "trend", label = "Trend alignment", principle = principle,
            points = points, maxPoints = MAX_TREND, measured = true,
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
                points = 0, maxPoints = MAX_RS, measured = false,
                evidence = "No measured S&P 500 baseline this run."
            )
        }
        fun pointsFor(weighted: Double): Int =
            (MAX_RS * ((weighted + 5.0) / 10.0).coerceIn(0.0, 1.0)).roundToInt()

        var weightedSum = measurable.sumOf { it.rel20Pct!! * it.weightPct }
        val weighted = weightedSum / mw
        val points = pointsFor(weighted)
        val target = greenTarget(MAX_RS)
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
                        maxPoints = MAX_RS
                    )
                )
                curPts = after; weightedSum = newSum; buyIndex++
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, MAX_RS,
                        "Today's board-approved candidates cannot lift the weighted relative " +
                            "strength further — re-check when the sector scan surfaces stronger names."
                    )
                )
            }
        }
        return GradeComponent(
            key = "rs", label = "Relative strength", principle = principle,
            points = points, maxPoints = MAX_RS, measured = true,
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
                points = 0, maxPoints = MAX_FLOW, measured = false,
                evidence = "No holding maps to a measured sector flow this run."
            )
        }
        fun pointsFor(inflowW: Double, outflowW: Double): Int =
            (MAX_FLOW * (((inflowW - outflowW) / mw).coerceIn(-1.0, 1.0) + 1.0) / 2.0).roundToInt()

        val inflowW0 = mapped.filter { it.flowVerdictName == FlowVerdict.INFLOW.name }
            .sumOf { it.weightPct }
        val outflowW0 = mapped.filter { it.flowVerdictName == FlowVerdict.OUTFLOW.name }
            .sumOf { it.weightPct }
        val points = pointsFor(inflowW0, outflowW0)
        val target = greenTarget(MAX_FLOW)
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
                // Only a measurably inflowing candidate justifies a ROTATE —
                // falling back to any candidate would pair "rotate into X"
                // with a neutral-flow name the principle doesn't endorse.
                val buy = inflowBuys.getOrNull(buyIndex) ?: inflowBuys.lastOrNull()
                val buyIsInflow = buy != null
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
                        maxPoints = MAX_FLOW
                    )
                )
                curPts = after; inflowW = newInflow; outflowW = newOutflow
                if (buyIndex < inflowBuys.size - 1) buyIndex++
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, MAX_FLOW,
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
            points = points, maxPoints = MAX_FLOW, measured = true,
            evidence = String.format(
                Locale.US,
                "Of the flow-mapped book, %.0f%% sits in inflow sectors and %.0f%% in outflow sectors.",
                inflowW0 / mw * 100.0, outflowW0 / mw * 100.0
            ),
            actions = actions, projectedPoints = curPts
        )
    }

    /** Livermore: trade with the tape — fight the market and it collects. */
    private fun regimeFit(
        verdicts: List<HoldingVerdict>,
        pulse: MarketRating?,
        measuredBase: Double
    ): GradeComponent {
        val principle = "Livermore: trade with the tape, never against it."
        if (pulse == null) {
            return GradeComponent(
                key = "regime", label = "Regime fit", principle = principle,
                points = 0, maxPoints = MAX_REGIME, measured = false,
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
                points = 0, maxPoints = MAX_REGIME, measured = false,
                evidence = "The market pulse had too little measured data for a call."
            )
        }
        // Non-INCOMPLETE calls always carry a score; the null case above
        // already returned, so this read is a contract check, not a fallback.
        val pulseScore = pulse.score ?: return GradeComponent(
            key = "regime", label = "Regime fit", principle = principle,
            points = 0, maxPoints = MAX_REGIME, measured = false,
            evidence = "The market pulse carried no measured score."
        )
        fun pointsFor(bearShare: Double): Int =
            (MAX_REGIME * (1.0 - (bearShare * multiplier).coerceIn(0.0, 1.0))).roundToInt()

        val bearish = verdicts.filter { it.techDirection == TechniqueVerdict.BEARISH }
            .sortedByDescending { it.weightPct }
        // Of the MEASURED book — unverifiable weight is not assumed friendly.
        val bearShare0 = (bearish.sumOf { it.weightPct } / measuredBase).coerceIn(0.0, 1.0)
        val points = pointsFor(bearShare0)
        val target = greenTarget(MAX_REGIME)
        val actions = ArrayList<GradeAction>()
        // Chained simulation: exit bearish-board names largest-first.
        var curPts = points
        var bearAbs = bearish.sumOf { it.weightPct }
        var sold = 0.0
        if (points < target) {
            for (holding in bearish) {
                if (curPts >= target || actions.size >= MAX_STEPS) break
                if (holding.weightPct >= measuredBase - sold) break
                val newSold = sold + holding.weightPct
                val newBear =
                    ((bearAbs - holding.weightPct) / (measuredBase - newSold)).coerceIn(0.0, 1.0)
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
                            pulseScore, pulse.call.name.lowercase(Locale.US), newBear * 100.0
                        ),
                        symbol = holding.symbol,
                        movePct = round1(holding.weightPct),
                        pointsNow = curPts,
                        pointsAfter = after,
                        maxPoints = MAX_REGIME
                    )
                )
                curPts = after; bearAbs -= holding.weightPct; sold = newSold
            }
            if (curPts < target) {
                actions.add(
                    topsOut(
                        curPts, MAX_REGIME,
                        "The remaining bearish weight cannot be exited in these steps — " +
                            "their holding cards say exactly when to act."
                    )
                )
            }
        }
        return GradeComponent(
            key = "regime", label = "Regime fit", principle = principle,
            points = points, maxPoints = MAX_REGIME, measured = true,
            evidence = String.format(
                Locale.US,
                "Market pulse %d/100 (%s); %.0f%% of the book fights a bearish board.",
                pulseScore, pulse.call.name.lowercase(Locale.US), bearShare0 * 100.0
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
