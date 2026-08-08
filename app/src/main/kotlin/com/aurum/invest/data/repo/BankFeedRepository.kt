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
     * 60 seconds are skipped. Returns the new event id, or -1 when deduped
     * (or when storage fails).
     */
    suspend fun recordNotification(pkg: String, title: String, text: String, postedAt: Long): Long {
        return try {
            val since = System.currentTimeMillis() - 60_000L
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

    /** Imports the event as a BANK-source transaction and marks it IMPORTED. */
    suspend fun importEvent(eventId: Long, symbol: String, side: TradeSide, shares: Double, price: Double) {
        try {
            val event = bankDao.get(eventId) ?: return
            portfolio.addTransaction(
                symbol = symbol,
                side = side,
                shares = shares,
                price = price,
                ts = event.postedAt,
                source = "BANK"
            )
            bankDao.setStatus(eventId, BankEvent.STATUS_IMPORTED)
        } catch (_: Exception) {
            // never throw to callers
        }
    }

    suspend fun dismissEvent(eventId: Long) {
        try {
            bankDao.setStatus(eventId, BankEvent.STATUS_DISMISSED)
        } catch (_: Exception) {
            // never throw to callers
        }
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
