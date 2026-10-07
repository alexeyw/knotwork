package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 18 to 19 — pipeline binding to chat.
 *
 * Adds the nullable `pipelineId` column to `chat_sessions`. `NULL` means the
 * chat uses the application-wide default pipeline (the user-marked
 * `EntryPointSettings.defaultPipelineId`), preserving the prior
 * default for every existing row without requiring a data backfill.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `pipelineId` TEXT")
    }
}
