package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 21 to 22 — chat-session favorites.
 *
 * Adds the `isStarred` column to `chat_sessions` so the drawer can
 * surface favorited chats at the top of the list. Backfilled to `0`
 * for every existing row.
 *
 * Distinct from the message-level `isStarred` introduced in
 * `MIGRATION_19_20` on `chat_messages`.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `chat_sessions` ADD COLUMN `isStarred` INTEGER NOT NULL DEFAULT 0",
        )
    }
}
