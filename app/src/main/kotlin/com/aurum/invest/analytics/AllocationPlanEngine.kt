package com.aurum.invest.analytics

import com.aurum.invest.data.repo.InvestorProfile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** What the plan does to one existing position. */
enum class AllocationMove { EXIT, REDUCE, HOLD, ADD }

/** One existing holding's place in the plan: where it sits, where it should sit. */
data class AllocationTarget(
    val symbol: String,
    val name: String,
    val sector: String,
    /** Share of the plan's base (equity when cash is tracked, the book otherwise). */
    val currentPct: Double,
    val targetPct: Double,
    val currentValue: Double,
    val targetValue: Double,
    /** Positive = add this many dollars, negative = release this many. */
    val deltaValue: Double,
    /** Shares to buy (+) or sell (−) at the latest price; 0 when the price is unusable. */
    val approxShares: Double,
    val move: AllocationMove,
    val conviction: Int,
    val convictionMax: Int,
    /** Which rule set the ceiling — named so the number is never a mystery. */
    val cappedBy: String,
    val note: String
)

/**
 * The whole-book allocation answer: what each position should be worth, what
 * that frees, how much cash to keep, and what the market scan would do with
 * the rest.
 */
data class AllocationPlan(
    val computedAt: Long,
    /** The money every percentage below is a share of. */
    val base: Double,
    /** True when [base] is real equity (holdings + tracked cash); false when it is the book alone. */
    val baseIsEquity: Boolean,
    val invested: Double,
    /** Uninvested cash; null when the wallet total is not tracked. */
    val liquidity: Double?,
    val headline: String,
    val marketNote: String,
    val targets: List<AllocationTarget>,
    /** New positions the market scan would open with what the plan frees up. */
    val adds: List<LiquidityAllocationLine>,
    val sectorTargets: List<SectorAllocationTarget>,
    /** Dollars the trims and exits release. */
    val freedCash: Double,
    val cashFloorPct: Double,
    val cashFloorValue: Double,
    /** Cash as a share of the base once the whole plan is executed. */
    val targetCashPct: Double,
    val cashNote: String,
    val notes: List<String>,
    val policyNote: String,
    val caveat: String
)

/**
 * The standalone allocation-plan engine: the answer behind "Allocation plan".
 *
 * It does three things no simple weight table does:
 *  1. sizes every position against the RISK its own stop implies, not against
 *     a flat percentage — a wide-stopped name earns a smaller dollar slot than
 *     a tight-stopped one at the same conviction;
 *  2. keeps evaluating the book it already holds — every verdict's own
 *     keep-share decides how much of a position survives, so the plan and the
 *     holding cards can never contradict each other;
 *  3. scans the market for what to do with the money the plan frees, by
 *     handing the post-plan book to [LiquidityAllocationEngine] — the same
 *     candidate scoring, the same caps, the same reserve honesty, one
 *     derivation.
 *
 * Integrity rules:
 *  - pure function over its inputs; no I/O, no throws, no clock beyond the stamp
 *  - percentages are shares of a base that is NAMED: real equity when the
 *    wallet is tracked, the invested book when it is not — never a silent mix
 *  - a target is never raised above the tightest of (position cap, risk budget,
 *    conviction ceiling), and the binding rule is printed on the line
 *  - adds to an existing winner require a MEASURED conviction, a measured stop
 *    and free capital; without any of the three the line just holds
 *  - the cash floor is a stated rule (regime + risk tolerance), not a residual
 *  - nothing is deployed that the freed capital and tracked cash cannot pay for
 */
object AllocationPlanEngine {

    /** Conviction ratio at or above which an under-sized winner earns an add. */
    private const val ADD_CONVICTION = 70

    /** Risk ratio above which no add is proposed, whatever the conviction. */
    private const val ADD_MAX_RISK = 40

    /** Don't move money for less than this — a rounding-noise ticket helps no one. */
    private const val MIN_TICKET = 25.0

