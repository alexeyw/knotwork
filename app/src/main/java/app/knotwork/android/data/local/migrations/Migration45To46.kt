package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `sessionId` column to the `triggers` table backing the bound
 * chat session a trigger's background runs land in. Additive — the column
 * is nullable with no default, so existing trigger rows keep `NULL` and
 * lazily bind a session on their next fire.
 *
 * No foreign key: a trigger may outlive (or be reconfigured past) the
 * bound session, which is detected at fire time and replaced rather than
 * cascaded here.
 */
val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `triggers` ADD COLUMN `sessionId` TEXT")
    }
}
