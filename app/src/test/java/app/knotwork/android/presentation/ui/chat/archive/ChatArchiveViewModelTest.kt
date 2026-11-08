package app.knotwork.android.presentation.ui.chat.archive

import app.knotwork.android.R
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.usecases.ChatExportDocument
import app.knotwork.android.domain.usecases.ExportChatUseCase
import app.knotwork.android.domain.usecases.UnarchiveChatUseCase
import app.knotwork.android.presentation.ui.chat.ChatExportEvent
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.design.screens.chatarchive.ChatArchiveVisualState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ChatArchiveViewModel] and the state → view-state projection.
 *
 * The archive is the only place a chat can be permanently deleted, and the only
 * place it can come back, so the contract under test is: rows mirror the
 * repository order, the "ran after archiving" marker is derived (not guessed),
 * delete is gated behind an explicit confirmation, and a read failure never
 * masquerades as an empty archive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatArchiveViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val archivedFlow = MutableStateFlow<List<ChatSession>>(emptyList())

    private lateinit var chatRepository: ChatRepository
    private lateinit var unarchiveChatUseCase: UnarchiveChatUseCase
    private lateinit var exportChatUseCase: ExportChatUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        chatRepository = mockk(relaxed = true)
        every { chatRepository.getArchivedSessionsFlow() } returns archivedFlow
        unarchiveChatUseCase = UnarchiveChatUseCase(chatRepository)
        exportChatUseCase = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): ChatArchiveViewModel = ChatArchiveViewModel(
        chatRepository = chatRepository,
        unarchiveChatUseCase = unarchiveChatUseCase,
        exportChatUseCase = exportChatUseCase,
    )

    @Test
    fun `given archived sessions when observed then rows mirror the repository order`() = runTest(testDispatcher) {
        archivedFlow.value = listOf(
            ChatSession(id = "newest", name = "Newest", updatedAt = 5L, isArchived = true, archivedAt = 5L),
            ChatSession(id = "older", name = "Older", updatedAt = 1L, isArchived = true, archivedAt = 1L),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        // The repository already orders most-recently-archived first; the
        // ViewModel must not re-sort and quietly disagree with it.
        assertEquals(listOf("newest", "older"), viewModel.uiState.value.rows.map { it.id })
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `given a session written to after archiving when observed then the row is marked`() = runTest(testDispatcher) {
        archivedFlow.value = listOf(
            ChatSession(id = "ran", name = "Ran", updatedAt = 500L, isArchived = true, archivedAt = 100L),
            ChatSession(id = "quiet", name = "Quiet", updatedAt = 100L, isArchived = true, archivedAt = 100L),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val rows = viewModel.uiState.value.rows.associateBy { it.id }
        // A background run may write into an archived chat without
        // un-archiving it — the user must not be surprised by that.
        assertTrue(rows.getValue("ran").ranAfterArchiving)
        assertFalse(rows.getValue("quiet").ranAfterArchiving)
    }

    @Test
    fun `given a session with no archive instant when observed then the label is unknown`() = runTest(testDispatcher) {
        archivedFlow.value = listOf(
            ChatSession(id = "legacy", name = "Legacy", updatedAt = 500L, isArchived = true),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val row = viewModel.uiState.value.rows.single()
        assertEquals(ArchivedAtLabel.Unknown, row.archivedAt)
        assertFalse("Nothing to compare against, so no run marker", row.ranAfterArchiving)
    }

    @Test
    fun `given an unnamed session when observed then the screen words its title`() = runTest(testDispatcher) {
        archivedFlow.value = listOf(
            ChatSession(id = "blank", name = "   ", updatedAt = 5L, isArchived = true, archivedAt = 5L),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        // The ViewModel keeps the stored name; the title in the interface language
        // is the screen's, so it follows a change of language.
        assertEquals(UNTITLED, viewModel.uiState.value.project(emptyMap(), subtitle = "").rows.single().title)
    }

    @Test
    fun `given restore when invoked then the chat is unarchived and confirmed`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        // Subscribe before acting: the events flow has no replay, so a one-shot
        // emitted while nobody is listening is dropped by design.
        val restored = async { viewModel.restoreEvents.first() }
        runCurrent()

        viewModel.restore("session-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepository.setSessionArchived("session-1", archived = false) }
        assertEquals("session-1", restored.await())
    }

    @Test
    fun `given a blank id when restoring then nothing is written`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.restore("  ")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepository.setSessionArchived(any(), any()) }
    }

    @Test
    fun `given delete requested when dismissed then nothing is deleted`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.requestDelete("session-1")
        assertEquals("session-1", viewModel.uiState.value.deleteTargetId)

        viewModel.dismissDelete()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.deleteTargetId)
        coVerify(exactly = 0) { chatRepository.deleteSession(any()) }
    }

    @Test
    fun `given delete confirmed when invoked then the session is deleted`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.requestDelete("session-1")
        viewModel.confirmDelete("session-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepository.deleteSession("session-1") }
        assertNull(viewModel.uiState.value.deleteTargetId)
    }

    @Test
    fun `given share when invoked then the document is raised for the share sheet`() = runTest(testDispatcher) {
        val document = ChatExportDocument(sessionName = "Trip", json = "{}")
        coEvery { exportChatUseCase("session-1") } returns Result.success(document)
        val viewModel = buildViewModel()
        advanceUntilIdle()

        val received = async { viewModel.export.events.first() }
        runCurrent()

        viewModel.shareChat("session-1")
        advanceUntilIdle()

        val share = received.await() as ChatExportEvent.Share
        assertEquals(document, share.document)
        assertTrue(share.fileName, share.fileName.startsWith("chat-Trip-"))
    }

    @Test
    fun `given save when invoked then the picker is asked for with the chat's file name`() = runTest(testDispatcher) {
        coEvery { exportChatUseCase("session-1") } returns
            Result.success(ChatExportDocument(sessionName = "Trip", json = "{}"))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        val received = async { viewModel.export.events.first() }
        runCurrent()

        viewModel.saveChat("session-1")
        advanceUntilIdle()

        val pick = received.await() as ChatExportEvent.PickDestination
        assertTrue(pick.fileName, pick.fileName.startsWith("chat-Trip-"))
    }

    @Test
    fun `given the export fails when shared then the failure is reported`() = runTest(testDispatcher) {
        coEvery { exportChatUseCase("session-1") } returns Result.failure(IllegalStateException("nope"))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        val received = async { viewModel.export.events.first() }
        runCurrent()

        viewModel.shareChat("session-1")
        advanceUntilIdle()

        // Reported, no longer swallowed: an export that silently does nothing
        // reads as a dead menu item.
        assertEquals(ChatExportEvent.RenderFailed, received.await())
    }

    @Test
    fun `given the archived flow fails when observed then the error state wins over empty`() = runTest(testDispatcher) {
        every { chatRepository.getArchivedSessionsFlow() } returns
            flow { throw IllegalStateException("db unreadable") }

        val viewModel = buildViewModel()
        advanceUntilIdle()

        // Dressing a read failure up as an empty archive would tell the user
        // their chats are gone.
        val state = viewModel.uiState.value
        assertEquals(UiText.Dynamic("db unreadable"), state.errorMessage)
        assertFalse(state.loading)
        assertEquals(
            ChatArchiveVisualState.Error,
            state.project(archivedLabels = emptyMap(), subtitle = "").visualState,
        )
    }

    @Test
    fun `given the archived flow fails without a message when observed then the app words it`() =
        runTest(testDispatcher) {
            every { chatRepository.getArchivedSessionsFlow() } returns flow { throw IllegalStateException() }

            val viewModel = buildViewModel()
            advanceUntilIdle()

            assertEquals(UiText(R.string.chat_archive_error_read_failed), viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `given menu state when opened and dismissed then it round-trips`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.openRowMenu("session-1")
        assertEquals("session-1", viewModel.uiState.value.openMenuRowId)

        viewModel.dismissRowMenu()
        assertNull(viewModel.uiState.value.openMenuRowId)
    }

    // region view-state projection

    @Test
    fun `given loading when projected then the skeleton state is shown`() {
        val state = ChatArchiveUiState(loading = true).project(emptyMap(), subtitle = "ignored")

        assertEquals(ChatArchiveVisualState.Loading, state.visualState)
        // The subtitle only makes sense over a real list.
        assertEquals("", state.subtitle)
    }

    @Test
    fun `given no rows when projected then the teaching empty state is shown`() {
        val state = ChatArchiveUiState(loading = false).project(emptyMap(), subtitle = "ignored")

        assertEquals(ChatArchiveVisualState.Empty, state.visualState)
    }

    @Test
    fun `given rows when projected then labels and the delete target are resolved`() {
        val rows = listOf(
            ChatArchiveRow(
                id = "a1",
                title = "Trip",
                archivedAt = ArchivedAtLabel.Hours(2),
                starred = true,
                ranAfterArchiving = false,
            ),
        )
        val uiState = ChatArchiveUiState(loading = false, rows = rows, deleteTargetId = "a1")

        val state = uiState.project(
            archivedLabels = mapOf("a1" to "Archived 2 h ago"),
            subtitle = "1 chat · newest first",
        )

        assertEquals(ChatArchiveVisualState.Default, state.visualState)
        assertEquals("Archived 2 h ago", state.rows.single().archivedLabel)
        assertTrue(state.rows.single().starred)
        assertEquals("a1", state.deleteTarget?.id)
        assertEquals("1 chat · newest first", state.subtitle)
    }

    @Test
    fun `given a stale delete target when projected then no dialog is raised`() {
        val uiState = ChatArchiveUiState(loading = false, rows = emptyList(), deleteTargetId = "gone")

        // The row vanished between the request and this frame (restored on
        // another surface); resolving to null closes the dialog rather than
        // confirming a delete against nothing.
        assertNull(uiState.project(emptyMap(), subtitle = "").deleteTarget)
    }

    // endregion

    /** Projects with fixed words: [UNTITLED] for an unnamed chat, a failure's own text as written. */
    private fun ChatArchiveUiState.project(archivedLabels: Map<String, String>, subtitle: String) = toViewState(
        archivedLabels = archivedLabels,
        subtitle = subtitle,
        untitled = UNTITLED,
        resolveText = { text -> (text as? UiText.Dynamic)?.text ?: text.toString() },
    )

    private companion object {
        const val UNTITLED = "Untitled chat"
    }
}
