package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v58 → v59: gives `pipeline_runs` the two spend counters and the typed
 * termination reason the autonomous-run ceilings need.
 *
 * Purely additive. The counters default to zero, which is the truth for
 * every pre-v59 row: nothing was counted before, so nothing was spent as
 * far as the ceiling mechanism is concerned, and an in-flight run picked
 * up across an upgrade simply starts its accounting here.
 * `terminationReason` is nullable and stays null for historical rows —
 * "why did this stop" was only ever recorded as prose, and prose is not
 * something a migration should try to parse back into an enum.
 *
 * The `DEFAULT 0` on both counters is load-bearing rather than
 * decorative: the entity declares `@ColumnInfo(defaultValue = "0")`, and
 * Room's `TableInfo` check compares the two sides. Drop it on either
 * side and the schema-validation suite fails — which is instrumented, so
 * `./gradlew check` would not be the thing that told you.
 */
val MIGRATION_58_59 = object : Migration(58, 59) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `stepsSpent` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `tokensSpent` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `terminationReason` TEXT")
    }
}
