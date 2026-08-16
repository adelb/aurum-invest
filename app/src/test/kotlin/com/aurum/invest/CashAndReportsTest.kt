package com.aurum.invest

import com.aurum.invest.analytics.ReportsEngine
import com.aurum.invest.analytics.ReportPeriod
import com.aurum.invest.data.db.CashEventEntity
import com.aurum.invest.data.db.CashType
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
import com.aurum.invest.data.repo.CashRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cash ledger math (C2) and report/ledger consistency (C7). */
class CashAndReportsTest {

    private var id = 0L
    private fun tx(symbol: String, side: String, shares: Double, price: Double, fees: Double = 0.0) =
        TransactionEntity(
            id = ++id, symbol = symbol, side = side, shares = shares,
            price = price, fees = fees, ts = id * 1000L
        )

    private fun cashEvent(type: String, amount: Double) =
        CashEventEntity(id = ++id, type = type, amount = amount, ts = id * 1000L)

    @Test
    fun `untracked until a deposit exists`() {
        val state = CashRepository.compute(
            cash = listOf(cashEvent(CashType.DIVIDEND, 50.0)),
            orderedTxs = emptyList()
        )
        assertTrue(!state.tracked)
    }

    @Test
    fun `balance reconciles deposits dividends fees and trades`() {
        val state = CashRepository.compute(
            cash = listOf(
                cashEvent(CashType.DEPOSIT, 10_000.0),
                cashEvent(CashType.DIVIDEND, 50.0),
                cashEvent(CashType.FEE, 10.0),
                cashEvent(CashType.WITHDRAW, 1_000.0)
            ),
            orderedTxs = listOf(
                tx("AAPL", TxSide.BUY, 10.0, 100.0, fees = 5.0),   // -1005
                tx("AAPL", TxSide.SELL, 5.0, 110.0, fees = 2.0)    // +548
            )
        )
        assertTrue(state.tracked)
        assertEquals(10_000.0 + 50.0 - 10.0 - 1_000.0 - 1_005.0 + 548.0, state.balance, 1e-9)
    }

    @Test
    fun `cash sell proceeds clamp to shares held`() {
        val state = CashRepository.compute(
            cash = listOf(cashEvent(CashType.DEPOSIT, 1_000.0)),
            orderedTxs = listOf(
                tx("AAPL", TxSide.BUY, 5.0, 100.0),
                tx("AAPL", TxSide.SELL, 50.0, 100.0) // legacy oversell row
            )
        )
        // Only 5 shares' proceeds count — the ledger cannot mint money.
        assertEquals(1_000.0 - 500.0 + 500.0, state.balance, 1e-9)
    }

    @Test
    fun `report sell totals use the clamped quantity`() {
        val reports = ReportsEngine.build(
            listOf(
                tx("AAPL", TxSide.BUY, 5.0, 100.0),
                tx("AAPL", TxSide.SELL, 50.0, 110.0) // legacy oversell row
            ),
            ReportPeriod.MONTH
        )
        val report = reports.single()
        // The report agrees with the position engine: 5 shares sold, not 50.
        assertEquals(5 * 110.0, report.sellsTotal, 1e-9)
        assertEquals(5 * 10.0, report.realizedPl, 1e-9)
    }

    @Test
    fun `splits do not appear as trade activity`() {
        val reports = ReportsEngine.build(
            listOf(
                tx("AAPL", TxSide.BUY, 10.0, 100.0),
                tx("AAPL", TxSide.SPLIT, 4.0, 0.0),
                tx("AAPL", TxSide.SELL, 40.0, 30.0)
            ),
            ReportPeriod.MONTH
        )
        val report = reports.single()
        assertEquals(1, report.buysCount)
        assertEquals(1, report.sellsCount)
        // Realized: 40 shares sold at 30 vs a post-split basis of 25.
        assertEquals(40 * 5.0, report.realizedPl, 1e-9)
    }
}
