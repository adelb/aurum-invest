package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A staged plan for deploying a fixed dollar budget into one stock over the
 * next five trading days, built from the same technique analysis the charts
 * show. The playbook encodes the documented habits of well-known investors:
 *
 *  - Jesse Livermore: probe first, pyramid only after the market proves you
 *    right, never average down on a failing probe.
 *  - William O'Neil: scale a winning entry roughly 50% / 30% / 20%.
 *  - Paul Tudor Jones: stay on the right side of the 200-day moving average;
 *    frame trades toward a 5:1 reward-to-risk; defense first.
 *  - Alexander Elder: cap the loss at the stop to a small fixed slice of
 *    capital (the 2% rule).
 *  - Buffett / Munger: don't chase — put limit orders where value is and let
 *    price come to you.
 *  - Volatility stops: place the stop an ATR-multiple under structure
 *    (support), not under the entry, so normal noise can't shake you out.
 *
 * Pure Kotlin, deterministic, never throws.
 */

/** One slice of the budget with its trigger. */
data class PlanTranche(
    val label: String,
    val amount: Double,
    val price: Double,
    val shares: Double,
    val condition: String,
    val day: String
)

data class PlanDay(val day: Int, val title: String, val actions: List<String>)

data class BuyPlan(
    val symbol: String,
    val budget: Double,
    /**
     * The account equity the plan was sized against; null when unknown — the
     * plan then falls back to a fixed order budget and must SAY so instead
     * of borrowing the authority of the 2% rule.
     */
    val accountEquity: Double? = null,
    /** The risk-per-trade policy used (percent of account equity). */
    val riskPerTradePct: Double = 0.0,
    /** One sentence on where the budget number came from. */
    val budgetBasis: String = "",
    /** One-line stance, e.g. "Deploy in three steps on strength." */
    val posture: String,
    /** 2-3 sentences explaining the stance. */
    val postureDetail: List<String>,
    /** The 200-day moving-average trend gate. */
    val trendNote: String,
    val tranches: List<PlanTranche>,
    /** Weighted average entry if every tranche fills. */
    val avgEntry: Double,
    val totalShares: Double,
    val stop: Double,
    val stopBasis: String,
    /** Dollars lost if all filled tranches stop out. */
    val riskDollars: Double,
    /** Risk as % of the ACCOUNT when [accountEquity] is known, else % of the order budget. */
    val riskPct: Double,
    val firstTarget: Double,
    val firstTargetNote: String,
    val stretchTarget: Double,
    val stretchTargetNote: String,
    /** (firstTarget - avgEntry) / (avgEntry - stop). */
    val rewardRisk: Double,
    val schedule: List<PlanDay>,
    /** (investor, principle applied here). */
    val principles: List<Pair<String, String>>,
    val caveat: String
)

object BuyPlanEngine {

    /** Fallback order budget when the account is unknown — labeled as such, never as "2%". */
    const val DEFAULT_BUDGET = 3000.0

