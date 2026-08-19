package com.aurum.invest.analytics

import com.aurum.invest.core.Fmt
import com.aurum.invest.data.repo.InvestorProfile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What to do with one holding. */
enum class HoldingAction { HOLD, TAKE_PROFIT, TRIM, SELL, CUT_LOSS }

/** Where a holding sits in its own price cycle (Weinstein's four stages). */
enum class HoldingStage { ADVANCING, TOPPING, BASING, DECLINING, UNMEASURED }

/** One holding's full verdict: the action, the why, and the when. */
data class HoldingVerdict(
    val symbol: String,
    val name: String,
    val sector: String,            // Yahoo sector, or "Unclassified"
    val action: HoldingAction,
    val headline: String,          // one sentence: what to do
    val whenText: String,          // when to do it, concretely
    val whyPoints: List<String>,   // measured reasons, numbers included
    val price: Double,
    val avgCost: Double,
    val marketValue: Double,
    val weightPct: Double,         // share of the invested book
    val unrealizedPl: Double,
    val unrealizedPlPct: Double,
    val target: Double,            // forward take-profit level
    val stop: Double,              // forward protective stop
    val techBullish: Int,
    val techTotal: Int,
    val techConfidence: Int,
    val techDirection: TechniqueVerdict,
    val rsi: Double,
    val newsScore: Int,
    val newsNote: String,          // "" when nothing headline-worthy
    /** Latest session move %, from the live quote (or the last two daily closes); null when not measurable. */
    val sessionMovePct: Double? = null,
    /** Price at/above its own 50-day average; null when the listing is too young to measure. */
    val above50: Boolean? = null,
    /** 20-day return minus the S&P 500's, percentage points; null without a measured SPY baseline. */
    val rel20Pct: Double? = null,
    /** The holding's own sector money-flow verdict name (INFLOW/NEUTRAL/OUTFLOW); "" when unmapped. */
    val flowVerdictName: String = "",

    // ---- v8: the standalone verdict engine's own reads -------------------
    /**
     * Hold-conviction, earned points over [convictionMax] MEASURABLE points on
     * a fixed 100-point scale. A band that could not be measured is removed
     * from both sides rather than defaulted, so 62/78 always means the same
     * thing — and [convictionMax] says how much of the read was real.
     */
    val conviction: Int = 0,
    val convictionMax: Int = 0,
    /** Damage read on the same fixed-band basis: earned risk points over measurable ones. */
    val riskScore: Int = 0,
    val riskScoreMax: Int = 0,
    /** Where the holding sits in its own cycle. */
    val stage: HoldingStage = HoldingStage.UNMEASURED,
    /**
     * The ratcheting trail under a position that is being ridden — it never
     * moves down between runs. Null when no trail could be measured (the
     * defensive structural [stop] is then the only exit level).
     */
    val trailStop: Double? = null,
    /** % of the open gain the trail has locked above cost; null when the trail sits below cost. */
    val lockedGainPct: Double? = null,
    /** % from the price to the nearest MEASURED resistance above it; null when none was measured. */
    val runwayPct: Double? = null,
    /** Forward reward-to-risk from this price to [target] against [stop]; null when unmeasurable. */
    val riskReward: Double? = null,
    /** Dollars this position loses if it is exited at [stop] from here; null when the stop is above price. */
    val riskAtStop: Double? = null,
    /** [riskAtStop] as a share of total equity; null when equity is not tracked. */
    val riskAtStopEquityPct: Double? = null,
    /** The winner-riding sentence — "" when the holding is not a winner being ridden. */
    val ridingNote: String = "",
    /**
     * How much of THIS position the verdict leaves in place, as a percentage of
     * what is currently held: 0 for an exit, 50 for "bank half", ~67 for
     * "bank a third", the cap-implied share for a size trim, 100 for a hold.
     *
     * The allocation plan reads this instead of re-deriving the decision, so a
     * card that says "bank half" and a plan that says "reduce to 50%" can never
     * disagree.
     */
    val keepSharePct: Double = 100.0,
    /** Inputs that could not be measured this run, named. Empty when the read was complete. */
    val notMeasured: List<String> = emptyList()
)

/**
 * The account the verdicts are sized against — the engine is not allowed to
 * talk about dollars at risk without knowing the money behind them.
 *
 * [liquidity] is null when the user does not track a wallet total; equity then
 * falls back to the live book value and every equity-relative figure says so
 * instead of pretending the book is the whole account.
 */
data class EquityContext(
    /** Cost basis of the open positions — what of the account is committed. */
    val invested: Double,
    /** Live market value of the holdings the engine could actually measure. */
    val holdingsValue: Double,
    /** Uninvested cash; null when the wallet total is not tracked. */
    val liquidity: Double?,
    /** P/L already booked by closed trades. */
    val realizedPl: Double = 0.0
) {
    /** Total equity the caps and risk budget are measured against. */
    val equity: Double get() = (holdingsValue + (liquidity ?: 0.0)).coerceAtLeast(0.0)

    /** True when the cash side is known, so equity is the whole account. */
    val cashTracked: Boolean get() = liquidity != null

    /** Open unrealized P/L across the measured book. */
    val openPl: Double get() = holdingsValue - invested

    companion object {
        val UNKNOWN = EquityContext(0.0, 0.0, null, 0.0)
    }
}

/** The verdict engine's whole answer for the current book. */
data class PortfolioVerdictReport(
    val computedAt: Long,
    val headline: String,
    val verdicts: List<HoldingVerdict>,      // by market value, largest first
    /** Total risk-to-stop across the book as a share of equity; null when unmeasurable. */
    val openRiskPct: Double?,
    /** The account line: invested, cash, equity, open P/L — measured, or said to be unknown. */
    val equityNote: String,
    /** Inputs the engine could not measure this run, named once for the whole book. */
    val notes: List<String>,
    val policyNote: String,
    val caveat: String
)

