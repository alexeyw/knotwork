package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.Converters
import app.knotwork.android.data.local.EmbeddingBlobCodec
import app.knotwork.android.data.local.dao.MemoryDao
import app.knotwork.android.data.local.dao.MemoryHistoryDao
import app.knotwork.android.data.local.models.MemoryChunkEntity
import app.knotwork.android.data.local.models.MemoryChunkVersionEntity
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.MemorySummary
import app.knotwork.android.domain.models.MemoryWithHistory
import app.knotwork.android.domain.services.MemoryVectorSimilarity
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MemoryRepositoryImpl].
 */
class MemoryRepositoryImplTest {

    private lateinit var memoryDao: MemoryDao
    private lateinit var historyDao: MemoryHistoryDao
    private lateinit var converters: Converters
    private lateinit var repository: MemoryRepositoryImpl

    @Before
    fun setup() {
        memoryDao = mockk(relaxed = true)
        historyDao = mockk(relaxed = true)
        converters = Converters()
        repository = MemoryRepositoryImpl(memoryDao, converters, historyDao)
    }

    /** Shorthand: encodes the given components into the stored BLOB form. */
    private fun emb(vararg values: Float): ByteArray = EmbeddingBlobCodec.encode(floatArrayOf(*values))

    @Test
    fun `given any vector pair when cosineSimilarity then it matches the shared memory metric`() {
        // The search hot path must not drift onto its own metric: the exhaustive
        // cases live in MemoryVectorSimilarityTest, this pins the delegation.
        val pairs = listOf(
            floatArrayOf(1f, 2f, 3f) to floatArrayOf(1f, 2f, 3f),
            floatArrayOf(1f, 0f, 0f) to floatArrayOf(0f, 1f, 0f),
            floatArrayOf(1f, 1f) to floatArrayOf(-1f, -1f),
            floatArrayOf() to floatArrayOf(),
            floatArrayOf(0f, 0f) to floatArrayOf(0f, 0f),
            floatArrayOf(1f, 0f) to floatArrayOf(1f, 0f, 0f),
        )

        pairs.forEach { (vectorA, vectorB) ->
            assertEquals(
                MemoryVectorSimilarity.cosine(vectorA, vectorB),
                repository.cosineSimilarity(vectorA, vectorB),
                0f,
            )
        }
    }

    @Test
    fun `findSimilarMemories scans the full table and returns correct pairs`() = kotlinx.coroutines.test.runTest {
        val queryEmbedding = floatArrayOf(1f, 0f)
        val entity1 = MemoryChunkEntity(1, "Text 1", emb(1.0f, 0.0f), 1000L)
        val entity2 = MemoryChunkEntity(2, "Text 2", emb(0.0f, 1.0f), 2000L)

        io.mockk.coEvery { memoryDao.getAllMemories() } returns listOf(entity1, entity2)

        val results = repository.findSimilarMemories(queryEmbedding, limit = 2)

        assertEquals(2, results.size)
        assertEquals(1L, results[0].first.id) // Most similar first
        assertEquals(1.0f, results[0].second, 0.001f)
        assertEquals(2L, results[1].first.id)
        assertEquals(0.0f, results[1].second, 0.001f)
    }

    @Test
    fun `given three chunks when query is close to one then that chunk ranks first by real cosine`() =
        kotlinx.coroutines.test.runTest {
            // Query points along the first axis; only the "Berlin" chunk lies
            // near it, so the real cosine math must surface it on top with a
            // near-1.0 score while the orthogonal/noisy chunks score far lower.
            val queryEmbedding = floatArrayOf(1f, 0f, 0f)
            val berlin = MemoryChunkEntity(1, "user lives in Berlin", emb(0.9f, 0.1f, 0.0f), 3000L)
            val coffee = MemoryChunkEntity(2, "user likes coffee", emb(0.0f, 1.0f, 0.0f), 2000L)
            val sky = MemoryChunkEntity(3, "the sky is blue", emb(0.2f, 0.2f, 0.95f), 1000L)

            io.mockk.coEvery { memoryDao.getAllMemories() } returns listOf(coffee, sky, berlin)

            val results = repository.findSimilarMemories(queryEmbedding, limit = 3)

            assertEquals(3, results.size)
            // Highest-similarity chunk first.
            assertEquals(1L, results[0].first.id)
            assertEquals("user lives in Berlin", results[0].first.text)
            assertEquals(0.994f, results[0].second, 0.01f)
            // The orthogonal "coffee" chunk scores ~0 and ranks last.
            assertEquals(2L, results[2].first.id)
            assertEquals(0.0f, results[2].second, 0.001f)
        }

