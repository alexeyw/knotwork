package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v53 → v54: adds the `isArchived` column to `chat_sessions` — the
 * chat-archive flag that moves a conversation out of the main thread
 * list without deleting anything it owns.
 *
 * Purely additive: a new column introduced on a new schema version, so
 * `NOT NULL DEFAULT 0` is safe — every pre-existing session upgrades to
 * "not archived" and the thread list keeps showing exactly what it
 * showed before.
 */
val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `chat_sessions` ADD COLUMN `isArchived` INTEGER NOT NULL DEFAULT 0",
        )
    }
}
