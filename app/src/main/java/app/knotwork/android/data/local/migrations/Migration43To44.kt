package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the nullable `modelName` column to `chat_messages` so an AGENT
 * answer records the model that generated it (snapshotted at save time)
 * and the chat keeps attributing it correctly after the active model is
 * switched. Additive and nullable — legacy rows keep `NULL`.
 */
val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `modelName` TEXT")
    }
}
