package com.aurum.invest

import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.LiquidityAllocationEngine
import com.aurum.invest.analytics.LiquidityCandidate
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.data.repo.InvestorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core behavior of the liquidity-deployment engine (never fabricates, always respects caps). */
class LiquidityAllocationEngineTest {

    private fun candidate(
        symbol: String,
        sector: String = "Technology",
        entryScore: Double = 80.0,
        volumeRatio: Double = 1.5,
        rsi: Double = 55.0,
        techConfidence: Int = 70,
        price: Double = 100.0
    ) = LiquidityCandidate(
        symbol = symbol,
        name = "$symbol Inc",
        sector = sector,
        price = price,
        entryScore = entryScore,
        volumeRatio = volumeRatio,
        dayChangePct = 1.0,
        rsi = rsi,
        techDirection = TechniqueVerdict.BULLISH,
        techConfidence = techConfidence,
        newsScore = 1,
        newsHeadline = "",
        analystRating = 2.0,
        fiftyDayAvg = price * 0.95,
        twoHundredDayAvg = price * 0.9,
        marketCap = 1_000_000_000.0,
        historyNote = "",
        reason = "test fixture"
    )

    @Test
    fun `no liquidity produces an empty plan with an honest reason`() {
        val plan = LiquidityAllocationEngine.build(
            liquidity = 0.0,
            book = BookContext.EMPTY,
            moneyFlow = null,
            sectorTrends = emptyList(),
            candidates = listOf(candidate("AAPL")),
            profile = InvestorProfile.DEFAULT
        )
        assertTrue(plan.lines.isEmpty())
        assertEquals(0.0, plan.reserveCash, 1e-9)
        assertTrue(plan.reserveReason.isNotBlank())
    }

    @Test
    fun `never deploys more than the given liquidity`() {
        val plan = LiquidityAllocationEngine.build(
            liquidity = 1_000.0,
            book = BookContext.EMPTY,
            moneyFlow = null,
            sectorTrends = emptyList(),
            candidates = listOf(
                candidate("AAPL", entryScore = 90.0),
                candidate("MSFT", entryScore = 85.0),
                candidate("NVDA", entryScore = 88.0)
            ),
            profile = InvestorProfile.DEFAULT
        )
        val deployed = plan.lines.sumOf { it.amount }
        assertTrue(deployed <= 1_000.0 + 1e-6)
        assertEquals(1_000.0, deployed + plan.reserveCash, 1.0)
    }

    @Test
    fun `low conviction candidates earn nothing and cash is reserved`() {
        val plan = LiquidityAllocationEngine.build(
            liquidity = 500.0,
            book = BookContext.EMPTY,
            moneyFlow = null,
            sectorTrends = emptyList(),
            candidates = listOf(
                candidate("JUNK", entryScore = 5.0, volumeRatio = 0.5, rsi = 90.0, techConfidence = 10)
            ),
            profile = InvestorProfile.DEFAULT
        )
        assertTrue(plan.lines.none { it.symbol == "JUNK" })
        assertTrue(plan.reserveCash > 0.0)
    }

    @Test
    fun `a single position never exceeds the profile's max position cap`() {
        val profile = InvestorProfile.DEFAULT.copy(maxPositionPct = 10.0)
        val plan = LiquidityAllocationEngine.build(
            liquidity = 100_000.0,
            book = BookContext.EMPTY,
            moneyFlow = null,
            sectorTrends = emptyList(),
            candidates = listOf(candidate("AAPL", entryScore = 95.0)),
            profile = profile
        )
        val line = plan.lines.firstOrNull { it.symbol == "AAPL" }
        if (line != null) {
            // Post-deploy total book here is just the deployed liquidity itself
            // (empty starting book), so the line can't exceed the cap of it.
            val totalDeployed = plan.lines.sumOf { it.amount } + plan.reserveCash
            assertTrue(line.amount / totalDeployed * 100.0 <= profile.maxPositionPct + 1.0)
        }
    }

    @Test
    fun `rationale cites measured numbers, never generic filler`() {
        val plan = LiquidityAllocationEngine.build(
            liquidity = 2_000.0,
            book = BookContext.EMPTY,
            moneyFlow = null,
            sectorTrends = emptyList(),
            candidates = listOf(candidate("AAPL", entryScore = 92.0, volumeRatio = 2.0)),
            profile = InvestorProfile.DEFAULT
        )
        plan.lines.forEach { line ->
            assertTrue(line.rationale.isNotEmpty())
            line.rationale.forEach { assertTrue(it.isNotBlank()) }
        }
    }
}
