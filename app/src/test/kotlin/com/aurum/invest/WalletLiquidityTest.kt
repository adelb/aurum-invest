package com.aurum.invest

import com.aurum.invest.data.model.PortfolioSummary
import com.aurum.invest.data.repo.WalletState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wallet cash identity, in one place:
 *   liquidity = total − invested + realizedPl
 * A sell returns its cost basis AND its booked P/L to liquidity. Deriving
 * liquidity as total − invested alone silently swallows every realized gain.
 */
class WalletLiquidityTest {

    private fun summary(
        invested: Double,
        realized: Double,
        unrealized: Double = 0.0,
        marketValue: Double = invested
    ) = PortfolioSummary(
        marketValue = marketValue,
        investedCost = invested,
        unrealizedPl = unrealized,
        realizedPl = realized,
        totalPl = unrealized + realized,
        dayPl = 0.0
    )

    @Test
    fun aSellReturnsCostBasisAndProfitToLiquidity() {
        // $10,000 wallet; bought $4,000; sold it all for +$400 profit.
        // Invested is back to 0, realized is +400: liquidity must be $10,400.
        val state = WalletState.of(10_000.0, true, summary(invested = 0.0, realized = 400.0, marketValue = 0.0))
        assertEquals(10_400.0, state.liquidity, 1e-9)
    }

    @Test
    fun liquidityWhilePositionsAreOpen() {
        // $10,000 wallet; $4,000 deployed; $150 booked from an earlier trim.
        val state = WalletState.of(
            10_000.0, true,
            summary(invested = 4_000.0, realized = 150.0, unrealized = 250.0, marketValue = 4_250.0)
        )
        assertEquals(6_150.0, state.liquidity, 1e-9)
        // Net worth = liquidity + holdings at market = total + totalPl.
        assertEquals(state.liquidity + 4_250.0, state.netWorth, 1e-9)
        assertEquals(10_000.0 + 400.0, state.netWorth, 1e-9)
    }

    @Test
    fun unrealizedPlNeverMovesLiquidity() {
        val flat = WalletState.of(5_000.0, true, summary(invested = 2_000.0, realized = 0.0, unrealized = 0.0))
        val up = WalletState.of(
            5_000.0, true,
            summary(invested = 2_000.0, realized = 0.0, unrealized = 900.0, marketValue = 2_900.0)
        )
        assertEquals(flat.liquidity, up.liquidity, 1e-9)
    }

    @Test
    fun shortfallIsReportedNotFlooredAtZero() {
        // The ledger has more deployed than the stated wallet covers.
        val state = WalletState.of(1_000.0, true, summary(invested = 1_500.0, realized = 0.0))
        assertTrue(state.shortfall)
        assertEquals(-500.0, state.liquidity, 1e-9)
        // But nothing may SPEND a negative number.
        assertEquals(0.0, state.deployable, 1e-9)
    }

    @Test
    fun unsetWalletIsUnconfiguredWithZeroes() {
        assertFalse(WalletState.UNSET.configured)
        assertEquals(0.0, WalletState.UNSET.netWorth, 1e-9)
    }
}
