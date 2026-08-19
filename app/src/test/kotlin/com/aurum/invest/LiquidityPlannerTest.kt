package com.aurum.invest

import com.aurum.invest.analytics.AllocationSlice
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.GapStatus
import com.aurum.invest.analytics.LiquidityPlanner
import com.aurum.invest.analytics.SectorGap
import com.aurum.invest.analytics.SectorPick
import com.aurum.invest.analytics.SectorSlice
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.data.repo.InvestorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The liquidity-management engine: how much of the wallet's uninvested cash
 * to deploy, into which sectors, into which stocks, and how much into each.
 * These tests lock the honesty rules — caps bound every ticket, nothing
 * below the conviction bar earns money, and reserve cash always carries its
 * reason.
 */
class LiquidityPlannerTest {

    private fun pick(
        symbol: String,
        price: Double = 100.0,
        bullish: Int = 28,
        total: Int = 35,
        rsi: Double = 55.0,
        vol: Double = 1.6,
        r20: Double = 8.0,
        news: Int = 1
    ) = SectorPick(
        symbol = symbol, name = symbol, price = price, r20Pct = r20, rsi = rsi,
        techBullish = bullish, techTotal = total, entry = price * 0.99,
        reason = "strongest on its shelf", r3Pct = 2.0, volumeRatio = vol,
        newsScore = news, newsNote = ""
    )

    private fun gap(
        key: String,
        held: Double = 0.0,
        target: Double = 25.0,
        picks: List<SectorPick>
    ) = SectorGap(
        themeKey = key, label = key.uppercase(), etf = "ETF", r5Pct = 2.0, r20Pct = 6.0,
        heldPct = held, targetPct = target,
        status = if (held < 2.0) GapStatus.MISSING else GapStatus.COVERED,
        coverageFromHoldings = false, picks = picks
    )

    private fun strategy(gaps: List<SectorGap>) = WeeklyStrategy(
        computedAt = 0L, investable = 0.0, headline = "h",
        gaps = gaps,
        allocations = gaps.map {
            AllocationSlice(
                themeKey = it.themeKey, label = it.label, amount = 0.0,
                sharePct = it.targetPct, heldPct = it.heldPct,
                lead = it.picks.firstOrNull(), alternates = it.picks.drop(1)
            )
        },
        notes = emptyList()
    )

    private fun book(totalValue: Double): BookContext =
        if (totalValue <= 0.0) BookContext.EMPTY
        else BookContext(
            totalValue = totalValue,
            heldWeights = mapOf("HELD" to 100.0),
            slices = listOf(
                SectorSlice(
                    sector = "Technology",
                    value = totalValue,
                    weightPct = 100.0,
                    symbols = listOf("HELD")
                )
            )
        )

    // ---- the answer: sectors, stocks, dollars --------------------------------

    @Test
    fun deploysAcrossSectorsAndNamesEveryStockWithADollarAmount() {
        val strat = strategy(
            listOf(
                gap("ai", picks = listOf(pick("NVDA"), pick("MSFT"))),
                gap("semis", picks = listOf(pick("AVGO")))
            )
        )
        val plan = LiquidityPlanner.build(
            liquidity = 2_000.0, book = book(0.0), strategy = strat,
            profile = InvestorProfile.DEFAULT
        )
        assertEquals(2, plan.sectors.size)
        assertTrue(plan.deployed > 0.0)
        // Every line carries a positive ticket and share count at its price.
        plan.sectors.flatMap { it.lines }.forEach { line ->
            assertTrue(line.amount >= LiquidityPlanner.MIN_TICKET)
            assertTrue(abs(line.approxShares - line.amount / line.price) < 0.01)
        }
        // The sector's amount is exactly the sum of its stock tickets.
        plan.sectors.forEach { s ->
            assertEquals(s.amount, s.lines.sumOf { it.amount }, 1e-6)
        }
        // Deployed + reserve = the liquidity given; nothing invented or lost.
        assertEquals(plan.liquidity, plan.deployed + plan.reserve, 1e-6)
    }