    @Test
    fun `given chunk older than any recency window when similarity is high then it is found`() =
        kotlinx.coroutines.test.runTest {
            // Given — an ancient chunk (epoch-adjacent timestamp) surrounded by
            // thousands of fresher rows. Under the old recency-window pool it
            // would never have been loaded for scoring; the full-table scan
            // must surface it as the top hit purely on cosine similarity.
            val queryEmbedding = floatArrayOf(1f, 0f)
            val ancientRelevant = MemoryChunkEntity(1L, "user was born in Riga", emb(1.0f, 0.0f), 1L)
            val freshNoise = (2L..1_502L).map { id ->
                MemoryChunkEntity(id, "noise $id", emb(0.0f, 1.0f), 1_000_000L + id)
            }
            io.mockk.coEvery { memoryDao.getAllMemories() } returns freshNoise + ancientRelevant

            // When
            val results = repository.findSimilarMemories(queryEmbedding, limit = 1)

            // Then — the old chunk wins despite being older than every other row.
            assertEquals(1L, results.single().first.id)
            assertEquals(1.0f, results.single().second, 0.001f)
        }

    @Test
    fun `findSimilarMemories with null limit returns the entire scored pool`() = kotlinx.coroutines.test.runTest {
        val queryEmbedding = floatArrayOf(1f, 0f)
        val entities = (1L..7L).map { id ->
            MemoryChunkEntity(id, "text $id", emb(1.0f, 0.0f), id)
        }
        io.mockk.coEvery { memoryDao.getAllMemories() } returns entities

        val results = repository.findSimilarMemories(queryEmbedding)

        assertEquals(7, results.size)
    }

    @Test
    fun `findSimilarMemories skips rows whose blob carries no usable embedding`() = kotlinx.coroutines.test.runTest {
        // The empty blob is the migration's marker for a legacy row whose
        // string embedding could not be parsed; an odd-length blob is
        // outright corrupt. Both must be invisible to retrieval without
        // aborting the scan.
        val healthy = MemoryChunkEntity(1, "healthy", emb(1.0f, 0.0f), 100L)
        val emptyMarker = MemoryChunkEntity(2, "unparseable legacy row", ByteArray(0), 200L)
        val corruptLength = MemoryChunkEntity(3, "corrupt blob", ByteArray(5), 300L)
        io.mockk.coEvery { memoryDao.getAllMemories() } returns
            listOf(healthy, emptyMarker, corruptLength)

        val results = repository.findSimilarMemories(floatArrayOf(1f, 0f), limit = null)

        assertEquals(listOf(1L), results.map { it.first.id })
    }

    @Test
    fun `findSimilarMemories ranks correctly over a 5000-chunk synthetic pool`() = kotlinx.coroutines.test.runTest {
        // Synthetic pool at the documented warn-threshold scale (512-dim
        // vectors). Guards correctness of the decode + rank path at the
        // size the BLOB storage change targets; the timing of this exact
        // scenario is what the PR-description measurement quotes.
        val dimensions = 512
        val needleId = 4_321L
        val entities = (1L..5_000L).map { id ->
            val vector = FloatArray(dimensions) { index ->
                if (id == needleId) {
                    if (index == 0) 1f else 0f
                } else {
                    // Orthogonal-ish noise: zero first component, varying tail.
                    if (index == ((id + index) % (dimensions - 1)).toInt() + 1) 1f else 0.01f
                }
            }
            MemoryChunkEntity(id, "chunk $id", EmbeddingBlobCodec.encode(vector), id)
        }
        io.mockk.coEvery { memoryDao.getAllMemories() } returns entities
        val query = FloatArray(dimensions) { index -> if (index == 0) 1f else 0f }

        val results = repository.findSimilarMemories(query, limit = 3)

        assertEquals(needleId, results.first().first.id)
        assertEquals(1.0f, results.first().second, 0.001f)
    }

    @Test
    fun `compactMemory calls dao deleteOldestMemories`() = kotlinx.coroutines.test.runTest {
        repository.compactMemory(500)
        io.mockk.coVerify(exactly = 1) { memoryDao.deleteOldestMemories(500) }
    }

    @Test
    fun `getRecentMemorySummaries forwards limit to dao and returns projections`() = kotlinx.coroutines.test.runTest {
        val expected = listOf(
            MemorySummary(id = 2L, text = "newer", timestamp = 200L),
            MemorySummary(id = 1L, text = "older", timestamp = 100L),
        )
        io.mockk.coEvery { memoryDao.getRecentMemorySummaries(2) } returns expected

        val result = repository.getRecentMemorySummaries(2)

        assertEquals(expected, result)
        io.mockk.coVerify(exactly = 1) { memoryDao.getRecentMemorySummaries(2) }
    }

