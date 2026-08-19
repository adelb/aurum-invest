package com.aurum.invest.data.repo

import com.aurum.invest.data.db.TransactionDao
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
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
 *   buy:   avg' = (shares*avg + qty*price + fees) / (shares + qty)
 *   sell:  realized += qty*(price - avg) - fees ; shares -= qty
 *   split: shares *= ratio ; avg /= ratio (ratio stored in the row's shares)
 */
class PortfolioRepository(private val txDao: TransactionDao) {

    fun observeTransactions(): Flow<List<TransactionEntity>> = txDao.observeAll()

    /** The full ledger, ordered — for the accurate day-P/L computation. */
    suspend fun orderedTransactionsNow(): List<TransactionEntity> = txDao.getAllOrdered()

    /** All symbols ever traded; filter shares > 0 for open holdings. */
    fun observePositions(): Flow<List<Position>> =
        txDao.observeAll().map { txs -> computePositions(txs.sortedWith(compareBy({ it.ts }, { it.id }))) }

    suspend fun positionsNow(): List<Position> = computePositions(txDao.getAllOrdered())

    /**
     * Shares of [symbol] that can be sold right now. The add-trade form
     * rejects any sell above this instead of letting the replay silently
     * clamp it later.
     */
    suspend fun sellableShares(symbol: String): Double =
        positionsNow().firstOrNull { it.symbol == symbol.trim().uppercase() }?.shares ?: 0.0

    suspend fun addTransaction(
        symbol: String,
        side: TradeSide,
        shares: Double,
        price: Double,
        fees: Double = 0.0,
        ts: Long = System.currentTimeMillis(),
        source: String = "MANUAL",
        note: String = "",
        currency: String = "USD",
        fxRate: Double = 1.0,
        ref: String? = null
    ): Long = txDao.insert(
        TransactionEntity(
            symbol = symbol.trim().uppercase(),
            side = side.name,
            shares = shares,
            price = price,
            fees = fees,
            ts = ts,
            source = source,
            note = note,
            currency = currency,
            fxRate = fxRate,
            ref = ref?.trim()?.takeIf { it.isNotEmpty() }
        )
    )

    /** Records a stock split as a ledger row: shares carries the ratio (4.0 = 4-for-1). */
    suspend fun addSplit(symbol: String, ratio: Double, ts: Long, note: String = ""): Long =
        txDao.insert(
            TransactionEntity(
                symbol = symbol.trim().uppercase(),
                side = TxSide.SPLIT,
                shares = ratio,
                price = 0.0,
                ts = ts,
                source = "MANUAL",
                note = note
            )
        )

    /**
     * True when an identical BANK trade already sits in the ledger near [ts] —
     * the guard that makes double-importing the same broker alert impossible.
     */
    suspend fun bankDuplicateExists(
        symbol: String,
        side: TradeSide,
        shares: Double,
        price: Double,
        ts: Long
    ): Boolean = txDao.countBankDuplicates(
        symbol = symbol.trim().uppercase(),
        side = side.name,
        shares = shares,
        price = price,
        tsFrom = ts - DUPLICATE_WINDOW_MS,
        tsTo = ts + DUPLICATE_WINDOW_MS
    ) > 0

    /**
     * True when a BANK row already carries this broker reference for this
     * symbol. The reference is the operation's unique identity: a different
     * ref is a different operation even when symbol, side, shares, and price
     * all match — so a genuine rebuy is never swallowed as a duplicate.
     */
    suspend fun bankRefExists(symbol: String, ref: String): Boolean =
        txDao.countBankRef(symbol.trim().uppercase(), ref.trim()) > 0

    /**
     * True when a BANK row with NO reference already matches this trade's
     * shape near [ts]. A ref-carrying alert must still be checked this way:
     * the same execution imported from an earlier ref-less alert has no ref
     * to be recognised by, and importing it again is what leaves a symbol
     * selling shares the ledger has no buy for.
     */
    suspend fun bankRefLessDuplicateExists(
        symbol: String,
        side: TradeSide,
        shares: Double,
        price: Double,
        ts: Long
    ): Boolean = txDao.countRefLessBankDuplicates(
        symbol = symbol.trim().uppercase(),
        side = side.name,
        shares = shares,
        price = price,
        tsFrom = ts - DUPLICATE_WINDOW_MS,
        tsTo = ts + DUPLICATE_WINDOW_MS
    ) > 0

    suspend fun deleteTransaction(tx: TransactionEntity) = txDao.delete(tx)

    /**
     * Replays the ledger WITHOUT [tx] and returns an error message when the
     * deletion would leave some later sell unbacked — deleting a buy that a
     * recorded sell depended on would silently corrupt every realized number.
     * Judged as a DIFFERENCE: a gap the ledger already carried is not this
     * deletion's doing and never blocks it. Null when the deletion is sound.
     */
    suspend fun validateDelete(tx: TransactionEntity): String? {
        val before = txDao.getAllOrdered()
        return worsenedGap(before, before.filter { it.id != tx.id }, "Deleting this trade")
    }

    /** Every ledger row for one symbol, newest first — the edit screen's source. */
    fun observeTransactionsFor(symbol: String): Flow<List<TransactionEntity>> =
        txDao.observeForSymbol(symbol.trim().uppercase())

    /**
     * Corrects an existing trade in place. Positions, P/L and reports all
     * recompute from the ledger, so fixing a row here fixes them everywhere.
     * [plOverride] pins a SELL's realized outcome to the broker's real
     * number; null returns it to the computed value.
     */
    suspend fun updateTransaction(
        tx: TransactionEntity,
        side: TradeSide,
        shares: Double,
        price: Double,
        fees: Double,
        ts: Long,
        plOverride: Double? = tx.plOverride
    ) = txDao.update(
        tx.copy(
            side = side.name,
            shares = shares,
            price = price,
            fees = fees,
            ts = ts,
            // An outcome override only means anything on a sell.
            plOverride = if (side == TradeSide.SELL) plOverride else null
        )
    )

    /**
     * Replays the ledger with [edited] in place of its current row (or
     * appended, for id 0) and returns an error message when the edit makes
     * some sell unbacked — null when the edit is sound.
     *
     * The verdict is a DIFFERENCE, not an absolute. The old check refused an
     * edit whenever the whole ledger contained any unbacked sell, so a single
     * gap inherited from an incomplete import (a bank SELL whose BUY the app
     * never saw) rejected every edit to every symbol, naming that symbol in
     * an error about a trade the user was not touching — and locked the user
     * out of repairing the gap itself. Only growth in the gap is refused.
     */
    suspend fun validateEdit(edited: TransactionEntity): String? {
        val before = txDao.getAllOrdered()
        val after = before
            .filter { it.id != edited.id }
            .plus(edited)
            .sortedWith(compareBy({ it.ts }, { it.id }))
        return worsenedGap(before, after, "This edit")
    }

    /**
     * Shares of [symbol] the ledger sells without a buy to back them — 0 for
     * a sound history. The position and realized P/L ignore those shares, so
     * the number is what the holding screen must own up to instead of leaving
     * the user to discover it through a rejected edit.
     */
    suspend fun ledgerGapFor(symbol: String): Double =
        unbackedBySymbol(txDao.getAllOrdered())[symbol.trim().uppercase()] ?: 0.0

    /**
     * Deletes every transaction recorded for [symbol] — removes that position
     * (e.g. a test entry) from the ledger while leaving all other symbols
     * untouched. Returns how many rows were deleted.
     */
    suspend fun removeSymbol(symbol: String): Int =
        txDao.deleteBySymbol(symbol.trim().uppercase())

    /** How many ledger rows exist for [symbol]. */
    suspend fun transactionCount(symbol: String): Int =
        txDao.countForSymbol(symbol.trim().uppercase())

    companion object {

        /** BANK rows matching within ±36h count as the same broker execution. */
        const val DUPLICATE_WINDOW_MS: Long = 36L * 60 * 60 * 1000

        fun computePositions(ordered: List<TransactionEntity>): List<Position> {
            data class Acc(var shares: Double = 0.0, var avg: Double = 0.0, var realized: Double = 0.0)

            val bySymbol = LinkedHashMap<String, Acc>()
            for (tx in ordered) {
                // trim + uppercase must match ReportsEngine and dayPlBySymbol,
                // or a stray-whitespace ledger row splits into two positions.
                val acc = bySymbol.getOrPut(tx.symbol.trim().uppercase()) { Acc() }
                when (tx.side) {
                    TxSide.BUY -> {
                        val newShares = acc.shares + tx.shares
                        if (newShares > 0) {
                            acc.avg = (acc.shares * acc.avg + tx.shares * tx.price + tx.fees) / newShares
                        }
                        acc.shares = newShares
                    }
                    TxSide.SPLIT -> {
                        val ratio = tx.shares
                        if (ratio > 0.0 && acc.shares > 0.0) {
                            acc.shares *= ratio
                            acc.avg /= ratio
                        }
                    }
                    else -> {
                        // Legacy safety net only: the entry forms reject
                        // oversells, so a clamp here means old/imported data.
                        val qty = minOf(tx.shares, acc.shares)
                        // A user-pinned outcome replaces the computed one; the
                        // share count and cost basis still replay identically.
                        acc.realized += tx.plOverride
                            ?: if (qty > 0) qty * (tx.price - acc.avg) - tx.fees else 0.0
                        if (qty > 0) {
                            acc.shares -= qty
                            if (acc.shares < 1e-9) {
                                acc.shares = 0.0
                                acc.avg = 0.0
                            }
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

        /**
         * Replays [ordered] and reports the first sell that exceeds the held
         * quantity, as a user-readable message — null when the ledger is sound.
         */
        fun firstOversell(ordered: List<TransactionEntity>): String? {
            val held = HashMap<String, Double>()
            for (tx in ordered) {
                val sym = tx.symbol.trim().uppercase()
                val cur = held[sym] ?: 0.0
                when (tx.side) {
                    TxSide.BUY -> held[sym] = cur + tx.shares
                    TxSide.SPLIT -> if (tx.shares > 0.0) held[sym] = cur * tx.shares
                    else -> {
                        if (tx.shares > cur + 1e-6) {
                            return "This would sell ${fmtQty(tx.shares)} $sym while only " +
                                "${fmtQty(cur)} are held at that time."
                        }
                        held[sym] = (cur - tx.shares).coerceAtLeast(0.0)
                    }
                }
            }
            return null
        }

        /**
         * Quantity per symbol that [ordered] sells without a buy to back it,
         * replaying with the same clamp the position engine uses. Empty for a
         * sound ledger. This is the measure the edit and delete guards compare
         * before and after a change: what has to be refused is a change that
         * MAKES the history less true, not a change made while some unrelated
         * gap already sits in the book.
         */
        fun unbackedBySymbol(ordered: List<TransactionEntity>): Map<String, Double> {
            val held = HashMap<String, Double>()
            val unbacked = HashMap<String, Double>()
            for (tx in ordered) {
                val sym = tx.symbol.trim().uppercase()
                val cur = held[sym] ?: 0.0
                when (tx.side) {
                    TxSide.BUY -> held[sym] = cur + tx.shares
                    TxSide.SPLIT -> if (tx.shares > 0.0) held[sym] = cur * tx.shares
                    else -> {
                        if (tx.shares > cur) {
                            unbacked[sym] = (unbacked[sym] ?: 0.0) + (tx.shares - cur)
                        }
                        held[sym] = (cur - tx.shares).coerceAtLeast(0.0)
                    }
                }
            }
            return unbacked
        }

        /**
         * The reason to refuse a ledger change, or null to allow it. [subject]
         * names the change ("This edit", "Deleting this trade") so the message
         * says what the user actually did. A change is refused only when it
         * widens some symbol's unbacked quantity — never for a gap that was
         * already there.
         */
        fun worsenedGap(
            before: List<TransactionEntity>,
            after: List<TransactionEntity>,
            subject: String = "This change"
        ): String? {
            val was = unbackedBySymbol(before)
            val now = unbackedBySymbol(after)
            for ((sym, qty) in now) {
                val prior = was[sym] ?: 0.0
                if (qty <= prior + 1e-6) continue
                // The fact only — each caller adds the advice that fits the
                // action it was about to take.
                return if (prior > 1e-6) {
                    "$subject would widen $sym's ledger gap from ${fmtQty(prior)} to " +
                        "${fmtQty(qty)} shares sold with no buy behind them."
                } else {
                    "$subject would leave ${fmtQty(qty)} $sym sold with no buy to back " +
                        "them at that moment."
                }
            }
            return null
        }

        private fun fmtQty(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.US, "%.4f", v)

        fun toView(position: Position, quote: Quote?, dayPlOverride: Double? = null): PositionView {
            // No quote -> no honest market value. The position is carried at
            // cost, and the flag below makes the UI say so instead of showing
            // a plausible-looking price with zero movement.
            val priced = quote != null
            val price = quote?.price ?: position.avgCost
            val marketValue = position.shares * price
            val unrealized = if (priced) position.shares * (price - position.avgCost) else 0.0
            val unrealizedPct =
                if (priced && position.investedCost > 1e-9) unrealized / position.investedCost * 100.0 else 0.0
            val dayPl = dayPlOverride
                ?: if (quote != null) position.shares * quote.dayChangeAbs else 0.0
            return PositionView(
                position = position,
                quote = quote,
                marketValue = marketValue,
                unrealizedPl = unrealized,
                unrealizedPlPct = unrealizedPct,
                dayPl = dayPl,
                priced = priced
            )
        }

        /**
         * Accurate "today" P/L per symbol. Shares held from before today move
         * against the previous close; shares BOUGHT today move only from their
         * own buy price (fees folded in); shares SOLD today lock in their move
         * at the sell price (sell fees deducted). Without this, a position
         * opened mid-session is credited with the whole day's move — including
         * the part that happened before it was owned.
         */
        fun dayPlBySymbol(
            ordered: List<TransactionEntity>,
            quotes: Map<String, Quote>,
            dayStartTs: Long
        ): Map<String, Double> {
            val heldStart = computePositions(ordered.filter { it.ts < dayStartTs })
                .associate { it.symbol to it.shares }
            val todayBySymbol = ordered.filter { it.ts >= dayStartTs }
                .groupBy { it.symbol.trim().uppercase() }

            val out = HashMap<String, Double>()
            for (sym in heldStart.keys + todayBySymbol.keys) {
                val quote = quotes[sym]
                var fromYesterday = (heldStart[sym] ?: 0.0).coerceAtLeast(0.0)
                var pl = 0.0
                // FIFO lots bought today: [remaining qty, cost per share incl. fees]
                val lots = ArrayDeque<DoubleArray>()
                for (tx in todayBySymbol[sym].orEmpty()) {
                    when (tx.side) {
                        TxSide.BUY -> {
                            if (tx.shares > 0.0) {
                                lots.addLast(doubleArrayOf(tx.shares, tx.price + tx.fees / tx.shares))
                            }
                        }
                        TxSide.SPLIT -> {
                            // A same-day split scales overnight shares; Yahoo's
                            // prevClose is already split-adjusted, so no P/L moves.
                            if (tx.shares > 0.0) fromYesterday *= tx.shares
                        }
                        else -> {
                            var qty = tx.shares
                            val soldOvernight = minOf(qty, fromYesterday)
                            if (soldOvernight > 0.0 && quote != null) {
                                pl += soldOvernight * (tx.price - quote.prevClose)
                            }
                            fromYesterday -= soldOvernight
                            qty -= soldOvernight
                            while (qty > 1e-9 && lots.isNotEmpty()) {
                                val lot = lots.first()
                                val q = minOf(qty, lot[0])
                                pl += q * (tx.price - lot[1])
                                lot[0] -= q
                                qty -= q
                                if (lot[0] <= 1e-9) lots.removeFirst()
                            }
                            pl -= tx.fees
                        }
                    }
                }
                // Still-open remainders marked to the live price.
                if (quote != null) {
                    if (fromYesterday > 1e-9) pl += fromYesterday * quote.dayChangeAbs
                    for (lot in lots) pl += lot[0] * (quote.price - lot[1])
                }
                out[sym] = pl
            }
            return out
        }

        /** [allPositions] must include closed positions so realized P/L is complete. */
        fun summarize(
            openViews: List<PositionView>,
            allPositions: List<Position>,
            dayPlOverride: Double? = null
        ): PortfolioSummary {
            val marketValue = openViews.sumOf { it.marketValue }
            val invested = openViews.sumOf { it.position.investedCost }
            val unrealized = openViews.sumOf { it.unrealizedPl }
            val realized = allPositions.sumOf { it.realizedPl }
            val dayPl = dayPlOverride ?: openViews.sumOf { it.dayPl }
            val unpriced = openViews.filter { !it.priced }
            return PortfolioSummary(
                marketValue = marketValue,
                investedCost = invested,
                unrealizedPl = unrealized,
                realizedPl = realized,
                totalPl = unrealized + realized,
                dayPl = dayPl,
                unpricedCount = unpriced.size,
                unpricedCost = unpriced.sumOf { it.position.investedCost }
            )
        }

        fun isOpen(p: Position): Boolean = abs(p.shares) > 1e-9
    }
}
