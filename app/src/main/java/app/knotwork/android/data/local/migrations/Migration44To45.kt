package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `triggers` table backing user-defined automation triggers
 * (a persisted `condition → bound pipeline` rule). Additive — no
 * existing rows are touched.
 *
 * The activation condition is stored as a JSON string in `conditionJson`
 * (see [app.knotwork.android.domain.triggerio.TriggerConditionCodec]) so
 * the schema stays narrow across condition shapes. `pipelineId` carries
 * no foreign key: a trigger may outlive its bound pipeline, and the fire
 * path auto-disables a trigger whose pipeline has been deleted. The
 * `enabled` index backs the active-trigger query the scheduler sync runs.
 */
val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `triggers` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `pipelineId` TEXT,
                `prompt` TEXT NOT NULL,
                `conditionJson` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `armed` INTEGER NOT NULL DEFAULT 1,
                `createdAt` INTEGER NOT NULL,
                `lastFiredAt` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_triggers_enabled` ON `triggers` (`enabled`)",
        )
    }
}
