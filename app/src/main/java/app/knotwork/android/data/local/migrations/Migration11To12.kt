package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 11 to 12.
 * Updates previously created default prompts that had the 'Default' category to their correct NodeType.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE `prompt_templates` SET `category` = 'INTENT_ROUTER' " +
                "WHERE `category` = 'Default' AND `name` = 'Classifier'",
        )
        db.execSQL(
            "UPDATE `prompt_templates` SET `category` = 'DECOMPOSITION' " +
                "WHERE `category` = 'Default' AND `name` = 'Decomposer'",
        )
        db.execSQL(
            "UPDATE `prompt_templates` SET `category` = 'SUMMARY' " +
                "WHERE `category` = 'Default' AND `name` = 'Summarizer'",
        )
        db.execSQL(
            "UPDATE `prompt_templates` SET `category` = 'TOOL' " +
                "WHERE `category` = 'Default' AND `name` = 'Tool Picker'",
        )

        // For any other prompts that somehow ended up as 'Default', reassign them to CUSTOM or something safe, or leave them.
        // We'll leave the rest as is, but users can edit them.
    }
}
