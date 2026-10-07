package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v56 → v57: creates `usage_pipeline_day`, the `(day, pipeline)` activity
 * set behind the weekly-retention figures of the local usage statistics.
 *
 * The existing counters are all-time totals carrying no date, so "which
 * pipelines are alive this week" is not derivable from them; this table
 * adds exactly that one missing dimension as a set (composite primary
 * key + `INSERT OR IGNORE`), not as a second run counter.
 *
 * Purely additive, and deliberately **not** back-filled: the historical
 * counters hold no dates to back-fill from, so on an upgraded install the
 * *live-pipelines* figure starts at zero and fills as the app is used.
 * (The day-based figures are unaffected — they read `usage_active_day`,
 * which has been recorded since v47.) Fabricating a history here would be
 * inventing data, which is worse than a week of honest zeros.
 */
val MIGRATION_56_57 = object : Migration(56, 57) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `usage_pipeline_day` (" +
                "`day` TEXT NOT NULL, `pipelineId` TEXT NOT NULL, " +
                "PRIMARY KEY(`day`, `pipelineId`))",
        )
    }
}
