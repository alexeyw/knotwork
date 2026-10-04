package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.MemoryStats
import app.knotwork.android.domain.models.MemorySummary
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing and searching long-term memory.
 */
interface MemoryRepository {

    /**
     * Saves a new text snippet and its vector embedding to the database.
     *
     * @param text The raw text to save.
     * @param embedding The float array vector representing the text.
     * @param source Provenance of the chunk (which conversation / action it
     *   came from). Defaults to [MemorySource.Manual] so existing direct-save
     *   call sites — which represent a deliberate, user-attributable write —
     *   keep a sensible attribution without each having to spell it out.
     * @param tags Optional labels to attach to the chunk (e.g. the
     *   auto-extraction fact type). Defaults to empty.
     * @return The ID of the saved memory chunk.
     */
    suspend fun saveMemory(
        text: String,
        embedding: FloatArray,
        source: MemorySource = MemorySource.Manual,
        tags: List<String> = emptyList(),
    ): Long

    /**
     * Atomically replaces a set of chunks with the consolidation summary that
     * subsumes them: the summary is written and the originals removed in a
     * single transaction.
     *
     * This is the only sanctioned way for compaction to delete a chunk. Saving
     * and deleting separately can half-apply — leaving a summary alongside the
     * facts it duplicates, or (worse) deleting facts whose summary never
     * landed — and compaction is the one background path that destroys data the
     * user cannot recover.
     *
     * @param text The summary text produced by the consolidation prompt.
     * @param embedding The summary's embedding, produced by the **active**
     *   provider so it shares the space of the chunks it replaces.
     * @param originalIds Ids of the chunks the summary replaces. These are the
     *   members that passed the coverage gate
     *   ([app.knotwork.android.domain.services.CompactionCoverageVerifier]) —
     *   unverified members are deliberately left alive and must not appear
     *   here. The same ids are recorded on the summary as
     *   [MemorySource.Compaction.originalChunkIds].
     * @return The ID of the saved summary chunk.
     */
    suspend fun replaceWithConsolidated(text: String, embedding: FloatArray, originalIds: List<Long>): Long

    /**
     * Retrieves all saved memories.
     *
     * @return A list of [MemoryChunk] objects.
     */
    suspend fun getAllMemories(): List<MemoryChunk>

    /**
     * Retrieves the most recent memories as a lightweight [MemorySummary]
     * projection that omits embeddings.
     *
     * Use this when only the textual content / ordering is needed (e.g. the
     * `$MEMORY_SUMMARY` prompt variable). Pulling a few hundred rows of
     * `getAllMemories()` just to take the top N would needlessly deserialise
     * every embedding from its column-encoded string form.
     *
     * @param limit Maximum number of recent memories to return; values `<= 0`
     * yield an empty list.
     * @return Recent memories ordered newest-first.
     */
    suspend fun getRecentMemorySummaries(limit: Int): List<MemorySummary>

    /**
     * Finds the most semantically similar memories to a given query embedding
     * using cosine similarity.
     *
     * **The whole table is scanned on every call — there is no recency
     * window.** A chunk's age must never decide whether it is *visible* to
     * retrieval (that would silently expire long-term facts); recency only
     * adds an ordering bonus to already-found candidates, and that bonus lives
     * in [app.knotwork.android.domain.services.MemoryReranker] — it is added
     * to the similarity, never multiplied into it, so no candidate can be
     * scored out of the results by age alone. The pool is
     * bounded in practice by the compaction hard-limit
     * (`MemorySettings.maxMemoryChunks`), which is the explicit
     * performance cap; implementations log a warning when the scanned pool
     * grows large enough for the linear scan to become noticeable.
     *
     * @param queryEmbedding The vector embedding of the user's query.
     * @param limit The maximum number of results to return, or `null` to
     *   return the entire scored pool (used by retrieval, which re-ranks
     *   before applying its own top-K).
     * @return A list of pairs containing the [MemoryChunk] and its similarity score (0.0 to 1.0),
     *         sorted by similarity in descending order (highest first).
     */
    suspend fun findSimilarMemories(queryEmbedding: FloatArray, limit: Int? = null): List<Pair<MemoryChunk, Float>>

    /**
     * Deletes older memory chunks, keeping only the specified number of the most recent ones.
     *
     * @param keepLimit The number of recent memory chunks to keep.
     */
    suspend fun compactMemory(keepLimit: Int)

    /**
     * Retrieves the non-pinned chunks older than [olderThanMillis] — the
     * candidate set for the background compaction worker. Pinned chunks are
     * never returned (they are exempt from consolidation), and chunks younger
     * than the cutoff are excluded so recent facts keep their exact wording.
     *
     * @param olderThanMillis Exclusive upper bound on a chunk's timestamp.
     * @return Candidate chunks ordered newest-first.
     */
    suspend fun getCompactionCandidates(olderThanMillis: Long): List<MemoryChunk>

    /**
     * One-shot total count of stored chunks (pinned and unpinned). Backs the
     * compaction scheduler's hard-limit check.
     *
     * @return The number of stored memory chunks.
     */
    suspend fun countMemories(): Int

    /**
     * Deletes a memory chunk by its unique ID.
     *
     * @param id The ID of the memory chunk to delete.
     */
    suspend fun deleteMemory(id: Long)

