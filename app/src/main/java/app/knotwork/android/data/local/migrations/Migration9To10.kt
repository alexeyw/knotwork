package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 9 to 10.
 * Adds the `prompt_templates` table.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `prompt_templates` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `name` TEXT NOT NULL, 
                `text` TEXT NOT NULL, 
                `category` TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }
}
