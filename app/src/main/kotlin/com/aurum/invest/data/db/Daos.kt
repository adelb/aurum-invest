package com.aurum.invest.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Delete
    suspend fun delete(tx: TransactionEntity)

    @Query("SELECT * FROM transactions ORDER BY ts DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

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
