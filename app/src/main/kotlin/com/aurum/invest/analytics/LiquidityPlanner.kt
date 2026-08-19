package com.aurum.invest.analytics

import com.aurum.invest.data.model.EarningsInfo
import com.aurum.invest.data.repo.InvestorProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** One concrete "put this much into this stock" line of the deployment. */
data class DeployLine(
    val rank: Int,                 // 1 = strongest conviction in its theme
    val symbol: String,
    val name: String,
    val amount: Double,            // dollars, > 0
    val approxShares: Double,
    val price: Double,
    val entry: Double,             // the strategy's suggested entry for the name
    val conviction: Int,           // 0..100, from measured evidence only
    val rationale: List<String>
)

/** One trendy theme's slice of the money: how much, and which stocks carry it. */
data class SectorDeployment(
    val themeKey: String,
    val label: String,
    val amount: Double,            // dollars into this theme, = sum of lines
    val heldPct: Double,           // share of the book already here
    val targetPct: Double,         // the strategy's suggested share
    val status: GapStatus,
    val lines: List<DeployLine>
)

/** The whole liquidity answer: deploy this much, here, keep the rest as cash. */
data class DeploymentPlan(
    val computedAt: Long,
    val liquidity: Double,
    val deployed: Double,
    val reserve: Double,
    val reserveReason: String,
    val headline: String,
    val policyNote: String,
    val marketNote: String,
    val sectors: List<SectorDeployment>,
    val caveat: String
)

/**
 * The liquidity-management engine: takes the wallet's real uninvested cash,
 * the current book, and the week's sector strategy (trendy themes, coverage
 * against the book, and the board-passed picks per theme), and answers "how
 * much of this cash to put to work, in which sectors, in which stocks, and
 * how much into each."
 *
 * Integrity rules:
 *  - pure function over given inputs; no I/O, never throws — partial input
 *    produces a smaller plan, never a fabricated one
 *  - every rationale line cites the measured number behind it (board
 *    agreement, RSI, volume ratio, 20-day move, headline), never filler
 *  - hard caps come from the acting [InvestorProfile]: no theme past
 *    `maxSectorPct` of the whole account (book + liquidity), no single stock
 *    past `maxPositionPct`
 *  - a stock below the conviction bar earns nothing; leftover cash is
 *    reported as reserve with the reason — when nothing qualifies, holding
 *    cash IS the recommendation
 */
object LiquidityPlanner {

    /** Below this conviction a name earns none of the liquidity. */
    const val MIN_CONVICTION = 55

    /** Don't print tickets below this — rounding noise helps no one. */
    const val MIN_TICKET = 25.0

    /** At most this many names per theme (the strategy proposes up to 3). */
    private const val MAX_PER_THEME = 3

