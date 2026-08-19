package com.aurum.invest

import com.aurum.invest.analytics.ReportPeriod
import com.aurum.invest.analytics.ReportsEngine
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.repo.PortfolioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ledgers migrated from the v6–v9 releases can carry SPLIT rows (the ratio in
 * `shares`, price 0). This build's UI never records one, but every replay must
 * rescale on them — a SPLIT mistaken for a sell corrupts the position, the
 * realized numbers, and the ledger guard alike. This is upgrade-safety, not a
 * feature.
 */
class SplitReplayTest {

    private var nextId = 1L
    private fun tx(side: String, shares: Double, price: Double, ts: Long): TransactionEntity =
        TransactionEntity(
            id = nextId++, symbol = "AAPL", side = side,
            shares = shares, price = price, fees = 0.0, ts = ts
        )

    @Test
    fun splitRescalesSharesAndAverageButNotValue() {
        // Buy 10 @ $200, then a 4-for-1 split: 40 shares @ $50 average.
        val positions = PortfolioRepository.computePositions(
            listOf(
                tx("BUY", 10.0, 200.0, 1_000L),
                tx(PortfolioRepository.SIDE_SPLIT, 4.0, 0.0, 2_000L)
            )
        )
        val p = positions.single()
        assertEquals(40.0, p.shares, 1e-9)
        assertEquals(50.0, p.avgCost, 1e-9)
        assertEquals(2_000.0, p.investedCost, 1e-9) // value unchanged
    }

    @Test
    fun sellAfterSplitRealizesAgainstTheSplitAdjustedAverage() {
        // Buy 10 @ $200 → split 4:1 → sell 40 @ $60: realized = 40*(60−50).
        val positions = PortfolioRepository.computePositions(
            listOf(
                tx("BUY", 10.0, 200.0, 1_000L),
                tx(PortfolioRepository.SIDE_SPLIT, 4.0, 0.0, 2_000L),
                tx("SELL", 40.0, 60.0, 3_000L)
            )
        )
        val p = positions.single()
        assertEquals(0.0, p.shares, 1e-9)
        assertEquals(400.0, p.realizedPl, 1e-9)
    }

    @Test
    fun ledgerGuardCountsSplitAdjustedSharesAsBacked() {
        // Without SPLIT handling, selling 40 against a 10-share buy would read
        // as 30 unbacked shares and wrongly block edits.
        val unbacked = PortfolioRepository.unbackedBySymbol(
            listOf(
                tx("BUY", 10.0, 200.0, 1_000L),
                tx(PortfolioRepository.SIDE_SPLIT, 4.0, 0.0, 2_000L),
                tx("SELL", 40.0, 60.0, 3_000L)
            )
        )
        assertTrue(unbacked.isEmpty())
    }

    @Test
    fun reportsSkipSplitRowsAsActivityButHonorTheirScaling() {
        val txs = listOf(
            tx("BUY", 10.0, 200.0, 1_000_000_000_000L),
            tx(PortfolioRepository.SIDE_SPLIT, 4.0, 0.0, 1_000_100_000_000L),
            tx("SELL", 40.0, 60.0, 1_000_200_000_000L)
        )
        val all = ReportsEngine.build(txs, ReportPeriod.YEAR)
        val year = all.single()
        // The split is not a trade: one buy, one sell, no third line.
        assertEquals(2, year.trades.size)
        assertTrue(year.trades.none { it.side == PortfolioRepository.SIDE_SPLIT })
        // And the sell's realized number reflects the split-adjusted average.
        assertEquals(400.0, year.realizedPl, 1e-9)
    }
}
