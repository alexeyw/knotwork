package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.dao.ChatDao
import app.knotwork.android.data.local.dao.ChatHistorySummaryDao
import app.knotwork.android.data.local.models.ChatMessageEntity
import app.knotwork.android.data.local.models.ChatSessionEntity
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.services.AttachmentStore
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatRepositoryImplTest {

    private lateinit var chatDao: ChatDao
    private lateinit var chatHistorySummaryDao: ChatHistorySummaryDao
    private lateinit var attachmentStore: AttachmentStore
    private lateinit var repository: ChatRepositoryImpl

    @Before
    fun setup() {
        chatDao = mockk(relaxed = true)
        chatHistorySummaryDao = mockk(relaxed = true)
        attachmentStore = mockk(relaxed = true)
        repository = ChatRepositoryImpl(chatDao, chatHistorySummaryDao, attachmentStore)
    }

    /**
     * Session deletion must go through the single transactional DAO method —
     * messages, pipeline-run records (no FK cascade) and the session row die
     * together or not at all.
     */
    @Test
    fun `given session deletion then transactional complete delete is used`() = runTest {
        repository.deleteSession("session-abc")

        coVerify { chatDao.deleteSessionCompletely("session-abc") }
    }

    @Test
    fun `given sessionExists then delegates to the dao existence probe`() = runTest {
        coEvery { chatDao.sessionExists("sess-1") } returns true
        coEvery { chatDao.sessionExists("missing") } returns false

        assertTrue(repository.sessionExists("sess-1"))
        assertFalse(repository.sessionExists("missing"))
    }

    @Test
    fun `given session with attachments when deleted then each attachment file is removed`() = runTest {
        coEvery { chatDao.getAttachmentPathsForSession("sess-att") } returns listOf("a.jpg", "b.jpg")

        repository.deleteSession("sess-att")

        coVerify(exactly = 1) { chatDao.deleteSessionCompletely("sess-att") }
        coVerify(exactly = 1) { attachmentStore.delete("a.jpg") }
        coVerify(exactly = 1) { attachmentStore.delete("b.jpg") }
    }

    @Test
    fun `given message with attachment when deleted then attachment file is removed after row`() = runTest {
        coEvery { chatDao.getAttachmentPathById(99L) } returns "pic.jpg"

        repository.deleteMessage(99L)

        coVerify(exactly = 1) { chatDao.deleteMessageById(99L) }
        coVerify(exactly = 1) { attachmentStore.delete("pic.jpg") }
    }

    @Test
    fun `given message without attachment when deleted then no file deletion happens`() = runTest {
        coEvery { chatDao.getAttachmentPathById(100L) } returns null

        repository.deleteMessage(100L)

        coVerify(exactly = 1) { chatDao.deleteMessageById(100L) }
        coVerify(exactly = 0) { attachmentStore.delete(any()) }
    }

    @Test
    fun `given referenced attachment paths requested then dao getAllAttachmentPaths is returned`() = runTest {
        coEvery { chatDao.getAllAttachmentPaths() } returns listOf("x.jpg", "y.jpg")

        val paths = repository.getReferencedAttachmentPaths()

        assertEquals(listOf("x.jpg", "y.jpg"), paths)
    }

    @Test
    fun `given same sessionId when saveMessage called twice then getSessionById called only once`() = runTest {
        val sessionId = "session-abc"
        val existingSession = ChatSessionEntity(id = sessionId, name = "Chat session", updatedAt = 0L)
        val message1 =
            ChatMessage(id = 1L, sessionId = sessionId, role = Role.USER, content = "Hello", timestamp = 1000L)
        val message2 = ChatMessage(id = 2L, sessionId = sessionId, role = Role.AGENT, content = "Hi", timestamp = 2000L)

        coEvery { chatDao.getSessionById(sessionId) } returns existingSession

        repository.saveMessage(message1)
        repository.saveMessage(message2)

        coVerify(exactly = 1) { chatDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { chatDao.updateSessionTimestamp(sessionId, message2.timestamp) }
    }

    @Test
    fun `given non-existent sessionId when saveMessage called then session is created`() = runTest {
        val sessionId = "new-session"
        val message =
            ChatMessage(id = 1L, sessionId = sessionId, role = Role.USER, content = "Hello", timestamp = 1000L)

        coEvery { chatDao.getSessionById(sessionId) } returns null

        repository.saveMessage(message)

        coVerify(exactly = 1) { chatDao.getSessionById(sessionId) }
        coVerify(exactly = 1) {
            chatDao.insertSession(
                ChatSessionEntity(
                    id = sessionId,
                    name = "Chat " + sessionId.take(6),
                    updatedAt = message.timestamp,
                ),
            )
        }
    }

    @Test
    fun `given different sessionIds when saveMessage called for each then getSessionById called for each`() = runTest {
        val sessionId1 = "session-1"
        val sessionId2 = "session-2"
        val session1 = ChatSessionEntity(id = sessionId1, name = "Chat ses", updatedAt = 0L)
        val session2 = ChatSessionEntity(id = sessionId2, name = "Chat ses", updatedAt = 0L)
        val message1 = ChatMessage(id = 1L, sessionId = sessionId1, role = Role.USER, content = "A", timestamp = 1000L)
        val message2 = ChatMessage(id = 2L, sessionId = sessionId2, role = Role.USER, content = "B", timestamp = 2000L)

        coEvery { chatDao.getSessionById(sessionId1) } returns session1
        coEvery { chatDao.getSessionById(sessionId2) } returns session2

        repository.saveMessage(message1)
        repository.saveMessage(message2)

        coVerify(exactly = 1) { chatDao.getSessionById(sessionId1) }
        coVerify(exactly = 1) { chatDao.getSessionById(sessionId2) }
    }

    @Test
    fun `given existing sessionId when saveMessage called then only the session timestamp is written`() = runTest {
        val sessionId = "existing-session"
        // The copy read here is stale: the chat has since been renamed from this very
        // message. Writing the whole row back from it would restore "New Chat".
        val staleRead = ChatSessionEntity(id = sessionId, name = "New Chat", updatedAt = 500L)
        val message = ChatMessage(id = 1L, sessionId = sessionId, role = Role.USER, content = "Hi", timestamp = 1500L)

        coEvery { chatDao.getSessionById(sessionId) } returns staleRead

        repository.saveMessage(message)

        coVerify(exactly = 1) { chatDao.updateSessionTimestamp(sessionId, message.timestamp) }
        coVerify(exactly = 0) { chatDao.updateSession(any()) }
    }

    @Test
    fun `given display flow when collected then only isFinal=true messages are emitted`() = runTest {
        val sessionId = "display-session"
        val finalEntity = ChatMessageEntity(
            id = 1L,
            sessionId = sessionId,
            role = "AGENT",
            content = "final answer",
            timestamp = 1000L,
            isFinal = true,
            isStarred = false,
        )
        every { chatDao.getDisplayMessagesBySessionId(sessionId) } returns flowOf(listOf(finalEntity))

        val emitted = repository.getDisplayMessagesForSession(sessionId).first()

        assertEquals(1, emitted.size)
        assertEquals("final answer", emitted.first().content)
        assertEquals(true, emitted.first().isFinal)
    }

    @Test
    fun `given setMessageStarred when called then dao update is invoked with same args`() = runTest {
        repository.setMessageStarred(messageId = 42L, starred = true)
        coVerify(exactly = 1) { chatDao.setMessageStarred(42L, true) }
    }

    @Test
    fun `given starred flow when collected then mapped messages preserve isStarred`() = runTest {
        val starred = ChatMessageEntity(
            id = 7L,
            sessionId = "any",
            role = "USER",
            content = "saved msg",
            timestamp = 1L,
            isFinal = true,
            isStarred = true,
        )
        every { chatDao.getStarredMessages() } returns flowOf(listOf(starred))

        val emitted = repository.getStarredMessages().first()

        assertEquals(1, emitted.size)
        assertEquals(true, emitted.first().isStarred)
    }

    @Test
    fun `given renameSession when called then dao renameSession is invoked with same args`() = runTest {
        repository.renameSession("sess-rename", "Brand new name")
        coVerify(exactly = 1) { chatDao.renameSession("sess-rename", "Brand new name") }
        // No other session-touching DAO call should fire — rename is a single UPDATE.
        coVerify(exactly = 0) { chatDao.getSessionById(any()) }
        coVerify(exactly = 0) { chatDao.upsertSession(any()) }
        coVerify(exactly = 0) { chatDao.updateSession(any()) }
    }

    @Test
    fun `given setSessionFavorite when called then dao setSessionStarred flips the flag`() = runTest {
        repository.setSessionFavorite("sess-fav", true)
        repository.setSessionFavorite("sess-fav", false)
        coVerify(exactly = 1) { chatDao.setSessionStarred("sess-fav", true) }
        coVerify(exactly = 1) { chatDao.setSessionStarred("sess-fav", false) }
    }

    @Test
    fun `given setSessionArchived when called then dao setSessionArchived flips the flag`() = runTest {
        repository.setSessionArchived("sess-arch", true)
        repository.setSessionArchived("sess-arch", false)

        // Archiving stamps the instant the archive surface orders and labels by;
        // restoring clears it, so the flag and the instant can never disagree.
        coVerify(exactly = 1) {
            chatDao.setSessionArchived("sess-arch", archived = true, archivedAt = match { it != null })
        }
        coVerify(exactly = 1) {
            chatDao.setSessionArchived("sess-arch", archived = false, archivedAt = null)
        }
        // Archiving must not delete anything the session owns.
        coVerify(exactly = 0) { chatDao.deleteSessionCompletely(any()) }
    }

    /**
     * The archive flag must survive the entity → domain hop of the list flow;
     * the thread list and the archive surface both branch on it.
     */
    @Test
    fun `given sessions flow when collected then mapped sessions preserve isArchived`() = runTest {
        val archived = ChatSessionEntity(
            id = "sess-archived",
            name = "Archived",
            updatedAt = 10L,
            isArchived = true,
        )
        every { chatDao.getSessionsFlow(true) } returns flowOf(listOf(archived))

        val emitted = repository.getSessionsFlow(includeArchived = true).first()

        assertEquals(1, emitted.size)
        assertTrue(emitted.first().isArchived)
    }

    /**
     * The default must be "active chats only": every list-facing caller relies
     * on it to keep archived conversations out of the thread list.
     */
    @Test
    fun `given getSessionsFlow without arguments then the dao is queried excluding archived`() = runTest {
        every { chatDao.getSessionsFlow(false) } returns flowOf(emptyList())

        repository.getSessionsFlow().first()

        verify(exactly = 1) { chatDao.getSessionsFlow(false) }
        verify(exactly = 0) { chatDao.getSessionsFlow(true) }
    }

    @Test
    fun `given getArchivedSessionsFlow when collected then dao archived flow is mapped to domain`() = runTest {
        val archived = ChatSessionEntity(
            id = "sess-archived",
            name = "Archived",
            updatedAt = 11L,
            pipelineId = "pipe-1",
            isArchived = true,
        )
        every { chatDao.getArchivedSessionsFlow() } returns flowOf(listOf(archived))

        val emitted = repository.getArchivedSessionsFlow().first()

        assertEquals(listOf("sess-archived"), emitted.map { it.id })
        assertEquals("pipe-1", emitted.first().pipelineId)
        assertTrue(emitted.first().isArchived)
    }

    @Test
    fun `given importChat with export-shaped json when called then session and messages persisted`() = runTest {
        val sessionSlot: CapturingSlot<ChatSessionEntity> = slot()
        val messages = mutableListOf<ChatMessageEntity>()
        coEvery { chatDao.upsertSession(capture(sessionSlot)) } returns Unit
        coEvery { chatDao.insertMessage(capture(messages)) } returns Unit

        val json = """{"sessionName":"Trip plan","messages":[
            {"role":"USER","text":"Plan a trip","timestamp":111},
            {"role":"AGENT","text":"Sure","timestamp":222}
        ]}
        """.trimIndent()

        val newId = repository.importChat(json)

        assertEquals("Trip plan", sessionSlot.captured.name)
        assertEquals(newId, sessionSlot.captured.id)
        assertEquals(2, messages.size)
        assertEquals("Plan a trip", messages[0].content)
        assertEquals("USER", messages[0].role)
        assertEquals(111L, messages[0].timestamp)
        assertEquals("AGENT", messages[1].role)
        assertEquals(newId, messages[0].sessionId)
        assertEquals(newId, messages[1].sessionId)
    }

    @Test
    fun `given importChat with bare-array json when called then session uses default imported name`() = runTest {
        val sessionSlot: CapturingSlot<ChatSessionEntity> = slot()
        coEvery { chatDao.upsertSession(capture(sessionSlot)) } returns Unit

        val json = """[{"role":"USER","text":"hi","timestamp":1}]"""
        repository.importChat(json)

        assertEquals("Imported Chat", sessionSlot.captured.name)
    }

    @Test(expected = org.json.JSONException::class)
    fun `given importChat with non-JSON when called then throws JSONException`() = runTest {
        repository.importChat("not json at all")
    }

    @Test
    fun `given saveSession when called then upsertSession is invoked once`() = runTest {
        // Defect 8 regression guard: `saveSession` must perform a single DAO round-trip
        // via `@Upsert`, replacing the previous SELECT + INSERT/UPDATE pattern. Verifying
        // the legacy methods are NOT called also guards against silent regressions where
        // an old code path is reintroduced.
        val session = ChatSession(
            id = "sess-x",
            name = "Some chat",
            updatedAt = 1000L,
        )

        repository.saveSession(session)

        coVerify(exactly = 1) {
            chatDao.upsertSession(
                ChatSessionEntity(id = "sess-x", name = "Some chat", updatedAt = 1000L),
            )
        }
        coVerify(exactly = 0) { chatDao.getSessionById("sess-x") }
        coVerify(exactly = 0) { chatDao.insertSession(any()) }
        coVerify(exactly = 0) { chatDao.updateSession(any()) }
    }
}