    fun build(
        verdicts: List<HoldingVerdict>,
        equity: EquityContext,
        moneyFlow: MoneyFlowReport?,
        sectorTrends: List<SectorTrend>,
        candidates: List<LiquidityCandidate>,
        profile: InvestorProfile,
        pulse: MarketRating? = null,
        marketNote: String = ""
    ): AllocationPlan {
        val now = System.currentTimeMillis()
        val caveat =
            "Every target is a share of the base named above, sized against this profile's " +
                "position cap, sector cap and per-trade risk budget, and against each holding's " +
                "own measured stop. Decision support, not financial advice."
        val policyNote = safePolicy(profile)

        val holdingsValue = verdicts.sumOf { it.marketValue }
        val baseIsEquity = equity.cashTracked
        val base = (if (baseIsEquity) holdingsValue + (equity.liquidity ?: 0.0) else holdingsValue)
            .coerceAtLeast(0.0)

        if (base <= 0.0 || verdicts.isEmpty()) {
            return AllocationPlan(
                computedAt = now, base = base, baseIsEquity = baseIsEquity,
                invested = equity.invested, liquidity = equity.liquidity,
                headline = "Nothing to allocate — no holding could be valued this run.",
                marketNote = marketNote, targets = emptyList(), adds = emptyList(),
                sectorTargets = emptyList(), freedCash = 0.0,
                cashFloorPct = 0.0, cashFloorValue = 0.0, targetCashPct = 0.0,
                cashNote = "", notes = emptyList(), policyNote = policyNote, caveat = caveat
            )
        }

        val positionCap = PortfolioAdvisor.positionCapPct(profile).coerceIn(1.0, 100.0)
        val maxSectorPct = profile.maxSectorPct.coerceIn(10.0, 100.0)
        val riskPerTradePct = profile.riskPerTradePct.coerceIn(0.1, 10.0)
        val positionCapValue = base * positionCap / 100.0
        val riskBudgetValue = base * riskPerTradePct / 100.0

        // ---- 1) every holding's ceiling and its verdict-implied size --------
        val notes = ArrayList<String>()
        val sized = verdicts.map { v ->
            sizeOne(v, base, positionCapValue, riskBudgetValue, positionCap, riskPerTradePct)
        }

        // ---- 2) the cash rule ----------------------------------------------
        val (cashFloorPct, cashReason) = cashFloor(profile, pulse)
        val cashFloorValue = base * cashFloorPct / 100.0

        // Money the trims and exits release.
        val freed = sized.sumOf { (it.currentValue - it.verdictValue).coerceAtLeast(0.0) }
        val cashAfterVerdicts = (equity.liquidity ?: 0.0) + freed
        var deployable = (cashAfterVerdicts - cashFloorValue).coerceAtLeast(0.0)

        // ---- 3) adds to positions that are under-sized for their conviction -
        // Ordered by conviction ratio; each add is capped by its own ceiling,
        // by the sector cap, and by what is actually left to spend.
        val sectorValueNow = LinkedHashMap<String, Double>()
        sized.forEach { s ->
            if (s.sector.isNotBlank() && s.sector != PortfolioLens.UNCLASSIFIED) {
                sectorValueNow[s.sector] = (sectorValueNow[s.sector] ?: 0.0) + s.verdictValue
            }
        }
        val sectorRoom = HashMap<String, Double>()
        sectorValueNow.forEach { (sector, held) ->
            sectorRoom[sector] = (base * maxSectorPct / 100.0 - held).coerceAtLeast(0.0)
        }

        val addByCandidate = HashMap<String, Double>()
        sized.filter { it.addRoom >= MIN_TICKET }
            .sortedByDescending { it.convictionRatio }
            .forEach { s ->
                if (deployable < MIN_TICKET) return@forEach
                val room = minOf(
                    s.addRoom,
                    deployable,
                    if (s.sector.isBlank() || s.sector == PortfolioLens.UNCLASSIFIED) deployable
                    else (sectorRoom[s.sector] ?: 0.0)
                )
                if (room < MIN_TICKET) return@forEach
                val amount = AllocationMath.roundToTicket(room).coerceAtMost(room)
                if (amount < MIN_TICKET) return@forEach
                addByCandidate[s.symbol] = amount
                deployable -= amount
                if (s.sector.isNotBlank() && s.sector != PortfolioLens.UNCLASSIFIED) {
                    sectorRoom[s.sector] = (sectorRoom[s.sector] ?: 0.0) - amount
                }
            }

        // ---- 4) the finished target lines ----------------------------------
        val targets = sized.map { s ->
            val added = addByCandidate[s.symbol] ?: 0.0
            val targetValue = s.verdictValue + added
            val delta = targetValue - s.currentValue
            val move = when {
                targetValue <= 0.005 -> AllocationMove.EXIT
                delta < -0.005 -> AllocationMove.REDUCE
                delta > 0.005 -> AllocationMove.ADD
                else -> AllocationMove.HOLD
            }
            AllocationTarget(
                symbol = s.symbol,
                name = s.name,
                sector = s.sector,
                currentPct = AllocationMath.round1(s.currentValue / base * 100.0),
                targetPct = AllocationMath.round1(targetValue / base * 100.0),
                currentValue = AllocationMath.round2(s.currentValue),
                targetValue = AllocationMath.round2(targetValue),
                deltaValue = AllocationMath.round2(delta),
                approxShares =
                    if (s.price > 0.0) AllocationMath.round4(delta / s.price) else 0.0,
                move = move,
                conviction = s.conviction,
                convictionMax = s.convictionMax,
                cappedBy = when {
                    added > 0.0 -> s.addCappedBy
                    move == AllocationMove.HOLD && s.currentValue < s.holdCeiling - 0.005 -> ""
                    else -> s.cappedBy
                },
                note = targetNote(s, added, move, base)
            )
        }.sortedByDescending { it.targetValue }

        // ---- 5) what the market scan does with what is left -----------------
        // The post-plan book is handed to the liquidity engine so new names are
        // scored, capped and reserved by exactly the same rules as the
        // "where to put your money" card. One derivation, no drift.
        val postBookValue = targets.sumOf { it.targetValue }
        val postBook = BookContext(
            totalValue = postBookValue,
            heldWeights =
                if (postBookValue > 0.0) {
                    targets.filter { it.targetValue > 0.0 }
                        .associate { it.symbol to it.targetValue / postBookValue * 100.0 }
                } else emptyMap(),
            slices =
                if (postBookValue > 0.0) {
                    targets.filter { it.targetValue > 0.0 }
                        .groupBy { it.sector.ifBlank { PortfolioLens.UNCLASSIFIED } }
                        .map { (sector, list) ->
                            val value = list.sumOf { it.targetValue }
                            SectorSlice(
                                sector = sector,
                                value = value,
                                weightPct = value / postBookValue * 100.0,
                                symbols = list.sortedByDescending { it.targetValue }.map { it.symbol }
                            )
                        }.sortedByDescending { it.value }
                } else emptyList()
        )
        val newMoney =
            if (deployable >= MIN_TICKET && candidates.isNotEmpty()) {
                LiquidityAllocationEngine.build(
                    liquidity = deployable,
                    book = postBook,
                    moneyFlow = moneyFlow,
                    sectorTrends = sectorTrends,
                    candidates = candidates.filter { c -> targets.none { it.symbol == c.symbol } },
                    profile = profile,
                    marketNote = marketNote
                )
            } else null
        val deployedNew = newMoney?.lines?.sumOf { it.amount } ?: 0.0
        val leftover = (deployable - deployedNew).coerceAtLeast(0.0)

        // Sector targets always come from the shared derivation, even when no
        // new money is being deployed — the card still shows where the book
        // should sit against the measured flow.
        val sectorTargets = newMoney?.sectorTargets
            ?: AllocationMath.sectorTargets(
                sectorValueNow = postBook.slices.associate { it.sector to it.value },
                totalBase = base,
                candidateSectors = candidates.mapNotNull { c ->
                    c.sector.takeIf { it.isNotBlank() && it != PortfolioLens.UNCLASSIFIED }
                }.toSet(),
                flowByKey = moneyFlow?.sectors?.associateBy { it.key } ?: emptyMap(),
                trendByKey = sectorTrends.associateBy { it.key },
                maxSectorPct = maxSectorPct
            )

        // ---- 6) the cash line ----------------------------------------------
        // Conservation, not aspiration: cash after the plan is whatever the
        // tracked wallet plus the freed capital minus everything deployed comes
        // to. Quoting the floor here would claim cash a book without a tracked
        // wallet has no way to actually hold.
        val addsTotal = addByCandidate.values.sum()
        val targetCashValue =
            ((equity.liquidity ?: 0.0) + freed - addsTotal - deployedNew).coerceAtLeast(0.0)
        val targetCashPct =
            if (base > 0.0) AllocationMath.round1(targetCashValue / base * 100.0) else 0.0
        val cashNote = buildString {
            append(
                String.format(
                    Locale.US, "Hold %s (%.0f%% of the base) as cash: %s.",
                    AllocationMath.money(targetCashValue), targetCashPct, cashReason
                )
            )
            if (targetCashValue < cashFloorValue - 0.005) {
                append(
                    String.format(
                        Locale.US,
                        " That is under the %.0f%% floor: only %s could be raised this run, so the " +
                            "floor is the direction of travel, not a claim about today.",
                        cashFloorPct, AllocationMath.money(targetCashValue)
                    )
                )
            }
            if (leftover >= MIN_TICKET) {
                append(" ")
                append(
                    newMoney?.reserveReason?.takeIf { it.isNotEmpty() }
                        ?: String.format(
                            Locale.US,
                            "%s of that is uncommitted because no market candidate was supplied this run.",
                            AllocationMath.money(leftover)
                        )
                )
            }
            if (!baseIsEquity) {
                append(
                    " The wallet total is not tracked, so the base is the invested book and only " +
                        "money this plan frees can be redeployed."
                )
            }
        }

        // ---- 7) notes and headline -----------------------------------------
        val exits = targets.count { it.move == AllocationMove.EXIT }
        val reduces = targets.count { it.move == AllocationMove.REDUCE }
        val addsToHeld = targets.count { it.move == AllocationMove.ADD }
        val newNames = newMoney?.lines?.size ?: 0
        val headline = when {
            exits + reduces + addsToHeld + newNames == 0 ->
                "Every position already sits inside its caps and its risk budget — no move required."
            else -> buildList {
                if (exits > 0) add("$exits to exit")
                if (reduces > 0) add("$reduces to reduce")
                if (addsToHeld > 0) add("$addsToHeld to add to")
                if (newNames > 0) add("$newNames new name${if (newNames == 1) "" else "s"}")
            }.joinToString(", ").replaceFirstChar { it.uppercase(Locale.US) } +
                String.format(Locale.US, " — %s moves.", AllocationMath.money(
                    targets.sumOf { abs(it.deltaValue) } + deployedNew
                ))
        }

        notes += String.format(
            Locale.US,
            "Sizing rule: no position above %s (your %.0f%% cap) and none risking more than %s to its own stop (your %s per trade).",
            AllocationMath.money(positionCapValue), positionCap,
            AllocationMath.money(riskBudgetValue), pct1(riskPerTradePct)
        )
        if (freed > 0.005) {
            notes += String.format(
                Locale.US,
                "Trims and exits release %s; %s is redeployed and %s stays in cash.",
                AllocationMath.money(freed), AllocationMath.money(addsTotal + deployedNew),
                AllocationMath.money(targetCashValue)
            )
        }
        val unsized = sized.count { it.riskSizedValue == null }
        if (unsized > 0) {
            notes += "$unsized of ${sized.size} holdings have no measurable stop this run, so the " +
                "risk budget could not size them — their ceiling is the position cap alone."
        }
        if (candidates.isEmpty()) {
            notes += "No market candidate was supplied this run, so the plan proposes no new names — " +
                "the freed cash is reported as cash, not forced into a position."
        }
        if (newMoney != null && newMoney.lines.isNotEmpty()) {
            notes += String.format(
                Locale.US,
                "New names are capped against the post-plan book plus the %s being deployed (%s), " +
                    "which is at or below the %s base above — the tighter of the two readings.",
                AllocationMath.money(deployable),
                AllocationMath.money(postBookValue + deployable),
                AllocationMath.money(base)
            )
        }

        return AllocationPlan(
            computedAt = now,
            base = AllocationMath.round2(base),
            baseIsEquity = baseIsEquity,
            invested = AllocationMath.round2(equity.invested),
            liquidity = equity.liquidity?.let { AllocationMath.round2(it) },
            headline = headline,
            marketNote = marketNote,
            targets = targets,
            adds = newMoney?.lines ?: emptyList(),
            sectorTargets = sectorTargets,
            freedCash = AllocationMath.round2(freed),
            cashFloorPct = AllocationMath.round1(cashFloorPct),
            cashFloorValue = AllocationMath.round2(cashFloorValue),
            targetCashPct = targetCashPct,
            cashNote = cashNote,
            notes = notes,
            policyNote = policyNote,
            caveat = caveat
        )
    }