    /**
     * [candles] is the full daily history available (ideally ~1 year so the
     * 200-day average exists); [analysis] the 35-technique read; [price] the
     * latest quote.
     *
     * When [accountEquity] is known, the budget is DERIVED: the plan is sized
     * so a full stop-out loses at most [riskPerTradePct]% of the account,
     * capped by [maxPositionPct]% of equity and by [cashAvailable]. When it
     * is not known, [fallbackBudget] is used and the plan says so — a fixed
     * order budget must never be dressed up as Elder's 2% account rule.
     */
    fun build(
        symbol: String,
        candles: List<Candle>,
        analysis: TechniqueAnalysis,
        price: Double,
        fallbackBudget: Double = DEFAULT_BUDGET,
        accountEquity: Double? = null,
        riskPerTradePct: Double = 2.0,
        maxPositionPct: Double = 22.0,
        cashAvailable: Double? = null,
        policyNote: String = ""
    ): BuyPlan {
        // First pass with a unit budget measures the plan's risk-per-dollar —
        // stop and tranche PRICES depend only on the chart, so risk scales
        // linearly with the budget and one probe sizes the real one.
        val equity = accountEquity?.takeIf { it > 0.0 }
        val budget: Double
        val budgetBasis: String
        if (equity != null) {
            val probe = buildInternal(symbol, candles, analysis, price, 1000.0)
            val riskPerDollar = (probe.riskDollars / 1000.0).coerceAtLeast(1e-6)
            val allowedRisk = equity * riskPerTradePct / 100.0
            val riskCapBudget = allowedRisk / riskPerDollar
            val positionCap = equity * maxPositionPct / 100.0
            val cashCap = cashAvailable?.takeIf { it > 0.0 } ?: Double.MAX_VALUE
            budget = minOf(riskCapBudget, positionCap, cashCap).coerceAtLeast(0.0)
            budgetBasis = buildString {
                append(
                    "Sized from your ${com.aurum.invest.core.Fmt.money(equity)} account: a full " +
                        "stop-out loses at most ${pct1(riskPerTradePct)} of it"
                )
                if (budget == positionCap) append(", capped at ${pct1(maxPositionPct)} position size")
                if (cashAvailable != null && budget == cashCap) append(", capped by available cash")
                append(".")
                if (policyNote.isNotBlank()) append(" $policyNote")
            }
        } else {
            budget = fallbackBudget
            budgetBasis = "Default ${com.aurum.invest.core.Fmt.money(fallbackBudget)} order " +
                "budget — the app doesn't know your account equity, so this is NOT sized by " +
                "the 2% account rule. Record your holdings (and cash) to size it properly."
        }
        return buildInternal(
            symbol, candles, analysis, price, budget,
            accountEquity = equity,
            riskPerTradePct = if (equity != null) riskPerTradePct else 0.0,
            budgetBasis = budgetBasis
        )
    }

