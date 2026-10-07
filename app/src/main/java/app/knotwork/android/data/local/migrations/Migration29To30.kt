package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 29 to 30.
 * Adds the `pipeline_runs` table — the persistent record of pipeline
 * runs that survives process death (see `PipelineRunEntity`). Purely
 * additive: no existing rows are touched. `sessionId` deliberately
 * carries no foreign key (a run may be created before its session row
 * exists — scheduler-originated sessions are materialised on first
 * message save); the index pair backs the per-session queries and the
 * status-driven orphan sweep.
 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pipeline_runs` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `pipelineId` TEXT,
                `origin` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `currentNodeId` TEXT,
                `startedAt` INTEGER NOT NULL,
                `finishedAt` INTEGER,
                `errorMessage` TEXT,
                `graphContentHash` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pipeline_runs_sessionId` " +
                "ON `pipeline_runs` (`sessionId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pipeline_runs_status` " +
                "ON `pipeline_runs` (`status`)",
        )
    }
}
