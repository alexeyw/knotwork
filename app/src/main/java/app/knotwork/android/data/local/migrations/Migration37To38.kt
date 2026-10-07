package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `chat_history_summaries` table backing long-session chat
 * compression. One row per session (PK = `sessionId`), with a
 * `ON DELETE CASCADE` foreign key onto `chat_sessions(id)` so a summary
 * is cleaned up with its conversation. Additive — existing sessions
 * start with no summary (the engine renders the full history until the
 * background compressor produces one).
 */
val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_history_summaries` (
                `sessionId` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `coveredMessageCount` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`sessionId`),
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}