    private fun buildInternal(
        symbol: String,
        candles: List<Candle>,
        analysis: TechniqueAnalysis,
        price: Double,
        budget: Double,
        accountEquity: Double? = null,
        riskPerTradePct: Double = 0.0,
        budgetBasis: String = ""
    ): BuyPlan {
        val closes = candles.map { it.close }
        val atr = Indicators.atr(candles, 14) ?: (price * 0.02)
        val sma200 = Indicators.sma(closes, 200)
        val sma20 = analysis.maData.sma20.lastOrNull { it != null }

        val supports = analysis.srData.supports.filter { it < price }.sorted()
        val resistances = analysis.srData.resistances.filter { it > price }.sorted()
        val nearestSupport = supports.lastOrNull()
        val deeperSupport = supports.getOrNull(supports.size - 2)
        val breakout = resistances.firstOrNull()

        val fibBelow = analysis.fibData.levels
            .filter { (name, lvl) -> lvl < price && name != "0.0" && name != "1.0" }
            .sortedByDescending { it.second }
        val dipLevel = nearestSupport ?: fibBelow.firstOrNull()?.second ?: (price - 1.5 * atr)
        val deepLevel = deeperSupport
            ?: fibBelow.getOrNull(1)?.second
            ?: min(dipLevel - atr, price - 2.5 * atr)

        val direction = analysis.outlook.direction

        val aboveMa200 = sma200?.let { price > it }
        val trendNote = when (aboveMa200) {
            true -> "Price is above the 200-day average (${money(sma200)}) — Paul Tudor Jones's " +
                "first gate for being long is open."
            false -> "Price is below the 200-day average (${money(sma200)}). Tudor Jones's rule: " +
                "nothing good happens under it — keep entries small and demand proof."
            null -> "Not enough history for a 200-day average; the trend gate falls back to the " +
                "20/50-day read."
        }

        val tranches = when (direction) {
            TechniqueVerdict.BULLISH -> bullishTranches(price, atr, breakout, dipLevel, budget)
            TechniqueVerdict.NEUTRAL -> neutralTranches(price, dipLevel, deepLevel, budget)
            TechniqueVerdict.BEARISH -> bearishTranches(price, atr, dipLevel, deepLevel, sma20, budget)
        }

        val totalAmount = tranches.sumOf { it.amount }
        val totalShares = tranches.sumOf { it.shares }
        // Average entry over the INVESTED tranches only — a cash reserve
        // (shares = 0) carries dollars but no shares and would inflate it,
        // dragging the stop, risk, and targets with it.
        val investedAmount = tranches.sumOf { if (it.shares > 0.0) it.amount else 0.0 }
        val avgEntry = if (totalShares > 1e-9) investedAmount / totalShares else price

        // Stop: ATR-padded structure, never tighter than 1 ATR, never wider
        // than ~12% below the average entry — and never ABOVE the lowest
        // planned entry, or the deep limit would fill straight into a stop-out.
        val lowestEntry = tranches.filter { it.shares > 0.0 }.minOf { it.price }
        val structure = supports.filter { it < lowestEntry }.maxOrNull() ?: (lowestEntry - atr)
        val structural = min(structure - 0.75 * atr, price - 1.0 * atr)
        val floor12 = avgEntry * 0.88
        val entryCap = lowestEntry - 0.25 * atr
        var stop = max(structural, floor12)
        val stopBasis: String
        if (stop > entryCap) {
            stop = entryCap
            stopBasis = "0.25 ATR under the lowest planned entry ${money(lowestEntry)} — the " +
                "deep limit must be able to fill without an instant stop-out."
        } else if (floor12 > structural) {
            stopBasis = "capped 12% under the ${money(round2(avgEntry))} average entry — Elder's " +
                "rule that no single position may risk more."
        } else {
            stopBasis = "0.75 ATR (${money(atr)}) under the ${money(structure)} structure — " +
                "wide enough that normal daily noise cannot shake the position out."
        }
        stop = max(stop, 0.01)

        val riskPerShare = max(avgEntry - stop, 0.0)
        val riskDollars = round2(totalShares * riskPerShare)
        // Percent of the ACCOUNT when equity is known — the number Elder's
        // rule is actually about. Percent of the order budget otherwise,
        // and the labels downstream say which one it is.
        val riskPct = when {
            accountEquity != null && accountEquity > 0.0 ->
                round2(riskDollars / accountEquity * 100.0)
            budget > 0.0 -> round2(riskDollars / budget * 100.0)
            else -> 0.0
        }

        val firstTarget = resistances.firstOrNull { it > avgEntry * 1.01 }
            ?: round2(avgEntry + 2.0 * (avgEntry - stop))
        val firstTargetNote = if (resistances.any { it > avgEntry * 1.01 })
            "The nearest clustered resistance — the natural place to take the first profit."
        else
            "No clustered resistance overhead; 2R above the average entry stands in."
        val stretchTarget = round2(avgEntry + 5.0 * (avgEntry - stop))
        val stretchTargetNote = "Tudor Jones's 5:1 frame — at five times the risk, you can be " +
            "wrong four trades out of five and still come out ahead. A multi-week objective, " +
            "not a 5-day one."

        val rewardRisk = if (riskPerShare > 1e-9)
            round2((firstTarget - avgEntry) / riskPerShare) else 0.0

        val posture: String
        val postureDetail: List<String>
        when (direction) {
            TechniqueVerdict.BULLISH -> {
                posture = "Deploy on strength in three steps."
                postureDetail = listOf(
                    "The techniques lean bullish (${analysis.outlook.bullishCount} of ${analysis.results.size}), so the plan " +
                        "follows O'Neil's scaling: half the budget starts the position, and the rest is " +
                        "added only if the stock proves the thesis by moving up.",
                    "Livermore's rule is enforced throughout: every add happens at a higher price than " +
                        "the last, never on the way down."
                )
            }
            TechniqueVerdict.NEUTRAL -> {
                posture = "Let price come to you with staged limits."
                postureDetail = listOf(
                    "The techniques are split (${analysis.outlook.bullishCount} bullish, " +
                        "${analysis.outlook.bearishCount} bearish), so chasing has no edge. The plan " +
                        "starts small and parks Buffett-style limit orders at the levels where the " +
                        "chart says value waits.",
                    "Unfilled orders are cancelled at the end of day 5 — patience includes the " +
                        "discipline to walk away."
                )
            }
            else -> {
                posture = "Probe only — the tape says wait."
                postureDetail = listOf(
                    "The techniques lean bearish (${analysis.outlook.bearishCount} of ${analysis.results.size}). Every " +
                        "investor in this playbook says the same thing here: do not fight the tape " +
                        "with full size.",
                    "Only a small probe goes in at deep support, and the remaining budget waits in " +
                        "cash for the reversal trigger. Missing the first day of a turn is cheaper " +
                        "than catching a falling knife."
                )
            }
        }

        val schedule = buildSchedule(direction, tranches, stop, breakout, sma20)

        val principles = listOf(
            "Jesse Livermore" to "Probe first, pyramid only after the market proves you right, " +
                "and never average down on a failing probe.",
            "William O'Neil" to "Scale a working entry roughly 50/30/20 so the biggest buy is " +
                "the best-informed one.",
            "Paul Tudor Jones" to "Only be long above the 200-day average, frame the stretch " +
                "exit at 5:1 reward-to-risk, and play defense first.",
            "Alexander Elder" to if (accountEquity != null && accountEquity > 0.0) {
                "The 2% rule, applied to your ACCOUNT: a full stop-out here loses " +
                    "${money(riskDollars)} — ${pct1(riskPct)} of your " +
                    "${money(accountEquity)} equity, inside the ${pct1(riskPerTradePct)} " +
                    "risk-per-trade policy."
            } else {
                "Elder's 2% rule caps the loss at 2% of ACCOUNT equity — which this app " +
                    "doesn't know yet. As shown, a stop-out loses ${money(riskDollars)} " +
                    "(${pct1(riskPct)} of the ${money(budget)} order budget, NOT of your " +
                    "account). Record your holdings to size this properly."
            },
            "Buffett & Munger" to "Wait for your price: limit orders at support instead of " +
                "market orders at whatever the day offers.",
            "Volatility stops" to "The stop lives ${money(atr)} (one ATR) worth of padding " +
                "under structure, not under the entry, so routine noise cannot trigger it."
        )

        return BuyPlan(
            symbol = symbol,
            budget = budget,
            accountEquity = accountEquity,
            riskPerTradePct = riskPerTradePct,
            budgetBasis = budgetBasis,
            posture = posture,
            postureDetail = postureDetail,
            trendNote = trendNote,
            tranches = tranches,
            avgEntry = round2(avgEntry),
            totalShares = round4(totalShares),
            stop = round2(stop),
            stopBasis = stopBasis,
            riskDollars = riskDollars,
            riskPct = riskPct,
            firstTarget = round2(firstTarget),
            firstTargetNote = firstTargetNote,
            stretchTarget = stretchTarget,
            stretchTargetNote = stretchTargetNote,
            rewardRisk = rewardRisk,
            schedule = schedule,
            principles = principles,
            caveat = "Computed from public price history and published investor playbooks. " +
                "It is a decision aid, not financial advice — position sizes assume the " +
                "${money(budget)} is money you can hold through a full stop-out."
        )
    }