    // -------------------------------------------------------------- one holding

    private data class Sized(
        val symbol: String,
        val name: String,
        val sector: String,
        val price: Double,
        val currentValue: Double,
        /** What the holding's own verdict leaves standing, before any ceiling. */
        val keptByVerdict: Double,
        /** What survives BOTH the verdict and risk control, before any add. */
        val verdictValue: Double,
        /** Headroom an add could use, already clamped by every ceiling. */
        val addRoom: Double,
        /** The largest slot risk control allows an EXISTING position to keep. */
        val holdCeiling: Double,
        val riskSizedValue: Double?,
        /** Which rule binds [holdCeiling]. */
        val cappedBy: String,
        /** Which rule binds the add ceiling — conviction usually. */
        val addCappedBy: String,
        val conviction: Int,
        val convictionMax: Int,
        val convictionRatio: Int
    )

    /**
     * Two ceilings, deliberately different:
     *  - the HOLD ceiling (position cap ∧ risk budget) is what an existing
     *    position may keep. Conviction does not force a sale — the holding's
     *    own verdict is the authority on selling, and the plan must not
     *    contradict a card that just said "hold".
     *  - the ADD ceiling additionally respects measured conviction: new money
     *    into an existing name has to be earned, not merely permitted.
     */
    private fun sizeOne(
        v: HoldingVerdict,
        base: Double,
        positionCapValue: Double,
        riskBudgetValue: Double,
        positionCapPct: Double,
        riskPerTradePct: Double
    ): Sized {
        val current = v.marketValue.coerceAtLeast(0.0)
        // The verdict's own keep-share is the single source of truth for how
        // much of a position survives — the plan never re-derives it.
        val kept = (current * v.keepSharePct / 100.0).coerceIn(0.0, current)

        // Risk-based ceiling: the dollar size at which this position losing to
        // its own stop costs exactly the per-trade risk budget.
        // stop == 0.0 means "no level could be measured", not "a stop at zero".
        val riskPerShare = if (v.stop > 0.0 && v.stop < v.price) v.price - v.stop else null
        val riskSized = riskPerShare
            ?.takeIf { it > 0.0 && v.price > 0.0 }
            ?.let { riskBudgetValue / it * v.price }

        val holdCeilings = listOfNotNull(
            positionCapValue to String.format(Locale.US, "your %.0f%% position cap", positionCapPct),
            riskSized?.let {
                it to String.format(Locale.US, "your %s per-trade risk budget", pct1(riskPerTradePct))
            }
        )
        val bindingHold = holdCeilings.minBy { it.first }
        val holdCeiling = bindingHold.first.coerceAtLeast(0.0)

        val convictionRatio =
            if (v.convictionMax > 0) v.conviction * 100 / v.convictionMax else 0
        val convictionSized =
            if (v.convictionMax > 0) positionCapValue * (convictionRatio / 100.0) else null
        val addCeilings = holdCeilings + listOfNotNull(
            convictionSized?.let { it to "its measured conviction ($convictionRatio%)" }
        )
        val bindingAdd = addCeilings.minBy { it.first }

        val riskRatio = if (v.riskScoreMax > 0) v.riskScore * 100 / v.riskScoreMax else 100
        val canAdd = v.action == HoldingAction.HOLD &&
            v.convictionMax > 0 &&
            convictionRatio >= ADD_CONVICTION &&
            riskRatio < ADD_MAX_RISK &&
            v.stage == HoldingStage.ADVANCING &&
            riskSized != null
        // A position already past its hold ceiling is reduced to it — that is
        // risk control, and the note says so rather than blaming the verdict.
        val verdictValue = if (kept > 0.0) min(kept, holdCeiling) else 0.0
        val addRoom =
            if (canAdd) (bindingAdd.first.coerceAtLeast(0.0) - verdictValue).coerceAtLeast(0.0)
            else 0.0

        return Sized(
            symbol = v.symbol,
            name = v.name,
            sector = v.sector,
            price = v.price,
            currentValue = current,
            keptByVerdict = kept,
            verdictValue = verdictValue,
            addRoom = addRoom,
            holdCeiling = holdCeiling,
            riskSizedValue = riskSized,
            cappedBy = bindingHold.second,
            addCappedBy = bindingAdd.second,
            conviction = v.conviction,
            convictionMax = v.convictionMax,
            convictionRatio = convictionRatio
        )
    }

