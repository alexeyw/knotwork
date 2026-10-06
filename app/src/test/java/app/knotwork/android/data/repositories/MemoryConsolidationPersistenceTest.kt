package app.knotwork.android.data.repositories

import androidx.room.Room
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.Converters
import app.knotwork.android.data.local.EmbeddingBlobCodec
import app.knotwork.android.data.local.dao.MemoryDao
import app.knotwork.android.data.local.models.MemoryChunkEntity
import app.knotwork.android.domain.models.MemorySource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Verifies the compaction write path against a **real** in-memory Room database
 * rather than a mocked DAO: consolidation must leave the store holding the
 * summary and nothing it replaced.
 *
 * The mocked-DAO tests in [MemoryRepositoryImplTest] pin the call contract; this
 * one pins the storage outcome, which is the part a user would feel — a
 * consolidation that inserts a summary but fails to remove the originals grows
 * the table it exists to shrink, and one that removes chunks it was not asked to
 * remove destroys facts.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryConsolidationPersistenceTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MemoryDao
    private lateinit var repository: MemoryRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.memoryDao()
        repository = MemoryRepositoryImpl(dao, Converters(), database.memoryHistoryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed(id: Long, text: String) {
        dao.insertMemory(
            MemoryChunkEntity(
                id = id,
                text = text,
                embedding = EmbeddingBlobCodec.encode(floatArrayOf(1f, 0f)),
                timestamp = id,
                source = MemorySource.Manual,
            ),
        )
    }

    @Test
    fun `given consolidated originals when replaced then only the summary and untouched chunks remain`() = runTest {
        seed(1L, "fact one")
        seed(2L, "fact two")
        seed(3L, "fact three")
        seed(4L, "unrelated fact")

        repository.replaceWithConsolidated(
            text = "merged fact",
            embedding = floatArrayOf(0.6f, 0.8f),
            originalIds = listOf(1L, 2L, 3L),
        )

        val stored = repository.getAllMemories()
        // No storage leak: three chunks in, one summary out, and the chunk that
        // was not part of the cluster is left exactly as it was.
        assertEquals(setOf("unrelated fact", "merged fact"), stored.map { it.text }.toSet())
        val summary = stored.single { it.text == "merged fact" }
        assertEquals(MemorySource.Compaction(originalChunkIds = listOf(1L, 2L, 3L)), summary.source)
        assertEquals(2, dao.countMemories())
    }

    @Test
    fun `given an empty original list when replaced then the summary is stored and nothing is deleted`() = runTest {
        seed(1L, "fact one")

        repository.replaceWithConsolidated(
            text = "standalone summary",
            embedding = floatArrayOf(0.6f, 0.8f),
            originalIds = emptyList(),
        )

        assertEquals(2, dao.countMemories())
        assertEquals(setOf("fact one", "standalone summary"), repository.getAllMemories().map { it.text }.toSet())
    }
}