/**
 * The standalone portfolio-verdict engine: the answer behind "Your portfolio —
 * the verdicts". Pure reasoning over pre-gathered evidence — no network I/O,
 * no clock beyond the stamp, no throws.
 *
 * It answers four questions per holding, in this order of authority:
 *   1. is the capital rule broken (cut it),
 *   2. was the stop this engine published last run broken (honour it),
 *   3. is a WINNER still worth riding (and where does its trail move to),
 *   4. does risk control require a trim.
 *
 * Integrity rules (the same ones [LiquidityAllocationEngine] is built on):
 *  - every band sits on a FIXED point scale; a band that could not be measured
 *    is removed from BOTH sides of the score and named in [HoldingVerdict.notMeasured]
 *    — never defaulted to a comfortable midpoint
 *  - every sentence cites the number behind it; no verdict is a mood
 *  - stops, targets and trails come from the stock's own structure (peak since
 *    entry, ATR, the 50-day, the measured supports) — never round numbers
 *  - the trail RATCHETS: a published stop never moves down between runs, so a
 *    "protected" gain cannot quietly un-protect itself
 *  - a profit percentage alone NEVER sells a winner. Taking profit requires a
 *    measured climax or measured deterioration; otherwise the position is
 *    ridden with a raised trail, and the card says what selling would give up
 *  - a cap trim is labelled as risk control, not as a verdict on the stock
 *  - dollars at risk are only printed when the equity behind them is known
 */
object PortfolioVerdictEngine {

    // ---- fixed scales (documented, not tuned per run) ----

    /** Conviction bands, total 100 when everything is measurable. */
    private const val B_STRUCTURE = 22.0
    private const val B_RELSTRENGTH = 18.0
    private const val B_BOARD = 16.0
    private const val B_MOMENTUM = 12.0
    private const val B_PARTICIPATION = 10.0
    private const val B_FLOW = 10.0
    private const val B_LEADERSHIP = 8.0
    private const val B_NEWS = 4.0

    /** Risk bands, total 100 when everything is measurable. */
    private const val R_LOSS = 25.0
    private const val R_DRAWDOWN = 20.0
    private const val R_TRENDBREAK = 20.0
    private const val R_BOARD = 15.0
    private const val R_FLOW = 10.0
    private const val R_DISTRIBUTION = 10.0

    /**
     * Below this share of the conviction scale being measurable, the read is
     * too thin to justify riding on conviction alone — the defensive rules
     * still apply and the card says the read was thin.
     */
    private const val MIN_COVERAGE = 55

    /**
     * Risk ratio at or above which a winner losing its trend banks half —
     * shaped by the investor's own horizon. A weeks-long trader banks on the
     * first real crack; a years-long investor sits through noise the trader
     * cannot afford to. The same book must not produce the same orders for
     * both, which is the whole point of carrying a profile.
     */
    private fun deteriorationRisk(policy: InvestorProfile): Int = when (policy.horizon) {
        InvestorProfile.HORIZON_SHORT -> 38
        InvestorProfile.HORIZON_LONG -> 55
        else -> 45
    }

    /**
     * How many ATRs of room the trail gives a winner: risk tolerance sets the
     * base, and the horizon widens or tightens it. A short-horizon conservative
     * trails at 1.5 ATR; a long-horizon aggressive gives the position 3.5.
     */
    internal fun trailAtrMultiple(policy: InvestorProfile): Double {
        val base = when (policy.riskTolerance) {
            InvestorProfile.TOL_CONSERVATIVE -> 2.0
            InvestorProfile.TOL_AGGRESSIVE -> 3.0
            else -> 2.5
        }
        val horizon = when (policy.horizon) {
            InvestorProfile.HORIZON_SHORT -> -0.5
            InvestorProfile.HORIZON_LONG -> 0.5
            else -> 0.0
        }
        return (base + horizon).coerceAtLeast(1.0)
    }

    /** Conviction ratio at or above which a winner is described as worth riding. */
    private const val RIDE_CONVICTION = 60

    // ------------------------------------------------------------- entry point

    /**
     * Pure function, never throws. [evidence] holds one entry per holding the
     * caller could actually measure; holdings that could not be measured are
     * the caller's business (they belong in the unverified list) — this engine
     * never invents one.
     */
    fun evaluate(
        evidence: List<HoldingEvidence>,
        equity: EquityContext,
        policy: InvestorProfile,
        pulse: MarketRating? = null
    ): PortfolioVerdictReport {
        val now = System.currentTimeMillis()
        val caveat =
            "Every level is computed from this stock's own structure — its peak since you bought " +
                "it, its 14-day ATR, its 50-day line and its measured supports — against your " +
                "profile's loss, profit and concentration rules. Decision support, not financial advice."

        val verdicts = evidence
            .filter { it.price > 0.0 && it.shares > 0.0 && it.symbol.isNotBlank() }
            .map { judge(it, equity, policy, pulse) }
            .sortedByDescending { it.marketValue }

        // ---- book-level risk: what every stop, taken together, would cost ----
        val riskedAll = verdicts.mapNotNull { it.riskAtStop }
        val openRiskPct =
            if (equity.equity > 0.0 && riskedAll.size == verdicts.size && verdicts.isNotEmpty()) {
                round1(riskedAll.sum() / equity.equity * 100.0)
            } else null

        val actions = verdicts.count { it.action != HoldingAction.HOLD }
        val riders = verdicts.count { it.ridingNote.isNotEmpty() }
        val headline = when {
            verdicts.isEmpty() -> "No holding could be measured this run."
            actions == 0 && riders > 0 ->
                "Nothing to sell — $riders winner${if (riders == 1) " is" else "s are"} riding with a raised trail."
            actions == 0 -> "Every holding earns its place this week — nothing to sell."
            actions == 1 -> "One holding needs action this week; the rest hold."
            else -> "$actions of ${verdicts.size} holdings need action this week."
        }

        val notes = buildList {
            // Name every input the whole book was blind to, once.
            val blind = verdicts.flatMap { it.notMeasured }.groupingBy { it }.eachCount()
            blind.entries.sortedByDescending { it.value }.take(4).forEach { (what, count) ->
                add("$what — not measured for $count of ${verdicts.size} holdings; those bands were removed from their scores, not defaulted.")
            }
            if (openRiskPct != null) {
                val budget = riskBudgetPct(policy)
                add(
                    String.format(
                        Locale.US,
                        "Every stop taken together risks %.1f%% of equity against your %.0f%% portfolio-heat budget (%s per trade × 3).",
                        openRiskPct, budget, pct1(policy.riskPerTradePct)
                    ) + if (openRiskPct > budget) " The book is running hot." else ""
                )
            } else if (verdicts.isNotEmpty()) {
                add(
                    "Total risk-to-stop is not reported: " +
                        (if (!equity.cashTracked) "the wallet total is not tracked, so equity is unknown. "
                        else "") + "Per-holding stops below are still measured."
                )
            }
            pulse?.let { p ->
                if (p.call == MarketCall.DEFENSIVE) {
                    add("The tape is defensive — trails matter more than targets this week.")
                }
            }
        }

        return PortfolioVerdictReport(
            computedAt = now,
            headline = headline,
            verdicts = verdicts,
            openRiskPct = openRiskPct,
            equityNote = equityNote(equity),
            notes = notes,
            policyNote = safePolicy(policy),
            caveat = caveat
        )
    }

