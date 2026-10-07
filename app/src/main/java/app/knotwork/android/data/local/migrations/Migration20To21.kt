package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 20 to 21 — pipeline editor.
 *
 * Adds the nullable `config_json` column to `pipeline_nodes`. The column
 * stores the per-type `NodeConfig` payload edited by the
 * `NodeConfigSheet` (catalog `pipelineeditor.NodeConfig`) as a JSON blob.
 *
 * `NULL` is the canonical "no payload yet" value for every pre-existing
 * row; on first edit the editor derives a default config
 * from the flat columns (`systemPrompt`, `cloudProvider`, `toolName`,
 * `conditionComplexity`, …) and writes the encoded payload here. The
 * flat columns are kept untouched so the orchestrator runtime path
 * (which still reads them) keeps working unchanged.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `config_json` TEXT")
    }
}
