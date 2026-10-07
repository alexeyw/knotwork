package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v65 → v66: adds the model file's SHA-256 to `local_models` — `sha256` and
 * the stamp of the file it was computed from (`sha256FileSize`,
 * `sha256FileModifiedAt`).
 *
 * The hash identifies the model a run used. It is computed once per file
 * by a background pass, never per run, and the stamp tells a current hash
 * from one of a file replaced since. All three columns are nullable with
 * no back-fill: a row installed before this migration has never been
 * hashed, and the start-up re-arm schedules the pass that hashes it.
 */
val MIGRATION_65_66 = object : Migration(65, 66) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `local_models` ADD COLUMN `sha256` TEXT")
        db.execSQL("ALTER TABLE `local_models` ADD COLUMN `sha256FileSize` INTEGER")
        db.execSQL("ALTER TABLE `local_models` ADD COLUMN `sha256FileModifiedAt` INTEGER")
    }
}