    // ------------------------------------------------------------ one holding

    private fun judge(
        e: HoldingEvidence,
        equity: EquityContext,
        policy: InvestorProfile,
        pulse: MarketRating?
    ): HoldingVerdict {
        val price = e.price
        val notMeasured = ArrayList<String>()

        // ---- the money, all arithmetic ----
        val marketValue = e.shares * price
        val unrealizedPl = e.shares * (price - e.avgCost)
        val plPct =
            if (e.investedCost > 1e-9) unrealizedPl / e.investedCost * 100.0
            else if (e.avgCost > 1e-9) (price / e.avgCost - 1.0) * 100.0
            else 0.0
        val inProfit = plPct > 0.0

        // ---- structure ----
        val stage = stageOf(e)
        val trendIntact = when {
            e.sma50 != null -> price >= e.sma50 && e.sma50Rising != false
            e.sma20 != null -> price >= e.sma20
            else -> false
        }
        val trendMeasurable = e.sma50 != null || e.sma20 != null

        // ---- the two fixed-scale reads ----
        val conv = conviction(e, notMeasured)
        val risk = riskRead(e, plPct, policy, notMeasured)
        val convictionRatio = if (conv.max > 0) (conv.earned / conv.max * 100.0).roundToInt() else 0
        val riskRatio = if (risk.max > 0) (risk.earned / risk.max * 100.0).roundToInt() else 0
        val coverage = conv.max.roundToInt()
        val thinRead = coverage < MIN_COVERAGE

        // ---- levels: the defensive stop, the ratcheting trail, the target ----
        val structuralStop = structuralStop(e)
        val trail = trailStop(e, plPct, policy, structuralStop)
        // The stop the card publishes: the higher of the two is the one that
        // actually protects capital, and a trail never sits below a structural
        // stop that is already tighter. When NEITHER could be measured there is
        // no stop — a round percentage under the price would be a number the
        // chart never said, and everything downstream (risk, reward-to-risk,
        // portfolio heat, position sizing) would inherit the fabrication.
        val stop = listOfNotNull(structuralStop, trail)
            .maxOrNull()
            ?.coerceAtMost(if (e.atr != null) price - 0.25 * e.atr else price * 0.995)
            ?.takeIf { it > 0.0 }
            ?.let { round2(it) }
            ?: 0.0
        val hasStop = stop > 0.0 && stop < price
        // The level the prose may quote. Without one, the sentences say so.
        val stopText = if (hasStop) Fmt.money(stop) else "no measurable level"
        val stopBroken = e.priorStop != null && e.priorStop > 0.0 && price <= e.priorStop

        val target = target(e, stop)
        val runwayPct = e.resistance
            ?.takeIf { it > price }
            ?.let { round1((it / price - 1.0) * 100.0) }
        val riskReward =
            if (target > price && hasStop) round2((target - price) / (price - stop)) else null
        val riskAtStop = if (hasStop) round2(e.shares * (price - stop)) else null
        val riskAtStopEquityPct =
            if (riskAtStop != null && equity.equity > 0.0) round2(riskAtStop / equity.equity * 100.0)
            else null
        val lockedGainPct =
            if (trail != null && e.avgCost > 1e-9 && trail > e.avgCost) {
                round1((trail / e.avgCost - 1.0) * 100.0)
            } else null

        // ---- the decision ladder, most defensive rule first ----
        val cut = PortfolioAdvisor.cutLossPct(policy)
        val positionCap = PortfolioAdvisor.positionCapPct(policy)
        val positionTrim = PortfolioAdvisor.positionTrimPct(policy)
        val boardBearish = e.techDirection == TechniqueVerdict.BEARISH
        val flowLeaving = e.sectorFlow != null &&
            e.sectorFlow.verdict == FlowVerdict.OUTFLOW && e.sectorFlow.confidence >= 75
        val climax = climax(e)

        val action: HoldingAction
        val headline: String
        val whenText: String
        var ridingNote = ""
        // How much of the position each branch leaves standing. The allocation
        // plan consumes this verbatim — one decision, one number.
        var keepShare = 100.0

        when {
            // 1 — the capital rule. Nothing outranks it.
            plPct <= cut && (stage == HoldingStage.DECLINING || boardBearish ||
                (e.sma50 != null && price < e.sma50)) -> {
                action = HoldingAction.CUT_LOSS
                keepShare = 0.0
                headline = "Cut the loss — down ${fmt1(-plPct)}% with the tape against it " +
                    "(your ${fmt1(-cut)}% loss rule)."
                whenText = "Sell at the next session's open. Capital comes first; re-entry is " +
                    "always available later."
            }

            // 2 — the stop this engine published last run was broken. Honouring
            // it is the whole point of publishing it.
            stopBroken -> {
                action = HoldingAction.SELL
                keepShare = 0.0
                headline = "Sell — ${Fmt.money(price)} is at or below the " +
                    "${Fmt.money(e.priorStop!!)} stop this review published."
                whenText = "Exit at the next session's open. The level was set in advance " +
                    "precisely so the decision would not be made in the moment."
            }

            // 3 — the board turned decisively and the trend is gone with it.
            boardBearish && e.techConfidence >= 60 && !trendIntact && trendMeasurable -> {
                action = HoldingAction.SELL
                keepShare = 0.0
                headline = "Sell — the board reads bearish at ${e.techConfidence}% indicator " +
                    "agreement and the trend has broken."
                whenText = "Sell into the next strength, or at the close of any day that ends " +
                    "below $stopText — whichever comes first this week."
            }

            // 4 — a measured climax in a winner: bank part, ride the rest.
            inProfit && climax -> {
                action = HoldingAction.TAKE_PROFIT
                keepShare = 100.0 / 3.0 * 2.0
                headline = "Bank a third — ${fmt1(plPct)}% up into a measured climax " +
                    "(${fmt1((price / e.sma20!! - 1.0) * 100.0)}% above the 20-day on " +
                    "${fmt1(e.volumeRatio!!)}x volume, RSI ${fmt0(e.rsi!!)})."
                whenText = "Sell a third into this strength and leave the rest riding behind " +
                    "the $stopText trail — a climax is a place to lighten, not to leave."
                ridingNote = ridingSentence(e, plPct, trail, lockedGainPct, runwayPct, partial = true, hasStop = hasStop, policy = policy)
            }

            // 5 — a winner whose trend has actually deteriorated.
            inProfit && riskRatio >= deteriorationRisk(policy) && !trendIntact && trendMeasurable -> {
                action = HoldingAction.TAKE_PROFIT
                keepShare = 50.0
                headline = "Bank half — ${fmt1(plPct)}% up but the read has turned " +
                    "(risk $riskRatio/100 on ${risk.max.roundToInt()} measured points, past the " +
                    "${deteriorationRisk(policy)} your ${horizonWord(policy)} horizon tolerates)."
                whenText = "Sell half now" +
                    (if (hasStop) " and raise the stop on the rest to $stopText, " +
                        "so the remainder cannot become a loss." else "; no stop level could be " +
                        "measured for the remainder this run.")
            }

            // 6 — risk control: one name has grown past what the profile allows.
            e.weightPct >= positionTrim -> {
                action = HoldingAction.TRIM
                keepShare = trimKeepSharePct(e.weightPct, positionCap)
                headline =
                    if (inProfit && trendIntact) {
                        "Trim to size — ${fmt0(e.weightPct)}% of the book is riding on one name. " +
                            "This is risk control, not a verdict on the stock."
                    } else {
                        "Trim — ${fmt0(e.weightPct)}% of the book is riding on one name."
                    }
                whenText = "Reduce toward your ${fmt0(positionCap)}% position cap this week, " +
                    "selling into strength rather than weakness" +
                    if (inProfit && trendIntact && hasStop) "; the remainder keeps riding behind $stopText."
                    else "."
                if (inProfit && trendIntact) {
                    ridingNote = ridingSentence(e, plPct, trail, lockedGainPct, runwayPct, partial = true, hasStop = hasStop, policy = policy)
                }
            }

            // 7 — the sector is bleeding, the name lags, and it is underwater.
            flowLeaving && !trendIntact && plPct < 0.0 && (e.rel20Pct ?: 0.0) < 0.0 -> {
                val f = e.sectorFlow!!
                action = HoldingAction.TRIM
                keepShare = 50.0
                headline = "Trim — money is leaving ${f.label} (flow ${f.flowScore}/100) and " +
                    "this name lags the market by ${fmt1(-(e.rel20Pct ?: 0.0))}pp over 20 days."
                whenText = "Reduce into the next bounce this week; revisit when the sector's " +
                    "flow turns neutral or the board turns bullish."
            }

            // 8 — hold. For a winner this is the ride, not a shrug.
            else -> {
                action = HoldingAction.HOLD
                val ride = inProfit && trendIntact && convictionRatio >= RIDE_CONVICTION && !thinRead
                headline = when {
                    ride -> "Ride it — ${fmt1(plPct)}% up, conviction ${conv.earned.roundToInt()}/" +
                        "${conv.max.roundToInt()} measured" +
                        (if (hasStop) ", and the trail moves to $stopText." else ".")
                    inProfit && trendIntact ->
                        "Hold — ${fmt1(plPct)}% up with the trend intact" +
                            (if (hasStop) "; the trail sits at $stopText." else ".")
                    e.techDirection == TechniqueVerdict.BULLISH ->
                        "Hold — the board backs it at ${e.techConfidence}% indicator agreement."
                    plPct >= 0.0 -> "Hold — in profit with no exit signal on the board."
                    else -> "Hold — the loss is inside the stop and the board has not turned."
                }
                whenText = when {
                    !hasStop ->
                        "No exit level could be measured this run — too little history for an ATR " +
                            "or a support. Re-check after the next session."
                    ride || (inProfit && trendIntact) ->
                        "Do nothing while it holds $stopText. The trail only ever moves up; the " +
                            "review raises it as the stock makes new ground."
                    else ->
                        "Re-check on a close below $stopText (exit) or at ${Fmt.money(target)} " +
                            "(take profit); the review re-runs the board live."
                }
                if (ride) {
                    ridingNote = ridingSentence(e, plPct, trail, lockedGainPct, runwayPct, partial = false, hasStop = hasStop, policy = policy)
                }
            }
        }

        // ---- the measured why ----
        val whyPoints = buildList {
            add(
                String.format(
                    Locale.US,
                    "P/L %+.1f%% (%s) on an average cost of %s; latest price %s.",
                    plPct, Fmt.signedMoney(unrealizedPl), Fmt.money(e.avgCost), Fmt.money(price)
                )
            )
            add(
                String.format(
                    Locale.US,
                    "Conviction %d/%d measured points (%d%%); risk %d/%d (%d%%)%s.",
                    conv.earned.roundToInt(), conv.max.roundToInt(), convictionRatio,
                    risk.earned.roundToInt(), risk.max.roundToInt(), riskRatio,
                    if (thinRead) " — a thin read this run, so the defensive rules lead" else ""
                )
            )
            if (stage != HoldingStage.UNMEASURED) add(stageLine(e, stage))
            if (e.techTotal > 0) {
                // "Agreement", not "confidence": this is the share of correlated
                // indicator votes, not a calibrated probability.
                add(
                    "${e.techBullish} of ${e.techTotal} techniques bullish — the board reads " +
                        e.techDirection.name.lowercase(Locale.US) +
                        " at ${e.techConfidence}% indicator agreement."
                )
            }
            e.rsi?.let { r ->
                add(
                    String.format(
                        Locale.US, "RSI %.0f%s.", r,
                        e.atr?.let { a -> "; 14-day ATR ${Fmt.money(a)}" } ?: ""
                    )
                )
            }
            e.peakSinceEntry?.let { peak ->
                if (peak > price) {
                    add(
                        String.format(
                            Locale.US,
                            "%.1f%% below its %s peak %s%s.",
                            (1.0 - price / peak) * 100.0,
                            if (e.peakMeasuredFromEntry) "since-purchase" else "recent",
                            Fmt.money(peak),
                            e.atr?.let { a -> String.format(Locale.US, " (%.1f ATR)", (peak - price) / a) } ?: ""
                        )
                    )
                } else {
                    add("At a new high for the period measured — nothing above it to sell into.")
                }
            }
            if (e.rel20Pct != null && e.r20Pct != null) {
                add(
                    String.format(
                        Locale.US,
                        "20-day move %+.1f%% vs the S&P 500's %+.1f%% — %+.1fpp relative%s.",
                        e.r20Pct, e.r20Pct - e.rel20Pct, e.rel20Pct,
                        e.rel60Pct?.let { String.format(Locale.US, "; %+.1fpp over 60 days", it) } ?: ""
                    )
                )
            }
            e.upDayVolumeSharePct?.let {
                add(
                    String.format(
                        Locale.US,
                        "%.0f%% of the last 20 sessions' volume traded on up days%s.",
                        it,
                        e.distributionDays?.let { d -> "; $d heavy down day${if (d == 1) "" else "s"} in 25" } ?: ""
                    )
                )
            }
            if (e.flowNote.isNotEmpty()) add(e.flowNote)
            add(String.format(Locale.US, "%.0f%% of the invested book.", e.weightPct))
            if (riskAtStop != null) {
                add(
                    String.format(Locale.US, "Exiting at %s costs %s from here%s.",
                        stopText, Fmt.money(riskAtStop),
                        riskAtStopEquityPct?.let {
                            String.format(Locale.US, " — %.2f%% of equity against your %s per-trade budget", it, pct1(policy.riskPerTradePct))
                        } ?: " (equity not tracked, so no % of account is claimed)"
                    )
                )
            }
            if (riskReward != null) {
                add(
                    String.format(
                        Locale.US, "Forward reward-to-risk %.2f to 1 (target %s, stop %s)%s.",
                        riskReward, Fmt.money(target), Fmt.money(stop),
                        if (riskReward < 1.0) " — below 1:1, size accordingly" else ""
                    )
                )
            }
            if (e.newsMeasured && e.newsScore != 0) {
                add("News tone ${if (e.newsScore > 0) "+" else ""}${e.newsScore} over 5 days.")
            } else if (!e.newsMeasured) {
                add("Headline tone not measured this run — its band was removed from the score, not defaulted.")
            }
        }

        return HoldingVerdict(
            symbol = e.symbol,
            name = e.name.ifBlank { e.symbol },
            sector = e.sector,
            action = action,
            headline = headline,
            whenText = whenText,
            whyPoints = whyPoints,
            price = round2(price),
            avgCost = round2(e.avgCost),
            marketValue = round2(marketValue),
            weightPct = round1(e.weightPct),
            unrealizedPl = round2(unrealizedPl),
            unrealizedPlPct = round1(plPct),
            target = target,
            stop = stop,
            techBullish = e.techBullish,
            techTotal = e.techTotal,
            techConfidence = e.techConfidence,
            techDirection = e.techDirection,
            rsi = round1(e.rsi ?: 0.0),
            newsScore = e.newsScore,
            newsNote = e.newsNote,
            sessionMovePct = e.sessionMovePct?.let { round1(it) },
            above50 = e.sma50?.let { price >= it },
            rel20Pct = e.rel20Pct?.let { round1(it) },
            flowVerdictName = e.sectorFlow?.verdict?.name ?: "",
            conviction = conv.earned.roundToInt(),
            convictionMax = conv.max.roundToInt(),
            riskScore = risk.earned.roundToInt(),
            riskScoreMax = risk.max.roundToInt(),
            stage = stage,
            trailStop = trail?.let { round2(it) },
            lockedGainPct = lockedGainPct,
            runwayPct = runwayPct,
            riskReward = riskReward,
            riskAtStop = riskAtStop,
            riskAtStopEquityPct = riskAtStopEquityPct,
            ridingNote = ridingNote,
            keepSharePct = round1(keepShare),
            notMeasured = notMeasured.distinct()
        )
    }

