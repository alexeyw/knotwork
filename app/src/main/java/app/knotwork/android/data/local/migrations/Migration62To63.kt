package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v62 → v63: adds `chat_messages.imported` — whether a row came from a chat
 * file (*Import chat*) rather than being written on this device.
 *
 * A file decides each row's role and text, and nothing used to record that a
 * row came from one: long-term memory extraction mined an imported USER row as
 * something this device's user had said. Every row that existed before this
 * migration was written on this device, so the back-fill is `0` — which is
 * also what an imported row from an earlier release gets, since no record of
 * its origin survives to back-fill from.
 */
val MIGRATION_62_63 = object : Migration(62, 63) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `imported` INTEGER NOT NULL DEFAULT 0")
    }
}
