package app.knotwork.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.knotwork.android.data.local.models.MemoryChunkEntity
import app.knotwork.android.data.local.models.MemoryPendingUpdateEntity
import app.knotwork.android.domain.models.MemorySummary
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for managing long-term memory chunks in the local database.
 */
@Dao
interface MemoryDao {

    /**
     * Inserts a new memory chunk into the database.
     *
     * @param memoryChunk The [MemoryChunkEntity] to insert.
     * @return The row ID of the newly inserted chunk.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memoryChunk: MemoryChunkEntity): Long

    /**
     * Inserts a batch of memory chunks in a single transaction. Used by the
     * memory import path; rows carrying an explicit non-zero `id` keep that id
     * (so a round-trip preserves identity), while `id = 0` rows are
     * auto-assigned. Conflicting ids are replaced.
     *
     * @param chunks The chunks to insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemories(chunks: List<MemoryChunkEntity>)

    /**
     * Atomically replaces the whole table with [chunks]: deletes every row then
     * inserts the batch in one transaction, so a Replace import can never leave
     * the store empty if the insert fails partway.
     *
     * @param chunks The replacement rows.
     */
    @Transaction
    suspend fun replaceAll(chunks: List<MemoryChunkEntity>) {
        deleteAllMemories()
        insertMemories(chunks)
    }

    /**
     * Projects the ids of every stored chunk. Backs the import Merge strategy's
     * duplicate check without deserialising any embeddings.
     *
     * @return All stored chunk ids.
     */
    @Query("SELECT id FROM memory_chunks")
    suspend fun getAllIds(): List<Long>

    /**
     * Retrieves all memory chunks from the database.
     * This is used to load embeddings into memory for vector similarity search.
     *
     * @return A list of all [MemoryChunkEntity] items.
     */
    @Query("SELECT * FROM memory_chunks")
    suspend fun getAllMemories(): List<MemoryChunkEntity>

