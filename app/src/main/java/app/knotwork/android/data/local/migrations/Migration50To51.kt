package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `trigger_evaluations` table backing the trigger-evaluation
 * journal — one row per evaluated *(trigger × moment)* recording the
 * verdict (fire / re-arm / typed skip) and, for a fire, the eventual fate
 * of the enqueued run. Additive — no existing rows are touched.
 *
 * The sealed verdict / run-outcome are flattened into string
 * discriminators (`verdictKind`, `skipReason`, `outcomeKind`,
 * `outcomeError`) rather than a JSON blob so the "by trigger, by time"
 * read and the run-outcome update stay plain indexed SQL. Two indexes are
 * created: `(triggerId, evaluatedAt)` backs the newest-first per-trigger
 * journal query, and `runId` backs the outcome-attribution update.
 *
 * No foreign key on `triggerId`: the journal is a diagnostic observer that
 * deliberately survives its trigger's deletion (its growth is bounded by
 * the retention pass, not a cascade), mirroring the FK-free convention of
 * `pipeline_runs` / `pending_interactions`. The table lives in the
 * SQLCipher-encrypted database and nothing it holds ever leaves the device.
 */
val MIGRATION_50_51 = object : Migration(50, 51) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `trigger_evaluations` (
                `id` TEXT NOT NULL,
                `triggerId` TEXT NOT NULL,
                `evaluatedAt` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `verdictKind` TEXT NOT NULL,
                `skipReason` TEXT,
                `runId` TEXT,
                `outcomeKind` TEXT,
                `outcomeError` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_trigger_evaluations_triggerId_evaluatedAt` " +
                "ON `trigger_evaluations` (`triggerId`, `evaluatedAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_trigger_evaluations_runId` " +
                "ON `trigger_evaluations` (`runId`)",
        )
    }
}