    fun build(
        liquidity: Double,
        book: BookContext,
        strategy: WeeklyStrategy?,
        profile: InvestorProfile,
        marketNote: String = "",
        /** Next earnings per candidate symbol; absent = unmeasured, no gate. */
        earnings: Map<String, EarningsInfo> = emptyMap()
    ): DeploymentPlan {
        val now = System.currentTimeMillis()
        val caveat = "Every figure is computed from live prices, the 35-technique board, and " +
            "this policy's own caps. Decision support, not financial advice."
        val policyNote = profile.label()
        val cash = liquidity.coerceAtLeast(0.0)

        if (cash < MIN_TICKET) {
            val reason =
                if (liquidity <= 0.0) {
                    "No uninvested cash was reported — set your total wallet on the " +
                        "Dashboard and the plan sizes itself from real liquidity."
                } else {
                    String.format(
                        Locale.US,
                        "Only %s of liquidity — below the %s minimum ticket, so this run is a no-op.",
                        money(cash), money(MIN_TICKET)
                    )
                }
            return DeploymentPlan(
                computedAt = now, liquidity = cash, deployed = 0.0, reserve = cash,
                reserveReason = reason,
                headline = "No liquidity to deploy right now.",
                policyNote = policyNote, marketNote = marketNote,
                sectors = emptyList(), caveat = caveat
            )
        }
        if (strategy == null || strategy.gaps.isEmpty()) {
            return DeploymentPlan(
                computedAt = now, liquidity = cash, deployed = 0.0, reserve = cash,
                reserveReason = "The sector scan could not be built this run (market " +
                    "unreachable?) — nothing is deployed on guesswork.",
                headline = money(cash) + " ready, but no measured sector read to deploy against.",
                policyNote = policyNote, marketNote = marketNote,
                sectors = emptyList(), caveat = caveat
            )
        }

        // ---- account math: caps measure against the whole account ----------
        val bookValue = book.totalValue.coerceAtLeast(0.0)
        val account = bookValue + cash
        val maxSector = profile.maxSectorPct.coerceIn(10.0, 100.0)
        val maxPosition = profile.maxPositionPct.coerceIn(1.0, 100.0)

        // ---- theme weights: the strategy's own trend-strength split --------
        // strategy.allocations already carries the trend-scaled-by-room split;
        // fall back to gap targets when allocations are empty.
        val weightByTheme: Map<String, Double> =
            if (strategy.allocations.isNotEmpty()) {
                strategy.allocations.associate { it.themeKey to it.sharePct.coerceAtLeast(0.0) }
            } else {
                strategy.gaps.associate { it.themeKey to it.targetPct.coerceAtLeast(0.0) }
            }
        val weightSum = weightByTheme.values.sum().takeIf { it > 0.0 } ?: 1.0

        val deployments = ArrayList<SectorDeployment>()
        var remaining = cash
        val skippedThemes = ArrayList<String>()

        for (gap in strategy.gaps) {
            val weight = (weightByTheme[gap.themeKey] ?: 0.0) / weightSum
            if (weight <= 0.0) continue
            var proposed = min(cash * weight, remaining)
            // Sector cap: (already held in theme + new money) ≤ maxSector% of account.
            val heldValue = gap.heldPct / 100.0 * bookValue
            val sectorRoom = (maxSector / 100.0 * account - heldValue).coerceAtLeast(0.0)
            proposed = min(proposed, sectorRoom)
            if (proposed < MIN_TICKET) {
                if (sectorRoom < MIN_TICKET) skippedThemes +=
                    "${gap.label} is already at this policy's ${maxSector.toInt()}% sector cap"
                continue
            }

            val scored = gap.picks.take(MAX_PER_THEME)
                .filter { it.price > 0.0 }
                .filter { pick ->
                    // The earnings blackout: no new money into a name whose
                    // report lands inside the window — a print is a coin flip
                    // no conviction score survives. Unknown dates never gate.
                    val next = earnings[pick.symbol]?.nextTs
                    val soon = next != null &&
                        next <= now + WealthEngine.EARNINGS_BLACKOUT_DAYS * 86_400_000L
                    if (soon && next != null) {
                        skippedThemes += "${pick.symbol} sized out — earnings " +
                            earningsDate(next) +
                            (if (earnings[pick.symbol]?.estimate == true) " (est.)" else "")
                    }
                    !soon
                }
                .map { it to convictionOf(it) }
                .filter { (_, c) -> c >= MIN_CONVICTION }
            if (scored.isEmpty()) {
                skippedThemes += "no ${gap.label} name cleared the $MIN_CONVICTION/100 conviction bar"
                continue
            }

            // Split the theme's dollars by conviction weight, under the
            // per-position cap; drop sub-ticket remainders honestly.
            val convSum = scored.sumOf { (_, c) -> c.toDouble() }
            val lines = ArrayList<DeployLine>()
            var themeSpent = 0.0
            scored.sortedByDescending { (_, c) -> c }.forEachIndexed { i, (pick, conviction) ->
                val share = conviction / convSum
                var amount = proposed * share
                val positionRoom = maxPosition / 100.0 * account
                amount = min(amount, positionRoom)
                amount = min(amount, remaining - themeSpent)
                amount = (amount / 5.0).toInt() * 5.0    // whole $5 tickets read cleanly
                if (amount < MIN_TICKET) return@forEachIndexed
                lines += DeployLine(
                    rank = i + 1,
                    symbol = pick.symbol,
                    name = pick.name.ifBlank { pick.symbol },
                    amount = amount,
                    approxShares = round4(amount / pick.price),
                    price = round2(pick.price),
                    entry = round2(pick.entry),
                    conviction = conviction,
                    rationale = rationaleOf(pick, conviction)
                )
                themeSpent += amount
            }
            if (lines.isEmpty()) continue
            deployments += SectorDeployment(
                themeKey = gap.themeKey,
                label = gap.label,
                amount = themeSpent,
                heldPct = gap.heldPct,
                targetPct = gap.targetPct,
                status = gap.status,
                lines = lines
            )
            remaining -= themeSpent
            if (remaining < MIN_TICKET) break
        }

        val deployed = deployments.sumOf { it.amount }
        val reserve = (cash - deployed).coerceAtLeast(0.0)
        val reserveReason = when {
            reserve <= 0.005 -> ""
            deployments.isEmpty() -> buildString {
                append("Nothing cleared the bar this run — cash is the honest advice. ")
                append(skippedThemes.joinToString("; ").replaceFirstChar { it.uppercase() })
                if (skippedThemes.isNotEmpty()) append(".")
            }
            else -> String.format(
                Locale.US,
                "%s (%.0f%% of liquidity) held back: the %d%%-position and %d%%-sector caps " +
                    "bound every ticket%s.",
                money(reserve), reserve / cash * 100.0,
                maxPosition.toInt(), maxSector.toInt(),
                if (skippedThemes.isEmpty()) "" else "; " + skippedThemes.joinToString("; ")
            )
        }
        val headline = when {
            deployments.isEmpty() ->
                money(cash) + " ready to deploy, but no name clears the conviction bar — holding as cash."
            reserve <= 0.005 -> String.format(
                Locale.US, "%s deployed across %d sector%s, %d stock%s; nothing held back.",
                money(deployed), deployments.size, plural(deployments.size),
                deployments.sumOf { it.lines.size }, plural(deployments.sumOf { it.lines.size })
            )
            else -> String.format(
                Locale.US, "%s to deploy across %d sector%s, %d stock%s; %s stays as buffer.",
                money(deployed), deployments.size, plural(deployments.size),
                deployments.sumOf { it.lines.size }, plural(deployments.sumOf { it.lines.size }),
                money(reserve)
            )
        }

        return DeploymentPlan(
            computedAt = now,
            liquidity = round2(cash),
            deployed = round2(deployed),
            reserve = round2(reserve),
            reserveReason = reserveReason,
            headline = headline,
            policyNote = policyNote,
            marketNote = marketNote,
            sectors = deployments,
            caveat = caveat
        )
    }

