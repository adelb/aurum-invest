package com.aurum.invest.data.repo

import com.aurum.invest.data.db.CashEventDao
import com.aurum.invest.data.db.CashEventEntity
import com.aurum.invest.data.db.CashType
import com.aurum.invest.data.db.TransactionDao
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Brokerage-cash state derived from the cash ledger plus trade flows.
 * Tracking is opt-in: with no DEPOSIT row the balance is unknown and the
 * app must show "cash not tracked" instead of inventing a number.
 */
data class CashState(
    val tracked: Boolean,
    val balance: Double,
    val deposits: Double,
    val withdrawals: Double,
    val dividends: Double,
    val interest: Double,
    val fees: Double,
    /** Net cash from trading: sell proceeds − buy costs (sells at clamped qty). */
    val tradeNet: Double
) {
    companion object {
        val UNTRACKED = CashState(false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

class CashRepository(
    private val cashDao: CashEventDao,
    private val txDao: TransactionDao
) {

    fun observeEvents(): Flow<List<CashEventEntity>> = cashDao.observeAll()

    fun observeCash(): Flow<CashState> =
        combine(cashDao.observeAll(), txDao.observeAll()) { cash, txs ->
            compute(cash, txs.sortedWith(compareBy({ it.ts }, { it.id })))
        }

    suspend fun addEvent(
        type: String,
        amount: Double,
        symbol: String = "",
        ts: Long = System.currentTimeMillis(),
        note: String = "",
        currency: String = "USD",
        fxRate: Double = 1.0
    ): Long = cashDao.insert(
        CashEventEntity(
            type = type,
            symbol = symbol.trim().uppercase(),
            amount = amount,
            currency = currency,
            fxRate = fxRate,
            ts = ts,
            note = note
        )
    )

    suspend fun deleteEvent(e: CashEventEntity) = cashDao.delete(e)

    companion object {

        fun compute(cash: List<CashEventEntity>, orderedTxs: List<TransactionEntity>): CashState {
            val tracked = cash.any { it.type == CashType.DEPOSIT }
            var deposits = 0.0
            var withdrawals = 0.0
            var dividends = 0.0
            var interest = 0.0
            var fees = 0.0
            for (e in cash) {
                when (e.type) {
                    CashType.DEPOSIT -> deposits += e.amount
                    CashType.WITHDRAW -> withdrawals += e.amount
                    CashType.DIVIDEND -> dividends += e.amount
                    CashType.INTEREST -> interest += e.amount
                    CashType.FEE -> fees += e.amount
                }
            }

            // Trade flows replayed with the same clamped-sell rule as the
            // position engine, so cash and positions can never disagree.
            val held = HashMap<String, Double>()
            var tradeNet = 0.0
            for (tx in orderedTxs) {
                val sym = tx.symbol.trim().uppercase()
                val cur = held[sym] ?: 0.0
                when (tx.side) {
                    TxSide.BUY -> {
                        held[sym] = cur + tx.shares
                        tradeNet -= tx.shares * tx.price + tx.fees
                    }
                    TxSide.SPLIT -> if (tx.shares > 0.0) held[sym] = cur * tx.shares
                    else -> {
                        val qty = minOf(tx.shares, cur)
                        held[sym] = (cur - qty).coerceAtLeast(0.0)
                        tradeNet += qty * tx.price - tx.fees
                    }
                }
            }

            val balance = deposits - withdrawals + dividends + interest - fees + tradeNet
            return CashState(
                tracked = tracked,
                balance = balance,
                deposits = deposits,
                withdrawals = withdrawals,
                dividends = dividends,
                interest = interest,
                fees = fees,
                tradeNet = tradeNet
            )
        }
    }
}