    // ------------------------------------------------------------ tranches

    private fun bullishTranches(
        price: Double,
        atr: Double,
        breakout: Double?,
        dipLevel: Double,
        budget: Double
    ): List<PlanTranche> {
        val t1Amt = round2(budget * 0.50)
        val t2Amt = round2(budget * 0.30)
        val t3Amt = round2(budget - t1Amt - t2Amt)
        val t2Price = round2(breakout ?: (price * 1.025))
        val t3Price = round2(max(t2Price * 1.02, price + 1.0 * atr))
        return listOf(
            PlanTranche(
                label = "Starter — half the budget",
                amount = t1Amt,
                price = round2(price),
                shares = shares(t1Amt, price),
                condition = "Buy near the current price with a limit at ${money(price)}. " +
                    "This is the O'Neil pilot position: big enough to matter, small enough " +
                    "to be wrong.",
                day = "Day 1"
            ),
            PlanTranche(
                label = "Add on proof",
                amount = t2Amt,
                price = t2Price,
                shares = shares(t2Amt, t2Price),
                condition = (if (breakout != null)
                    "Add only after a daily close above the ${money(t2Price)} resistance — "
                else
                    "Add only after price gains ~2.5% to ${money(t2Price)} — ") +
                    "the market has proven the starter right (Livermore).",
                day = "Days 2–3"
            ),
            PlanTranche(
                label = "Final add",
                amount = t3Amt,
                price = t3Price,
                shares = shares(t3Amt, t3Price),
                condition = "Last slice at ${money(t3Price)} if the advance continues, or on a " +
                    "pullback that holds ${money(dipLevel)} — never below your last buy point.",
                day = "Days 3–5"
            )
        )
    }

