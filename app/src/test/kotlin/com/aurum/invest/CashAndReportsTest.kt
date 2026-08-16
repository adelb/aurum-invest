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

    /** Two calendar years apart so YEAR buckets separately from MONTH/WEEK. */
    private fun txAt(symbol: String, side: String, shares: Double, price: Double, epochMs: Long) =
        TransactionEntity(id = ++id, symbol = symbol, side = side, shares = shares, price = price, ts = epochMs)

    @Test
    fun `year period buckets trades by calendar year`() {
        val y2024 = java.time.LocalDate.of(2024, 6, 1).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val y2025 = java.time.LocalDate.of(2025, 3, 1).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val reports = ReportsEngine.build(
            listOf(
                txAt("AAPL", TxSide.BUY, 10.0, 100.0, y2024),
                txAt("AAPL", TxSide.SELL, 10.0, 120.0, y2024 + 1000),
                txAt("MSFT", TxSide.BUY, 5.0, 200.0, y2025),
                txAt("MSFT", TxSide.SELL, 5.0, 180.0, y2025 + 1000)
            ),
            ReportPeriod.YEAR
        )
        assertEquals(2, reports.size)
        val r2024 = reports.single { it.periodKey == "2024" }
        val r2025 = reports.single { it.periodKey == "2025" }
        assertEquals("2024", r2024.label)
        assertEquals(200.0, r2024.realizedPl, 1e-9)   // 10 * (120-100)
        assertEquals(-100.0, r2025.realizedPl, 1e-9)   // 5 * (180-200)
    }

    @Test
    fun `accumulated P L is a running cumulative total across periods`() {
        val reports = ReportsEngine.build(
            listOf(
                tx("AAPL", TxSide.BUY, 10.0, 100.0),
                tx("AAPL", TxSide.SELL, 10.0, 110.0)   // +100, month 1
            ) + run {
                // A second month with its own buy/sell, two months later.
                val base = id
                listOf(
                    TransactionEntity(id = base + 1, symbol = "MSFT", side = TxSide.BUY, shares = 5.0, price = 50.0, ts = (base + 1) * 1000L + 5_184_000_000L),
                    TransactionEntity(id = base + 2, symbol = "MSFT", side = TxSide.SELL, shares = 5.0, price = 40.0, ts = (base + 2) * 1000L + 5_184_000_000L)
                )
            },
            ReportPeriod.MONTH
        )
        // Newest-first order preserved; accumulated P/L is monotonically
        // built from oldest to newest, so the newest report's accumulated
        // figure equals the sum of every period's own realizedPl.
        val totalRealized = reports.sumOf { it.realizedPl }
        val newest = reports.maxByOrNull { it.startTs }!!
        val oldest = reports.minByOrNull { it.startTs }!!
        assertEquals(totalRealized, newest.accumulatedPl, 1e-9)
        assertEquals(oldest.realizedPl, oldest.accumulatedPl, 1e-9)
    }

    @Test
    fun `week report groups its trades by day`() {
        // Monday and Wednesday of the same week.
        val monday = java.time.LocalDate.of(2025, 6, 2).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val wednesday = java.time.LocalDate.of(2025, 6, 4).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val reports = ReportsEngine.build(
            listOf(
                txAt("AAPL", TxSide.BUY, 10.0, 100.0, monday),
                txAt("AAPL", TxSide.SELL, 10.0, 110.0, monday + 1000),
                txAt("MSFT", TxSide.BUY, 5.0, 50.0, wednesday),
                txAt("MSFT", TxSide.SELL, 5.0, 40.0, wednesday + 1000)
            ),
            ReportPeriod.WEEK
        )
        assertEquals(1, reports.size)
        val week = reports.single()
        assertEquals(com.aurum.invest.analytics.TradeGrouping.DAY, week.grouping)
        assertEquals(2, week.groups.size)
        assertEquals(4, week.groups.sumOf { it.trades.size })
        assertEquals(100.0, week.groups.first { it.trades.any { t -> t.symbol == "AAPL" } }.realizedPl, 1e-9)
        assertEquals(-50.0, week.groups.first { it.trades.any { t -> t.symbol == "MSFT" } }.realizedPl, 1e-9)
    }

    @Test
    fun `month report groups its trades by week and year report groups by month`() {
        val week1 = java.time.LocalDate.of(2025, 6, 2).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val week3 = java.time.LocalDate.of(2025, 6, 16).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val monthReports = ReportsEngine.build(
            listOf(
                txAt("AAPL", TxSide.BUY, 10.0, 100.0, week1),
                txAt("AAPL", TxSide.SELL, 10.0, 110.0, week1 + 1000),
                txAt("MSFT", TxSide.BUY, 5.0, 50.0, week3),
                txAt("MSFT", TxSide.SELL, 5.0, 60.0, week3 + 1000)
            ),
            ReportPeriod.MONTH
        )
        assertEquals(1, monthReports.size)
        assertEquals(com.aurum.invest.analytics.TradeGrouping.WEEK, monthReports.single().grouping)
        assertEquals(2, monthReports.single().groups.size)

        val jan = java.time.LocalDate.of(2025, 1, 15).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val mar = java.time.LocalDate.of(2025, 3, 15).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val yearReports = ReportsEngine.build(
            listOf(
                txAt("AAPL", TxSide.BUY, 10.0, 100.0, jan),
                txAt("AAPL", TxSide.SELL, 10.0, 110.0, jan + 1000),
                txAt("MSFT", TxSide.BUY, 5.0, 50.0, mar),
                txAt("MSFT", TxSide.SELL, 5.0, 60.0, mar + 1000)
            ),
            ReportPeriod.YEAR
        )
        assertEquals(1, yearReports.size)
        assertEquals(com.aurum.invest.analytics.TradeGrouping.MONTH, yearReports.single().grouping)
        assertEquals(2, yearReports.single().groups.size)
    }

    @Test
    fun `day report has no grouping`() {
        val reports = ReportsEngine.build(
            listOf(
                tx("AAPL", TxSide.BUY, 10.0, 100.0),
                tx("AAPL", TxSide.SELL, 10.0, 110.0)
            ),
            ReportPeriod.DAY
        )
        assertEquals(com.aurum.invest.analytics.TradeGrouping.NONE, reports.single().grouping)
        assertTrue(reports.single().groups.isEmpty())
    }
}
