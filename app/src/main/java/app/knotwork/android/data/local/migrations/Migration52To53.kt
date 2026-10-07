package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v52 → v53: adds the nullable `memoryRetrievalQuery` column to
 * `pipelines` — the long-term-memory search key a pipeline declares for
 * its background (trigger / scheduled / tile) runs, whose authored
 * prompt is too generic to be a useful semantic key
 * (`DESCRIPTION.md` §6.10.1).
 *
 * Purely additive and nullable: every existing pipeline upgrades to
 * "declares nothing" and keeps the previous behaviour exactly (its
 * background runs fall through to the first memory-aware node's input,
 * then to the run prompt). Nullable rather than `NOT NULL DEFAULT ''`
 * so "never declared" and "declared blank" cannot drift apart.
 */
val MIGRATION_52_53 = object : Migration(52, 53) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipelines` ADD COLUMN `memoryRetrievalQuery` TEXT")
    }
}
