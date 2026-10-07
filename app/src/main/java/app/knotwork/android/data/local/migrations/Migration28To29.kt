package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.knotwork.android.data.local.EmbeddingBlobCodec

/**
 * Migration from version 28 to 29 — binary embedding storage.
 *
 * Rebuilds `memory_chunks` so the `embedding` column changes type from
 * TEXT (comma-separated floats) to BLOB (little-endian IEEE-754 floats,
 * 4 bytes per component, no header — see
 * [app.knotwork.android.data.local.EmbeddingBlobCodec]). SQLite cannot
 * change a column type in place, so the migration creates the new
 * table, streams every row through a Kotlin-side string → binary
 * conversion, then drops the old table and renames the new one.
 *
 * A legacy embedding string that cannot be parsed (blank or carrying a
 * non-numeric component) converts to a **zero-length blob** rather than
 * dropping the row: such rows are already invisible to similarity
 * retrieval, but their `text` is intact and the re-embedding repair
 * path can still recompute a fresh vector from it — deleting them here
 * would silently destroy user data. The empty blob decodes to `null`
 * at the entity boundary, preserving the pre-migration semantics
 * exactly.
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_chunks_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `text` TEXT NOT NULL,
                `embedding` BLOB NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `isPinned` INTEGER NOT NULL,
                `source` TEXT NOT NULL DEFAULT '{"type":"unknown"}',
                `tagsCsv` TEXT NOT NULL DEFAULT '',
                `useCount` INTEGER NOT NULL DEFAULT 0,
                `lastUsedAt` INTEGER,
                `needsReembedding` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.query(
            "SELECT `id`, `text`, `embedding`, `timestamp`, `isPinned`, `source`, " +
                "`tagsCsv`, `useCount`, `lastUsedAt`, `needsReembedding` FROM `memory_chunks`",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                db.execSQL(
                    "INSERT INTO `memory_chunks_new` (`id`, `text`, `embedding`, `timestamp`, " +
                        "`isPinned`, `source`, `tagsCsv`, `useCount`, `lastUsedAt`, `needsReembedding`) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        cursor.getLong(0),
                        cursor.getString(1),
                        parseLegacyEmbedding(cursor.getString(2)),
                        cursor.getLong(3),
                        cursor.getLong(4),
                        cursor.getString(5),
                        cursor.getString(6),
                        cursor.getLong(7),
                        if (cursor.isNull(8)) null else cursor.getLong(8),
                        cursor.getLong(9),
                    ),
                )
            }
        }
        db.execSQL("DROP TABLE `memory_chunks`")
        db.execSQL("ALTER TABLE `memory_chunks_new` RENAME TO `memory_chunks`")
    }

    /**
     * Parses the legacy comma-separated embedding string into the
     * binary BLOB form. This is the only remaining home of the
     * pre-BLOB string codec — kept private to the migration so no
     * production read/write path can resurrect the text encoding.
     *
     * @param value The legacy column value.
     * @return The encoded bytes, or a zero-length array when [value]
     *   is blank or contains a non-numeric component.
     */
    private fun parseLegacyEmbedding(value: String?): ByteArray {
        if (value.isNullOrBlank()) return ByteArray(0)
        val parts = value.split(",")
        val floats = FloatArray(parts.size)
        for (index in parts.indices) {
            floats[index] = parts[index].toFloatOrNull() ?: return ByteArray(0)
        }
        return EmbeddingBlobCodec.encode(floats)
    }
}
