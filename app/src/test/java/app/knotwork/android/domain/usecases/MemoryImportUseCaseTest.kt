package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.memoryio.MemoryJsonSerializer
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemoryExportDocument
import app.knotwork.android.domain.models.MemoryImportOutcome
import app.knotwork.android.domain.models.MemoryImportStrategy
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.MemoryVersion
import app.knotwork.android.domain.models.MemoryWithHistory
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import app.knotwork.android.domain.services.MemoryReembedScheduler
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MemoryImportUseCase] — Merge / Replace strategies, the
 * provider-mismatch re-embed scheduling, and the empty-document Replace guard.
 */
class MemoryImportUseCaseTest {

    private lateinit var repository: MemoryRepository
    private lateinit var resolver: EmbeddingProviderResolver
    private lateinit var scheduler: MemoryReembedScheduler
    private lateinit var useCase: MemoryImportUseCase

    @Before
    fun setup() {
        repository = mockk()
        resolver = mockk()
        scheduler = mockk(relaxed = true)
        useCase = MemoryImportUseCase(repository, resolver, scheduler)
        coEvery { repository.insertImportedMemories(any(), any()) } just Runs
        coEvery { repository.replaceImportedMemories(any(), any()) } just Runs
        // Default: retrieval resolves to the on-device USE provider.
        resolveActiveProviderTo(EmbeddingProvider.ID_USE)
    }

    /**
     * Stubs the resolver to report [id] as the provider retrieval will actually
     * use — the seam the import decision keys off, not the raw persisted setting.
     */
    private fun resolveActiveProviderTo(id: String) {
        val provider = mockk<EmbeddingProvider> { every { this@mockk.id } returns id }
        coEvery { resolver.resolve() } returns provider
    }

    private fun chunk(id: Long) = MemoryChunk(id = id, text = "t$id", embedding = floatArrayOf(0.1f), timestamp = id)

    private fun document(providerId: String, chunks: List<MemoryChunk>) =
        MemoryExportDocument(embeddingProviderId = providerId, exportedAt = 0L, chunks = chunks)

    @Test
    fun `parse delegates to the serializer`() {
        val doc = document("use", listOf(chunk(1)))
        val json = MemoryJsonSerializer.serialize(doc.chunks, "use", 0L)
        assertTrue(useCase.parse(json) is MemoryImportOutcome.Success)
    }

    @Test
    fun `Merge inserts only chunks whose id is not already present`() = runTest {
        coEvery { repository.getExistingMemoryIds() } returns setOf(1L, 2L)
        val captured = slot<List<MemoryWithHistory>>()
        coEvery { repository.insertImportedMemories(capture(captured), any()) } just Runs

        val doc = document("use", listOf(chunk(1), chunk(3)))
        val result = useCase.import(doc, MemoryImportStrategy.Merge)

        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(listOf(3L), captured.captured.map { it.chunk.id })
        coVerify(exactly = 0) { repository.replaceImportedMemories(any(), any()) }
    }

    @Test
    fun `given a file with history when Merge imports then each chunk carries its versions to the store`() = runTest {
        // Given
        coEvery { repository.getExistingMemoryIds() } returns setOf(1L)
        val captured = slot<List<MemoryWithHistory>>()
        coEvery { repository.insertImportedMemories(capture(captured), any()) } just Runs
        val earlier = MemoryVersion(
            id = 0L,
            chunkId = 0L,
            text = "Prefers coffee",
            source = MemorySource.Manual,
            tags = emptyList(),
            capturedAt = 1L,
            replacedAt = 2L,
        )
        val doc = MemoryExportDocument(
            embeddingProviderId = "use",
            exportedAt = 0L,
            chunks = listOf(chunk(1), chunk(3)),
            histories = listOf(listOf(earlier), listOf(earlier.copy(text = "Prefers juice"))),
        )

        // When
        useCase.import(doc, MemoryImportStrategy.Merge)

        // Then — the skipped chunk's history is skipped with it.
        assertEquals(listOf(3L), captured.captured.map { it.chunk.id })
        assertEquals(listOf("Prefers juice"), captured.captured.single().history.map { it.text })
    }

    @Test
    fun `Merge always inserts id-less chunks`() = runTest {
        coEvery { repository.getExistingMemoryIds() } returns setOf(1L)
        val captured = slot<List<MemoryWithHistory>>()
        coEvery { repository.insertImportedMemories(capture(captured), any()) } just Runs

        // Two id-less (id == 0) chunks must both be inserted (Room auto-assigns
        // fresh keys); dedupById keeps every id-0 chunk rather than collapsing.
        val doc = document("use", listOf(chunk(0), chunk(0)))
        val result = useCase.import(doc, MemoryImportStrategy.Merge)

        assertEquals(2, result.imported)
        assertEquals(2, captured.captured.size)
    }

