package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 26 to 27.
 *
 * Adds three additive columns to `memory_chunks` for the redesigned
 * Memory surface: `tagsCsv` (comma-separated tag list, default empty),
 * `useCount` (retrieval counter, default `0`) and `lastUsedAt`
 * (nullable epoch-millis of the most recent retrieval). All defaults
 * match the entity column defaults so the Room-generated schema and
 * this migration agree; existing rows keep their data untouched.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memory_chunks` ADD COLUMN `tagsCsv` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `memory_chunks` ADD COLUMN `useCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_chunks` ADD COLUMN `lastUsedAt` INTEGER")
    }
}
