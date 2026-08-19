package com.aurum.invest

import com.aurum.invest.analytics.AllocationSlice
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.GapStatus
import com.aurum.invest.analytics.HoldingMove
import com.aurum.invest.analytics.LiquidityPlanner
import com.aurum.invest.analytics.SectorGap
import com.aurum.invest.analytics.SectorPick
import com.aurum.invest.analytics.WealthEngine
import com.aurum.invest.analytics.WealthInputs
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.EarningsInfo
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.repo.InvestorProfile
import com.aurum.invest.data.repo.PortfolioRepository
import com.aurum.invest.data.repo.WalletState
import com.aurum.invest.analytics.WeeklyStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * The earnings blackout: no new money into a name whose report lands inside
 * the window — in the wealth engine's ADD calls and the liquidity planner's
 * deployment lines alike. An UNKNOWN date never gates: absence of data is
 * unmeasured, not "no earnings soon".
 */
class EarningsGateTest {

    private val dayMs = 86_400_000L
    private val now = System.currentTimeMillis()

    private fun earnings(symbol: String, inDays: Int?, estimate: Boolean = false) =
        EarningsInfo(
            symbol = symbol,
            nextTs = inDays?.let { now + it * dayMs },
            estimate = estimate,
            fetchedAt = now
        )

    // ---- the wealth engine's ADD gate ---------------------------------------

    /** An accelerating, gently breathing uptrend — the board reads firmly bullish. */
    private fun wavyUp(days: Int, from: Double, to: Double): List<Candle> {
        return (0 until days).map { i ->
            val t = i.toDouble() / (days - 1)
            val close = (from + (to - from) * Math.pow(t, 1.6)) *
                (1.0 + 0.004 * sin(i * 0.9))
            Candle(
                ts = 1_700_000_000_000L + i * dayMs,
                open = close * 0.994, high = close * 1.004, low = close * 0.988,
                close = close, volume = 1_000_000L + i * 4_000L
            )
        }
    }

    private fun view(symbol: String, shares: Double, avg: Double, price: Double) =
        PortfolioRepository.toView(
            Position(symbol = symbol, shares = shares, avgCost = avg,
                investedCost = shares * avg, realizedPl = 0.0),
            Quote(symbol = symbol, price = price, prevClose = price)
        )

    /**
     * Twelve equal holdings so each sits far under half the position cap,
     * plenty of deployable cash, every name on an intact wavy uptrend —
     * the ADD setup by construction.
     */
    private fun addSetupInputs(earningsMap: Map<String, EarningsInfo>): WealthInputs {
        val symbols = (1..12).map { "S%02d".format(it) }
        val views = symbols.map { view(it, 10.0, 80.0, 100.0) }
        val candles = symbols.associateWith { wavyUp(365, 60.0, 100.0) }
        return WealthInputs(
            wallet = WalletState.of(
                total = 50_000.0, configured = true,
                invested = views.sumOf { it.position.investedCost },
                realizedPl = 0.0, unrealizedPl = views.sumOf { it.unrealizedPl },
                holdingsValue = views.sumOf { it.marketValue }
            ),
            views = views,
            candles = candles,
            sectors = symbols.associateWith { "Technology" },
            spy = wavyUp(365, 400.0, 440.0),
            vix = 18.0,
            profile = InvestorProfile.DEFAULT,
            sellOutcomes = listOf(50.0, -20.0, 80.0, 10.0, -5.0, 120.0),
            earnings = earningsMap
        )
    }

    @Test
    fun earningsInsideTheBlackoutDemotesAnAddToHold() {
        val without = WealthEngine.evaluate(addSetupInputs(emptyMap()))
        val target = without.holdings.first { it.symbol == "S01" }
        // The fixture must genuinely produce the ADD — otherwise this test
        // would pass vacuously and prove nothing.
        assertEquals(HoldingMove.ADD, target.move)

        val with = WealthEngine.evaluate(
            addSetupInputs(mapOf("S01" to earnings("S01", inDays = 3)))
        )
        val gated = with.holdings.first { it.symbol == "S01" }
        assertTrue(gated.move != HoldingMove.ADD)
        assertTrue(gated.reasons.any { it.contains("blackout") })
        // The date is surfaced on the holding, not buried.
        assertNotNull(gated.nextEarningsTs)
    }

