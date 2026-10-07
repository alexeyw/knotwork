package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 24 to 25 — prompt presets.
 *
 * Adds the `prompt_presets` table backing the user-saved
 * prompt-preset catalogue. Bundled presets live in
 * `assets/presets/prompts` and never reach this table.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `prompt_presets` (
                `id` TEXT PRIMARY KEY NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `nodeTypeKey` TEXT NOT NULL,
                `systemPrompt` TEXT NOT NULL,
                `tagsCsv` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
