package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 14 to 15.
 * Adds `trace_steps` table.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `trace_steps` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `nodeName` TEXT NOT NULL,
                `outputText` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trace_steps_sessionId` ON `trace_steps` (`sessionId`)")
    }
}