    private fun neutralTranches(
        price: Double,
        dipLevel: Double,
        deepLevel: Double,
        budget: Double
    ): List<PlanTranche> {
        val t1Amt = round2(budget * 0.30)
        val t2Amt = round2(budget * 0.35)
        val t3Amt = round2(budget - t1Amt - t2Amt)
        val t2Price = round2(dipLevel)
        val t3Price = round2(min(deepLevel, t2Price * 0.995))
        return listOf(
            PlanTranche(
                label = "Starter — stake in the ground",
                amount = t1Amt,
                price = round2(price),
                shares = shares(t1Amt, price),
                condition = "Small buy near ${money(price)} so a surprise rally is not missed " +
                    "entirely. Deliberately the smallest slice — the edge is at the levels below.",
                day = "Day 1"
            ),
            PlanTranche(
                label = "Value limit at the dip",
                amount = t2Amt,
                price = t2Price,
                shares = shares(t2Amt, t2Price),
                condition = "Good-til-cancelled limit at ${money(t2Price)} — the first level the " +
                    "chart marks as value. Let price come to you (Buffett).",
                day = "Days 1–5"
            ),
            PlanTranche(
                label = "Deep value limit",
                amount = t3Amt,
                price = t3Price,
                shares = shares(t3Amt, t3Price),
                condition = "Limit at ${money(t3Price)}, the deeper support. Fills here buy fear. " +
                    "Cancel whatever has not filled by the day-5 close.",
                day = "Days 1–5"
            )
        )
    }

    private fun bearishTranches(
        price: Double,
        atr: Double,
        dipLevel: Double,
        deepLevel: Double,
        sma20: Double?,
        budget: Double
    ): List<PlanTranche> {
        val t1Amt = round2(budget * 0.20)
        val t2Amt = round2(budget * 0.40)
        val t3Amt = round2(budget - t1Amt - t2Amt)
        val probePrice = round2(deepLevel)
        val triggerPrice = round2(sma20 ?: (price + 1.0 * atr))
        return listOf(
            PlanTranche(
                label = "Probe at deep support",
                amount = t1Amt,
                price = probePrice,
                shares = shares(t1Amt, probePrice),
                condition = "Only a probe: limit at ${money(probePrice)}, the strongest support " +
                    "below. If it breaks on a close, the probe is wrong — take the small loss " +
                    "(Livermore's test position).",
                day = "Days 2–5"
            ),
            PlanTranche(
                label = "Add on the turn",
                amount = t2Amt,
                price = triggerPrice,
                shares = shares(t2Amt, triggerPrice),
                condition = "Add only after a daily close back above ${money(triggerPrice)} " +
                    "(the 20-day average) — the first objective sign the downtrend is breaking.",
                day = "After the trigger"
            ),
            PlanTranche(
                label = "Reserve — stays in cash",
                amount = t3Amt,
                price = round2(price),
                shares = 0.0,
                condition = "Held back until the technique board flips bullish. Cash is a " +
                    "position; re-run this analysis after the reversal.",
                day = "Not this week"
            )
        )
    }

    // ------------------------------------------------------------ schedule

