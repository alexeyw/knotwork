package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `isFinal` and `isStarred` columns to `chat_messages`.
 *
 * - `isFinal` — distinguishes user-facing messages (USER input, final AGENT
 *   answers) from intermediate node outputs (tool observations, internal
 *   SYSTEM logs). Backfilled to `1` so every pre-existing message keeps
 *   rendering in the main chat list.
 * - `isStarred` — backs the new "save message" action; defaults to `0`
 *   for all legacy rows.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `chat_messages` ADD COLUMN `isFinal` INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL(
            "ALTER TABLE `chat_messages` ADD COLUMN `isStarred` INTEGER NOT NULL DEFAULT 0",
        )
    }
}
