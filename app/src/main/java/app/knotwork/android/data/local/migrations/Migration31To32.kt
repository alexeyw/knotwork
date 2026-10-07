package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 31 to 32 — checkpoint/resume support.
 *
 * Purely additive `ALTER TABLE … ADD COLUMN` statements; no existing
 * rows are rewritten:
 *
 * - `trace_steps.conditionResult` / `routingKey` / `resolvedToolName`
 *   — the recorded routing verdicts and tool attribution of `NODE_IO`
 *   rows, needed to restore IF_CONDITION / INTENT_ROUTER / EVALUATION
 *   branches and tool observations when an interrupted run is replayed
 *   from its persisted trace (`NULL` for legacy rows: resume of runs
 *   interrupted under the previous schema falls back gracefully —
 *   branch restoration just lacks the recorded verdicts, exactly as if
 *   the nodes had never recorded them).
 * - `pipeline_runs.userPrompt` — the user message that started the
 *   run, captured at enqueue time; resume feeds it back to the engine
 *   as the immutable original prompt. `NULL` for legacy rows, which
 *   are therefore not resumable.
 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `conditionResult` INTEGER")
        db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `routingKey` TEXT")
        db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `resolvedToolName` TEXT")
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `userPrompt` TEXT")
    }
}
