package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the image-attachment columns to `chat_messages`: the store-relative
 * path, MIME type, and pixel dimensions of an image attached to a user
 * message. All nullable and additive — pre-existing rows keep `NULL`
 * (no attachment). The image bytes live in the on-device attachment store
 * (`filesDir/attachments/`), not the database.
 */
val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `attachmentPath` TEXT")
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `attachmentMimeType` TEXT")
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `attachmentWidth` INTEGER")
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `attachmentHeight` INTEGER")
    }
}
