package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 32 to 33 — persistent background HITL.
 *
 * Adds the `pending_interactions` table holding the parked HITL
 * interaction of a run whose live in-process waiting phase timed out
 * (see `PendingInteractionEntity`). One row per run (`runId` primary
 * key); indexed by `sessionId` for the chat reattach lookup.
 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_interactions` (" +
                "`runId` TEXT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`toolName` TEXT, " +
                "`toolArgs` TEXT, " +
                "`risk` TEXT, " +
                "`question` TEXT, " +
                "`optionsJson` TEXT, " +
                "`decision` TEXT, " +
                "`answer` TEXT, " +
                "`requestedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`runId`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_interactions_sessionId` " +
                "ON `pending_interactions` (`sessionId`)",
        )
    }
}