    @Test
    fun `getRecentMemorySummaries with non positive limit short-circuits without dao call`() =
        kotlinx.coroutines.test.runTest {
            val result = repository.getRecentMemorySummaries(0)

            assertEquals(emptyList<MemorySummary>(), result)
            io.mockk.coVerify(exactly = 0) { memoryDao.getRecentMemorySummaries(any()) }
        }

    @Test
    fun `updateMemory serializes embedding and forwards to dao`() = kotlinx.coroutines.test.runTest {
        val newEmbedding = floatArrayOf(0.25f, -0.5f, 0.75f)
        val expectedSerialized = converters.fromFloatArray(newEmbedding)

        repository.updateMemory(id = 42L, text = "edited text", embedding = newEmbedding)

        io.mockk.coVerify(exactly = 1) {
            memoryDao.updateMemory(id = 42L, text = "edited text", embedding = expectedSerialized!!)
        }
    }

    @Test
    fun `replaceWithConsolidated writes the summary and its originals in one dao transaction`() =
        kotlinx.coroutines.test.runTest {
            val embedding = floatArrayOf(0.5f, 0.5f)
            val summarySlot = io.mockk.slot<MemoryChunkEntity>()
            io.mockk.coEvery {
                memoryDao.replaceWithConsolidated(capture(summarySlot), any())
            } returns 77L

            val id = repository.replaceWithConsolidated(
                text = "merged fact",
                embedding = embedding,
                originalIds = listOf(1L, 2L, 3L),
            )

            assertEquals(77L, id)
            // Provenance records exactly the chunks the transaction removes, so
            // the ids stay a usable link rather than a historical note.
            assertEquals(MemorySource.Compaction(originalChunkIds = listOf(1L, 2L, 3L)), summarySlot.captured.source)
            assertEquals("merged fact", summarySlot.captured.text)
            assertArrayEquals(embedding, converters.toFloatArray(summarySlot.captured.embedding), 1e-6f)
            io.mockk.coVerify(exactly = 1) { memoryDao.replaceWithConsolidated(any(), listOf(1L, 2L, 3L)) }
            // Never the separable save-then-delete pair it replaces.
            io.mockk.coVerify(exactly = 0) { memoryDao.insertMemory(any()) }
            io.mockk.coVerify(exactly = 0) { memoryDao.deleteMemoryById(any()) }
        }

    @Test
    fun `setMemoryPinned forwards pinned true to dao`() = kotlinx.coroutines.test.runTest {
        repository.setMemoryPinned(id = 7L, pinned = true)
        io.mockk.coVerify(exactly = 1) { memoryDao.setMemoryPinned(id = 7L, isPinned = true) }
    }

    @Test
    fun `setMemoryPinned forwards pinned false to dao`() = kotlinx.coroutines.test.runTest {
        repository.setMemoryPinned(id = 7L, pinned = false)
        io.mockk.coVerify(exactly = 1) { memoryDao.setMemoryPinned(id = 7L, isPinned = false) }
    }

    @Test
    fun `getCompactionCandidates forwards cutoff and maps entities`() = kotlinx.coroutines.test.runTest {
        val a = MemoryChunkEntity(1, "older fact", emb(1.0f, 0.0f), 500L)
        val b = MemoryChunkEntity(2, "another fact", emb(0.0f, 1.0f), 400L)
        io.mockk.coEvery { memoryDao.getCompactionCandidates(1000L) } returns listOf(a, b)

        val result = repository.getCompactionCandidates(olderThanMillis = 1000L)

        assertEquals(2, result.size)
        assertEquals(listOf(1L, 2L), result.map { it.id })
        io.mockk.coVerify(exactly = 1) { memoryDao.getCompactionCandidates(1000L) }
    }

    @Test
    fun `countMemories forwards to dao`() = kotlinx.coroutines.test.runTest {
        io.mockk.coEvery { memoryDao.countMemories() } returns 42

        val result = repository.countMemories()

        assertEquals(42, result)
        io.mockk.coVerify(exactly = 1) { memoryDao.countMemories() }
    }