    // --------------------------------------------------------------- the reads

    /** Earned points over the points that were actually measurable. */
    private data class Read(val earned: Double, val max: Double)

    private class Scale {
        var earned = 0.0
        var max = 0.0
        fun band(points: Double, of: Double) {
            earned += points.coerceIn(0.0, of)
            max += of
        }
        fun read() = Read(earned, max)
    }

    /**
     * Hold-conviction on the fixed 100-point scale. Every band that cannot be
     * measured is skipped entirely — it adds to neither side — and named in
     * [blind], so a holding with three blind bands reports 44/62, not 44/100
     * and not a flattering 71/100.
     */
    private fun conviction(e: HoldingEvidence, blind: MutableList<String>): Read {
        val s = Scale()
        val price = e.price

        // --- 1) structure: where price sits against its own averages ---
        run {
            var pts = 0.0
            var of = 0.0
            val ofMa50 = 8.0
            val ofMa200 = 6.0
            val ofStack = 4.0
            // The four sub-parts sum to B_STRUCTURE by construction.
            val ofSlope = B_STRUCTURE - ofMa50 - ofMa200 - ofStack
            e.sma50?.let { m ->
                of += ofMa50
                val gap = (price / m - 1.0) * 100.0
                pts += when {
                    gap >= 0.0 && gap <= 20.0 -> 8.0
                    gap > 20.0 -> 5.5          // extended, still above
                    gap >= -2.0 -> 4.0         // reclaiming
                    else -> 0.0
                }
            }
            e.sma200?.let { m ->
                of += ofMa200
                pts += if (price >= m) ofMa200 else if ((price / m - 1.0) * 100.0 >= -5.0) 2.0 else 0.0
            }
            if (e.sma50 != null && e.sma200 != null) {
                of += ofStack
                pts += if (e.sma50 > e.sma200) ofStack else 0.0
            }
            e.sma50Rising?.let { rising ->
                of += ofSlope
                pts += if (rising) ofSlope else 0.0
            }
            // Whatever could not be measured is simply absent from the band's
            // max — never silently topped back up to B_STRUCTURE.
            if (of <= 0.0) blind += "moving-average structure" else s.band(pts, of)
        }

        // --- 2) relative strength vs the S&P 500 ---
        run {
            var pts = 0.0
            var of = 0.0
            val of20 = 10.0
            val of60 = B_RELSTRENGTH - of20
            e.rel20Pct?.let { r ->
                of += of20
                pts += (of20 * ((r + 8.0) / 16.0)).coerceIn(0.0, of20)
            }
            e.rel60Pct?.let { r ->
                of += of60
                pts += (of60 * ((r + 15.0) / 30.0)).coerceIn(0.0, of60)
            }
            if (of <= 0.0) blind += "relative strength vs the S&P 500" else s.band(pts, of)
        }

        // --- 3) the 35-technique board ---
        if (e.techTotal > 0) {
            val conf = e.techConfidence.coerceIn(0, 100) / 100.0
            val pts = when (e.techDirection) {
                TechniqueVerdict.BULLISH -> 8.0 + 8.0 * conf
                TechniqueVerdict.NEUTRAL -> 8.0 - 3.0 * conf
                TechniqueVerdict.BEARISH -> 8.0 - 8.0 * conf
            }
            s.band(pts, B_BOARD)
        } else {
            blind += "the 35-technique board"
        }

        // --- 4) momentum quality ---
        run {
            var pts = 0.0
            var of = 0.0
            val ofRsi = 6.0
            val ofShape = B_MOMENTUM - ofRsi
            e.rsi?.let { r ->
                of += ofRsi
                pts += when {
                    r in 55.0..70.0 -> ofRsi
                    r in 70.0..78.0 -> 4.0     // strong, running hot
                    r in 45.0..55.0 -> 4.0
                    r > 78.0 -> 1.5            // stretched
                    r in 35.0..45.0 -> 2.0
                    else -> 0.0
                }
            }
            val shape = listOfNotNull(e.r5Pct, e.r20Pct, e.r60Pct)
            if (shape.isNotEmpty()) {
                of += ofShape
                pts += ofShape * shape.count { it > 0.0 } / shape.size
            }
            if (of <= 0.0) blind += "momentum (RSI and returns)" else s.band(pts, of)
        }

        // --- 5) volume participation: who is doing the buying ---
        run {
            var pts = 0.0
            var of = 0.0
            val ofUpDays = 6.0
            val ofRatio = B_PARTICIPATION - ofUpDays
            e.upDayVolumeSharePct?.let { share ->
                of += ofUpDays
                pts += when {
                    share >= 60.0 -> ofUpDays
                    share >= 52.0 -> 4.0
                    share >= 45.0 -> 2.0
                    else -> 0.0
                }
            }
            e.volumeRatio?.takeIf { it > 0.0 }?.let { vr ->
                of += ofRatio
                pts += when {
                    vr in 1.2..2.5 -> ofRatio
                    vr in 1.0..1.2 -> 2.5
                    vr in 2.5..4.0 -> 2.0      // churn — could be a catalyst either way
                    vr > 4.0 -> 1.0
                    else -> 1.0                // drying up
                }
            }
            if (of <= 0.0) blind += "volume participation" else s.band(pts, of)
        }

        // --- 6) sector money flow ---
        val flow = e.sectorFlow
        if (flow != null) {
            s.band(B_FLOW * flow.flowScore.coerceIn(0, 100) / 100.0, B_FLOW)
        } else {
            blind += "sector money flow"
        }

        // --- 7) leadership: how close to its own 52-week high ---
        val high52 = e.high52
        if (high52 != null && high52 > 0.0) {
            val below = (1.0 - price / high52) * 100.0
            val pts = when {
                below <= 5.0 -> 8.0
                below <= 15.0 -> 6.0
                below <= 25.0 -> 3.5
                below <= 40.0 -> 1.5
                else -> 0.0
            }
            s.band(pts, B_LEADERSHIP)
        } else {
            blind += "the 52-week high"
        }

        // --- 8) headline tone ---
        if (e.newsMeasured) {
            s.band(2.0 + 2.0 * (e.newsScore.coerceIn(-3, 3) / 3.0), B_NEWS)
        } else {
            blind += "headline tone"
        }

        return s.read()
    }