    @Test
    fun aDistantOrUnknownDateNeverGates() {
        val distant = WealthEngine.evaluate(
            addSetupInputs(mapOf("S01" to earnings("S01", inDays = 30)))
        ).holdings.first { it.symbol == "S01" }
        assertEquals(HoldingMove.ADD, distant.move)
        // 30 days out never gates — but the date is still surfaced.
        assertNotNull(distant.nextEarningsTs)

        val unknown = WealthEngine.evaluate(
            addSetupInputs(mapOf("S01" to earnings("S01", inDays = null)))
        ).holdings.first { it.symbol == "S01" }
        assertEquals(HoldingMove.ADD, unknown.move)
        // "Checked, none known" surfaces nothing — never a guessed date.
        assertNull(unknown.nextEarningsTs)
    }

    @Test
    fun aKnownDateIsAlwaysSurfacedWithItsEstimateFlag() {
        val report = WealthEngine.evaluate(
            addSetupInputs(mapOf("S02" to earnings("S02", inDays = 45, estimate = true)))
        )
        val holding = report.holdings.first { it.symbol == "S02" }
        assertNotNull(holding.nextEarningsTs)
        assertTrue(holding.earningsEstimate)
        // A holding with no earnings data carries none — absent, not faked.
        val other = report.holdings.first { it.symbol == "S03" }
        assertNull(other.nextEarningsTs)
    }

    // ---- the liquidity planner's deployment gate ----------------------------

    private fun pick(symbol: String) = SectorPick(
        symbol = symbol, name = symbol, price = 100.0, r20Pct = 8.0, rsi = 55.0,
        techBullish = 28, techTotal = 35, entry = 99.0,
        reason = "strongest on its shelf", r3Pct = 2.0, volumeRatio = 1.6,
        newsScore = 1, newsNote = ""
    )

    private fun strategy(picks: List<SectorPick>): WeeklyStrategy {
        val gap = SectorGap(
            themeKey = "ai", label = "AI", etf = "ETF", r5Pct = 2.0, r20Pct = 6.0,
            heldPct = 0.0, targetPct = 25.0, status = GapStatus.MISSING,
            coverageFromHoldings = false, picks = picks
        )
        return WeeklyStrategy(
            computedAt = 0L, investable = 0.0, headline = "h", gaps = listOf(gap),
            allocations = listOf(
                AllocationSlice(
                    themeKey = "ai", label = "AI", amount = 0.0, sharePct = 25.0,
                    heldPct = 0.0, lead = picks.firstOrNull(), alternates = picks.drop(1)
                )
            ),
            notes = emptyList()
        )
    }

    @Test
    fun aNameReportingThisWeekIsSizedOutAndNamed() {
        val plan = LiquidityPlanner.build(
            liquidity = 2_000.0, book = BookContext.EMPTY,
            strategy = strategy(listOf(pick("NVDA"))),
            profile = InvestorProfile.DEFAULT,
            earnings = mapOf("NVDA" to earnings("NVDA", inDays = 2))
        )
        assertTrue(plan.sectors.isEmpty())
        assertTrue(plan.reserveReason.contains("NVDA sized out"))
        assertTrue(plan.reserveReason.contains("earnings"))
        // Nothing invented, nothing lost.
        assertEquals(plan.liquidity, plan.reserve, 1e-6)
    }

    @Test
    fun theGateTakesOnlyTheReportingNameNotTheTheme() {
        val plan = LiquidityPlanner.build(
            liquidity = 2_000.0, book = BookContext.EMPTY,
            strategy = strategy(listOf(pick("NVDA"), pick("MSFT"))),
            profile = InvestorProfile.DEFAULT,
            earnings = mapOf("NVDA" to earnings("NVDA", inDays = 2))
        )
        val symbols = plan.sectors.flatMap { it.lines }.map { it.symbol }
        assertTrue("MSFT" in symbols)
        assertTrue("NVDA" !in symbols)
    }

    @Test
    fun unknownAndDistantDatesDeployNormally() {
        val plan = LiquidityPlanner.build(
            liquidity = 2_000.0, book = BookContext.EMPTY,
            strategy = strategy(listOf(pick("NVDA"))),
            profile = InvestorProfile.DEFAULT,
            earnings = mapOf("NVDA" to earnings("NVDA", inDays = 25))
        )
        assertEquals(1, plan.sectors.flatMap { it.lines }.size)

        val noData = LiquidityPlanner.build(
            liquidity = 2_000.0, book = BookContext.EMPTY,
            strategy = strategy(listOf(pick("NVDA"))),
            profile = InvestorProfile.DEFAULT
        )
        assertEquals(1, noData.sectors.flatMap { it.lines }.size)
    }
}
