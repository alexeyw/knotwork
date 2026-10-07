package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 30 to 31 — persistent run trace.
 *
 * Extends `trace_steps` from per-session node outputs into the full
 * per-run trace (see `TraceStepEntity`): `runId` (FK to `pipeline_runs`
 * with CASCADE delete, indexed), `seq` (monotonic in-run position used
 * by the console replay/live seam), `recordKind` (`NODE_IO` vs
 * `CONSOLE_EVENT` discriminator), `consoleEventType`, `nodeId` and
 * `inputText`.
 *
 * SQLite cannot add a foreign key to an existing table, so the
 * migration recreates it: new table, `INSERT … SELECT` copy, drop,
 * rename, then index recreation. Legacy rows are preserved with
 * `runId = NULL` (no run attribution existed before this version),
 * `seq = 0` and `recordKind = 'NODE_IO'` — every pre-31 row is a node
 * output by construction.
 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `trace_steps_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `nodeName` TEXT NOT NULL,
                `outputText` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `tokenCount` INTEGER,
                `runId` TEXT,
                `seq` INTEGER NOT NULL,
                `recordKind` TEXT NOT NULL,
                `consoleEventType` TEXT,
                `nodeId` TEXT,
                `inputText` TEXT,
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`runId`) REFERENCES `pipeline_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `trace_steps_new` (
                `id`, `sessionId`, `nodeName`, `outputText`, `timestamp`,
                `durationMs`, `tokenCount`, `runId`, `seq`, `recordKind`,
                `consoleEventType`, `nodeId`, `inputText`
            )
            SELECT
                `id`, `sessionId`, `nodeName`, `outputText`, `timestamp`,
                `durationMs`, `tokenCount`, NULL, 0, 'NODE_IO',
                NULL, NULL, NULL
            FROM `trace_steps`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `trace_steps`")
        db.execSQL("ALTER TABLE `trace_steps_new` RENAME TO `trace_steps`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trace_steps_sessionId` ON `trace_steps` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trace_steps_runId` ON `trace_steps` (`runId`)")
    }
}
