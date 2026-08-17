package com.aurum.invest

import com.aurum.invest.analytics.ReportPeriod
import com.aurum.invest.analytics.ReportsEngine
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
import com.aurum.invest.data.repo.PortfolioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every operation is unique, despite the stock in the operation: a
 * buy→sell→rebuy round trip must produce distinct ledger rows, correct
 * per-operation realized P/L, and reports that agree with the position
 * engine — and deleting a row a later sell depended on must be refused.
 */
class OperationUniquenessTest {

    private var id = 0L
    private fun tx(
        symbol: String,
        side: String,
        shares: Double,
        price: Double,
        fees: Double = 0.0,
        ref: String? = null
    ): TransactionEntity {
        ++id
        return TransactionEntity(
            id = id, symbol = symbol, side = side, shares = shares,
            price = price, fees = fees, ts = id * 1000L, ref = ref
        )
    }

    @Test
    fun `buy sell rebuy in one period keeps every operation distinct`() {
        val ledger = listOf(
            tx("AAPL", TxSide.BUY, 10.0, 100.0),
            tx("AAPL", TxSide.SELL, 10.0, 110.0),   // +100 realized, closes the first operation
            tx("AAPL", TxSide.BUY, 10.0, 100.0)      // identical shape — a NEW operation
        )
        val report = ReportsEngine.build(ledger, ReportPeriod.MONTH).single()

        assertEquals(2, report.buysCount)
        assertEquals(1, report.sellsCount)
        assertEquals(3, report.trades.size)
        // Each line is backed by its own ledger row.
        assertEquals(3, report.trades.map { it.txId }.distinct().size)
        // The realized outcome belongs to the closed operation only.
        assertEquals(100.0, report.realizedPl, 1e-9)

        // The position engine agrees: the rebuy starts a fresh basis.
        val position = PortfolioRepository.computePositions(ledger).single()
        assertEquals(10.0, position.shares, 1e-9)
        assertEquals(100.0, position.avgCost, 1e-9)
        assertEquals(100.0, position.realizedPl, 1e-9)
    }

    @Test
    fun `rebuy at the same price and size does not contaminate the second sell`() {
        val ledger = listOf(
            tx("TSLA", TxSide.BUY, 5.0, 200.0),
            tx("TSLA", TxSide.SELL, 5.0, 210.0),    // +50
            tx("TSLA", TxSide.BUY, 5.0, 200.0),     // same shape rebuy
            tx("TSLA", TxSide.SELL, 5.0, 190.0)     // -50 on the SECOND operation's basis
        )
        val report = ReportsEngine.build(ledger, ReportPeriod.MONTH).single()
        assertEquals(0.0, report.realizedPl, 1e-9)
        val sells = report.trades.filter { it.realizedPl != null }
        assertEquals(2, sells.size)
        assertEquals(50.0, sells[0].realizedPl!!, 1e-9)
        assertEquals(-50.0, sells[1].realizedPl!!, 1e-9)
    }

    @Test
    fun `distinct broker refs are distinct operations even with identical shape`() {
        val first = tx("NVDA", TxSide.BUY, 10.0, 150.25, ref = "A100")
        val rebuy = tx("NVDA", TxSide.BUY, 10.0, 150.25, ref = "A102")
        assertNotNull(first.ref)
        assertNotNull(rebuy.ref)
        assertTrue(first.ref != rebuy.ref)
        // Same shape, different identity — the ledger must hold both.
        val positions = PortfolioRepository.computePositions(listOf(first, rebuy))
        assertEquals(20.0, positions.single().shares, 1e-9)
    }

    @Test
    fun `deleting the buy behind a recorded sell is an oversell`() {
        val buy = tx("AMD", TxSide.BUY, 10.0, 100.0)
        val sell = tx("AMD", TxSide.SELL, 10.0, 110.0)
        val rebuy = tx("AMD", TxSide.BUY, 4.0, 105.0)
        val ledger = listOf(buy, sell, rebuy)

        // Without the first buy the sell hangs in the air.
        assertNotNull(PortfolioRepository.firstOversell(ledger.filter { it.id != buy.id }))
        // Deleting the rebuy leaves a sound ledger.
        assertNull(PortfolioRepository.firstOversell(ledger.filter { it.id != rebuy.id }))
    }
}
