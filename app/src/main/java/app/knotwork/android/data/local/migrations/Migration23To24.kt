package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 23 to 24 — pipeline presets.
 *
 * Adds the `pipeline_presets` table backing the user-saved
 * preset catalogue. Bundled presets live in
 * `assets/presets/pipelines` and never reach this table.
 *
 * The graph is stored as a JSON blob produced by
 * `PipelinePresetJsonSerializer.serialize(...)` so preset rows
 * are self-contained and the existing pipeline schema can evolve
 * independently of stored presets.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pipeline_presets` (
                `id` TEXT PRIMARY KEY NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `categoryKey` TEXT NOT NULL,
                `graphJson` TEXT NOT NULL,
                `tagsCsv` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
