package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 10 to 11.
 * Updates the `prompt_templates` table to make `category` NOT NULL.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `prompt_templates_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `name` TEXT NOT NULL, 
                `text` TEXT NOT NULL, 
                `category` TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `prompt_templates_new` (`id`, `name`, `text`, `category`)
            SELECT `id`, `name`, `text`, COALESCE(`category`, 'Default') FROM `prompt_templates`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `prompt_templates`")
        db.execSQL("ALTER TABLE `prompt_templates_new` RENAME TO `prompt_templates`")
    }
}