    private fun buildSchedule(
        direction: TechniqueVerdict,
        tranches: List<PlanTranche>,
        stop: Double,
        breakout: Double?,
        sma20: Double?
    ): List<PlanDay> {
        val t1 = tranches.getOrNull(0)
        val t2 = tranches.getOrNull(1)
        val t3 = tranches.getOrNull(2)
        return when (direction) {
            TechniqueVerdict.BULLISH -> listOf(
                PlanDay(
                    1, "Open the starter",
                    listOf(
                        "Place the ${t1?.let { money(it.amount) } ?: ""} starter limit at ${t1?.let { money(it.price) } ?: "the current price"}.",
                        "Set the stop alert at ${money(stop)} the moment the fill lands — the stop is part of the entry, not an afterthought."
                    )
                ),
                PlanDay(
                    2, "Watch for proof",
                    listOf(
                        breakout?.let { "Watch the ${money(it)} resistance — a daily close above it arms tranche 2." }
                            ?: "Watch for the +2.5% confirmation level that arms tranche 2.",
                        "If instead the stock closes below ${money(stop)}, the starter exits. No adds, no averaging down."
                    )
                ),
                PlanDay(
                    3, "Add on strength",
                    listOf(
                        "Fill tranche 2 (${t2?.let { money(it.amount) } ?: ""}) only on the confirmed close above its trigger.",
                        "Raise the mental stop to just under the breakout level once it is reclaimed as support."
                    )
                ),
                PlanDay(
                    4, "Complete the pyramid",
                    listOf(
                        "If the advance holds, place the final ${t3?.let { money(it.amount) } ?: ""} at ${t3?.let { money(it.price) } ?: "the final trigger"}.",
                        "Every add sits above the last buy — the position is only ever full-size when the market has been right twice."
                    )
                ),
                PlanDay(
                    5, "Review and lock the plan",
                    listOf(
                        "Whatever filled, write the exit pair down: stop ${money(stop)}, first target at the resistance overhead.",
                        "If nothing confirmed all week, the starter stays the whole position — that is the system working, not failing."
                    )
                )
            )
            TechniqueVerdict.NEUTRAL -> listOf(
                PlanDay(
                    1, "Set the grid",
                    listOf(
                        "Buy the small starter, then park both good-til-cancelled limits at the value levels.",
                        "Set the stop alert at ${money(stop)} for everything that fills."
                    )
                ),
                PlanDay(2, "Do nothing well", listOf(
                    "The orders are working. Checking the chart hourly adds risk, not information."
                )),
                PlanDay(3, "Mid-week check", listOf(
                    "If a limit filled, confirm the level held into the close — a close through it puts the fill on notice against ${money(stop)}."
                )),
                PlanDay(4, "Respect the range", listOf(
                    "No chasing strength above the starter price — the plan's edge is buying weakness at chosen levels."
                )),
                PlanDay(
                    5, "Cancel and reassess",
                    listOf(
                        "Cancel unfilled limits at the close (Buffett's patience includes walking away).",
                        "Re-run the analysis: if the board turned bullish, next week's plan pivots to the strength playbook."
                    )
                )
            )
            else -> listOf(
                PlanDay(1, "Stand aside", listOf(
                    "No market orders. Place only the small probe limit at deep support.",
                    "Log the reversal trigger: a daily close back above ${sma20?.let { money(it) } ?: "the 20-day average"}."
                )),
                PlanDay(2, "Let it fall to you", listOf(
                    "If the probe fills, the stop is immediate and tight at ${money(stop)} — a probe that breaks support was simply wrong."
                )),
                PlanDay(3, "Watch the trigger", listOf(
                    "Only a close above the trigger level arms the second tranche. Intraday spikes do not count."
                )),
                PlanDay(4, "Patience is the position", listOf(
                    "The reserve stays in cash. Nothing in this playbook rewards being early to a downtrend."
                )),
                PlanDay(
                    5, "Weekly verdict",
                    listOf(
                        "Re-run the analysis. A bullish flip unlocks the full deployment plan; a still-bearish board keeps the cash.",
                        "If the probe stopped out, the loss was the price of information — the budget survives to buy the actual turn."
                    )
                )
            )
        }
    }

    // ------------------------------------------------------------ helpers

    private fun shares(amount: Double, price: Double): Double =
        if (price > 1e-9) round4(amount / price) else 0.0

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun round4(v: Double): Double = Math.round(v * 10000.0) / 10000.0

    private fun money(v: Double?): String =
        if (v == null) "—" else com.aurum.invest.core.Fmt.money(v)

    private fun pct1(v: Double): String = String.format(java.util.Locale.US, "%.1f%%", abs(v))
}
