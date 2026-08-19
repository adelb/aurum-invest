package com.aurum.invest.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val side: String,           // BUY / SELL / SPLIT (SPLIT: shares = ratio, price = 0)
    val shares: Double,
    val price: Double,          // per share, always in USD after any conversion
    val fees: Double = 0.0,
    val ts: Long,               // epoch millis
    val source: String = "MANUAL",  // MANUAL / BANK
    val note: String = "",
    /**
     * User-corrected realized outcome for a SELL, in dollars (signed).
     * Null means the outcome is computed from the ledger as usual
     * (qty * (price - avg cost) - fees). Set when the broker's real number
     * differs from the replay — wrong basis history, transfer fees, and so
     * on — and honored by positions, summaries, and reports alike.
     */
    val plOverride: Double? = null,
    /**
     * The currency the trade was originally denominated in. [price] is always
     * stored in USD; when [currency] is not USD, [fxRate] records the
     * original-currency→USD rate that produced it, so the conversion is
     * auditable instead of silently assumed.
     */
    val currency: String = "USD",
    val fxRate: Double = 1.0,
    /**
     * The broker's own transaction reference for this execution (BANK rows),
     * parsed from the notification. It is the identity of the OPERATION: two
     * trades with different refs are two different operations even when the
     * symbol, side, size, and price all match — a same-size rebuy at the same
     * level must never be swallowed as a "duplicate". Null for manual rows
     * and for bank alerts that carried no reference.
     */
    val ref: String? = null
)

/** SPLIT ledger rows store the ratio in [TransactionEntity.shares] (e.g. 4.0 for a 4-for-1). */
object TxSide {
    const val BUY = "BUY"
    const val SELL = "SELL"
    const val SPLIT = "SPLIT"
}

/**
 * A cash-account event. [amount] is always positive USD; [type] decides the
 * direction. Cash tracking is optional — until the first DEPOSIT/WITHDRAW row
 * exists the app treats the cash balance as "not tracked" and never shows a
 * fabricated number.
 */
@Entity(tableName = "cash_events")
data class CashEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,           // DEPOSIT / WITHDRAW / DIVIDEND / INTEREST / FEE
    val symbol: String = "",    // the paying symbol for DIVIDEND; "" otherwise
    val amount: Double,         // positive USD
    val currency: String = "USD",
    val fxRate: Double = 1.0,
    val ts: Long,
    val note: String = ""
)

object CashType {
    const val DEPOSIT = "DEPOSIT"
    const val WITHDRAW = "WITHDRAW"
    const val DIVIDEND = "DIVIDEND"
    const val INTEREST = "INTEREST"
    const val FEE = "FEE"
    val ALL = listOf(DEPOSIT, WITHDRAW, DIVIDEND, INTEREST, FEE)
}

/** A user-defined price alert. Fires once, then keeps [triggeredAt] as history. */
@Entity(tableName = "price_alerts")
data class PriceAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val direction: String,      // ABOVE / BELOW
    val threshold: Double,
    val note: String = "",      // e.g. "target" / "stop"
    val active: Boolean = true,
    val createdAt: Long,
    val triggeredAt: Long? = null,
    val priceAtTrigger: Double? = null
)

object AlertDirection {
    const val ABOVE = "ABOVE"
    const val BELOW = "BELOW"
}

/**
 * Immutable record of a recommendation the app showed, so its outcome can be
 * reviewed later. One row per (engine, symbol, day) — re-renders of the same
 * advice on the same day do not duplicate.
 */
@Entity(tableName = "advice_log")
data class AdviceLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val day: String,            // ISO local date, part of the dedup key
    val engine: String,         // WEALTH / PICKS / NEXT_SESSION / ...
    val symbol: String,
    val action: String,         // BUY / SELL / HOLD / TRIM / ...
    val priceAt: Double,        // price when the advice was produced (0 = unknown)
    val score: Int,             // engine score, -1 = none
    val modelVersion: String,   // app versionName when emitted
    val detail: String = ""     // one-line reason snapshot
)

@Entity(tableName = "watchlist")
data class WatchItemEntity(
    @PrimaryKey val symbol: String,
    val name: String = "",
    val pinned: Boolean = false,
    val addedAt: Long
)

/** Generic JSON cache. Keys: "quote:AAPL", "candles:AAPL:120", "intraday:AAPL", "news:AAPL". */
@Entity(tableName = "cache")
data class CacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long
)

@Entity(tableName = "bank_events")
data class BankEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val status: String = "PENDING",   // PENDING / IMPORTED / DISMISSED
    val parsedJson: String? = null
)

@Entity(tableName = "weekly_picks")
data class WeeklyPickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: String,   // ISO Monday date "2026-08-03"
    val rank: Int,
    val symbol: String,
    val name: String,
    val score: Double,
    val reason: String,
    val priceAtPick: Double
)
