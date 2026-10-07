package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v68 → v69: lets a newer statement of a memory fact replace the stored one
 * without losing it.
 *
 * - `memory_chunk_history` keeps a chunk's earlier texts — the text, its
 *   source, tags, when it was first stored and when it was replaced — and
 *   cascades with its chunk.
 * - `memory_pending_updates` links a chunk that updates a pinned chunk to that
 *   chunk, at most one per pinned chunk (unique index), cascading from both.
 *
 * Both tables are new and start empty; `memory_chunks` is not touched, so no
 * stored memory is rewritten by the upgrade.
 */
val MIGRATION_68_69 = object : Migration(68, 69) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_chunk_history` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chunkId` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, `source` TEXT NOT NULL DEFAULT '{\"type\":\"unknown\"}', " +
                "`tagsCsv` TEXT NOT NULL DEFAULT '', `capturedAt` INTEGER NOT NULL, " +
                "`replacedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`chunkId`) REFERENCES `memory_chunks`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_chunk_history_chunkId` " +
                "ON `memory_chunk_history` (`chunkId`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_pending_updates` (" +
                "`updateChunkId` INTEGER NOT NULL, `pinnedChunkId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`updateChunkId`), " +
                "FOREIGN KEY(`updateChunkId`) REFERENCES `memory_chunks`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`pinnedChunkId`) REFERENCES `memory_chunks`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_pending_updates_pinnedChunkId` " +
                "ON `memory_pending_updates` (`pinnedChunkId`)",
        )
    }
}
