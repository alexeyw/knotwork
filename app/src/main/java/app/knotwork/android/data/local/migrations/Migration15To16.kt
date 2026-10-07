package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 15 to 16.
 * Adds `durationMs` and `tokenCount` columns to `trace_steps` so that per-node
 * execution time and token usage can be persisted alongside the trace output.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `tokenCount` INTEGER")
    }
}