    @Test
    fun nothingBelowTheConvictionBarEarnsMoney() {
        // A fully bearish board: 2 of 35 bullish, weak everything.
        val weak = pick("WEAK", bullish = 2, total = 35, rsi = 80.0, vol = 0.5, r20 = -10.0, news = -3)
        assertTrue(LiquidityPlanner.convictionOf(weak) < LiquidityPlanner.MIN_CONVICTION)
        val plan = LiquidityPlanner.build(
            liquidity = 1_000.0, book = book(0.0),
            strategy = strategy(listOf(gap("ai", picks = listOf(weak)))),
            profile = InvestorProfile.DEFAULT
        )
        assertTrue(plan.sectors.isEmpty())
        assertEquals(plan.liquidity, plan.reserve, 1e-6)
        // And the refusal explains itself.
        assertTrue(plan.reserveReason.contains("conviction bar"))
    }

    @Test
    fun sectorCapBoundsATrendyThemeTheBookIsAlreadyHeavyIn() {
        // Book of $10,000 all in one theme that is also the trendy one; with
        // maxSectorPct 35 and $10,000 liquidity, the account is $20,000 and
        // the theme is already at 50% — no new money may go there.
        val strat = strategy(listOf(gap("ai", held = 100.0, target = 30.0, picks = listOf(pick("NVDA")))))
        val plan = LiquidityPlanner.build(
            liquidity = 10_000.0, book = book(10_000.0), strategy = strat,
            profile = InvestorProfile.DEFAULT.copy(maxSectorPct = 35.0)
        )
        assertTrue(plan.sectors.isEmpty())
        assertTrue(plan.reserveReason.contains("sector cap"))
    }

    @Test
    fun positionCapBoundsASingleTicket() {
        val strat = strategy(listOf(gap("ai", picks = listOf(pick("NVDA")))))
        val plan = LiquidityPlanner.build(
            liquidity = 10_000.0, book = book(0.0), strategy = strat,
            profile = InvestorProfile.DEFAULT.copy(maxPositionPct = 10.0)
        )
        // Account is $10,000; a single position may not exceed $1,000.
        val line = plan.sectors.flatMap { it.lines }.single()
        assertTrue(line.amount <= 1_000.0 + 1e-6)
        assertTrue(plan.reserve > 0.0)
    }

    @Test
    fun noLiquidityIsAnHonestNoOpPointingAtTheWallet() {
        val plan = LiquidityPlanner.build(
            liquidity = 0.0, book = book(0.0),
            strategy = strategy(listOf(gap("ai", picks = listOf(pick("NVDA"))))),
            profile = InvestorProfile.DEFAULT
        )
        assertTrue(plan.sectors.isEmpty())
        assertEquals(0.0, plan.deployed, 1e-9)
        assertTrue(plan.reserveReason.contains("total wallet"))
    }

    @Test
    fun noStrategyMeansNoDeploymentOnGuesswork() {
        val plan = LiquidityPlanner.build(
            liquidity = 5_000.0, book = book(0.0), strategy = null,
            profile = InvestorProfile.DEFAULT
        )
        assertTrue(plan.sectors.isEmpty())
        assertEquals(plan.liquidity, plan.reserve, 1e-6)
    }

    @Test
    fun strongerBoardEarnsTheBiggerTicket() {
        val strong = pick("STRG", bullish = 30, total = 35)
        // Clears the 55 bar (agreement 24/35 ≈ 69% → ~58 total) but well
        // below STRG's read — the weaker board must get the smaller ticket.
        val soft = pick("SOFT", bullish = 24, total = 35, vol = 1.0, r20 = 1.0, news = 0)
        val plan = LiquidityPlanner.build(
            liquidity = 3_000.0, book = book(0.0),
            strategy = strategy(listOf(gap("ai", picks = listOf(strong, soft)))),
            profile = InvestorProfile.DEFAULT
        )
        val lines = plan.sectors.single().lines
        assertEquals(2, lines.size)
        val strongLine = lines.first { it.symbol == "STRG" }
        val softLine = lines.first { it.symbol == "SOFT" }
        assertTrue(strongLine.amount > softLine.amount)
        assertEquals(1, strongLine.rank)
    }

    @Test
    fun everyLineCitesMeasuredEvidence() {
        val plan = LiquidityPlanner.build(
            liquidity = 1_000.0, book = book(0.0),
            strategy = strategy(listOf(gap("ai", picks = listOf(pick("NVDA"))))),
            profile = InvestorProfile.DEFAULT
        )
        val line = plan.sectors.single().lines.single()
        assertTrue(line.rationale.any { it.contains("techniques on the board") })
        assertTrue(line.rationale.any { it.contains("Conviction ${line.conviction}/100") })
    }
}