    /**
     * Conviction 0..100 from the pick's own measured evidence: board
     * agreement leads (60%), then volume behaviour, RSI regime, the 20-day
     * trend, and headline tone. Unmeasured components add zero, never a guess.
     */
    fun convictionOf(pick: SectorPick): Int {
        val agreement =
            if (pick.techTotal > 0) pick.techBullish * 100.0 / pick.techTotal else 0.0
        var score = agreement * 0.60
        score += when {
            pick.volumeRatio >= 3.0 -> 4.0    // extreme surge: small bonus, verify the catalyst
            pick.volumeRatio >= 1.5 -> 10.0
            pick.volumeRatio >= 1.0 -> 5.0
            pick.volumeRatio > 0.0 -> -3.0    // dwindling
            else -> 0.0                       // unmeasured
        }
        score += when {
            pick.rsi <= 0.0 -> 0.0            // unmeasured
            pick.rsi >= 75.0 -> -8.0
            pick.rsi >= 65.0 -> -2.0
            pick.rsi >= 45.0 -> 8.0
            pick.rsi >= 30.0 -> 4.0
            else -> -2.0
        }
        score += when {
            pick.r20Pct >= 25.0 -> 4.0        // strong but stretched
            pick.r20Pct >= 5.0 -> 10.0
            pick.r20Pct >= 0.0 -> 4.0
            else -> -6.0
        }
        score += pick.newsScore.coerceIn(-3, 3) * 2.0
        return score.roundToInt().coerceIn(0, 100)
    }

    private fun rationaleOf(pick: SectorPick, conviction: Int): List<String> {
        val out = ArrayList<String>(4)
        if (pick.techTotal > 0) {
            out += "${pick.techBullish} of ${pick.techTotal} techniques on the board read bullish."
        }
        val parts = ArrayList<String>(3)
        if (pick.r20Pct != 0.0) parts += String.format(Locale.US, "%+.1f%% in 20 days", pick.r20Pct)
        if (pick.rsi > 0.0) parts += String.format(Locale.US, "RSI %.0f", pick.rsi)
        if (pick.volumeRatio > 0.0) {
            parts += String.format(Locale.US, "volume %.1fx its 20-day average", pick.volumeRatio)
        }
        if (parts.isNotEmpty()) out += parts.joinToString(" · ") + "."
        if (pick.newsNote.isNotBlank()) out += pick.newsNote
        if (pick.reason.isNotBlank()) out += pick.reason
        out += "Conviction $conviction/100 from the measured evidence above."
        return out
    }

    private fun earningsDate(ts: Long): String =
        SimpleDateFormat("MMM d", Locale.US).format(Date(ts))

    private fun plural(n: Int) = if (n == 1) "" else "s"
    private fun money(v: Double): String =
        if (v >= 10_000) String.format(Locale.US, "$%,.0f", v)
        else String.format(Locale.US, "$%,.2f", v)
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun round4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
}
