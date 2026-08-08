package com.aurum.invest.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransactionEntity::class,
        WatchItemEntity::class,
        CacheEntity::class,
        BankEventEntity::class,
        WeeklyPickEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AurumDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun watchDao(): WatchDao
    abstract fun cacheDao(): CacheDao
    abstract fun bankEventDao(): BankEventDao
    abstract fun picksDao(): PicksDao

    companion object {
        // No destructive fallback: the user's ledger must survive every app
        // update. Any future schema change MUST ship an explicit Migration.
        fun build(context: Context): AurumDatabase =
            Room.databaseBuilder(context, AurumDatabase::class.java, "aurum.db")
                .build()
    }
}
