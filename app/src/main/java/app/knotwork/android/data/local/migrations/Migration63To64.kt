package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v63 → v64: adds `background_prompts` — the prompts of queued background
 * runs, which used to travel in the background runtime's own unencrypted
 * store as the worker's input. Additive; nothing to back-fill: requests
 * queued before the update still carry their prompt and are read as such.
 */
val MIGRATION_63_64 = object : Migration(63, 64) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `background_prompts` " +
                "(`id` TEXT NOT NULL, `prompt` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}
