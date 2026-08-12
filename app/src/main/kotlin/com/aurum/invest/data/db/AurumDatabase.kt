package com.aurum.invest.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TransactionEntity::class,
        WatchItemEntity::class,
        CacheEntity::class,
        BankEventEntity::class,
        WeeklyPickEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AurumDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun watchDao(): WatchDao
    abstract fun cacheDao(): CacheDao
    abstract fun bankEventDao(): BankEventDao
    abstract fun picksDao(): PicksDao

    companion object {
        /** v2: transactions gain the nullable realized-outcome override. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN plOverride REAL")
            }
        }

        // No destructive fallback: the user's ledger must survive every app
        // update. Any future schema change MUST ship an explicit Migration.
        fun build(context: Context): AurumDatabase =
            Room.databaseBuilder(context, AurumDatabase::class.java, "aurum.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
