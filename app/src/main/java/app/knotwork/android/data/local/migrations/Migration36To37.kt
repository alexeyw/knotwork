package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the nullable `skillId` column to `pipeline_nodes`, backing
 * [NodeType.SKILL][app.knotwork.android.domain.models.NodeType.SKILL]
 * nodes. Additive and nullable, so existing rows (every node predates
 * the skill feature) default to `NULL` — "no skill referenced".
 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `skillId` TEXT")
    }
}
