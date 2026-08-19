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
        EngineCallEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AurumDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun watchDao(): WatchDao
    abstract fun cacheDao(): CacheDao
    abstract fun bankEventDao(): BankEventDao
    abstract fun picksDao(): PicksDao
    abstract fun engineCallDao(): EngineCallDao

    companion object {
        /** v2: transactions gain the nullable realized-outcome override. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN plOverride REAL")
            }
        }

        /**
         * v5 is the v10 rebuild's schema — the same five tables the v3.8 line
         * always had. Versions 3 and 4 belong to the v6–v9 releases, whose
         * `transactions` grew `currency`, `fxRate`, and `ref` columns (price
         * was ALWAYS stored in USD, so dropping the audit columns loses no
         * money math), and whose extra tables (cash_events, price_alerts,
         * advice_log) belong to features this build does not carry.
         *
         * The ledger itself is sacred: every row — including SPLIT rows the
         * v6+ releases could record — is copied through untouched. The extra
         * tables are LEFT IN PLACE, dormant: Room ignores tables it does not
         * manage, and a future version can resurrect their data. Nothing is
         * destroyed on any path.
         *
         * Without these, updating over a v9 install crashed on open:
         * Room cannot downgrade a version-4 file to a version-2 schema.
         */
        private fun rebuildTransactionsToV10(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `transactions_v10` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`symbol` TEXT NOT NULL, `side` TEXT NOT NULL, " +
                    "`shares` REAL NOT NULL, `price` REAL NOT NULL, " +
                    "`fees` REAL NOT NULL, `ts` INTEGER NOT NULL, " +
                    "`source` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                    "`plOverride` REAL)"
            )
            db.execSQL(
                "INSERT INTO `transactions_v10` " +
                    "(`id`, `symbol`, `side`, `shares`, `price`, `fees`, `ts`, " +
                    "`source`, `note`, `plOverride`) " +
                    "SELECT `id`, `symbol`, `side`, `shares`, `price`, `fees`, `ts`, " +
                    "`source`, `note`, `plOverride` FROM `transactions`"
            )
            db.execSQL("DROP TABLE `transactions`")
            db.execSQL("ALTER TABLE `transactions_v10` RENAME TO `transactions`")
        }

        // The ten copied columns exist identically in schemas 2, 3, AND 4, and
        // the four side tables never changed shape across them — one rebuild
        // body honestly serves every upgrade path. Room composes 2→5→6 etc.
        private val MIGRATION_2_5 = object : Migration(2, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = rebuildTransactionsToV10(db)
        }
        private val MIGRATION_3_5 = object : Migration(3, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = rebuildTransactionsToV10(db)
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = rebuildTransactionsToV10(db)
        }

        /**
         * v6: the engines' self-scoring call ledger. A NEW table (engine_calls,
         * deliberately not v9's dormant advice_log, whose shape differs) — no
         * existing data is touched on any path.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `engine_calls` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                        "`symbol` TEXT NOT NULL, `refPrice` REAL NOT NULL, " +
                        "`note` TEXT NOT NULL, `fwd5Pct` REAL, `fwd20Pct` REAL)"
                )
            }
        }

        // No destructive fallback: the user's ledger must survive every app
        // update — upgrades AND the v9→v10 rebuild alike. Any future schema
        // change MUST ship an explicit Migration.
        fun build(context: Context): AurumDatabase =
            Room.databaseBuilder(context, AurumDatabase::class.java, "aurum.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_5, MIGRATION_3_5, MIGRATION_4_5, MIGRATION_5_6
                )
                .build()
    }
}
