package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.Converters
import app.knotwork.android.data.local.TagsCsv
import app.knotwork.android.data.local.dao.MemoryDao
import app.knotwork.android.data.local.dao.MemoryHistoryDao
import app.knotwork.android.data.local.models.MemoryChunkEntity
import app.knotwork.android.data.local.models.MemoryChunkVersionEntity
import app.knotwork.android.data.mappers.toDomainOrNull
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemoryPendingUpdate
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.MemoryVersion
import app.knotwork.android.domain.repositories.MemoryHistoryRepository
import app.knotwork.android.domain.services.MemoryVectorSimilarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [MemoryHistoryRepository]: candidate search over `memory_chunks`
 * through [MemoryDao], and the in-place replacement, version history and waiting
 * updates through [MemoryHistoryDao], whose multi-row writes are single transactions.
 *
 * @property memoryDao Reads the stored chunks for the candidate search.
 * @property historyDao Replaces chunks, keeps their versions, links waiting updates.
 * @property converters Encodes and decodes embedding BLOBs.
 */
@Singleton
class MemoryHistoryRepositoryImpl @Inject constructor(
    private val memoryDao: MemoryDao,
    private val historyDao: MemoryHistoryDao,
    private val converters: Converters,
) : MemoryHistoryRepository {

    override suspend fun findSupersedeCandidate(embedding: FloatArray): Pair<MemoryChunk, Float>? =
        withContext(Dispatchers.Default) {
            memoryDao.getAllMemories()
                .asSequence()
                .filterNot { it.needsReembedding }
                .mapNotNull { entity -> entity.toDomainOrNull(converters) }
                .map { chunk -> chunk to MemoryVectorSimilarity.cosine(embedding, chunk.embedding) }
                .maxByOrNull { it.second }
        }

    override suspend fun supersede(
        id: Long,
        text: String,
        embedding: FloatArray,
        source: MemorySource,
        tags: List<String>,
    ): Boolean = withContext(Dispatchers.IO) {
        historyDao.supersede(
            id = id,
            text = text,
            embedding = encode(embedding),
            source = source,
            tags = tags,
            nowMillis = System.currentTimeMillis(),
            keepVersions = MemoryVersion.MAX_PER_CHUNK,
        )
    }

    override suspend fun saveUpdateOfPinned(
        pinnedId: Long,
        text: String,
        embedding: FloatArray,
        source: MemorySource,
        tags: List<String>,
    ): Long = withContext(Dispatchers.IO) {
        historyDao.saveUpdateOfPinned(
            pinnedChunkId = pinnedId,
            update = MemoryChunkEntity(
                text = text,
                embedding = encode(embedding),
                timestamp = System.currentTimeMillis(),
                source = source,
                tagsCsv = TagsCsv.encode(tags),
            ),
            keepVersions = MemoryVersion.MAX_PER_CHUNK,
        )
    }

    override suspend fun getHistory(chunkId: Long): List<MemoryVersion> = withContext(Dispatchers.IO) {
        historyDao.getVersions(chunkId).map { it.toDomain() }
    }

    override suspend fun getAllHistory(): Map<Long, List<MemoryVersion>> = withContext(Dispatchers.IO) {
        historyDao.getAllVersions().groupBy(keySelector = { it.chunkId }, valueTransform = { it.toDomain() })
    }

    override suspend fun getPendingUpdates(): List<MemoryPendingUpdate> = withContext(Dispatchers.IO) {
        historyDao.getPendingUpdates().map { link ->
            MemoryPendingUpdate(updateChunkId = link.updateChunkId, pinnedChunkId = link.pinnedChunkId)
        }
    }

    override suspend fun applyWaitingUpdate(chunkId: Long): Long? = withContext(Dispatchers.IO) {
        historyDao.applyPendingUpdate(chunkId, System.currentTimeMillis(), MemoryVersion.MAX_PER_CHUNK)
    }

    override suspend fun discardWaitingUpdate(chunkId: Long): Long? = withContext(Dispatchers.IO) {
        historyDao.discardPendingUpdate(chunkId)
    }

    override suspend fun setPinned(chunkId: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        historyDao.setPinnedSettlingPair(chunkId, pinned, System.currentTimeMillis(), MemoryVersion.MAX_PER_CHUNK)
    }

    override suspend fun deleteVersion(versionId: Long) = withContext(Dispatchers.IO) {
        historyDao.deleteVersion(versionId)
    }

    private fun encode(embedding: FloatArray): ByteArray =
        converters.fromFloatArray(embedding) ?: throw IllegalArgumentException("Failed to serialize embedding")

    private fun MemoryChunkVersionEntity.toDomain(): MemoryVersion = MemoryVersion(
        id = id,
        chunkId = chunkId,
        text = text,
        source = source,
        tags = TagsCsv.decode(tagsCsv),
        capturedAt = capturedAt,
        replacedAt = replacedAt,
    )
}
