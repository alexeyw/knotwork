package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 27 to 28 — memory export/import.
 *
 * Adds the `needsReembedding` column to `memory_chunks`. Set to `1` on
 * chunks imported from a device whose active embedding provider differs
 * from the local one: their stored vectors live in an incompatible
 * space and are re-computed lazily on the next retrieval
 * ([app.knotwork.android.domain.usecases.RecomputePendingEmbeddingsUseCase]).
 * Backfilled to `0` for every existing row — locally-written chunks are
 * already in the active provider's space. The default matches the entity
 * column default so the Room-generated schema and this migration agree.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memory_chunks` ADD COLUMN `needsReembedding` INTEGER NOT NULL DEFAULT 0")
    }
}
