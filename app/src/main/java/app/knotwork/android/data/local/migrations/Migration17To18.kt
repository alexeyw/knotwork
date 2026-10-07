package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 17 to 18.
 *
 * Adds the `context_config` column to `pipeline_nodes` that stores the
 * per-node [app.knotwork.android.domain.models.NodeContextConfig] as a JSON
 * blob. The default value enables every flag (`chatHistory`,
 * `originalTask`, `nodeInput`, `longTermMemory`, `toolResults`) so that
 * existing pipelines keep receiving the full context on every node.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `pipeline_nodes` ADD COLUMN `context_config` TEXT NOT NULL " +
                "DEFAULT '{\"chatHistory\":true,\"originalTask\":true,\"nodeInput\":true," +
                "\"longTermMemory\":true,\"toolResults\":true}'",
        )
    }
}
