package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v64 → v65: adds `chat_messages.relayed` — whether an AGENT row's text was
 * handed on unchanged rather than written by a model.
 *
 * An OUTPUT node in echo mode saves its input as the assistant's reply, so
 * behind a TOOL node the reply is the tool's result verbatim, and long-term
 * memory extraction read it as something the assistant had said. How an
 * earlier row was produced was never recorded, so the back-fill is `0` —
 * the behaviour every row had before this migration.
 */
val MIGRATION_64_65 = object : Migration(64, 65) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `relayed` INTEGER NOT NULL DEFAULT 0")
    }
}
