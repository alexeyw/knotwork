package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 13 to 14.
 * Adds `cloudProvider` column to `pipeline_nodes` table.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `cloudProvider` TEXT")
    }
}