    private fun targetNote(s: Sized, added: Double, move: AllocationMove, base: Double): String {
        val pct = if (base > 0.0) s.currentValue / base * 100.0 else 0.0
        val targetPct = if (base > 0.0) s.verdictValue / base * 100.0 else 0.0
        // A reduction has exactly two possible causes and they must not be
        // confused: the holding's own verdict released part of it, or risk
        // control capped the slot. Both can apply, and then both are named.
        val cutByVerdict = s.keptByVerdict < s.currentValue - 0.005
        val cutByCeiling = s.verdictValue < s.keptByVerdict - 0.005
        return when (move) {
            AllocationMove.EXIT ->
                "Exit in full — its holding card carries the measured reason and the timing."
            AllocationMove.REDUCE -> when {
                cutByVerdict && cutByCeiling -> String.format(
                    Locale.US,
                    "Reduce from %.1f%% of the base to %.1f%% — its own verdict releases part of " +
                        "it, and risk control caps the rest at %s.",
                    pct, targetPct, s.cappedBy
                )
                cutByVerdict -> String.format(
                    Locale.US,
                    "Reduce from %.1f%% of the base to %.1f%% — the size its own verdict leaves in place.",
                    pct, targetPct
                )
                else -> String.format(
                    Locale.US,
                    "Reduce from %.1f%% of the base to %.1f%% — risk control, not a verdict on the " +
                        "stock: %s is the binding rule.",
                    pct, targetPct, s.cappedBy
                )
            }
            AllocationMove.ADD -> String.format(
                Locale.US,
                "Add %s — conviction %d/%d (%d%%) with an advancing stage, and the slot is still " +
                    "under %s.",
                AllocationMath.money(added), s.conviction, s.convictionMax, s.convictionRatio,
                s.addCappedBy
            )
            AllocationMove.HOLD ->
                if (s.currentValue >= s.holdCeiling - 0.005) {
                    "Already at its ceiling — ${s.cappedBy} is the binding rule."
                } else {
                    String.format(
                        Locale.US,
                        "Sized correctly at %.1f%% of the base; no add — %s.",
                        pct,
                        if (s.convictionMax <= 0) "conviction could not be measured this run"
                        else if (s.convictionRatio < ADD_CONVICTION)
                            "conviction ${s.convictionRatio}% is below the $ADD_CONVICTION% bar for adding"
                        else "the plan has no free capital for it"
                    )
                }
        }
    }

