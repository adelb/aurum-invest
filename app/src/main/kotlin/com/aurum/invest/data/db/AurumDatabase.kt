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
        WeeklyPickEntity::class,
        CashEventEntity::class,
        PriceAlertEntity::class,
        AdviceLogEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AurumDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun watchDao(): WatchDao
    abstract fun cacheDao(): CacheDao
    abstract fun bankEventDao(): BankEventDao
    abstract fun picksDao(): PicksDao
    abstract fun cashEventDao(): CashEventDao
    abstract fun priceAlertDao(): PriceAlertDao
    abstract fun adviceLogDao(): AdviceLogDao

    companion object {
        /** v2: transactions gain the nullable realized-outcome override. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN plOverride REAL")
            }
        }

        /**
         * v3: transactions gain currency/fxRate provenance; new tables for the
         * cash ledger, user price alerts, and the recommendation audit log.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN currency TEXT NOT NULL DEFAULT 'USD'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN fxRate REAL NOT NULL DEFAULT 1.0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cash_events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "symbol TEXT NOT NULL DEFAULT '', " +
                        "amount REAL NOT NULL, " +
                        "currency TEXT NOT NULL DEFAULT 'USD', " +
                        "fxRate REAL NOT NULL DEFAULT 1.0, " +
                        "ts INTEGER NOT NULL, " +
                        "note TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS price_alerts (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "symbol TEXT NOT NULL, " +
                        "direction TEXT NOT NULL, " +
                        "threshold REAL NOT NULL, " +
                        "note TEXT NOT NULL DEFAULT '', " +
                        "active INTEGER NOT NULL DEFAULT 1, " +
                        "createdAt INTEGER NOT NULL, " +
                        "triggeredAt INTEGER, " +
                        "priceAtTrigger REAL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS advice_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "ts INTEGER NOT NULL, " +
                        "day TEXT NOT NULL, " +
                        "engine TEXT NOT NULL, " +
                        "symbol TEXT NOT NULL, " +
                        "action TEXT NOT NULL, " +
                        "priceAt REAL NOT NULL DEFAULT 0.0, " +
                        "score INTEGER NOT NULL DEFAULT -1, " +
                        "modelVersion TEXT NOT NULL DEFAULT '', " +
                        "detail TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        // No destructive fallback: the user's ledger must survive every app
        // update. Any future schema change MUST ship an explicit Migration.
        fun build(context: Context): AurumDatabase =
            Room.databaseBuilder(context, AurumDatabase::class.java, "aurum.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
