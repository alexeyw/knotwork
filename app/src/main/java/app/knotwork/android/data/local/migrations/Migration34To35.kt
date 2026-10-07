package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 34 to 35 — nested-pipeline run tree and trace
 * nesting level.
 *
 * Purely additive `ALTER TABLE … ADD COLUMN` statements; no existing
 * rows are rewritten:
 *
 * - `pipeline_runs.parentRunId` — the parent run of a sub-pipeline run
 *   spawned by a `PIPELINE` node (`NULL` for top-level runs, including
 *   every legacy row). Declared as a self-referential foreign key with
 *   `ON DELETE CASCADE` so retention of a root run removes its whole
 *   sub-tree; indexed for the child-run lookups that rebuild the tree.
 *   SQLite permits adding a column with a `REFERENCES` clause in place
 *   precisely because the default value is `NULL`, so no table rebuild
 *   (and no risky drop of the `trace_steps` foreign-key parent) is
 *   needed.
 * - `trace_steps.depth` — pipeline-nesting level of the run that
 *   produced the record (`0` top-level, `1` a direct sub-pipeline, …),
 *   so the console can render a sub-pipeline's logs/vars/trace spans
 *   nested under the spawning `PIPELINE` node. Backfilled to `0` for
 *   every existing row, matching the entity column default.
 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `pipeline_runs` ADD COLUMN `parentRunId` TEXT " +
                "REFERENCES `pipeline_runs`(`id`) ON DELETE CASCADE",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pipeline_runs_parentRunId` " +
                "ON `pipeline_runs` (`parentRunId`)",
        )
        db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `depth` INTEGER NOT NULL DEFAULT 0")
    }
}
