package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v47 → v48: adds the nullable `conditionHasImage` flag to `pipeline_nodes`,
 * backing the IF_CONDITION node's "branch True when the input carries an image"
 * deterministic check. Purely additive `ALTER TABLE … ADD COLUMN`; existing rows
 * read `NULL` (image-presence branching off, treated identically to `false`).
 *
 * The column is nullable on purpose — SQLite cannot add a NOT NULL column without a
 * constant default, and (more importantly) this migration already shipped to dev
 * builds as a nullable `INTEGER`; tightening it to NOT NULL afterwards would leave
 * those devices' column nullable while the entity expected NOT NULL, crashing Room's
 * post-migration schema validation. A migration that has run must never be mutated.
 */
val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `conditionHasImage` INTEGER")
    }
}
