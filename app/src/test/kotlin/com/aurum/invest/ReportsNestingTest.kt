package com.aurum.invest

import com.aurum.invest.analytics.ReportPeriod
import com.aurum.invest.analytics.ReportsEngine
import com.aurum.invest.analytics.TradeGrouping
import com.aurum.invest.data.db.TransactionEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reports nest one level finer than their own period: a week's card holds
 * its days, a month's its weeks, a year's its months — and accumulated P/L
 * runs from the ledger's start through each period.
 */
class ReportsNestingTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private var nextId = 1L

    private fun tx(date: String, side: String, shares: Double, price: Double): TransactionEntity =
        TransactionEntity(
            id = nextId++,
            symbol = "AAPL",
            side = side,
            shares = shares,
            price = price,
            fees = 0.0,
            ts = LocalDate.parse(date).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        )

    // A buy each in Jan and Feb 2026 (different weeks), sells booking +100
    // then +50, spread over two months of one year.
    private fun ledger() = listOf(
        tx("2026-01-05", "BUY", 10.0, 100.0),   // week of Jan 5
        tx("2026-01-14", "SELL", 5.0, 120.0),   // +100, week of Jan 12
        tx("2026-02-03", "BUY", 5.0, 110.0),    // week of Feb 2
        tx("2026-02-10", "SELL", 5.0, 110.0)    // +50 vs avg 100? replay decides
    )

    @Test
    fun weekCardsGroupTheirTradesByDay() {
        val weeks = ReportsEngine.build(ledger(), ReportPeriod.WEEK)
        assertTrue(weeks.isNotEmpty())
        weeks.forEach { w ->
            assertEquals(TradeGrouping.DAY, w.grouping)
            // Every trade of the week appears in exactly one day bucket.
            assertEquals(w.trades.size, w.groups.sumOf { it.trades.size })
        }
    }

    @Test
    fun monthCardsGroupTheirTradesByWeek() {
        val months = ReportsEngine.build(ledger(), ReportPeriod.MONTH)
        assertEquals(2, months.size) // Jan and Feb 2026
        months.forEach { m -> assertEquals(TradeGrouping.WEEK, m.grouping) }
        // January had trades in two different weeks.
        val january = months.first { it.periodKey == "2026-01" }
        assertEquals(2, january.groups.size)
    }

    @Test
    fun yearCardsGroupTheirTradesByMonth() {
        val years = ReportsEngine.build(ledger(), ReportPeriod.YEAR)
        assertEquals(1, years.size)
        val y = years.single()
        assertEquals("2026", y.periodKey)
        assertEquals(TradeGrouping.MONTH, y.grouping)
        assertEquals(2, y.groups.size) // Jan + Feb
        assertEquals(y.trades.size, y.groups.sumOf { it.trades.size })
    }

    @Test
    fun accumulatedPlRunsAcrossPeriods() {
        val months = ReportsEngine.build(ledger(), ReportPeriod.MONTH)
        // Newest first: February's accumulated = January + February realized.
        val january = months.first { it.periodKey == "2026-01" }
        val february = months.first { it.periodKey == "2026-02" }
        assertEquals(
            january.realizedPl + february.realizedPl,
            february.accumulatedPl,
            1e-9
        )
        assertEquals(january.realizedPl, january.accumulatedPl, 1e-9)
    }

    @Test
    fun sellsClampToHeldSharesLikeThePositionEngine() {
        // Selling 20 when only 10 are held: the report must count 10, and the
        // realized number must match the clamped replay, not the stated 20.
        val txs = listOf(
            tx("2026-03-02", "BUY", 10.0, 100.0),
            tx("2026-03-03", "SELL", 20.0, 110.0)
        )
        val day = ReportsEngine.build(txs, ReportPeriod.DAY)
        val sellDay = day.first { it.periodKey == "2026-03-03" }
        assertEquals(10.0, sellDay.trades.single().shares, 1e-9)
        assertEquals(100.0, sellDay.realizedPl, 1e-9) // 10 * (110-100)
    }
}