    // ------------------------------------------------------------------ cash

    /**
     * The cash floor: the tape's demand and the profile's own tolerance,
     * whichever is more defensive. An unmeasured tape contributes nothing —
     * the profile's floor stands alone and the note says the regime was not
     * measured, rather than inventing a neutral market.
     */
    private fun cashFloor(profile: InvestorProfile, pulse: MarketRating?): Pair<Double, String> {
        val toleranceFloor = when (profile.riskTolerance) {
            InvestorProfile.TOL_CONSERVATIVE -> 15.0
            InvestorProfile.TOL_AGGRESSIVE -> 4.0
            else -> 8.0
        }
        val regimeFloor = when (pulse?.call) {
            MarketCall.INVEST -> 5.0
            MarketCall.SELECTIVE -> 12.0
            MarketCall.DEFENSIVE -> 25.0
            MarketCall.INCOMPLETE, null -> null
        }
        val floor = maxOf(toleranceFloor, regimeFloor ?: 0.0)
        val reason = when {
            regimeFloor == null -> String.format(
                Locale.US,
                "your %s tolerance asks for %.0f%%, and the market pulse had no measured call this run",
                profile.riskTolerance.lowercase(Locale.US), toleranceFloor
            )
            regimeFloor >= toleranceFloor -> String.format(
                Locale.US,
                "a %s tape asks for %.0f%%, above your %s tolerance's %.0f%%",
                pulse!!.call.name.lowercase(Locale.US), regimeFloor,
                profile.riskTolerance.lowercase(Locale.US), toleranceFloor
            )
            else -> String.format(
                Locale.US,
                "your %s tolerance asks for %.0f%%, above the %.0f%% a %s tape would need",
                profile.riskTolerance.lowercase(Locale.US), toleranceFloor, regimeFloor,
                pulse!!.call.name.lowercase(Locale.US)
            )
        }
        return floor to reason
    }

    // --------------------------------------------------------------- helpers

    private fun safePolicy(profile: InvestorProfile): String = try {
        profile.label()
    } catch (_: Throwable) {
        String.format(
            Locale.US,
            "%s · %s risk/trade · %d%% max position · %d%% max sector",
            profile.riskTolerance.lowercase(Locale.US), pct1(profile.riskPerTradePct),
            profile.maxPositionPct.toInt(), profile.maxSectorPct.toInt()
        )
    }

    private fun pct1(v: Double): String = String.format(Locale.US, "%.1f%%", v)
}