    /** The damage read on its own fixed scale — what argues for getting out. */
    private fun riskRead(
        e: HoldingEvidence,
        plPct: Double,
        policy: InvestorProfile,
        blind: MutableList<String>
    ): Read {
        val s = Scale()
        val cut = PortfolioAdvisor.cutLossPct(policy)   // negative

        // 1 — how far into the loss rule the position already is. Always measurable.
        s.band(
            if (plPct >= 0.0) 0.0 else R_LOSS * (plPct / cut).coerceIn(0.0, 1.0),
            R_LOSS
        )

        // 2 — give-back from the peak, in ATRs (a percentage means nothing
        // without the stock's own volatility next to it).
        val peak = e.peakSinceEntry
        val atr = e.atr
        if (peak != null && atr != null && atr > 0.0 && peak > 0.0) {
            val ddAtr = ((peak - e.price) / atr).coerceAtLeast(0.0)
            s.band(
                when {
                    ddAtr >= 4.0 -> R_DRAWDOWN
                    ddAtr >= 3.0 -> 15.0
                    ddAtr >= 2.0 -> 10.0
                    ddAtr >= 1.0 -> 4.0
                    else -> 0.0
                },
                R_DRAWDOWN
            )
        } else {
            blind += "give-back from the peak"
        }

        // 3 — trend break.
        if (e.sma50 != null) {
            var pts = 0.0
            if (e.price < e.sma50) pts += 12.0
            if (e.sma200 != null && e.sma50 < e.sma200) pts += 5.0
            if (e.sma50Rising == false) pts += R_TRENDBREAK - 12.0 - 5.0
            s.band(pts, R_TRENDBREAK)
        } else {
            blind += "the 50-day trend line"
        }

        // 4 — the board against it.
        if (e.techTotal > 0) {
            val conf = e.techConfidence.coerceIn(0, 100) / 100.0
            s.band(
                when (e.techDirection) {
                    TechniqueVerdict.BEARISH -> 5.0 + 10.0 * conf
                    TechniqueVerdict.NEUTRAL -> 4.0
                    TechniqueVerdict.BULLISH -> 0.0
                },
                R_BOARD
            )
        }
        // (no else-blind: the board is already named by the conviction read)

        // 5 — money leaving the sector.
        val flow = e.sectorFlow
        if (flow != null) {
            s.band(
                when (flow.verdict) {
                    FlowVerdict.OUTFLOW -> R_FLOW * (flow.confidence.coerceIn(0, 100) / 100.0)
                    FlowVerdict.NEUTRAL -> 3.0
                    FlowVerdict.INFLOW -> 0.0
                },
                R_FLOW
            )
        }

        // 6 — institutional selling footprints.
        val dd = e.distributionDays
        if (dd != null) {
            s.band(
                when {
                    dd >= 5 -> R_DISTRIBUTION
                    dd == 4 -> 7.0
                    dd == 3 -> 4.0
                    else -> 0.0
                },
                R_DISTRIBUTION
            )
        } else {
            blind += "distribution days"
        }

        return s.read()
    }

