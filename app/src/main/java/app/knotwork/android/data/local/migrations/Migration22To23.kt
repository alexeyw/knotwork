package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 22 to 23 — memory chunk pinning.
 *
 * Adds the `isPinned` column to `memory_chunks` so users can mark a
 * memory chunk as pinned. Pinned rows sort ahead of unpinned rows on
 * the memory surface and are exempt from future `compactMemory()`
 * passes. Backfilled to `0` for every existing row.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `memory_chunks` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0",
        )
    }
}