    @Test
    fun `getExistingMemoryIds returns the dao id set`() = kotlinx.coroutines.test.runTest {
        io.mockk.coEvery { memoryDao.getAllIds() } returns listOf(1L, 2L, 3L)

        val ids = repository.getExistingMemoryIds()

        assertEquals(setOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `insertImportedMemories preserves id, provenance, pin state and flags re-embedding`() =
        kotlinx.coroutines.test.runTest {
            val captured = io.mockk.slot<List<Pair<MemoryChunkEntity, List<MemoryChunkVersionEntity>>>>()
            io.mockk.coEvery { historyDao.importChunks(capture(captured), replaceAll = false) } returns Unit
            val chunk = MemoryChunk(
                id = 9,
                text = "imported",
                embedding = floatArrayOf(0.1f, 0.2f),
                timestamp = 1_234L,
                isPinned = true,
                source = MemorySource.Manual,
                tags = listOf("preference"),
            )

            repository.insertImportedMemories(listOf(MemoryWithHistory(chunk)), needsReembedding = true)

            val entity = captured.captured.single().first
            assertEquals(9L, entity.id)
            assertEquals("imported", entity.text)
            assertEquals(1_234L, entity.timestamp)
            assertEquals(true, entity.isPinned)
            assertEquals(MemorySource.Manual, entity.source)
            assertEquals("preference", entity.tagsCsv)
            assertEquals(true, entity.needsReembedding)
            assertArrayEquals(converters.fromFloatArray(floatArrayOf(0.1f, 0.2f)), entity.embedding)
        }

    @Test
    fun `given the store refuses the import transaction when importing then a failure comes back`() =
        kotlinx.coroutines.test.runTest {
            val refusal = android.database.sqlite.SQLiteFullException("database or disk is full")
            io.mockk.coEvery { historyDao.importChunks(any(), any()) } throws refusal
            val chunk = MemoryChunk(id = 9, text = "imported", embedding = floatArrayOf(0.1f), timestamp = 1L)

            val merged = repository.insertImportedMemories(listOf(MemoryWithHistory(chunk)), needsReembedding = false)
            val replaced = repository.replaceImportedMemories(
                listOf(MemoryWithHistory(chunk)),
                needsReembedding = false,
            )

            assertEquals(refusal, merged.exceptionOrNull())
            assertEquals(refusal, replaced.exceptionOrNull())
        }

    @Test
    fun `insertImportedMemories is a no-op for an empty list`() = kotlinx.coroutines.test.runTest {
        repository.insertImportedMemories(emptyList(), needsReembedding = false)
        io.mockk.coVerify(exactly = 0) { historyDao.importChunks(any(), any()) }
    }

    @Test
    fun `replaceImportedMemories maps chunks and forwards them as a replacing import`() =
        kotlinx.coroutines.test.runTest {
            val captured = io.mockk.slot<List<Pair<MemoryChunkEntity, List<MemoryChunkVersionEntity>>>>()
            io.mockk.coEvery { historyDao.importChunks(capture(captured), replaceAll = true) } returns Unit
            val chunk = MemoryChunk(
                id = 5,
                text = "replace me",
                embedding = floatArrayOf(0.3f),
                timestamp = 7L,
                source = MemorySource.ChatSession("s"),
            )

            repository.replaceImportedMemories(listOf(MemoryWithHistory(chunk)), needsReembedding = false)

            val entity = captured.captured.single().first
            assertEquals(5L, entity.id)
            assertEquals("replace me", entity.text)
            assertEquals(MemorySource.ChatSession("s"), entity.source)
            assertEquals(false, entity.needsReembedding)
        }

    @Test
    fun `getMemoriesNeedingReembedding returns a corrupt-embedding row instead of dropping it`() =
        kotlinx.coroutines.test.runTest {
            // A row whose blob carries no usable vector (the migration's empty
            // marker, or a corrupt length) would otherwise be dropped, leaving
            // it flagged forever while the COUNT keeps re-arming the worker. It
            // must be returned (with an empty embedding) so the re-embed pass
            // can repair it from text and clear the flag.
            val emptyMarker = MemoryChunkEntity(
                id = 1,
                text = "still has text",
                embedding = ByteArray(0),
                timestamp = 10L,
                needsReembedding = true,
            )
            val corruptLength = MemoryChunkEntity(
                id = 2,
                text = "also has text",
                embedding = ByteArray(3),
                timestamp = 11L,
                needsReembedding = true,
            )
            io.mockk.coEvery { memoryDao.getMemoriesNeedingReembedding() } returns
                listOf(emptyMarker, corruptLength)

            val result = repository.getMemoriesNeedingReembedding()

            assertEquals(2, result.size)
            assertEquals("still has text", result[0].text)
            assertEquals(0, result[0].embedding.size)
            assertEquals("also has text", result[1].text)
            assertEquals(0, result[1].embedding.size)
        }

    @Test
    fun `countMemoriesNeedingReembedding forwards to dao`() = kotlinx.coroutines.test.runTest {
        io.mockk.coEvery { memoryDao.countNeedingReembedding() } returns 4

        assertEquals(4, repository.countMemoriesNeedingReembedding())
    }

    @Test
    fun `markMemoryReembedded serializes embedding and forwards to dao`() = kotlinx.coroutines.test.runTest {
        val embedding = floatArrayOf(0.5f, 0.6f)
        val expected = converters.fromFloatArray(embedding)

        repository.markMemoryReembedded(id = 3L, embedding = embedding)

        io.mockk.coVerify(exactly = 1) { memoryDao.markReembedded(id = 3L, embedding = expected!!) }
    }
}
