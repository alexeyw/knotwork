package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v49 → v50: adds the non-null `samplePrompts` column to `pipelines`,
 * storing the pipeline's starter ("quick action") suggestions shown on
 * the new-chat empty state as a JSON array string. Purely additive
 * `ALTER TABLE … ADD COLUMN`; existing rows default to `'[]'` (no
 * suggestions), which the `Converters` type converter reads as an empty
 * list.
 */
val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipelines` ADD COLUMN `samplePrompts` TEXT NOT NULL DEFAULT '[]'")
    }
}
