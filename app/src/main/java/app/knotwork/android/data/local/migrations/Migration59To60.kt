package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Five per-node settings the editor could already show and the engine
 * could not read: the INTENT_ROUTER fallback class, the CLARIFICATION
 * quick replies, the TOOL always-confirm switch, the DECOMPOSITION
 * sub-task cap and the QUEUE_PROCESSOR stop-on-error switch.
 *
 * Every column is nullable with no default, and `null` means "behave
 * exactly as before" for all five. That is what lets an existing
 * pipeline cross this migration without changing how it runs.
 */
val MIGRATION_59_60 = object : Migration(59, 60) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `fallbackClass` TEXT")
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `quickReplies` TEXT")
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `alwaysConfirm` INTEGER")
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `maxSubtasks` INTEGER")
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `stopOnError` INTEGER")
    }
}