    /**
     * Deletes a memory chunk by its unique ID.
     *
     * @param id The ID of the memory chunk to delete.
     */
    @Query("DELETE FROM memory_chunks WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    /**
     * Replaces the text content and embedding of an existing memory chunk.
     *
     * Used by the in-app memory editor: when the user commits an edit the
     * embedding must be regenerated for the new text, so the DAO writes both
     * columns atomically. The `timestamp` is intentionally left untouched —
     * an edit is not a fresh entry and should keep its original position in
     * the time-ordered history.
     *
     * @param id Identifier of the chunk to update.
     * @param text New raw text content.
     * @param embedding Embedding vector encoded into the binary BLOB form
     *   (see [app.knotwork.android.data.local.EmbeddingBlobCodec]).
     */
    @Query("UPDATE memory_chunks SET text = :text, embedding = :embedding WHERE id = :id")
    suspend fun updateMemory(id: Long, text: String, embedding: ByteArray)

    /**
     * Sets the `isPinned` flag on a single memory chunk.
     *
     * @param id Identifier of the chunk to update.
     * @param isPinned `true` to pin the chunk, `false` to unpin it.
     */
    @Query("UPDATE memory_chunks SET isPinned = :isPinned WHERE id = :id")
    suspend fun setMemoryPinned(id: Long, isPinned: Boolean)

    /**
     * Replaces the comma-separated tag list of a single chunk. The text and
     * embedding are untouched — tag edits do not require re-embedding.
     *
     * @param id Identifier of the chunk to update.
     * @param tagsCsv New comma-separated tag list (empty string clears tags).
     */
    @Query("UPDATE memory_chunks SET tagsCsv = :tagsCsv WHERE id = :id")
    suspend fun setMemoryTags(id: Long, tagsCsv: String)

    /**
     * Atomically replaces the text, embedding, and tags of a chunk in one
     * transaction, so an inline edit never half-applies (e.g. new text saved
     * but tags not). The `timestamp` is left untouched.
     *
     * @param id Identifier of the chunk to update.
     * @param text New raw text content.
     * @param embedding Binary-encoded embedding vector for [text].
     * @param tagsCsv New comma-separated tag list.
     */
    @Transaction
    suspend fun updateMemoryWithTags(id: Long, text: String, embedding: ByteArray, tagsCsv: String) {
        updateMemory(id = id, text = text, embedding = embedding)
        setMemoryTags(id = id, tagsCsv = tagsCsv)
    }

    /**
     * Records that the given chunks were just retrieved into a pipeline run's
     * Long-Term Memory context: increments each chunk's `useCount` and stamps
     * `lastUsedAt`. Backs the detail sheet's "Used in N replies" line.
     *
     * @param ids Identifiers of the chunks that were injected.
     * @param atMillis Epoch-millis to stamp as the most-recent use time.
     */
    @Query("UPDATE memory_chunks SET useCount = useCount + 1, lastUsedAt = :atMillis WHERE id IN (:ids)")
    suspend fun recordUsage(ids: List<Long>, atMillis: Long)

    /**
     * Retrieves the [limit] most recent memory chunks projected to text/timestamp
     * fields only — without the (potentially large) embedding payload.
     *
     * Intended for read paths that just display or quote the memory text (e.g.
     * the `$MEMORY_SUMMARY` prompt variable), so they avoid the cost of pulling
     * and deserialising every stored embedding string on every render.
     *
     * @param limit Maximum number of recent rows to return.
     * @return Recent rows ordered newest-first as [MemorySummary] projections.
     */
    @Query("SELECT id, text, timestamp FROM memory_chunks ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemorySummaries(limit: Int): List<MemorySummary>

    /**
     * Deletes older unpinned memory chunks, keeping only the specified number
     * of the most recent ones.
     *
     * Pinned chunks (`isPinned = 1`) are exempt — they survive compaction
     * regardless of `keepLimit`, matching the contract advertised on
     * [app.knotwork.android.data.local.models.MemoryChunkEntity.isPinned]. The
     * `keepLimit` window is therefore evaluated over the *unpinned* subset
     * only.
     *
     * @param keepLimit The number of recent unpinned memory chunks to keep.
     */
    @Query(
        "DELETE FROM memory_chunks WHERE isPinned = 0 AND id NOT IN " +
            "(SELECT id FROM memory_chunks WHERE isPinned = 0 " +
            "ORDER BY timestamp DESC LIMIT :keepLimit)",
    )
    suspend fun deleteOldestMemories(keepLimit: Int)

    /**
     * Deletes a batch of memory chunks by id in one statement.
     *
     * Backs the atomic consolidation step ([replaceWithConsolidated]); a
     * per-id loop there could fail partway and leave a summary coexisting with
     * some of the very chunks it replaced.
     *
     * @param ids Identifiers of the chunks to delete. An empty list is a no-op.
     */
    @Query("DELETE FROM memory_chunks WHERE id IN (:ids)")
    suspend fun deleteMemoriesByIds(ids: List<Long>)

    /**
     * Writes a consolidation summary and removes the chunks it replaces in a
     * single transaction.
     *
     * Compaction is the one path that *destroys* user data, so the write and
     * the deletions must not be separable: a crash (or a failing statement)
     * between them would either duplicate the facts (summary saved, originals
     * kept) or lose them outright (originals deleted, summary missing). Room's
     * transaction gives the all-or-nothing guarantee the compaction contract
     * advertises.
     *
     * @param summary The summary row to insert.
     * @param originalIds Ids of the chunks the summary replaces — exactly the
     *   members the coverage gate approved, never the whole cluster.
     * @return The row id of the inserted summary.
     */
    @Transaction
    suspend fun replaceWithConsolidated(summary: MemoryChunkEntity, originalIds: List<Long>): Long {
        val insertedId = insertMemory(summary)
        deleteMemoriesByIds(originalIds)
        return insertedId
    }

    /**
     * Retrieves the non-pinned memory chunks older than a given timestamp —
     * the candidate set for background compaction.
     *
     * Pinned chunks (`isPinned = 1`) are excluded so they are never touched by
     * consolidation, fresh chunks (`timestamp >= :olderThan`) are excluded so
     * recently-learned facts keep their exact wording, and an update waiting on a
     * pinned chunk ([MemoryPendingUpdateEntity]) is excluded so a summary cannot
     * dissolve the pair before the user resolves it. The full embedding payload is
     * returned because the compaction worker clusters on it.
     *
     * @param olderThan Exclusive upper bound on `timestamp`; only rows strictly
     *   older than this are returned.
     * @return Candidate chunks ordered newest-first.
     */
    @Query(
        "SELECT * FROM memory_chunks WHERE isPinned = 0 AND timestamp < :olderThan " +
            "AND id NOT IN (SELECT updateChunkId FROM memory_pending_updates) ORDER BY timestamp DESC",
    )
    suspend fun getCompactionCandidates(olderThan: Long): List<MemoryChunkEntity>

    /**
     * One-shot count of every stored memory chunk (pinned and unpinned). Used
     * by the compaction scheduler's hard-limit check; distinct from the
     * reactive [observeChunkCount] used by the Settings stats card.
     *
     * @return The total number of rows in `memory_chunks`.
     */
    @Query("SELECT COUNT(*) FROM memory_chunks")
    suspend fun countMemories(): Int

    /**
     * Removes every memory chunk from the table — including pinned entries.
     * Backs the Settings → Memory → Clear destructive action. Pinned-or-not
     * is intentionally ignored because the user has explicitly confirmed
     * the wipe via the typed-confirm dialog.
     */
    @Query("DELETE FROM memory_chunks")
    suspend fun deleteAllMemories()

    /**
     * One-shot count of chunks awaiting re-embedding. Backs the cheap startup
     * re-arm check (`MainActivity`) that re-schedules the re-embed worker when a
     * previous one-off was lost (process killed before WorkManager persisted it)
     * or exhausted its retries — without loading every pending row + embedding.
     *
     * @return The number of rows with `needsReembedding = 1`.
     */
    @Query("SELECT COUNT(*) FROM memory_chunks WHERE needsReembedding = 1")
    suspend fun countNeedingReembedding(): Int

    /**
     * Retrieves every chunk awaiting re-embedding. The full embedding payload
     * is returned for symmetry with [getAllMemories]; the recompute use case
     * only needs the text but the mapper is shared.
     *
     * @return Chunks with `needsReembedding = 1`.
     */
    @Query("SELECT * FROM memory_chunks WHERE needsReembedding = 1")
    suspend fun getMemoriesNeedingReembedding(): List<MemoryChunkEntity>

    /**
     * Writes a freshly-computed embedding back to a chunk and clears its
     * `needsReembedding` flag, so a once-imported chunk is repaired exactly
     * once. The `text` and `timestamp` are untouched.
     *
     * @param id Identifier of the chunk to repair.
     * @param embedding Binary-encoded embedding vector produced by the active provider.
     */
    @Query("UPDATE memory_chunks SET embedding = :embedding, needsReembedding = 0 WHERE id = :id")
    suspend fun markReembedded(id: Long, embedding: ByteArray)

    /**
     * Live count of stored memory chunks. Powers the Settings → Memory
     * "CHUNKS" stat cell.
     */
    @Query("SELECT COUNT(*) FROM memory_chunks")
    fun observeChunkCount(): Flow<Int>

    /**
     * Live aggregate byte size of the table content. Uses `LENGTH(text)` +
     * `LENGTH(embedding)` so the estimate matches what the SQLite VFS
     * actually stores rather than a raw row count (`LENGTH` on the embedding
     * BLOB counts bytes directly). Powers the Settings → Memory "SIZE"
     * stat cell.
     */
    @Query("SELECT COALESCE(SUM(LENGTH(text) + LENGTH(embedding)), 0) FROM memory_chunks")
    fun observeTotalBytes(): Flow<Long>
}
