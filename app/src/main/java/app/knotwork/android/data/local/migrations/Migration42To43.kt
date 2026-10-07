package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds an index on `chat_messages.sessionId`, the filter column of every
 * hot chat query (loading a chat, deleting a session's messages,
 * collecting its attachment paths). Additive — index-only, no row is
 * touched; the name matches Room's generated `index_<table>_<column>`.
 */
val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` " +
                "ON `chat_messages` (`sessionId`)",
        )
    }
}
