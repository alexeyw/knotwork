package app.knotwork.android.data.repositories

import androidx.room.Room
import app.knotwork.android.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [BackgroundPromptRepositoryImpl] against a real (in-memory) Room database: the
 * encrypted home of the prompts queued background runs execute.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundPromptRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: BackgroundPromptRepositoryImpl
    private var now = 1_000L

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BackgroundPromptRepositoryImpl(database.backgroundPromptDao()) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `given a stored prompt when read then it comes back verbatim`() = runTest {
        repository.store("p-1", "  check emails\\nand reply ")

        assertEquals("  check emails\\nand reply ", repository.get("p-1"))
        assertNull(repository.get("p-2"))
    }

    @Test
    fun `given a prompt stored twice under one id when read then the second replaces the first`() = runTest {
        // A recurring task scheduled again keys its prompt the same way.
        repository.store("periodic-x", "old")
        repository.store("periodic-x", "new")

        assertEquals(mapOf("periodic-x" to "new"), repository.observeAll().first())
    }

    @Test
    fun `given a deleted prompt when read then it is gone`() = runTest {
        repository.store("p-1", "a")

        repository.delete("p-1")

        assertNull(repository.get("p-1"))
    }

    @Test
    fun `given live and orphaned prompts when retained then only the live ones remain`() = runTest {
        repository.store("live", "a")
        repository.store("orphan-1", "b")
        repository.store("orphan-2", "c")

        val removed = repository.retainOnly(setOf("live", "not-stored"), storedBefore = 2_000L)

        assertEquals(2, removed)
        assertEquals(mapOf("live" to "a"), repository.observeAll().first())
    }

    @Test
    fun `given an unreferenced prompt stored after the cutoff when retained then it stays`() = runTest {
        // Stored, its request not enqueued yet: the prune must not catch it.
        now = 5_000L
        repository.store("just-stored", "a")

        val removed = repository.retainOnly(emptySet(), storedBefore = 4_000L)

        assertEquals(0, removed)
        assertEquals("a", repository.get("just-stored"))
    }
}
