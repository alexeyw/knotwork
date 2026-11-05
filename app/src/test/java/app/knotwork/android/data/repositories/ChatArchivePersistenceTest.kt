package app.knotwork.android.data.repositories

import androidx.room.Room
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.dao.ChatDao
import app.knotwork.android.data.local.models.ChatMessageEntity
import app.knotwork.android.data.local.models.ChatSessionEntity
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.services.EnglishDefaultNames
import app.knotwork.android.domain.usecases.ArchiveChatUseCase
import app.knotwork.android.domain.usecases.UnarchiveChatUseCase
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Drives the chat-archive stack — [ArchiveChatUseCase] / [UnarchiveChatUseCase]
 * → [ChatRepositoryImpl] → [ChatDao] — against a **real in-memory Room
 * database**, so the SQL itself is under test and not just the call routing the
 * mocked repository tests cover.
 *
 * What only a real database can prove here: the `isArchived` column default,
 * the `WHERE :includeArchived OR isArchived = 0` predicate (a bound boolean
 * driving an OR), the archived-only projection, and — the property the whole
 * feature rests on — that archiving discards nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ChatArchivePersistenceTest {

    private lateinit var database: AppDatabase
    private lateinit var chatDao: ChatDao
    private lateinit var repository: ChatRepositoryImpl
    private lateinit var archive: ArchiveChatUseCase
    private lateinit var unarchive: UnarchiveChatUseCase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatDao = database.chatDao()
        repository =
            ChatRepositoryImpl(chatDao, database.chatHistorySummaryDao(), mockk(relaxed = true), EnglishDefaultNames)
        archive = ArchiveChatUseCase(repository)
        unarchive = UnarchiveChatUseCase(repository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedSession(id: String, updatedAt: Long) {
        chatDao.insertSession(ChatSessionEntity(id = id, name = "Chat $id", updatedAt = updatedAt))
    }

    @Test
    fun `given a fresh session then it is not archived and appears in the active list`() = runTest {
        seedSession("s1", updatedAt = 100L)

        assertEquals(listOf("s1"), repository.getSessionsFlow().first().map { it.id })
        assertTrue(repository.getArchivedSessionsFlow().first().isEmpty())
        assertFalse(repository.getSessionById("s1")?.isArchived == true)
    }

    @Test
    fun `given an archived session then it leaves the active list and enters the archive`() = runTest {
        seedSession("s1", updatedAt = 100L)
        seedSession("s2", updatedAt = 200L)

        assertTrue(archive("s1").isSuccess)

        assertEquals(listOf("s2"), repository.getSessionsFlow().first().map { it.id })
        assertEquals(listOf("s1"), repository.getArchivedSessionsFlow().first().map { it.id })
        // The unfiltered view still sees both, newest-first.
        assertEquals(
            listOf("s2", "s1"),
            repository.getSessionsFlow(includeArchived = true).first().map { it.id },
        )
    }

    @Test
    fun `given an archived session when unarchived then it returns to the active list`() = runTest {
        seedSession("s1", updatedAt = 100L)
        archive("s1")

        assertTrue(unarchive("s1").isSuccess)

        assertEquals(listOf("s1"), repository.getSessionsFlow().first().map { it.id })
        assertTrue(repository.getArchivedSessionsFlow().first().isEmpty())
    }

    /**
     * The core promise of the feature: archiving is a visibility change, not a
     * deletion. Everything the session owns must still be there afterwards, and
     * unarchiving must restore the conversation unchanged.
     */
    @Test
    fun `given an archived session then its messages and metadata survive intact`() = runTest {
        chatDao.insertSession(
            ChatSessionEntity(id = "s1", name = "Trip plan", updatedAt = 100L, pipelineId = "pipe-1", isStarred = true),
        )
        chatDao.insertMessage(
            ChatMessageEntity(sessionId = "s1", role = "USER", content = "hello", timestamp = 1L, isFinal = true),
        )
        chatDao.insertMessage(
            ChatMessageEntity(sessionId = "s1", role = "AGENT", content = "hi", timestamp = 2L, isFinal = true),
        )

        archive("s1")

        val archived = repository.getArchivedSessionsFlow().first().single()
        assertEquals("Trip plan", archived.name)
        assertEquals("pipe-1", archived.pipelineId)
        assertTrue("archiving must not clear other flags", archived.isStarred)
        assertEquals(
            listOf("hello", "hi"),
            repository.getMessagesForSession("s1").first().map { it.content },
        )

        unarchive("s1")

        val restored = repository.getSessionsFlow().first().single()
        assertEquals("Trip plan", restored.name)
        assertEquals(100L, restored.updatedAt)
        assertEquals("pipe-1", restored.pipelineId)
        assertTrue(restored.isStarred)
    }

    /**
     * Archiving is stated as a user-only decision: a background trigger or
     * scheduled run writing into an archived chat must not resurface it. The
     * message-write path updates `updatedAt` through a row copy, which is
     * exactly where the flag could be clobbered.
     */
    @Test
    fun `given a new message in an archived session then the session stays archived`() = runTest {
        seedSession("s1", updatedAt = 100L)
        archive("s1")

        repository.saveMessage(
            ChatMessage(
                sessionId = "s1",
                role = Role.AGENT,
                content = "background run finished",
                timestamp = 500L,
            ),
        )

        val session = repository.getSessionById("s1")
        assertTrue("a background write must not un-archive the chat", session?.isArchived == true)
        assertEquals("the write itself must still land", 500L, session?.updatedAt)
        assertTrue(repository.getSessionsFlow().first().isEmpty())
    }

    @Test
    fun `given archiving an unknown session then nothing is created and the call succeeds`() = runTest {
        seedSession("s1", updatedAt = 100L)

        assertTrue(archive("does-not-exist").isSuccess)

        assertEquals(listOf("s1"), repository.getSessionsFlow().first().map { it.id })
        assertTrue(repository.getArchivedSessionsFlow().first().isEmpty())
    }
}
