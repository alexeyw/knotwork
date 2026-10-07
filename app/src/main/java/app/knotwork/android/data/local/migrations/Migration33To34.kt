package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the nullable `targetPipelineId` column to `pipeline_nodes`. It
 * carries the callee id of a `PIPELINE` node (the composition primitive
 * that runs another pipeline as a sub-step); `null` for every other node
 * type, so existing rows need no backfill.
 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `targetPipelineId` TEXT")
    }
}
