package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the local usage-telemetry tables backing the privacy-preserving
 * Usage statistics screen. Additive — no existing rows are touched.
 *
 * - `usage_counter` — a generic `(category, counterKey) → count` tally
 *   (terminal root runs per pipeline and per outcome, trigger firings per
 *   kind). The composite primary key matches the entity so atomic UPSERT
 *   increments target a single row.
 * - `usage_active_day` — the set of distinct device-local active days
 *   (one row per ISO `yyyy-MM-dd` day), backing the daily-active count.
 *
 * Both tables live in the SQLCipher-encrypted database and nothing they
 * hold ever leaves the device.
 */
val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `usage_counter` (
                `category` TEXT NOT NULL,
                `counterKey` TEXT NOT NULL,
                `count` INTEGER NOT NULL,
                PRIMARY KEY(`category`, `counterKey`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `usage_active_day` (
                `day` TEXT NOT NULL,
                PRIMARY KEY(`day`)
            )
            """.trimIndent(),
        )
    }
}
