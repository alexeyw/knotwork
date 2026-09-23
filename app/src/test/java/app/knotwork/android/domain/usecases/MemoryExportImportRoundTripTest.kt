package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemoryExportDocument
import app.knotwork.android.domain.models.MemoryImportOutcome
import app.knotwork.android.domain.models.MemoryImportStrategy
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.MemoryStats
import app.knotwork.android.domain.models.MemorySummary
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import app.knotwork.android.domain.services.MemoryReembedScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * End-to-end round-trip coverage for the memory export/import feature
 * Export → wipe → import (Replace) → assert the store is identical except for
 * the pins, which a file never carries back (security audit 07/F3). Exercises [ExportMemoryBaseUseCase] and
 * [MemoryImportUseCase] against a real in-memory store so the serializer, the
 * strategy, and the id/provenance preservation all compose.
 */
class MemoryExportImportRoundTripTest {

    private val scheduler = mockk<MemoryReembedScheduler>(relaxed = true)

    // Retrieval resolves to the on-device USE provider; the round-trip files are
    // stamped with the same provider, so no re-embedding is expected.
    private val resolver = mockk<EmbeddingProviderResolver> {
        coEvery { resolve() } returns mockk { every { id } returns EmbeddingProvider.ID_USE }
    }

    @Test
    fun `export then wipe then import Replace reproduces the original store except its pins`() = runTest {
        val store = InMemoryMemoryStore()
        val original = listOf(
            MemoryChunk(
                id = 1,
                text = "I prefer dark mode",
                embedding = floatArrayOf(0.1f, 0.2f, 0.3f),
                timestamp = 1_000L,
                isPinned = true,
                source = MemorySource.Manual,
                tags = listOf("preference"),
            ),
            MemoryChunk(
                id = 2,
                text = "User lives in Berlin",
                embedding = floatArrayOf(0.4f, 0.5f),
                timestamp = 2_000L,
                source = MemorySource.ChatSession("sess-1"),
            ),
        )
        store.seed(original)

        val settings = mockk<SettingsRepository>()
        every { settings.activeEmbeddingProviderId } returns flowOf(EmbeddingProvider.ID_USE)
        val export = ExportMemoryBaseUseCase(store, settings)
        val importUseCase = MemoryImportUseCase(store, resolver, scheduler)

        val out = ByteArrayOutputStream()
        export(out)

        store.deleteAllMemories()
        assertTrue(store.getAllMemories().isEmpty())

        val outcome = importUseCase.parse(out.toString(Charsets.UTF_8.name()))
        assertTrue(outcome is MemoryImportOutcome.Success)
        val result = importUseCase.import(
            (outcome as MemoryImportOutcome.Success).document,
            MemoryImportStrategy.Replace,
        )

        assertEquals(2, result.imported)
        // Deliberately not a byte-for-byte round-trip: an import cannot pin, even
        // the user's own backup. The dialog reports the dropped pin instead.
        assertEquals(1, outcome.document.pinnedInFile)
        assertEquals(
            original.map { it.copy(isPinned = false) }.sortedBy { it.id },
            store.getAllMemories().sortedBy { it.id },
        )
    }

    @Test
    fun `import Merge into a populated store keeps existing chunks and skips duplicates`() = runTest {
        val store = InMemoryMemoryStore()
        store.seed(
            listOf(
                MemoryChunk(id = 1, text = "kept", embedding = floatArrayOf(0.1f), timestamp = 1L),
            ),
        )
        val incoming = listOf(
            MemoryChunk(id = 1, text = "dup", embedding = floatArrayOf(0.9f), timestamp = 9L),
            MemoryChunk(id = 2, text = "fresh", embedding = floatArrayOf(0.2f), timestamp = 2L),
        )

        val importUseCase = MemoryImportUseCase(store, resolver, scheduler)
        val document = MemoryExportDocument(EmbeddingProvider.ID_USE, 0L, incoming)

        val result = importUseCase.import(document, MemoryImportStrategy.Merge)

        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
        // The original chunk #1 ("kept") is untouched; only #2 ("fresh") was added.
        assertEquals(setOf(1L, 2L), store.getAllMemories().map { it.id }.toSet())
        assertEquals("kept", store.getAllMemories().single { it.id == 1L }.text)
    }
}

/**
 * Minimal in-memory [MemoryRepository] for round-trip tests. Only the
 * export/import call paths are backed by real behaviour; the rest throw so an
 * accidental dependency surfaces loudly.
 */
private class InMemoryMemoryStore : MemoryRepository {
    private val rows = mutableListOf<MemoryChunk>()

    fun seed(chunks: List<MemoryChunk>) {
        rows.clear()
        rows.addAll(chunks)
    }

    override suspend fun getAllMemories(): List<MemoryChunk> = rows.toList()

    override suspend fun getExistingMemoryIds(): Set<Long> = rows.map { it.id }.toSet()

    override suspend fun insertImportedMemories(chunks: List<MemoryChunk>, needsReembedding: Boolean) {
        rows.addAll(chunks)
    }

    override suspend fun replaceImportedMemories(chunks: List<MemoryChunk>, needsReembedding: Boolean) {
        rows.clear()
        rows.addAll(chunks)
    }

    override suspend fun deleteAllMemories() {
        rows.clear()
    }

    override suspend fun saveMemory(
        text: String,
        embedding: FloatArray,
        source: MemorySource,
        tags: List<String>,
    ): Long = throw NotImplementedError()

    override suspend fun replaceWithConsolidated(text: String, embedding: FloatArray, originalIds: List<Long>): Long =
        throw NotImplementedError()

    override suspend fun getRecentMemorySummaries(limit: Int): List<MemorySummary> = throw NotImplementedError()

    override suspend fun findSimilarMemories(queryEmbedding: FloatArray, limit: Int?): List<Pair<MemoryChunk, Float>> =
        throw NotImplementedError()

    override suspend fun compactMemory(keepLimit: Int) = throw NotImplementedError()

    override suspend fun getCompactionCandidates(olderThanMillis: Long): List<MemoryChunk> = throw NotImplementedError()

    override suspend fun countMemories(): Int = rows.size

    override suspend fun deleteMemory(id: Long) = throw NotImplementedError()

    override suspend fun updateMemory(id: Long, text: String, embedding: FloatArray) = throw NotImplementedError()

    override suspend fun updateMemoryWithTags(id: Long, text: String, embedding: FloatArray, tags: List<String>) =
        throw NotImplementedError()

    override suspend fun setMemoryPinned(id: Long, pinned: Boolean) = throw NotImplementedError()

    override suspend fun setMemoryTags(id: Long, tags: List<String>) = throw NotImplementedError()

    override suspend fun recordUsage(ids: List<Long>, atMillis: Long) = throw NotImplementedError()

    override suspend fun countMemoriesNeedingReembedding(): Int = 0

    override suspend fun getMemoriesNeedingReembedding(): List<MemoryChunk> = throw NotImplementedError()

    override suspend fun markMemoryReembedded(id: Long, embedding: FloatArray) = throw NotImplementedError()

    override fun observeStats(): Flow<MemoryStats> = throw NotImplementedError()
}
