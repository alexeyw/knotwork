package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 25 to 26 — memory chunk provenance.
 *
 * Adds the `source` column to `memory_chunks` recording each chunk's
 * provenance ([app.knotwork.android.domain.models.MemorySource]) as a
 * compact JSON string. Existing rows predate source attribution, so
 * they are backfilled to the `Unknown` encoding — identical to what
 * `Converters.fromMemorySource(MemorySource.Unknown)` produces and to
 * the entity's column default, keeping the Room-generated schema and
 * this migration in agreement.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `memory_chunks` ADD COLUMN `source` TEXT NOT NULL " +
                "DEFAULT '{\"type\":\"unknown\"}'",
        )
    }
}
