package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v67 → v68: adds `model_calls.durationMs` — how long an on-device call's
 * stream took.
 *
 * A run's check repeats every on-device call; the recorded durations let it
 * say beforehand how long that will take, and ask first when it is long.
 * Nullable with no back-fill: a call recorded before this migration has no
 * duration, and the check falls back to counting calls.
 */
val MIGRATION_67_68 = object : Migration(67, 68) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `model_calls` ADD COLUMN `durationMs` INTEGER")
    }
}