    // ------------------------------------------------------------ the levels

    /**
     * The defensive stop from the stock's own structure — the level that would
     * be used if there were no gain to trail. Null when neither an ATR nor a
     * support could be measured (the caller then has no honest level to print).
     */
    private fun structuralStop(e: HoldingEvidence): Double? {
        val atr = e.atr ?: return e.support?.takeIf { it < e.price }
        val structural = e.support?.takeIf { it < e.price }
        val raw = structural?.let { min(it - 0.5 * atr, e.price - 1.5 * atr) }
            ?: (e.price - 2.0 * atr)
        return max(raw, e.price * 0.85)
    }

    /**
     * The ratcheting trail under a position with an open gain: the highest
     * defensible level among the chandelier (peak − k·ATR), the 50-day line,
     * the 20-day Donchian low, and — once the gain is worth twice the risk
     * rule — the cost basis itself.
     *
     * It NEVER returns a level below [HoldingEvidence.priorStop]: a stop this
     * engine already published cannot quietly move down, which is what makes
     * "your gain is protected" a statement rather than a hope. Null when
     * nothing could be measured.
     */
    private fun trailStop(
        e: HoldingEvidence,
        plPct: Double,
        policy: InvestorProfile,
        structural: Double?
    ): Double? {
        val atr = e.atr
        val k = trailAtrMultiple(policy)
        val levels = ArrayList<Double>()
        if (atr != null && atr > 0.0) {
            e.peakSinceEntry?.let { levels += it - k * atr }
            e.sma50?.takeIf { e.price > it }?.let { levels += it - 0.5 * atr }
            e.donchianLow20?.let { levels += it - 0.25 * atr }
        } else {
            e.donchianLow20?.let { levels += it }
            e.sma50?.takeIf { e.price > it }?.let { levels += it }
        }
        // Breakeven ratchet: once the open gain is worth twice what the loss
        // rule would ever have risked, the position stops being able to lose.
        val cut = abs(PortfolioAdvisor.cutLossPct(policy))
        if (plPct >= 2.0 * cut && e.avgCost > 0.0) levels += e.avgCost
        structural?.let { levels += it }

        var trail = levels.filter { it > 0.0 }.maxOrNull() ?: return null
        // The ratchet. A prior stop above the current price is not clamped away
        // here — the caller reads that as a broken stop, which is the signal.
        e.priorStop?.takeIf { it > 0.0 }?.let { trail = max(trail, it) }
        return trail
    }

