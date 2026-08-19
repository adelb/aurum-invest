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
}

@Dao
interface EngineCallDao {
    @Insert
    suspend fun insert(call: EngineCallEntity): Long

    /** Only ever fills in the forward outcomes — the call itself is immutable. */
    @Update
    suspend fun update(call: EngineCallEntity)

    @Query("SELECT * FROM engine_calls ORDER BY ts DESC")
    suspend fun all(): List<EngineCallEntity>

    @Query("SELECT * FROM engine_calls WHERE fwd5Pct IS NULL OR fwd20Pct IS NULL ORDER BY ts ASC")
    suspend fun unscored(): List<EngineCallEntity>

    /** Dedupe guard: how many identical calls were already logged since [sinceTs]. */
    @Query("SELECT COUNT(*) FROM engine_calls WHERE kind = :kind AND symbol = :symbol AND ts >= :sinceTs")
    suspend fun countSince(kind: String, symbol: String, sinceTs: Long): Int
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
