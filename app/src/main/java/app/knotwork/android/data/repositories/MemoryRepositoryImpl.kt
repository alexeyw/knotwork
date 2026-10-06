package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.Converters
import app.knotwork.android.data.local.TagsCsv
import app.knotwork.android.data.local.dao.MemoryDao
import app.knotwork.android.data.local.dao.MemoryHistoryDao
import app.knotwork.android.data.local.models.MemoryChunkEntity
import app.knotwork.android.data.local.models.MemoryChunkVersionEntity
import app.knotwork.android.data.mappers.toDomainOrNull
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.MemoryStats
import app.knotwork.android.domain.models.MemorySummary
import app.knotwork.android.domain.models.MemoryWithHistory
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.services.MemoryVectorSimilarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [MemoryRepository] that uses Room as a local storage and performs
 * in-memory cosine similarity search for vector embeddings.
 *
 * @property memoryDao Reads and writes `memory_chunks`.
 * @property converters Encodes and decodes embedding BLOBs.
 * @property historyDao Loads imported chunks together with their earlier versions in
 *   one transaction.
 */
@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val memoryDao: MemoryDao,
    private val converters: Converters,
    private val historyDao: MemoryHistoryDao,
) : MemoryRepository {

    override suspend fun saveMemory(
        text: String,
        embedding: FloatArray,
        source: MemorySource,
        tags: List<String>,
    ): Long = withContext(Dispatchers.IO) {
        val embeddingBlob = converters.fromFloatArray(embedding)
            ?: throw IllegalArgumentException("Failed to serialize embedding")

        val entity = MemoryChunkEntity(
            text = text,
            embedding = embeddingBlob,
            timestamp = System.currentTimeMillis(),
            source = source,
            tagsCsv = TagsCsv.encode(tags),
        )
        memoryDao.insertMemory(entity)
    }

    override suspend fun replaceWithConsolidated(text: String, embedding: FloatArray, originalIds: List<Long>): Long =
        withContext(Dispatchers.IO) {
            val embeddingBlob = converters.fromFloatArray(embedding)
                ?: throw IllegalArgumentException("Failed to serialize embedding")

            val summary = MemoryChunkEntity(
                text = text,
                embedding = embeddingBlob,
                timestamp = System.currentTimeMillis(),
                source = MemorySource.Compaction(originalChunkIds = originalIds),
            )
            memoryDao.replaceWithConsolidated(summary = summary, originalIds = originalIds)
        }

    override suspend fun getAllMemories(): List<MemoryChunk> = withContext(Dispatchers.IO) {
        memoryDao.getAllMemories().mapNotNull { entity -> entity.toMemoryChunkOrNull() }
    }

    override suspend fun getRecentMemorySummaries(limit: Int): List<MemorySummary> = if (limit <= 0) {
        emptyList()
    } else {
        withContext(Dispatchers.IO) {
            memoryDao.getRecentMemorySummaries(limit)
        }
    }

    override suspend fun findSimilarMemories(queryEmbedding: FloatArray, limit: Int?): List<Pair<MemoryChunk, Float>> =
        withContext(Dispatchers.Default) {
            // Full-table scan, deliberately: a recency window here would make old
            // but relevant chunks invisible to retrieval regardless of similarity
            // (see the interface KDoc). The pool stays bounded by the compaction
            // hard-limit; the warning below flags the pathological case where the
            // linear scan starts to cost real time.
            val startedAtNanos = System.nanoTime()
            val pool = memoryDao.getAllMemories().mapNotNull { entity ->
                entity.toMemoryChunkOrNull()
            }
            if (pool.size > SEARCH_POOL_WARN_THRESHOLD) {
                Timber.w(
                    "Memory similarity search is scanning %d chunks; expect degraded latency",
                    pool.size,
                )
            }

            val ranked = pool.map { memory ->
                val similarity = cosineSimilarity(queryEmbedding, memory.embedding)
                memory to similarity
            }.sortedByDescending { it.second }
            Timber.d(
                "findSimilarMemories scanned %d chunks in %d ms",
                pool.size,
                (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI,
            )

            if (limit != null) ranked.take(limit) else ranked
        }

    override suspend fun compactMemory(keepLimit: Int) = withContext(Dispatchers.IO) {
        memoryDao.deleteOldestMemories(keepLimit)
    }

    override suspend fun getCompactionCandidates(olderThanMillis: Long): List<MemoryChunk> =
        withContext(Dispatchers.IO) {
            memoryDao.getCompactionCandidates(olderThanMillis).mapNotNull { entity ->
                entity.toMemoryChunkOrNull()
            }
        }

    override suspend fun countMemories(): Int = withContext(Dispatchers.IO) {
        memoryDao.countMemories()
    }

    override suspend fun deleteMemory(id: Long) = withContext(Dispatchers.IO) {
        memoryDao.deleteMemoryById(id)
    }

    override suspend fun updateMemory(id: Long, text: String, embedding: FloatArray) = withContext(Dispatchers.IO) {
        val embeddingBlob = converters.fromFloatArray(embedding)
            ?: throw IllegalArgumentException("Failed to serialize embedding")
        memoryDao.updateMemory(id = id, text = text, embedding = embeddingBlob)
    }

    override suspend fun updateMemoryWithTags(id: Long, text: String, embedding: FloatArray, tags: List<String>) =
        withContext(Dispatchers.IO) {
            val embeddingBlob = converters.fromFloatArray(embedding)
                ?: throw IllegalArgumentException("Failed to serialize embedding")
            memoryDao.updateMemoryWithTags(
                id = id,
                text = text,
                embedding = embeddingBlob,
                tagsCsv = TagsCsv.encode(tags),
            )
        }

    override suspend fun setMemoryPinned(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        memoryDao.setMemoryPinned(id = id, isPinned = pinned)
    }

    override suspend fun setMemoryTags(id: Long, tags: List<String>) = withContext(Dispatchers.IO) {
        memoryDao.setMemoryTags(id = id, tagsCsv = TagsCsv.encode(tags))
    }

    override suspend fun recordUsage(ids: List<Long>, atMillis: Long) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            memoryDao.recordUsage(ids = ids, atMillis = atMillis)
        }
    }

    override suspend fun deleteAllMemories() = withContext(Dispatchers.IO) {
        memoryDao.deleteAllMemories()
    }

    override suspend fun getExistingMemoryIds(): Set<Long> = withContext(Dispatchers.IO) {
        memoryDao.getAllIds().toSet()
    }

    override suspend fun insertImportedMemories(memories: List<MemoryWithHistory>, needsReembedding: Boolean) {
        if (memories.isEmpty()) return
        withContext(Dispatchers.IO) {
            historyDao.importChunks(memories.toImportEntities(needsReembedding), replaceAll = false)
        }
    }

    override suspend fun replaceImportedMemories(memories: List<MemoryWithHistory>, needsReembedding: Boolean) =
        withContext(Dispatchers.IO) {
            historyDao.importChunks(memories.toImportEntities(needsReembedding), replaceAll = true)
        }

    override suspend fun countMemoriesNeedingReembedding(): Int = withContext(Dispatchers.IO) {
        memoryDao.countNeedingReembedding()
    }

    override suspend fun getMemoriesNeedingReembedding(): List<MemoryChunk> = withContext(Dispatchers.IO) {
        // Deliberately lenient (no `mapNotNull` drop): the re-embed pass recomputes
        // the vector from `text` and ignores the stored embedding, so a row whose
        // embedding blob is unusable must still be returned — otherwise it
        // would be dropped here yet keep counting in `countNeedingReembedding`,
        // re-arming the worker on every startup as a permanent no-op. A bad
        // embedding falls back to an empty array (the caller never reads it).
        memoryDao.getMemoriesNeedingReembedding().map { entity ->
            MemoryChunk(
                id = entity.id,
                text = entity.text,
                embedding = converters.toFloatArray(entity.embedding) ?: FloatArray(0),
                timestamp = entity.timestamp,
                isPinned = entity.isPinned,
                source = entity.source,
                tags = TagsCsv.decode(entity.tagsCsv),
                useCount = entity.useCount,
                lastUsedAt = entity.lastUsedAt,
            )
        }
    }

    /**
     * Maps imported domain chunks to entities, preserving id / timestamp /
     * provenance / pin state / tags and resetting per-device usage telemetry.
     * Chunks whose embedding cannot be serialised are dropped (their embedding
     * is already validated non-empty by the import parser, so this is a defensive
     * guard rather than an expected path).
     */
    private fun List<MemoryWithHistory>.toImportEntities(
        needsReembedding: Boolean,
    ): List<Pair<MemoryChunkEntity, List<MemoryChunkVersionEntity>>> = mapNotNull { (chunk, history) ->
        val embeddingBlob = converters.fromFloatArray(chunk.embedding) ?: return@mapNotNull null
        val entity = MemoryChunkEntity(
            id = chunk.id,
            text = chunk.text,
            embedding = embeddingBlob,
            timestamp = chunk.timestamp,
            isPinned = chunk.isPinned,
            source = chunk.source,
            tagsCsv = TagsCsv.encode(chunk.tags),
            needsReembedding = needsReembedding,
        )
        val versions = history.map { version ->
            MemoryChunkVersionEntity(
                chunkId = 0L,
                text = version.text,
                source = version.source,
                tagsCsv = TagsCsv.encode(version.tags),
                capturedAt = version.capturedAt,
                replacedAt = version.replacedAt,
            )
        }
        entity to versions
    }

    override suspend fun markMemoryReembedded(id: Long, embedding: FloatArray) = withContext(Dispatchers.IO) {
        val embeddingBlob = converters.fromFloatArray(embedding)
            ?: throw IllegalArgumentException("Failed to serialize embedding")
        memoryDao.markReembedded(id = id, embedding = embeddingBlob)
    }

    override fun observeStats(): Flow<MemoryStats> = combine(
        memoryDao.observeChunkCount(),
        memoryDao.observeTotalBytes(),
    ) { chunkCount, totalBytes ->
        MemoryStats(
            chunkCount = chunkCount,
            totalBytes = totalBytes,
            // Thread attribution lands in a follow-up — v0.1 reports zero
            // and the UI then renders a dash for the THREADS stat cell.
            threadCount = 0,
        )
    }

    /**
     * Maps a persisted [MemoryChunkEntity] to its domain [MemoryChunk],
     * decoding the binary-encoded embedding. Returns `null` when the
     * embedding blob carries no usable vector (empty marker or corrupt
     * length) so a single bad row is skipped rather than aborting the
     * whole load.
     *
     * @return The domain chunk, or `null` if the embedding failed to decode.
     */
    private fun MemoryChunkEntity.toMemoryChunkOrNull(): MemoryChunk? = toDomainOrNull(converters)

    /**
     * Calculates the cosine similarity between two vectors.
     *
     * Delegates to the domain-level [MemoryVectorSimilarity.cosine] so the
     * search, extraction, re-ranking and compaction stages cannot drift onto
     * subtly different metrics.
     *
     * @param vectorA The first vector.
     * @param vectorB The second vector.
     * @return The cosine similarity score, ranging from -1.0 (opposite) to 1.0 (identical).
     *         Returns 0.0 if either vector has a magnitude of 0 or if their sizes don't match.
     */
    internal fun cosineSimilarity(vectorA: FloatArray, vectorB: FloatArray): Float =
        MemoryVectorSimilarity.cosine(vectorA, vectorB)

    private companion object {
        /**
         * Pool size above which [findSimilarMemories] logs a warning: the
         * linear cosine scan over every stored chunk starts to take a
         * user-noticeable amount of time around this many rows. Purely a
         * diagnostic — the search still scans the full pool, because cutting
         * candidates by recency would silently expire old facts.
         */
        const val SEARCH_POOL_WARN_THRESHOLD: Int = 5_000

        /** Nanoseconds per millisecond, for the retrieval timing log. */
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