    /**
     * The forward take-profit level, capped at the nearest MEASURED resistance
     * so the card never promises ground the chart has not shown.
     */
    private fun target(e: HoldingEvidence, stop: Double): Double {
        val price = e.price
        val atr = e.atr
        val resistance = e.resistance?.takeIf { it > price }
        val high52 = e.high52?.takeIf { it > price }
        val expected = e.expectedHigh?.takeIf { it > price }
        val raw = when {
            resistance != null -> resistance
            high52 != null -> high52
            expected != null -> expected
            atr != null -> price + 2.0 * atr
            else -> price
        }
        // Give the target at least half the measured risk as room — but only
        // when there IS a measured stop to compute that risk from.
        val floor = if (stop > 0.0 && stop < price) price + (price - stop) * 0.5 else price
        return round2(max(raw, floor))
    }

    private fun horizonWord(policy: InvestorProfile): String = when (policy.horizon) {
        InvestorProfile.HORIZON_SHORT -> "short"
        InvestorProfile.HORIZON_LONG -> "long"
        else -> "medium"
    }

    /** A measured blow-off: stretched from the 20-day, overbought, and on volume. */
    private fun climax(e: HoldingEvidence): Boolean {
        val atr = e.atr ?: return false
        val sma20 = e.sma20 ?: return false
        val rsi = e.rsi ?: return false
        val vr = e.volumeRatio ?: return false
        if (atr <= 0.0 || sma20 <= 0.0) return false
        return e.price >= sma20 + 3.0 * atr && rsi >= 78.0 && vr >= 2.0
    }

    // ----------------------------------------------------------------- prose

    private fun stageOf(e: HoldingEvidence): HoldingStage {
        val s50 = e.sma50 ?: return HoldingStage.UNMEASURED
        val s200 = e.sma200
        val above50 = e.price >= s50
        return when {
            s200 == null -> if (above50 && e.sma50Rising != false) HoldingStage.ADVANCING
            else if (!above50) HoldingStage.TOPPING else HoldingStage.UNMEASURED
            above50 && s50 >= s200 -> HoldingStage.ADVANCING
            above50 && s50 < s200 -> HoldingStage.BASING
            !above50 && s50 >= s200 -> HoldingStage.TOPPING
            else -> HoldingStage.DECLINING
        }
    }

    private fun stageLine(e: HoldingEvidence, stage: HoldingStage): String {
        val where = when (stage) {
            HoldingStage.ADVANCING -> "advancing"
            HoldingStage.TOPPING -> "topping"
            HoldingStage.BASING -> "basing"
            HoldingStage.DECLINING -> "declining"
            HoldingStage.UNMEASURED -> "unmeasured"
        }
        val s50 = e.sma50
        val s200 = e.sma200
        return when {
            s50 != null && s200 != null -> String.format(
                Locale.US,
                "Stage: %s — price %s the 50-day %s, which is %s the 200-day %s.",
                where, if (e.price >= s50) "above" else "below", Fmt.money(s50),
                if (s50 >= s200) "above" else "below", Fmt.money(s200)
            )
            s50 != null -> String.format(
                Locale.US,
                "Stage: %s — price %s the 50-day %s (the 200-day is not yet measurable).",
                where, if (e.price >= s50) "above" else "below", Fmt.money(s50)
            )
            else -> "Stage: not measurable — too little history for a 50-day line."
        }
    }

    /**
     * The winner-riding sentence: what the position has, what the trail now
     * protects, and what selling here would hand back. Every clause carries its
     * measured number; clauses without one are simply not written.
     */
    private fun ridingSentence(
        e: HoldingEvidence,
        plPct: Double,
        trail: Double?,
        lockedGainPct: Double?,
        runwayPct: Double?,
        partial: Boolean,
        hasStop: Boolean,
        policy: InvestorProfile
    ): String {
        val parts = ArrayList<String>()
        parts += String.format(
            Locale.US, "%s%+.1f%% open", if (partial) "Riding the rest: " else "Riding: ", plPct
        )
        e.sma50?.takeIf { e.price > it }?.let {
            parts += "the 50-day ${Fmt.money(it)} is holding beneath it" +
                (if (e.sma50Rising == true) " and rising" else "")
        }
        if (e.techTotal > 0 && e.techDirection == TechniqueVerdict.BULLISH) {
            parts += "${e.techBullish} of ${e.techTotal} techniques bullish"
        }
        val head = parts.joinToString(", ") + "."
        val trailPart = trail?.let { t ->
            String.format(
                Locale.US, " The trail sits at %s, %.1f%% under the price%s.",
                Fmt.money(t), (1.0 - t / e.price) * 100.0,
                lockedGainPct?.let {
                    String.format(Locale.US, " — above your cost, so this position can no longer become a loss (%+.1f%% locked)", it)
                } ?: ""
            )
        } ?: if (hasStop) " No trail could be measured this run — the structural stop below is the only level."
        else " Neither a trail nor a structural stop could be measured this run; there is no exit level to quote."
        val roomPart =
            if (trail != null && e.atr != null && e.atr > 0.0) String.format(
                Locale.US,
                " That is %.1f ATR of room — what your %s tolerance over a %s horizon gives a winner before it is asked to leave.",
                trailAtrMultiple(policy), policy.riskTolerance.lowercase(Locale.US), horizonWord(policy)
            ) else ""
        val runwayPart = runwayPct?.let {
            String.format(
                Locale.US,
                " Selling here hands back the %.1f%% of runway to the nearest measured resistance.", it
            )
        } ?: ""
        return head + trailPart + roomPart + runwayPart
    }

