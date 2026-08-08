package com.aurum.invest.data.repo

import com.aurum.invest.data.db.TransactionDao
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.model.PortfolioSummary
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.PositionView
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.model.TradeSide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * The ledger. Positions and P/L are always derived from the ordered list of
 * transactions with weighted-average cost basis:
 *   buy:  avg' = (shares*avg + qty*price + fees) / (shares + qty)
 *   sell: realized += qty*(price - avg) - fees ; shares -= qty
 */
class PortfolioRepository(private val txDao: TransactionDao) {

    fun observeTransactions(): Flow<List<TransactionEntity>> = txDao.observeAll()

    /** All symbols ever traded; filter shares > 0 for open holdings. */
    fun observePositions(): Flow<List<Position>> =
        txDao.observeAll().map { txs -> computePositions(txs.sortedWith(compareBy({ it.ts }, { it.id }))) }

    suspend fun positionsNow(): List<Position> = computePositions(txDao.getAllOrdered())

    suspend fun addTransaction(
        symbol: String,
        side: TradeSide,
        shares: Double,
        price: Double,
        fees: Double = 0.0,
        ts: Long = System.currentTimeMillis(),
        source: String = "MANUAL",
        note: String = ""
    ): Long = txDao.insert(
        TransactionEntity(
            symbol = symbol.trim().uppercase(),
            side = side.name,
            shares = shares,
            price = price,
            fees = fees,
            ts = ts,
            source = source,
            note = note
        )
    )

    suspend fun deleteTransaction(tx: TransactionEntity) = txDao.delete(tx)

    companion object {

        fun computePositions(ordered: List<TransactionEntity>): List<Position> {
            data class Acc(var shares: Double = 0.0, var avg: Double = 0.0, var realized: Double = 0.0)

            val bySymbol = LinkedHashMap<String, Acc>()
            for (tx in ordered) {
                val acc = bySymbol.getOrPut(tx.symbol.uppercase()) { Acc() }
                if (tx.side == TradeSide.BUY.name) {
                    val newShares = acc.shares + tx.shares
                    if (newShares > 0) {
                        acc.avg = (acc.shares * acc.avg + tx.shares * tx.price + tx.fees) / newShares
                    }
                    acc.shares = newShares
                } else {
                    // never sell more than held; ignore the excess
                    val qty = minOf(tx.shares, acc.shares)
                    if (qty > 0) {
                        acc.realized += qty * (tx.price - acc.avg) - tx.fees
                        acc.shares -= qty
                        if (acc.shares < 1e-9) {
                            acc.shares = 0.0
                            acc.avg = 0.0
                        }
                    }
                }
            }
            return bySymbol.map { (symbol, acc) ->
                Position(
                    symbol = symbol,
                    shares = acc.shares,
                    avgCost = acc.avg,
                    investedCost = acc.shares * acc.avg,
                    realizedPl = acc.realized
                )
            }
        }

        fun toView(position: Position, quote: Quote?): PositionView {
            val price = quote?.price ?: position.avgCost
            val marketValue = position.shares * price
            val unrealized = position.shares * (price - position.avgCost)
            val unrealizedPct =
                if (position.investedCost > 1e-9) unrealized / position.investedCost * 100.0 else 0.0
            val dayPl = if (quote != null) position.shares * quote.dayChangeAbs else 0.0
            return PositionView(
                position = position,
                quote = quote,
                marketValue = marketValue,
                unrealizedPl = unrealized,
                unrealizedPlPct = unrealizedPct,
                dayPl = dayPl
            )
        }

        /** [allPositions] must include closed positions so realized P/L is complete. */
        fun summarize(openViews: List<PositionView>, allPositions: List<Position>): PortfolioSummary {
            val marketValue = openViews.sumOf { it.marketValue }
            val invested = openViews.sumOf { it.position.investedCost }
            val unrealized = openViews.sumOf { it.unrealizedPl }
            val realized = allPositions.sumOf { it.realizedPl }
            val dayPl = openViews.sumOf { it.dayPl }
            return PortfolioSummary(
                marketValue = marketValue,
                investedCost = invested,
                unrealizedPl = unrealized,
                realizedPl = realized,
                totalPl = unrealized + realized,
                dayPl = dayPl
            )
        }

        fun isOpen(p: Position): Boolean = abs(p.shares) > 1e-9
    }
}
