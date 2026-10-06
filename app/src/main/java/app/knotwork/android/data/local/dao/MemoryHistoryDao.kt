package app.knotwork.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.knotwork.android.data.local.TagsCsv
import app.knotwork.android.data.local.models.MemoryChunkEntity
import app.knotwork.android.data.local.models.MemoryChunkVersionEntity
import app.knotwork.android.data.local.models.MemoryPendingUpdateEntity
import app.knotwork.android.domain.models.MemorySource

/**
 * Data Access Object for the history of long-term memory chunks: replacing a chunk's
 * text in place while keeping the replaced text as an earlier version
 * (`memory_chunk_history`), and storing an update of a pinned chunk beside it
 * (`memory_pending_updates`). Each write that touches more than one row is a single
 * transaction.
 */
@Dao
interface MemoryHistoryDao {

    /**
     * Inserts a new memory chunk.
     *
     * @param chunk The chunk to insert.
     * @return The row id of the inserted chunk.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChunk(chunk: MemoryChunkEntity): Long

    /**
     * Reads one chunk by id, embedding included.
     *
     * @param id Identifier of the chunk.
     * @return The chunk, or `null` when no row has that id.
     */
    @Query("SELECT * FROM memory_chunks WHERE id = :id")
    suspend fun getMemoryById(id: Long): MemoryChunkEntity?

    /**
     * Writes one earlier version of a chunk.
     *
     * @param version The version to store.
     * @return The version's row id.
     */
    @Insert
    suspend fun insertVersion(version: MemoryChunkVersionEntity): Long

    /**
     * Overwrites a chunk with a newer statement of its fact: text, embedding,
     * source, tags and timestamp are the new statement's; the usage counter and
     * last-use stamp restart, since they described a text that is gone; the
     * re-embed flag clears, since the embedding was just computed by the active
     * provider. The pin is left as it is.
     *
     * @param id Identifier of the chunk to overwrite.
     * @param text The new text.
     * @param embedding The new text's embedding in its stored BLOB form.
     * @param source Provenance of the new text.
     * @param tagsCsv The chunk's tags after the overwrite.
     * @param timestamp When the new statement was stored, epoch millis.
     */
    @Query(
        "UPDATE memory_chunks SET text = :text, embedding = :embedding, source = :source, " +
            "tagsCsv = :tagsCsv, timestamp = :timestamp, useCount = 0, lastUsedAt = NULL, " +
            "needsReembedding = 0 WHERE id = :id",
    )
    suspend fun overwriteChunk(
        id: Long,
        text: String,
        embedding: ByteArray,
        source: MemorySource,
        tagsCsv: String,
        timestamp: Long,
    )

    /**
     * Keeps only the [keep] most recently replaced versions of a chunk.
     *
     * @param chunkId Identifier of the chunk whose history is trimmed.
     * @param keep How many of the newest versions survive.
     */
    @Query(
        "DELETE FROM memory_chunk_history WHERE chunkId = :chunkId AND id NOT IN " +
            "(SELECT id FROM memory_chunk_history WHERE chunkId = :chunkId " +
            "ORDER BY replacedAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimVersions(chunkId: Long, keep: Int)

    /**
     * Replaces a chunk's text in place with a newer statement of the same fact and
     * keeps the text it replaces as an earlier version, in one transaction: a crash
     * between the two can neither lose the old text nor leave it twice.
     *
     * The chunk keeps its id, so everything that refers to it — a compaction
     * summary's provenance, an open detail sheet, a merge import's id check — still
     * finds it. Its tags become the union of its own and [tags]: a tag the user
     * added is not lost to an automatic write.
     *
     * @param id Identifier of the chunk to replace.
     * @param text The newer statement.
     * @param embedding The newer statement's embedding in its stored BLOB form.
     * @param source Provenance of the newer statement.
     * @param tags Tags the newer statement brings.
     * @param nowMillis When the replacement happens, epoch millis.
     * @param keepVersions How many earlier versions the chunk keeps at most.
     * @return `false` when no chunk has [id] (nothing was written), `true` otherwise.
     */
    @Transaction
    suspend fun supersede(
        id: Long,
        text: String,
        embedding: ByteArray,
        source: MemorySource,
        tags: List<String>,
        nowMillis: Long,
        keepVersions: Int,
    ): Boolean {
        val current = getMemoryById(id) ?: return false
        insertVersion(
            MemoryChunkVersionEntity(
                chunkId = id,
                text = current.text,
                source = current.source,
                tagsCsv = current.tagsCsv,
                capturedAt = current.timestamp,
                replacedAt = nowMillis,
            ),
        )
        val mergedTags = (TagsCsv.decode(current.tagsCsv) + tags).distinct()
        overwriteChunk(
            id = id,
            text = text,
            embedding = embedding,
            source = source,
            tagsCsv = TagsCsv.encode(mergedTags),
            timestamp = nowMillis,
        )
        trimVersions(chunkId = id, keep = keepVersions)
        return true
    }

    /**
     * Earlier versions of a chunk, the most recently replaced first.
     *
     * @param chunkId Identifier of the chunk.
     * @return Its versions; empty when it has none.
     */
    @Query("SELECT * FROM memory_chunk_history WHERE chunkId = :chunkId ORDER BY replacedAt DESC, id DESC")
    suspend fun getVersions(chunkId: Long): List<MemoryChunkVersionEntity>

    /**
     * Links a chunk to the pinned chunk it updates.
     *
     * @param link The link to store.
     */
    @Insert
    suspend fun insertPendingUpdate(link: MemoryPendingUpdateEntity)

    /**
     * The update waiting on a pinned chunk, if any.
     *
     * @param pinnedChunkId Identifier of the pinned chunk.
     * @return Id of the chunk holding its waiting update, or `null`.
     */
    @Query("SELECT updateChunkId FROM memory_pending_updates WHERE pinnedChunkId = :pinnedChunkId")
    suspend fun getPendingUpdateFor(pinnedChunkId: Long): Long?

    /**
     * Every waiting update and the pinned chunk it updates.
     *
     * @return All links; empty when no update waits.
     */
    @Query("SELECT * FROM memory_pending_updates")
    suspend fun getPendingUpdates(): List<MemoryPendingUpdateEntity>

    /**
     * Stores an update of a pinned chunk, which is never replaced by an automatic
     * write. When an update already waits on that chunk, the newer one replaces it
     * in place — the waiting chunk keeps the older update as an earlier version — so
     * at most one update waits per pinned chunk. Otherwise [update] is inserted and
     * linked. One transaction either way.
     *
     * @param pinnedChunkId Identifier of the pinned chunk being updated.
     * @param update The new chunk to insert when no update waits yet.
     * @param keepVersions How many earlier versions a chunk keeps at most.
     * @return Id of the chunk now holding the waiting update.
     */
    @Transaction
    suspend fun saveUpdateOfPinned(pinnedChunkId: Long, update: MemoryChunkEntity, keepVersions: Int): Long {
        val waiting = getPendingUpdateFor(pinnedChunkId)
        if (waiting != null) {
            supersede(
                id = waiting,
                text = update.text,
                embedding = update.embedding,
                source = update.source,
                tags = TagsCsv.decode(update.tagsCsv),
                nowMillis = update.timestamp,
                keepVersions = keepVersions,
            )
            return waiting
        }
        val id = insertChunk(update)
        insertPendingUpdate(MemoryPendingUpdateEntity(updateChunkId = id, pinnedChunkId = pinnedChunkId))
        return id
    }
}
