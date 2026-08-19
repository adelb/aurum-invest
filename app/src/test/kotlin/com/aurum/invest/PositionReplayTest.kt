package com.aurum.invest

import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.repo.PortfolioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors for the ledger truth engine (C7): weighted-average cost,
 * realized P/L, oversell handling, splits, and day P/L.
 */
class PositionReplayTest {

    private var id = 0L
    private fun tx(
        symbol: String,
        side: String,
        shares: Double,
        price: Double,
        fees: Double = 0.0,
        ts: Long = ++id * 1000L,
        plOverride: Double? = null
    ) = TransactionEntity(
        id = ++id, symbol = symbol, side = side, shares = shares,
        price = price, fees = fees, ts = ts, plOverride = plOverride
    )

    @Test
    fun `buy folds fees into average cost`() {
        val positions = PortfolioRepository.computePositions(
            listOf(tx("AAPL", TxSide.BUY, 10.0, 100.0, fees = 10.0))
        )
        val p = positions.single()
        assertEquals(10.0, p.shares, 1e-9)
        assertEquals(101.0, p.avgCost, 1e-9) // (10*100 + 10) / 10
    }

    @Test
    fun `two buys average correctly`() {
        val positions = PortfolioRepository.computePositions(
            listOf(
                tx("AAPL", TxSide.BUY, 10.0, 100.0),
                tx("AAPL", TxSide.BUY, 10.0, 120.0)
            )
        )
        assertEquals(110.0, positions.single().avgCost, 1e-9)
    }

    @Test
    fun `sell realizes against average cost minus fees`() {
        val positions = PortfolioRepository.computePositions(
            listOf(
                tx("AAPL", TxSide.BUY, 10.0, 100.0),
                tx("AAPL", TxSide.SELL, 4.0, 110.0, fees = 2.0)
            )
        )
        val p = positions.single()
        assertEquals(6.0, p.shares, 1e-9)
        assertEquals(4 * 10.0 - 2.0, p.realizedPl, 1e-9)
        assertEquals(100.0, p.avgCost, 1e-9) // selling does not move the average
    }

    @Test
    fun `position goes flat and average resets`() {
        val positions = PortfolioRepository.computePositions(
            listOf(
                tx("AAPL", TxSide.BUY, 5.0, 100.0),
                tx("AAPL", TxSide.SELL, 5.0, 90.0),
                tx("AAPL", TxSide.BUY, 2.0, 50.0)
            )
        )
        val p = positions.single()
        assertEquals(2.0, p.shares, 1e-9)
        assertEquals(50.0, p.avgCost, 1e-9)
        assertEquals(-50.0, p.realizedPl, 1e-9)
    }

    @Test
    fun `plOverride pins the realized outcome`() {
        val positions = PortfolioRepository.computePositions(
            listOf(
                tx("AAPL", TxSide.BUY, 10.0, 100.0),
                tx("AAPL", TxSide.SELL, 10.0, 110.0, plOverride = 42.0)
            )
        )
        assertEquals(42.0, positions.single().realizedPl, 1e-9)
    }

    @Test
    fun `legacy oversell is clamped to shares held`() {
        val positions = PortfolioRepository.computePositions(
            listOf(
                tx("AAPL", TxSide.BUY, 5.0, 100.0),
                tx("AAPL", TxSide.SELL, 50.0, 110.0) // legacy bad row
            )
        )
        val p = positions.single()
        assertEquals(0.0, p.shares, 1e-9)
        assertEquals(5 * 10.0, p.realizedPl, 1e-9) // only 5 shares of profit
    }

    @Test
    fun `firstOversell reports the violation and passes a sound ledger`() {
        val bad = listOf(
            tx("AAPL", TxSide.BUY, 5.0, 100.0),
            tx("AAPL", TxSide.SELL, 6.0, 110.0)
        )
        assertNotNull(PortfolioRepository.firstOversell(bad))

        val good = listOf(
            tx("AAPL", TxSide.BUY, 5.0, 100.0),
            tx("AAPL", TxSide.SELL, 5.0, 110.0)
        )
        assertNull(PortfolioRepository.firstOversell(good))
    }

    @Test
    fun `split multiplies shares and divides cost basis`() {
        val positions = PortfolioRepository.computePositions(
            listOf(
                tx("AAPL", TxSide.BUY, 10.0, 100.0),
                tx("AAPL", TxSide.SPLIT, 4.0, 0.0) // 4-for-1
            )
        )
        val p = positions.single()
        assertEquals(40.0, p.shares, 1e-9)
        assertEquals(25.0, p.avgCost, 1e-9)
        assertEquals(1000.0, p.investedCost, 1e-9) // invested value unchanged
    }

    @Test
    fun `split respects the oversell check`() {
        val ledger = listOf(
            tx("AAPL", TxSide.BUY, 10.0, 100.0),
            tx("AAPL", TxSide.SPLIT, 2.0, 0.0),
            tx("AAPL", TxSide.SELL, 20.0, 60.0)
        )
        assertNull(PortfolioRepository.firstOversell(ledger))
    }

    @Test
    fun `unpriced view carries cost and is flagged`() {
        val position = PortfolioRepository.computePositions(
            listOf(tx("XYZ", TxSide.BUY, 10.0, 50.0))
        ).single()
        val view = PortfolioRepository.toView(position, quote = null)
        assertTrue(!view.priced)
        assertEquals(500.0, view.marketValue, 1e-9)
        assertEquals(0.0, view.unrealizedPl, 1e-9)
    }

    @Test
    fun `summarize counts unpriced holdings separately`() {
        val priced = PortfolioRepository.computePositions(
            listOf(tx("AAPL", TxSide.BUY, 10.0, 100.0))
        ).single()
        val unpriced = PortfolioRepository.computePositions(
            listOf(tx("XYZ", TxSide.BUY, 10.0, 50.0))
        ).single()
        val quote = Quote(symbol = "AAPL", price = 110.0, prevClose = 100.0)
        val views = listOf(
            PortfolioRepository.toView(priced, quote),
            PortfolioRepository.toView(unpriced, null)
        )
        val summary = PortfolioRepository.summarize(views, listOf(priced, unpriced))
        assertEquals(1, summary.unpricedCount)
        assertEquals(500.0, summary.unpricedCost, 1e-9)
    }

    @Test
    fun `day pl credits only intraday moves on shares bought today`() {
        val dayStart = 1_000_000L
        val ledger = listOf(
            // Bought today at 100 (after day start).
            tx("AAPL", TxSide.BUY, 10.0, 100.0, ts = dayStart + 1000L)
        )
        val quote = Quote(symbol = "AAPL", price = 105.0, prevClose = 90.0)
        val dayPl = PortfolioRepository.dayPlBySymbol(ledger, mapOf("AAPL" to quote), dayStart)
        // Only 100 -> 105 counts, never 90 -> 105.
        assertEquals(50.0, dayPl["AAPL"] ?: 0.0, 1e-9)
    }
}
