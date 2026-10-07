package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The ceiling pause: a run that spends a ceiling now stops to ask
 * instead of ending, and both halves of that exchange need somewhere to
 * live.
 *
 * On `pipeline_runs`, two per-axis grant counters. Two columns rather
 * than one encoded value because the answer buys a portion **of the axis
 * that bound** — a run waved past its step ceiling has not been granted
 * more tokens. `NOT NULL DEFAULT 0` (matching the `@ColumnInfo` on the
 * entity exactly, or `TableInfo` rejects the schema at open): every
 * existing run has been granted nothing, which is a number.
 *
 * On `pending_interactions`, the question itself — which axis, and the
 * two numbers the card has to state. Nullable, because the other two
 * kinds of park have no ceiling to describe, and because every row
 * written before this migration is one of them.
 */
val MIGRATION_60_61 = object : Migration(60, 61) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `pipeline_runs` ADD COLUMN `stepCeilingExtensions` " +
                "INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `pipeline_runs` ADD COLUMN `tokenCeilingExtensions` " +
                "INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `ceilingAxis` TEXT")
        db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `ceilingLimit` INTEGER")
        db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `ceilingSpent` INTEGER")
    }
}
