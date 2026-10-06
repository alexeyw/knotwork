package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemoryPendingUpdate
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.MemoryVersion

/**
 * The history side of long-term memory: finding the stored chunk a new fact may
 * restate or update, replacing a chunk in place while keeping what it said before,
 * and holding an update of a pinned chunk beside it until the user resolves the pair.
 *
 * Kept apart from [MemoryRepository], which stores, searches and curates chunks; the
 * two share the `memory_chunks` table.
 */
interface MemoryHistoryRepository {

    /**
     * The stored chunk most similar to [embedding] among those whose vector can be
     * compared with it — the candidate a newly extracted fact may restate or
     * update. A chunk awaiting a re-embed lives in another provider's vector space
     * and is never a candidate. The whole table is scanned, like
     * [findSimilarMemories]: an old fact must stay a candidate however old it is.
     *
     * @param embedding Embedding of the new fact, from the active provider.
     * @return The nearest chunk with its cosine similarity, or `null` when no chunk
     *   has a comparable vector.
     */
    suspend fun findSupersedeCandidate(embedding: FloatArray): Pair<MemoryChunk, Float>?

    /**
     * Replaces a chunk's text in place with a newer statement of the same fact and
     * keeps the replaced text as an earlier version ([MemoryVersion]) — one
     * transaction. The chunk keeps its id and its pin; its tags gain [tags]; its
     * usage counter restarts. At most [MemoryVersion.MAX_PER_CHUNK] versions stay.
     *
     * @param id Identifier of the chunk to replace.
     * @param text The newer statement.
     * @param embedding The newer statement's embedding, from the active provider.
     * @param source Provenance of the newer statement.
     * @param tags Tags the newer statement brings.
     * @return `false` when no chunk has [id] (nothing written), `true` otherwise.
     */
    suspend fun supersede(
        id: Long,
        text: String,
        embedding: FloatArray,
        source: MemorySource,
        tags: List<String> = emptyList(),
    ): Boolean

    /**
     * Stores an update of a pinned chunk, which an automatic write never replaces:
     * as a chunk of its own, linked to the pinned one ([MemoryPendingUpdate]). When
     * an update already waits on that chunk, the newer one replaces it in place, so
     * at most one update waits per pinned chunk.
     *
     * @param pinnedId Identifier of the pinned chunk being updated.
     * @param text The update.
     * @param embedding The update's embedding, from the active provider.
     * @param source Provenance of the update.
     * @param tags Tags the update brings.
     * @return Identifier of the chunk now holding the waiting update.
     */
    suspend fun saveUpdateOfPinned(
        pinnedId: Long,
        text: String,
        embedding: FloatArray,
        source: MemorySource,
        tags: List<String> = emptyList(),
    ): Long

    /**
     * Earlier versions of a chunk, the most recently replaced first.
     *
     * @param chunkId Identifier of the chunk.
     * @return Its versions; empty when it has none or does not exist.
     */
    suspend fun getHistory(chunkId: Long): List<MemoryVersion>

    /**
     * Every update waiting on a pinned chunk.
     *
     * @return The waiting updates; empty when none waits.
     */
    suspend fun getPendingUpdates(): List<MemoryPendingUpdate>

    /**
     * Every chunk's earlier versions, for an export.
     *
     * @return Versions by chunk id, the most recently replaced first; a chunk with
     *   no history has no entry.
     */
    suspend fun getAllHistory(): Map<Long, List<MemoryVersion>>
}
