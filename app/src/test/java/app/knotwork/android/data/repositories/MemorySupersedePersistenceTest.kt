package app.knotwork.android.data.repositories

import androidx.room.Room
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.Converters
import app.knotwork.android.data.local.EmbeddingBlobCodec
import app.knotwork.android.data.local.dao.MemoryDao
import app.knotwork.android.data.local.models.MemoryChunkEntity
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.MemoryVersion
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Verifies the supersede write path ([MemoryHistoryRepositoryImpl]) against a **real** in-memory Room database: a
 * newer statement replaces a chunk in place and the replaced text survives as an
 * earlier version, an update of a pinned chunk waits beside it, and both kinds of
 * link go with their chunks. These are storage outcomes — transactions, the
 * version cap, `CASCADE` foreign keys — that a mocked DAO cannot show.
 */
@RunWith(RobolectricTestRunner::class)
class MemorySupersedePersistenceTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MemoryDao
    private lateinit var repository: MemoryRepositoryImpl
    private lateinit var history: MemoryHistoryRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.memoryDao()
        repository = MemoryRepositoryImpl(dao, Converters())
        history = MemoryHistoryRepositoryImpl(dao, database.memoryHistoryDao(), Converters())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed(
        id: Long,
        text: String,
        isPinned: Boolean = false,
        tagsCsv: String = "",
        vector: FloatArray = floatArrayOf(1f, 0f),
        needsReembedding: Boolean = false,
    ) {
        dao.insertMemory(
            MemoryChunkEntity(
                id = id,
                text = text,
                embedding = EmbeddingBlobCodec.encode(vector),
                timestamp = id,
                isPinned = isPinned,
                source = MemorySource.Manual,
                tagsCsv = tagsCsv,
                useCount = 3,
                lastUsedAt = 5L,
                needsReembedding = needsReembedding,
            ),
        )
    }

    @Test
    fun `given a stored chunk when superseded then it keeps its id and pin and holds the newer text`() = runTest {
        // Given
        seed(id = 1, text = "Lives in Berlin", isPinned = false, tagsCsv = "home")

        // When
        val written = history.supersede(
            id = 1,
            text = "Lives in Munich",
            embedding = floatArrayOf(0f, 1f),
            source = MemorySource.ChatSession("s2"),
            tags = listOf("relation"),
        )

        // Then
        assertTrue(written)
        val chunk = repository.getAllMemories().single()
        assertEquals(1L, chunk.id)
        assertEquals("Lives in Munich", chunk.text)
        assertEquals(MemorySource.ChatSession("s2"), chunk.source)
        assertEquals(listOf("home", "relation"), chunk.tags)
        assertEquals(0, chunk.useCount)
        assertNull(chunk.lastUsedAt)
        assertEquals(0f, chunk.embedding[0], 0f)
    }

    @Test
    fun `given a stored chunk when superseded then the replaced text is its newest earlier version`() = runTest {
        // Given
        seed(id = 1, text = "Lives in Berlin", tagsCsv = "home")

        // When
        history.supersede(1, "Lives in Munich", floatArrayOf(0f, 1f), MemorySource.ChatSession("s2"))
        history.supersede(1, "Lives in Hamburg", floatArrayOf(0f, 1f), MemorySource.ChatSession("s3"))

        // Then
        val history = history.getHistory(1)
        assertEquals(listOf("Lives in Munich", "Lives in Berlin"), history.map { it.text })
        val oldest = history.last()
        assertEquals(MemorySource.Manual, oldest.source)
        assertEquals(listOf("home"), oldest.tags)
        assertEquals(1L, oldest.capturedAt)
        assertTrue(oldest.replacedAt >= oldest.capturedAt)
    }

    @Test
    fun `given more replacements than the cap when superseded then only the newest versions stay`() = runTest {
        // Given
        seed(id = 1, text = "v0")

        // When
        repeat(MemoryVersion.MAX_PER_CHUNK + 3) { i ->
            history.supersede(1, "v${i + 1}", floatArrayOf(1f, 0f), MemorySource.ChatSession("s"))
        }

        // Then — the current text is v13; versions v3..v12 survive, newest first.
        val history = history.getHistory(1)
        assertEquals(MemoryVersion.MAX_PER_CHUNK, history.size)
        assertEquals("v12", history.first().text)
        assertEquals("v3", history.last().text)
    }

    @Test
    fun `given no chunk with the id when superseded then nothing is written`() = runTest {
        assertFalse(history.supersede(404, "text", floatArrayOf(1f, 0f), MemorySource.Manual))
        assertTrue(history.getHistory(404).isEmpty())
    }

    @Test
    fun `given a chunk with history when it is deleted or memory is cleared then its history goes with it`() = runTest {
        // Given
        seed(id = 1, text = "Lives in Berlin")
        seed(id = 2, text = "Has a brother named Alex")
        history.supersede(1, "Lives in Munich", floatArrayOf(1f, 0f), MemorySource.Manual)
        history.supersede(2, "Has a brother named Max", floatArrayOf(1f, 0f), MemorySource.Manual)

        // When
        repository.deleteMemory(1)

        // Then
        assertTrue(history.getHistory(1).isEmpty())
        assertEquals(1, history.getHistory(2).size)

        // When
        repository.deleteAllMemories()

        // Then
        assertTrue(history.getHistory(2).isEmpty())
    }

    @Test
    fun `given an update of a pinned chunk when saved then it waits beside it and the pinned text stays`() = runTest {
        // Given
        seed(id = 1, text = "Lives in Berlin", isPinned = true)

        // When
        val updateId = history.saveUpdateOfPinned(
            pinnedId = 1,
            text = "Lives in Munich",
            embedding = floatArrayOf(0f, 1f),
            source = MemorySource.ChatSession("s2"),
            tags = listOf("relation"),
        )

        // Then
        val chunks = repository.getAllMemories().associateBy { it.id }
        assertEquals("Lives in Berlin", chunks.getValue(1).text)
        assertTrue(chunks.getValue(1).isPinned)
        assertEquals("Lives in Munich", chunks.getValue(updateId).text)
        assertFalse(chunks.getValue(updateId).isPinned)
        assertEquals(1L, history.getPendingUpdates().single { it.updateChunkId == updateId }.pinnedChunkId)
    }

    @Test
    fun `given an update already waiting when a newer one arrives then it replaces the waiting one`() = runTest {
        // Given
        seed(id = 1, text = "Lives in Berlin", isPinned = true)
        val first = history.saveUpdateOfPinned(1, "Lives in Munich", floatArrayOf(0f, 1f), MemorySource.Manual)

        // When
        val second = history.saveUpdateOfPinned(1, "Lives in Hamburg", floatArrayOf(0f, 1f), MemorySource.Manual)

        // Then — at most one update waits; the older one is the waiting chunk's history.
        assertEquals(first, second)
        assertEquals(1, history.getPendingUpdates().size)
        assertEquals(2, repository.getAllMemories().size)
        assertEquals("Lives in Hamburg", repository.getAllMemories().single { it.id == second }.text)
        assertEquals(listOf("Lives in Munich"), history.getHistory(second).map { it.text })
    }

    @Test
    fun `given a waiting update when its pinned chunk is deleted then it becomes an ordinary chunk`() = runTest {
        // Given
        seed(id = 1, text = "Lives in Berlin", isPinned = true)
        val updateId = history.saveUpdateOfPinned(1, "Lives in Munich", floatArrayOf(0f, 1f), MemorySource.Manual)

        // When
        repository.deleteMemory(1)

        // Then
        assertTrue(history.getPendingUpdates().isEmpty())
        assertEquals(listOf(updateId), repository.getAllMemories().map { it.id })
    }

    @Test
    fun `given a waiting update when it is deleted then the pair ends and the pinned chunk stays`() = runTest {
        // Given
        seed(id = 1, text = "Lives in Berlin", isPinned = true)
        val updateId = history.saveUpdateOfPinned(1, "Lives in Munich", floatArrayOf(0f, 1f), MemorySource.Manual)

        // When
        repository.deleteMemory(updateId)

        // Then
        assertTrue(history.getPendingUpdates().isEmpty())
        assertEquals(listOf(1L), repository.getAllMemories().map { it.id })
    }

    @Test
    fun `given a waiting update when compaction picks candidates then the update is not one of them`() = runTest {
        // Given — both chunks are old enough for compaction; the pinned one is excluded anyway.
        seed(id = 1, text = "Lives in Berlin", isPinned = true)
        seed(id = 2, text = "Has a brother named Alex")
        val updateId = history.saveUpdateOfPinned(1, "Lives in Munich", floatArrayOf(0f, 1f), MemorySource.Manual)

        // When
        val candidates = repository.getCompactionCandidates(olderThanMillis = Long.MAX_VALUE)

        // Then
        assertEquals(listOf(2L), candidates.map { it.id })
        assertNotNull(repository.getAllMemories().singleOrNull { it.id == updateId })
    }

    @Test
    fun `given chunks awaiting a re-embed when a candidate is sought then they are never the candidate`() = runTest {
        // Given — the foreign-space chunk would score 1.0 against the probe.
        seed(id = 1, text = "imported", vector = floatArrayOf(1f, 0f), needsReembedding = true)
        seed(id = 2, text = "native", vector = floatArrayOf(0.6f, 0.8f))

        // When
        val candidate = history.findSupersedeCandidate(floatArrayOf(1f, 0f))

        // Then
        assertEquals(2L, candidate?.first?.id)
        assertEquals(0.6f, candidate!!.second, 1e-4f)
    }

    @Test
    fun `given only chunks awaiting a re-embed when a candidate is sought then there is none`() = runTest {
        seed(id = 1, text = "imported", needsReembedding = true)

        assertNull(history.findSupersedeCandidate(floatArrayOf(1f, 0f)))
    }
}