    /**
     * Replaces the text content and embedding of an existing memory chunk.
     *
     * The caller is responsible for regenerating the embedding for the new
     * text — the repository will not derive it implicitly. The chunk's
     * `timestamp` is intentionally left untouched so the entry keeps its
     * original chronological position.
     *
     * @param id Identifier of the chunk to update.
     * @param text The new raw text content.
     * @param embedding The new vector embedding produced from [text].
     */
    suspend fun updateMemory(id: Long, text: String, embedding: FloatArray)

    /**
     * Atomically replaces the text, embedding, and tags of a chunk in a single
     * transaction, so an inline edit can never half-apply (e.g. text committed
     * but tags not). The chunk's `timestamp` is left untouched.
     *
     * @param id Identifier of the chunk to update.
     * @param text The new raw text content.
     * @param embedding The new vector embedding produced from [text].
     * @param tags The new tag list (empty clears all tags).
     */
    suspend fun updateMemoryWithTags(id: Long, text: String, embedding: FloatArray, tags: List<String>)

    /**
     * Flips the pinned state of a single memory chunk. Pinned chunks sort
     * ahead of unpinned chunks on the memory surface and are exempt from
     * future compaction passes.
     *
     * @param id Identifier of the chunk to update.
     * @param pinned `true` to pin the chunk, `false` to unpin it.
     */
    suspend fun setMemoryPinned(id: Long, pinned: Boolean)

    /**
     * Replaces the tag list of a single chunk. Does not touch the text or
     * embedding (tag edits never require re-embedding).
     *
     * @param id Identifier of the chunk to update.
     * @param tags New tag list (empty clears all tags).
     */
    suspend fun setMemoryTags(id: Long, tags: List<String>)

    /**
     * Records that the given chunks were just retrieved into a pipeline run's
     * Long-Term Memory context: increments each chunk's use count and stamps
     * the most-recent-use time. Best-effort; a no-op for an empty [ids].
     *
     * @param ids Identifiers of the chunks that were injected.
     * @param atMillis Epoch-millis to stamp as the most-recent use time.
     */
    suspend fun recordUsage(ids: List<Long>, atMillis: Long)

    /**
     * Removes every memory chunk — including pinned entries — from the
     * underlying table. Backs the Settings → Memory → Clear destructive
     * action (typed-confirm dialog).
     */
    suspend fun deleteAllMemories()

    /**
     * Returns the ids of every stored chunk. Backs the import Merge strategy's
     * duplicate check; avoids deserialising embeddings just to read ids.
     *
     * @return All stored chunk ids as a set.
     */
    suspend fun getExistingMemoryIds(): Set<Long>

    /**
     * Bulk-inserts imported chunks (the Merge strategy), preserving each chunk's
     * id, text, embedding, timestamp, provenance, pin state, and tags. Usage
     * telemetry (`useCount` / `lastUsedAt`) is reset because it does not survive
     * a transfer.
     *
     * @param chunks The chunks to insert (already filtered against existing ids).
     * @param needsReembedding When `true`, every inserted chunk is flagged for
     *   background re-embedding because the file was exported under a different
     *   embedding provider.
     */
    suspend fun insertImportedMemories(chunks: List<MemoryChunk>, needsReembedding: Boolean)

    /**
     * Atomically replaces the entire memory table with [chunks] (the Replace
     * strategy): the wipe and the bulk insert run in a single transaction, so a
     * failure mid-insert never leaves the store empty. Field preservation and
     * the [needsReembedding] flag match [insertImportedMemories].
     *
     * @param chunks The chunks to load (must be non-empty; the caller guards
     *   against wiping with nothing to insert).
     * @param needsReembedding When `true`, every loaded chunk is flagged for
     *   background re-embedding.
     */
    suspend fun replaceImportedMemories(chunks: List<MemoryChunk>, needsReembedding: Boolean)

    /**
     * One-shot count of chunks awaiting re-embedding. Backs the cheap startup
     * re-arm check that re-schedules the background re-embed worker when a prior
     * one-off pass was lost or exhausted its retries.
     *
     * @return The number of chunks flagged `needsReembedding`.
     */
    suspend fun countMemoriesNeedingReembedding(): Int

    /**
     * Retrieves the chunks awaiting re-embedding (imported under a different
     * provider). Their stored embeddings are in an incompatible space until
     * re-computed by the background re-embed worker.
     *
     * @return Chunks flagged `needsReembedding`.
     */
    suspend fun getMemoriesNeedingReembedding(): List<MemoryChunk>

    /**
     * Writes a freshly-computed embedding back to a chunk and clears its
     * re-embedding flag, repairing an imported chunk exactly once. The text
     * and timestamp are untouched.
     *
     * @param id Identifier of the chunk to repair.
     * @param embedding The new vector embedding produced by the active provider.
     */
    suspend fun markMemoryReembedded(id: Long, embedding: FloatArray)

    /**
     * Live snapshot of the persistent aggregate stats rendered in the
     * Settings → Memory card. Emits a fresh value whenever the underlying
     * table mutates; consumers should `collectAsState` or `stateIn` it.
     *
     * Only table-level figures are reported here. The volatile AVG SCORE
     * statistic is session-scoped and lives in
     * [app.knotwork.android.domain.services.MemorySearchStatsTracker],
     * recorded by the search call sites rather than the repository.
     *
     * Thread count is best-effort: the v0.1 implementation returns `0`
     * (thread-attribution lands in a follow-up) and the UI renders a dash.
     */
    fun observeStats(): Flow<MemoryStats>
}