    @Test
    fun `Replace dedupes file-internal duplicate ids so the count matches stored rows`() = runTest {
        val captured = slot<List<MemoryWithHistory>>()
        coEvery { repository.replaceImportedMemories(capture(captured), any()) } just Runs

        // Two chunks share id=5; @Insert REPLACE would collapse them to one row,
        // so imported must report 1, not 2.
        val doc = document("use", listOf(chunk(5), chunk(5), chunk(6)))
        val result = useCase.import(doc, MemoryImportStrategy.Replace)

        assertEquals(2, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(listOf(5L, 6L), captured.captured.map { it.chunk.id })
    }

    @Test
    fun `Replace transactionally replaces with every chunk`() = runTest {
        val captured = slot<List<MemoryWithHistory>>()
        coEvery { repository.replaceImportedMemories(capture(captured), any()) } just Runs

        val doc = document("use", listOf(chunk(1), chunk(2)))
        val result = useCase.import(doc, MemoryImportStrategy.Replace)

        assertEquals(2, result.imported)
        assertEquals(0, result.skipped)
        assertEquals(listOf(1L, 2L), captured.captured.map { it.chunk.id })
        coVerify(exactly = 1) { repository.replaceImportedMemories(any(), any()) }
    }

    @Test
    fun `Replace with an empty document leaves the store untouched`() = runTest {
        val doc = document("use", emptyList())

        val result = useCase.import(doc, MemoryImportStrategy.Replace)

        assertEquals(0, result.imported)
        coVerify(exactly = 0) { repository.replaceImportedMemories(any(), any()) }
        coVerify(exactly = 0) { repository.insertImportedMemories(any(), any()) }
        verify(exactly = 0) { scheduler.schedule() }
    }

    @Test
    fun `provider mismatch flags re-embedding and schedules the background pass`() = runTest {
        val needsFlag = slot<Boolean>()
        coEvery { repository.replaceImportedMemories(any(), capture(needsFlag)) } just Runs

        val result = useCase.import(
            document("openai_3_small", listOf(chunk(1))),
            MemoryImportStrategy.Replace,
        )

        assertTrue(result.needsReembedding)
        assertTrue(needsFlag.captured)
        verify(exactly = 1) { scheduler.schedule() }
    }

    @Test
    fun `matching provider does not schedule a re-embed`() = runTest {
        coEvery { repository.replaceImportedMemories(any(), capture(slot())) } just Runs

        val result = useCase.import(
            document("use", listOf(chunk(1))),
            MemoryImportStrategy.Replace,
        )

        assertFalse(result.needsReembedding)
        verify(exactly = 0) { scheduler.schedule() }
    }

    @Test
    fun `mismatch with nothing inserted does not schedule`() = runTest {
        coEvery { repository.getExistingMemoryIds() } returns setOf(1L)

        // All incoming chunks are duplicates, so nothing lands and no re-embed
        // is needed despite the provider mismatch.
        val result = useCase.import(
            document("openai_3_small", listOf(chunk(1))),
            MemoryImportStrategy.Merge,
        )

        assertEquals(0, result.imported)
        assertFalse(result.needsReembedding)
        verify(exactly = 0) { scheduler.schedule() }
    }

    @Test
    fun `flags re-embedding against the resolved provider when the selection falls back`() = runTest {
        // The user selected openai_3_small but it is unavailable (no API key), so
        // the resolver falls back to USE — exactly what queries embed with. An
        // openai_3_small-stamped file therefore IS a mismatch and must be flagged,
        // even though the raw persisted setting would have "matched" the file.
        resolveActiveProviderTo(EmbeddingProvider.ID_USE)
        coEvery { repository.replaceImportedMemories(any(), capture(slot())) } just Runs

        val result = useCase.import(
            document(EmbeddingProvider.ID_OPENAI_3_SMALL, listOf(chunk(1))),
            MemoryImportStrategy.Replace,
        )

        assertTrue(result.needsReembedding)
        verify(exactly = 1) { scheduler.schedule() }
    }

    @Test
    fun `does not flag re-embedding when the resolved provider matches the file`() = runTest {
        // Selection resolves to openai_3_small (key configured) and the file was
        // exported under the same provider, so the vectors are already compatible.
        resolveActiveProviderTo(EmbeddingProvider.ID_OPENAI_3_SMALL)
        coEvery { repository.replaceImportedMemories(any(), capture(slot())) } just Runs

        val result = useCase.import(
            document(EmbeddingProvider.ID_OPENAI_3_SMALL, listOf(chunk(1))),
            MemoryImportStrategy.Replace,
        )

        assertFalse(result.needsReembedding)
        verify(exactly = 0) { scheduler.schedule() }
    }

    @Test
    fun `isProviderMismatched follows the resolved provider not the file stamp`() = runTest {
        resolveActiveProviderTo(EmbeddingProvider.ID_USE)

        assertTrue(useCase.isProviderMismatched(document(EmbeddingProvider.ID_OPENAI_3_SMALL, emptyList())))
        assertFalse(useCase.isProviderMismatched(document(EmbeddingProvider.ID_USE, emptyList())))
    }
}
