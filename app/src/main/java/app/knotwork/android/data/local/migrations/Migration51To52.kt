package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v51 → v52: adds the `onboarding_milestone` table holding the write-once
 * markers of the install → first-value path (onboarding opened, scenario
 * chosen, model download started/finished, first value), which turn the
 * "< 10 minutes to first value" metric into a repeatable measurement
 * instead of a stopwatch reading.
 *
 * Purely additive: no existing table is touched, so upgrading installs
 * simply start with an empty marker set (their journey happened before
 * the markers existed and is therefore not measurable — by design, the
 * metric is taken on a clean install). The marker key is the primary key,
 * which is what backs the `INSERT OR IGNORE` write-once semantics. The
 * table lives in the SQLCipher-encrypted database and nothing it holds
 * ever leaves the device.
 */
val MIGRATION_51_52 = object : Migration(51, 52) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `onboarding_milestone` (
                `milestoneKey` TEXT NOT NULL,
                `atMillis` INTEGER NOT NULL,
                `detail` TEXT,
                PRIMARY KEY(`milestoneKey`)
            )
            """.trimIndent(),
        )
    }
}
