package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `supportsVision` flag to `local_models`. A user-set marker
 * declaring a model vision-capable (able to read an attached image),
 * read by the multimodal pre-flight send guard. Additive and backfilled
 * to `0` (text-only) for every existing row, matching the entity column
 * default — the LiteRT-LM runtime exposes no capability probe, so vision
 * support is opt-in rather than auto-detected.
 */
val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `local_models` ADD COLUMN `supportsVision` INTEGER NOT NULL DEFAULT 0")
    }
}
