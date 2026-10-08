package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v69 → v70: lets a trigger's health be measured from when it was switched on, and
 * remembers which silence the user was told about.
 *
 * - `triggers.activatedAt` — the moment the trigger last became active (enabled and
 *   bound). Before it, a trigger re-enabled after a long pause read as overdue
 *   until its next poll, measured from an evaluation older than the pause.
 * - `triggers.staleNoticeFor` — the last sign of life a "has not checked in"
 *   notice was about, so a silence is announced once.
 *
 * Both are nullable and start empty: an existing trigger behaves as before until
 * it is next switched on, and no notice is recorded as sent.
 */
val MIGRATION_69_70 = object : Migration(69, 70) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `triggers` ADD COLUMN `activatedAt` INTEGER")
        db.execSQL("ALTER TABLE `triggers` ADD COLUMN `staleNoticeFor` INTEGER")
    }
}
