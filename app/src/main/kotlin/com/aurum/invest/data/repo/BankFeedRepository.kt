package com.aurum.invest.data.repo

import com.aurum.invest.bank.TradeParser
import com.aurum.invest.data.db.BankEventDao
import com.aurum.invest.data.db.BankEventEntity
import com.aurum.invest.data.model.BankEvent
import com.aurum.invest.data.model.TradeSide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Store of captured bank notifications and the bridge into the ledger.
 * Notifications are parsed ONCE at record time; reads only deserialize
 * the stored parse result. Nothing here throws to callers.
 */
class BankFeedRepository(
    private val bankDao: BankEventDao,
    private val portfolio: PortfolioRepository
) {

    fun observeEvents(): Flow<List<BankEvent>> =
        bankDao.observeAll().map { entities -> entities.map { it.toModel() } }

    fun observePendingCount(): Flow<Int> = bankDao.observePendingCount()

    /**
     * Records a captured notification. Exact duplicates seen within the last
     * 24 hours are skipped (banks re-post the same alert on reconnect and
     * device restart, not just within a minute). Returns the new event id,
     * or -1 when deduped (or when storage fails).
     */
    suspend fun recordNotification(pkg: String, title: String, text: String, postedAt: Long): Long {
        return try {
            val since = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            if (bankDao.countRecentDuplicates(pkg, title, text, since) > 0) return -1L
            val parsed = TradeParser.parse(title, text)
            bankDao.insert(
                BankEventEntity(
                    pkg = pkg,
                    title = title,
                    text = text,
                    postedAt = postedAt,
                    status = BankEvent.STATUS_PENDING,
                    parsedJson = parsed?.let { TradeParser.toJson(it) }
                )
            )
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Imports the event as a BANK-source transaction and marks it IMPORTED.
     * [price] must already be in USD; when the alert was denominated in
     * another currency, [currency]/[fxRate] record the conversion that
     * produced it.
     *
     * Every operation is unique. Duplicate detection is layered by identity
     * strength, never by the stock alone:
     *  1. an event already IMPORTED never writes again (double-tap safe);
     *  2. when the alert carries a broker reference, THAT is the operation's
     *     identity — only a ledger row with the same (symbol, ref) is a
     *     duplicate. A same-size rebuy at the same price is a different ref,
     *     so it imports like any other operation;
     *  3. only a ref-less alert falls back to the shape heuristic (same
     *     symbol/side/shares/price within ±36h), the last net against a
     *     broker re-sending the same alert text.
     *
     * Returns true when a ledger row was written, false otherwise.
     */
    suspend fun importEvent(
        eventId: Long,
        symbol: String,
        side: TradeSide,
        shares: Double,
        price: Double,
        currency: String = "USD",
        fxRate: Double = 1.0
    ): Boolean {
        return try {
            val event = bankDao.get(eventId) ?: return false
            if (event.status == BankEvent.STATUS_IMPORTED) return false
            val ref = event.parsedJson?.let { TradeParser.fromJson(it) }?.ref?.trim()
                ?.takeIf { it.isNotEmpty() }
            val duplicate =
                if (ref != null) {
                    portfolio.bankRefExists(symbol, ref)
                } else {
                    portfolio.bankDuplicateExists(symbol, side, shares, price, event.postedAt)
                }
            if (duplicate) {
                bankDao.setStatus(eventId, BankEvent.STATUS_IMPORTED)
                return false
            }
            portfolio.addTransaction(
                symbol = symbol,
                side = side,
                shares = shares,
                price = price,
                ts = event.postedAt,
                source = "BANK",
                currency = currency,
                fxRate = fxRate,
                ref = ref
            )
            bankDao.setStatus(eventId, BankEvent.STATUS_IMPORTED)
            true
        } catch (_: Exception) {
            // never throw to callers
            false
        }
    }

    suspend fun dismissEvent(eventId: Long) {
        try {
            bankDao.setStatus(eventId, BankEvent.STATUS_DISMISSED)
        } catch (_: Exception) {
            // never throw to callers
        }
    }

    /** Retention: raw notification text is sensitive — old captures are deleted. */
    suspend fun purgeOlderThan(days: Int): Int = try {
        bankDao.purgeOlderThan(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)
    } catch (_: Exception) {
        0
    }

    /** Deletes every captured notification (imported ledger rows stay). */
    suspend fun deleteAllCaptures(): Int = try {
        bankDao.deleteAll()
    } catch (_: Exception) {
        0
    }

    private fun BankEventEntity.toModel(): BankEvent = BankEvent(
        id = id,
        pkg = pkg,
        title = title,
        text = text,
        postedAt = postedAt,
        status = status,
        parsed = parsedJson?.let { TradeParser.fromJson(it) }
    )
}
