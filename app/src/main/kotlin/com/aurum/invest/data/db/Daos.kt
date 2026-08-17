package com.aurum.invest.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Delete
    suspend fun delete(tx: TransactionEntity)

    /** Edits an existing ledger row in place (same id). */
    @Update
    suspend fun update(tx: TransactionEntity)

    @Query("SELECT * FROM transactions ORDER BY ts DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    /** Every ledger row for one symbol, newest first. */
    @Query("SELECT * FROM transactions WHERE symbol = :symbol ORDER BY ts DESC, id DESC")
    fun observeForSymbol(symbol: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY ts ASC, id ASC")
    suspend fun getAllOrdered(): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE symbol = :symbol")
    suspend fun countForSymbol(symbol: String): Int

    /**
     * Guard against double-importing the same broker alert: counts BANK rows
     * that match this trade within a time window. Shares/price use small
     * tolerances so a re-parsed duplicate still matches.
     */
    @Query(
        "SELECT COUNT(*) FROM transactions WHERE source = 'BANK' AND symbol = :symbol " +
            "AND side = :side AND ABS(shares - :shares) < 0.0001 AND ABS(price - :price) < 0.01 " +
            "AND ts BETWEEN :tsFrom AND :tsTo"
    )
    suspend fun countBankDuplicates(
        symbol: String,
        side: String,
        shares: Double,
        price: Double,
        tsFrom: Long,
        tsTo: Long
    ): Int

    /**
     * How many BANK rows already carry this broker reference for this symbol.
     * The reference is the operation's identity — this is the precise
     * duplicate check, with no time window and no shape matching.
     */
    @Query(
        "SELECT COUNT(*) FROM transactions WHERE source = 'BANK' " +
            "AND symbol = :symbol AND ref = :ref"
    )
    suspend fun countBankRef(symbol: String, ref: String): Int
}

@Dao
interface CashEventDao {
    @Insert
    suspend fun insert(e: CashEventEntity): Long

    @Delete
    suspend fun delete(e: CashEventEntity)

    @Query("SELECT * FROM cash_events ORDER BY ts DESC, id DESC")
    fun observeAll(): Flow<List<CashEventEntity>>

    @Query("SELECT * FROM cash_events ORDER BY ts ASC, id ASC")
    suspend fun getAllOrdered(): List<CashEventEntity>

    @Query("SELECT COUNT(*) FROM cash_events")
    suspend fun count(): Int
}

@Dao
interface PriceAlertDao {
    @Insert
    suspend fun insert(a: PriceAlertEntity): Long

    @Delete
    suspend fun delete(a: PriceAlertEntity)

    @Update
    suspend fun update(a: PriceAlertEntity)

    @Query("SELECT * FROM price_alerts ORDER BY active DESC, createdAt DESC")
    fun observeAll(): Flow<List<PriceAlertEntity>>

    @Query("SELECT * FROM price_alerts WHERE symbol = :symbol ORDER BY active DESC, createdAt DESC")
    fun observeForSymbol(symbol: String): Flow<List<PriceAlertEntity>>

    @Query("SELECT * FROM price_alerts WHERE active = 1")
    suspend fun getActive(): List<PriceAlertEntity>
}

@Dao
interface AdviceLogDao {
    @Insert
    suspend fun insert(e: AdviceLogEntity): Long

    @Query("SELECT * FROM advice_log ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AdviceLogEntity>>

    @Query("SELECT * FROM advice_log ORDER BY ts DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AdviceLogEntity>

    /** One row per (engine, symbol, action, day): re-renders must not duplicate. */
    @Query(
        "SELECT COUNT(*) FROM advice_log WHERE engine = :engine AND symbol = :symbol " +
            "AND action = :action AND day = :day"
    )
    suspend fun countForDay(engine: String, symbol: String, action: String, day: String): Int

    @Query("DELETE FROM advice_log WHERE ts < :beforeTs")
    suspend fun purgeOlderThan(beforeTs: Long): Int
}

@Dao
interface WatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WatchItemEntity)

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun remove(symbol: String)

    @Query("SELECT * FROM watchlist ORDER BY pinned DESC, addedAt DESC")
    fun observeAll(): Flow<List<WatchItemEntity>>

    @Query("SELECT * FROM watchlist ORDER BY pinned DESC, addedAt DESC")
    suspend fun getAll(): List<WatchItemEntity>

    @Query("UPDATE watchlist SET pinned = :pinned WHERE symbol = :symbol")
    suspend fun setPinned(symbol: String, pinned: Boolean)

    @Query("SELECT * FROM watchlist WHERE symbol = :symbol")
    suspend fun get(symbol: String): WatchItemEntity?
}

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CacheEntity)

    @Query("SELECT * FROM cache WHERE `key` = :key")
    suspend fun get(key: String): CacheEntity?
}

@Dao
interface BankEventDao {
    @Insert
    suspend fun insert(e: BankEventEntity): Long

    @Query("SELECT * FROM bank_events ORDER BY postedAt DESC")
    fun observeAll(): Flow<List<BankEventEntity>>

    @Query("SELECT * FROM bank_events WHERE id = :id")
    suspend fun get(id: Long): BankEventEntity?

    @Query("UPDATE bank_events SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("SELECT COUNT(*) FROM bank_events WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM bank_events WHERE pkg = :pkg AND title = :title AND text = :text AND postedAt > :sinceTs")
    suspend fun countRecentDuplicates(pkg: String, title: String, text: String, sinceTs: Long): Int

    /** Retention: raw notification text is sensitive; old captures are deleted, not kept forever. */
    @Query("DELETE FROM bank_events WHERE postedAt < :beforeTs")
    suspend fun purgeOlderThan(beforeTs: Long): Int

    @Query("DELETE FROM bank_events")
    suspend fun deleteAll(): Int
}

@Dao
interface PicksDao {
    @Insert
    suspend fun insertAll(picks: List<WeeklyPickEntity>)

    @Query("DELETE FROM weekly_picks WHERE weekStart = :weekStart")
    suspend fun clearWeek(weekStart: String)

    @Query("SELECT * FROM weekly_picks WHERE weekStart = :weekStart ORDER BY rank ASC")
    suspend fun getWeek(weekStart: String): List<WeeklyPickEntity>

    @Query("SELECT * FROM weekly_picks WHERE weekStart = :weekStart ORDER BY rank ASC")
    fun observeWeek(weekStart: String): Flow<List<WeeklyPickEntity>>
}
