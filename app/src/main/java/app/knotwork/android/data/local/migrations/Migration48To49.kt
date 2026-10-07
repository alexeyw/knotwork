package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v48 → v49: adds the non-null `hadImage` flag to `pipeline_runs`, recording
 * whether the run's originating message carried an image. Persisted so a
 * checkpoint resume can still report image presence to IF/router nodes that
 * execute live past the resume point (a resumed run never re-delivers the
 * image). Purely additive `ALTER TABLE … ADD COLUMN`; existing rows default
 * to `0` (no image).
 */
val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `hadImage` INTEGER NOT NULL DEFAULT 0")
    }
}
