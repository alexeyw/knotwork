package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 16 to 17.
 * Adds `clarificationTimeoutMs` column to `pipeline_nodes` for the new
 * CLARIFICATION node type, which suspends the pipeline until the user replies
 * (or the configured timeout elapses).
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `clarificationTimeoutMs` INTEGER")
    }
}