    private fun equityNote(equity: EquityContext): String {
        if (equity.holdingsValue <= 0.0 && !equity.cashTracked) {
            return "Equity is not tracked this run — every figure below is measured on the book alone."
        }
        val open = equity.openPl
        return if (equity.cashTracked) {
            String.format(
                Locale.US,
                "Equity %s = %s invested at cost (now worth %s, %s open) + %s uninvested. " +
                    "Position and risk caps below are measured against that equity.",
                AllocationMath.money(equity.equity), AllocationMath.money(equity.invested),
                AllocationMath.money(equity.holdingsValue), Fmt.signedMoney(open),
                AllocationMath.money(equity.liquidity ?: 0.0)
            )
        } else {
            String.format(
                Locale.US,
                "Book %s against %s invested at cost (%s open). The wallet total is not tracked, " +
                    "so uninvested cash is unknown and no percentage of the whole account is claimed.",
                AllocationMath.money(equity.holdingsValue), AllocationMath.money(equity.invested),
                Fmt.signedMoney(open)
            )
        }
    }

    /**
     * The share of TODAY'S position to keep so the name ends up at [capPct] of
     * the book — accounting for the fact that selling shrinks the book itself.
     *
     * Keeping `cap/weight` of the position is the intuitive answer and it is
     * wrong: with a 34% position and a 22% cap it leaves the name at 25% of the
     * smaller book. Solving `(V - x)/(B - x) = cap` instead gives the share
     * below, which lands exactly on the cap.
     *
     * A book that is one position has nothing to re-base against — the cap can
     * only be approached by holding the proceeds as cash — so that degenerate
     * case falls back to the plain ratio and the card's text carries the rest.
     */
    internal fun trimKeepSharePct(weightPct: Double, capPct: Double): Double {
        if (weightPct <= 0.0) return 100.0
        val plain = (capPct / weightPct * 100.0).coerceIn(0.0, 100.0)
        if (weightPct >= 99.5 || capPct >= 99.5) return plain
        val exact = 100.0 * capPct * (100.0 - weightPct) / ((100.0 - capPct) * weightPct)
        return if (exact <= 0.0) plain else exact.coerceIn(0.0, 100.0)
    }

    /** The portfolio-heat budget: three full-risk trades, clamped to something sane. */
    internal fun riskBudgetPct(policy: InvestorProfile): Double =
        (policy.riskPerTradePct * 3.0).coerceIn(3.0, 12.0)

    private fun safePolicy(profile: InvestorProfile): String = try {
        profile.label()
    } catch (_: Throwable) {
        String.format(
            Locale.US,
            "%s · %s%% risk/trade · %d%% max position · %d%% max sector",
            profile.riskTolerance.lowercase(Locale.US), pct1(profile.riskPerTradePct),
            profile.maxPositionPct.toInt(), profile.maxSectorPct.toInt()
        )
    }

    // ----------------------------------------------------------------- helpers

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun pct1(v: Double): String = String.format(Locale.US, "%.1f%%", v)
}

/**
 * Everything MEASURED about one holding this run, gathered by the caller
 * (repository / advisor layer) from live data.
 *
 * The contract that makes the verdicts honest: a null field means "not
 * measured", never "zero" and never "neutral". [PortfolioVerdictEngine] removes
 * an unmeasured input's band from both sides of its scales and names it, so a
 * thin data run produces a visibly thin score rather than a confident-looking
 * one built on defaults.
 */
data class HoldingEvidence(
    val symbol: String,
    val name: String,
    val sector: String,

    // ---- the money (all arithmetic from the ledger, always measured) ----
    val shares: Double,
    val avgCost: Double,
    val investedCost: Double,
    val price: Double,
    /** Share of the invested book, from the same [PortfolioLens] math every screen uses. */
    val weightPct: Double,

    // ---- structure ----
    val atr: Double? = null,
    val rsi: Double? = null,
    val sma20: Double? = null,
    val sma50: Double? = null,
    val sma200: Double? = null,
    /** The 50-day average today vs 10 sessions ago; null when either could not be measured. */
    val sma50Rising: Boolean? = null,
    /** Highest CLOSE since the position was opened, or over the visible window — see [peakMeasuredFromEntry]. */
    val peakSinceEntry: Double? = null,
    /** True when [peakSinceEntry] really starts at the purchase; false when it is a window high. */
    val peakMeasuredFromEntry: Boolean = false,
    /** Nearest measured support below the price. */
    val support: Double? = null,
    /** Nearest measured resistance above the price — the honest cap for a target. */
    val resistance: Double? = null,
    val high52: Double? = null,
    val donchianLow20: Double? = null,

    // ---- momentum & participation ----
    val r5Pct: Double? = null,
    val r20Pct: Double? = null,
    val r60Pct: Double? = null,
    /** 20-day return minus the S&P 500's, percentage points. */
    val rel20Pct: Double? = null,
    /** 60-day return minus the S&P 500's, percentage points. */
    val rel60Pct: Double? = null,
    val sessionMovePct: Double? = null,
    /** Latest completed session's volume vs its 20-day average. */
    val volumeRatio: Double? = null,
    /** Share of the last 20 sessions' volume that traded on up days. */
    val upDayVolumeSharePct: Double? = null,
    /** Down sessions of >0.2% on above-average volume in the last 25 — institutional selling. */
    val distributionDays: Int? = null,

    // ---- board, news, flow ----
    val techDirection: TechniqueVerdict = TechniqueVerdict.NEUTRAL,
    val techBullish: Int = 0,
    val techTotal: Int = 0,
    val techConfidence: Int = 0,
    /** The board's own expected high; null when it could not be computed. */
    val expectedHigh: Double? = null,
    val newsScore: Int = 0,
    val newsNote: String = "",
    /** False when the news feed failed — distinct from "verified, nothing to report". */
    val newsMeasured: Boolean = false,
    val sectorFlow: SectorFlow? = null,
    val flowNote: String = "",

    // ---- memory of the last run ----
    /** The stop this engine published last run; the trail may never move below it. */
    val priorStop: Double? = null
)
