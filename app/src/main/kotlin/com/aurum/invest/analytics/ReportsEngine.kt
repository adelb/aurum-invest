package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
import com.aurum.invest.data.model.TradeSide
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class ReportPeriod { DAY, WEEK, MONTH }

/** One transaction as it appears inside a period report. */
data class TradeLine(
    val symbol: String,
    val side: String,
    val shares: Double,
    val price: Double,
    val ts: Long,
    val realizedPl: Double?,  // non-null for SELL rows only
    /** The ledger row behind this line — lets the report edit it in place. */
    val txId: Long = 0L,
    /** True when [realizedPl] is the user's pinned outcome, not the computed one. */
    val plOverridden: Boolean = false
)

/** Aggregated trade activity for one day, one week, or one month. */
data class PeriodReport(
    val periodKey: String,        // day: ISO "2026-08-10"; week: ISO Monday; month: "2026-08"
    val label: String,            // "Week of Aug 3" / "August 2026"
    val startTs: Long,
    val endTs: Long,
    val buysCount: Int,
    val buysTotal: Double,
    val sellsCount: Int,
    val sellsTotal: Double,
    val realizedPl: Double,
    val bestTrade: TradeLine?,    // highest realizedPl among the period sells
    val worstTrade: TradeLine?,   // lowest realizedPl among the period sells
    val trades: List<TradeLine>   // chronological
)

/**
 * Builds weekly / monthly trade reports by replaying the full ordered ledger with
 * the same weighted-average cost math as [com.aurum.invest.data.repo.PortfolioRepository]:
 * buys fold fees into cost; sell realized = qty*(price - avg) - fees with qty clamped
 * to the held quantity; average resets when a position goes flat.
 */
object ReportsEngine {

    private val monthKeyFmt = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
    private val monthLabelFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    private val dayLabelFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)

    fun build(transactions: List<TransactionEntity>, period: ReportPeriod): List<PeriodReport> {
        if (transactions.isEmpty()) return emptyList()

        val zone = ZoneId.systemDefault()
        val ordered = transactions.sortedWith(compareBy({ it.ts }, { it.id }))

        class Acc(var shares: Double = 0.0, var avg: Double = 0.0)
        class Bucket(
            val trades: MutableList<TradeLine> = mutableListOf(),
            var buysCount: Int = 0,
            var buysTotal: Double = 0.0,
            var sellsCount: Int = 0,
            var sellsTotal: Double = 0.0
        )

        val bySymbol = HashMap<String, Acc>()
        val buckets = LinkedHashMap<String, Bucket>()

        for (tx in ordered) {
            val symbol = tx.symbol.trim().uppercase()
            val acc = bySymbol.getOrPut(symbol) { Acc() }

            // Replicate PortfolioRepository.computePositions exactly. SPLIT
            // rows scale the replay state and never appear as trade activity.
            if (tx.side == TxSide.SPLIT) {
                if (tx.shares > 0.0 && acc.shares > 0.0) {
                    acc.shares *= tx.shares
                    acc.avg /= tx.shares
                }
                continue
            }

            val realized: Double?
            // Effective quantity: sells clamp to the held amount, matching the
            // position engine, so the report's totals can never disagree with
            // the portfolio's truth.
            val effShares: Double
            if (tx.side == TradeSide.BUY.name) {
                val newShares = acc.shares + tx.shares
                if (newShares > 0) {
                    acc.avg = (acc.shares * acc.avg + tx.shares * tx.price + tx.fees) / newShares
                }
                acc.shares = newShares
                realized = null
                effShares = tx.shares
            } else {
                val qty = minOf(tx.shares, acc.shares)
                val computed = if (qty > 0) {
                    val r = qty * (tx.price - acc.avg) - tx.fees
                    acc.shares -= qty
                    if (acc.shares < 1e-9) {
                        acc.shares = 0.0
                        acc.avg = 0.0
                    }
                    r
                } else {
                    0.0
                }
                // The user's pinned outcome wins over the replayed number —
                // same rule as PortfolioRepository.computePositions.
                realized = tx.plOverride ?: computed
                effShares = qty
            }

            val day = Instant.ofEpochMilli(tx.ts).atZone(zone).toLocalDate()
            val key = when (period) {
                ReportPeriod.DAY -> day.toString()
                ReportPeriod.WEEK ->
                    day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
                ReportPeriod.MONTH ->
                    day.format(monthKeyFmt)
            }

            val bucket = buckets.getOrPut(key) { Bucket() }
            bucket.trades += TradeLine(
                symbol = symbol,
                side = tx.side,
                shares = effShares,
                price = tx.price,
                ts = tx.ts,
                realizedPl = realized,
                txId = tx.id,
                plOverridden = tx.side == TradeSide.SELL.name && tx.plOverride != null
            )
            if (tx.side == TradeSide.BUY.name) {
                bucket.buysCount += 1
                bucket.buysTotal += tx.shares * tx.price + tx.fees
            } else {
                bucket.sellsCount += 1
                bucket.sellsTotal += effShares * tx.price - tx.fees
            }
        }

        return buckets.map { (key, bucket) ->
            val label: String
            val startTs: Long
            val endTs: Long
            when (period) {
                ReportPeriod.DAY -> {
                    val d = LocalDate.parse(key)
                    label = d.format(dayLabelFmt)
                    startTs = d.atStartOfDay(zone).toInstant().toEpochMilli()
                    endTs = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                }
                ReportPeriod.WEEK -> {
                    val monday = LocalDate.parse(key)
                    label = Dates.weekStartLabel(key)
                    startTs = monday.atStartOfDay(zone).toInstant().toEpochMilli()
                    endTs = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                }
                ReportPeriod.MONTH -> {
                    val ym = YearMonth.parse(key, monthKeyFmt)
                    label = ym.atDay(1).format(monthLabelFmt)
                    startTs = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    endTs = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                }
            }
            val sells = bucket.trades.filter { it.realizedPl != null }
            PeriodReport(
                periodKey = key,
                label = label,
                startTs = startTs,
                endTs = endTs,
                buysCount = bucket.buysCount,
                buysTotal = bucket.buysTotal,
                sellsCount = bucket.sellsCount,
                sellsTotal = bucket.sellsTotal,
                realizedPl = sells.sumOf { it.realizedPl ?: 0.0 },
                bestTrade = sells.maxByOrNull { it.realizedPl ?: 0.0 },
                worstTrade = sells.minByOrNull { it.realizedPl ?: 0.0 },
                trades = bucket.trades
            )
        }.sortedByDescending { it.periodKey }
    }
}
