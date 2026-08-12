package com.aurum.invest.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val side: String,           // BUY / SELL
    val shares: Double,
    val price: Double,          // per share, USD
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
    val plOverride: Double? = null
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
