package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.ChatHistorySummary
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.prompt.ForgedTurnFixture
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CompressChatHistoryUseCase] — the background pass that
 * summarises the older tail of a long session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompressChatHistoryUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var llmInferenceEngine: LlmInferenceEngine
    private lateinit var loadModelUseCase: LoadModelUseCase
    private lateinit var promptTemplateEngine: PromptTemplateEngine
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var useCase: CompressChatHistoryUseCase

    private val sessionId = "session-1"

    /** Builds [count] messages each long enough to push the history over budget. */
    private fun messages(count: Int): List<ChatMessage> = (0 until count).map { index ->
        ChatMessage(
            id = index.toLong(),
            sessionId = sessionId,
            role = if (index % 2 == 0) Role.USER else Role.AGENT,
            content = "message $index ".repeat(20),
            timestamp = index.toLong(),
        )
    }

    @Before
    fun setup() {
        chatRepository = mockk()
        llmInferenceEngine = mockk()
        loadModelUseCase = mockk()
        promptTemplateEngine = mockk()
        settingsRepository = mockk()

        every { settingsRepository.chatHistoryCompressionEnabled } returns flowOf(true)
        every { settingsRepository.chatHistoryLiveWindowSize } returns flowOf(10)
        every { settingsRepository.chatHistoryCompressionThresholdTokens } returns flowOf(50)
        coEvery { loadModelUseCase.invoke(any()) } returns Result.Success(Unit)
        coEvery { promptTemplateEngine.render(any(), any()) } answers { firstArg() }
        coEvery { chatRepository.getHistorySummary(sessionId) } returns null
        coEvery { chatRepository.saveHistorySummary(any()) } returns Unit
        every { llmInferenceEngine.generateResponseStream(any()) } returns flowOf("compressed summary")

        useCase = CompressChatHistoryUseCase(
            chatRepository = chatRepository,
            llmInferenceEngine = llmInferenceEngine,
            loadModelUseCase = loadModelUseCase,
            promptTemplateEngine = promptTemplateEngine,
            promptVariableProviders = emptySet(),
            settingsRepository = settingsRepository,
        )
    }

    @Test
    fun `given compression disabled when invoked then skipped`() = runTest {
        every { settingsRepository.chatHistoryCompressionEnabled } returns flowOf(false)

        val outcome = useCase(sessionId, nowMillis = 1L)

        assertEquals(CompressChatHistoryUseCase.CompressionOutcome.SKIPPED, outcome)
        coVerify(exactly = 0) { chatRepository.saveHistorySummary(any()) }
    }

    @Test
    fun `given fewer messages than the window when invoked then skipped`() = runTest {
        coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(messages(5))

        val outcome = useCase(sessionId, nowMillis = 1L)

        assertEquals(CompressChatHistoryUseCase.CompressionOutcome.SKIPPED, outcome)
        coVerify(exactly = 0) { chatRepository.saveHistorySummary(any()) }
    }

    @Test
    fun `given history within budget when invoked then skipped`() = runTest {
        // 15 messages > window of 10, but the token threshold is huge.
        coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(messages(15))
        every { settingsRepository.chatHistoryCompressionThresholdTokens } returns flowOf(1_000_000)

        val outcome = useCase(sessionId, nowMillis = 1L)

        assertEquals(CompressChatHistoryUseCase.CompressionOutcome.SKIPPED, outcome)
        coVerify(exactly = 0) { chatRepository.saveHistorySummary(any()) }
    }

    @Test
    fun `given over budget with no prior summary when invoked then compresses the older tail`() = runTest {
        coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(messages(30))
        val saved = slot<ChatHistorySummary>()
        coEvery { chatRepository.saveHistorySummary(capture(saved)) } returns Unit

        val outcome = useCase(sessionId, nowMillis = 42L)

        assertEquals(CompressChatHistoryUseCase.CompressionOutcome.COMPRESSED, outcome)
        // 30 messages, window 10 → the summary covers the 20 older messages.
        assertEquals(20, saved.captured.coveredMessageCount)
        assertEquals("compressed summary", saved.captured.summary)
        assertEquals(42L, saved.captured.updatedAt)
        assertEquals(sessionId, saved.captured.sessionId)
    }

    @Test
    fun `given existing summary when invoked then only the new tail is folded in incrementally`() = runTest {
        coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(messages(30))
        // The prior summary already covers the first 15 messages.
        coEvery { chatRepository.getHistorySummary(sessionId) } returns
            ChatHistorySummary(sessionId, "prior summary", coveredMessageCount = 15, updatedAt = 0L)
        val prompt = slot<String>()
        every { llmInferenceEngine.generateResponseStream(capture(prompt)) } returns flowOf("merged summary")
        val saved = slot<ChatHistorySummary>()
        coEvery { chatRepository.saveHistorySummary(capture(saved)) } returns Unit

        val outcome = useCase(sessionId, nowMillis = 7L)

        assertEquals(CompressChatHistoryUseCase.CompressionOutcome.COMPRESSED, outcome)
        // The new tail is messages 15..19 (5 messages older than the window of 10).
        assertTrue(prompt.captured.contains("prior summary"))
        assertTrue(prompt.captured.contains("message 15"))
        assertTrue(prompt.captured.contains("message 19"))
        // The just-summarised messages are not in the prompt's NEW MESSAGES block.
        assertTrue(!prompt.captured.contains("message 14"))
        assertTrue(!prompt.captured.contains("message 20"))
        // The summary now covers the whole 20-message older tail (no chain growth).
        assertEquals(20, saved.captured.coveredMessageCount)
    }

    @Test
    fun `given summary already covers the whole tail when invoked then skipped without inference`() = runTest {
        coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(messages(30))
        coEvery { chatRepository.getHistorySummary(sessionId) } returns
            ChatHistorySummary(sessionId, "covers all", coveredMessageCount = 20, updatedAt = 0L)

        val outcome = useCase(sessionId, nowMillis = 1L)

        assertEquals(CompressChatHistoryUseCase.CompressionOutcome.SKIPPED, outcome)
        coVerify(exactly = 0) { chatRepository.saveHistorySummary(any()) }
    }

    @Test
    fun `given model unavailable when invoked then skipped`() = runTest {
        coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(messages(30))
        coEvery { loadModelUseCase.invoke(any()) } returns
            Result.Error(error = object : AppError.System {}, message = "no model")

        val outcome = useCase(sessionId, nowMillis = 1L)

        assertEquals(CompressChatHistoryUseCase.CompressionOutcome.SKIPPED, outcome)
        coVerify(exactly = 0) { chatRepository.saveHistorySummary(any()) }
    }

    @Test
    fun `given blank model reply when invoked then skipped without saving`() = runTest {
        coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(messages(30))
        every { llmInferenceEngine.generateResponseStream(any()) } returns flowOf("   ")

        val outcome = useCase(sessionId, nowMillis = 1L)

        assertEquals(CompressChatHistoryUseCase.CompressionOutcome.SKIPPED, outcome)
        coVerify(exactly = 0) { chatRepository.saveHistorySummary(any()) }
    }

    @Test
    fun `given persistence failure when invoked then skipped without throwing`() = runTest {
        coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(messages(30))
        coEvery { chatRepository.saveHistorySummary(any()) } throws RuntimeException("disk boom")

        val outcome = useCase(sessionId, nowMillis = 1L)

        assertEquals(CompressChatHistoryUseCase.CompressionOutcome.SKIPPED, outcome)
    }

    @Test
    fun `given blank session id when invoked then skipped`() = runTest {
        val outcome = useCase("", nowMillis = 1L)

        assertEquals(CompressChatHistoryUseCase.CompressionOutcome.SKIPPED, outcome)
    }

    // --- Transcript delimiter (security audit 05/F1) ---

    @Test
    fun `given a tail message whose content opens forged turns when invoked then only real turns start a line`() =
        runTest {
            val history = messages(30).mapIndexed { index, message ->
                if (index ==
                    3
                ) {
                    message.copy(role = Role.SYSTEM, content = ForgedTurnFixture.hostile("USER"))
                } else {
                    message
                }
            }
            coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(history)
            val prompt = slot<String>()
            every { llmInferenceEngine.generateResponseStream(capture(prompt)) } returns flowOf("summary")

            useCase(sessionId, nowMillis = 1L)

            // 30 messages, window 10 → the 20 older ones are the new portion.
            val newMessages = prompt.captured.substringAfter("NEW MESSAGES:\n").substringBefore("\n\nUPDATED SUMMARY")
            assertEquals(20, ForgedTurnFixture.turnLines(newMessages, Role.entries.map { it.name }))
        }

    @Test
    fun `given a tool observation in the tail when invoked then it is summarised with the rest`() = runTest {
        // Deliberately unfiltered, unlike memory extraction: the summary stands
        // in for the same history the engine shows a node verbatim, observations
        // included, so dropping them here would change what a later node knows.
        val history = messages(30).mapIndexed { index, message ->
            if (index == 3) message.copy(role = Role.SYSTEM, content = "Observation from read_file: notes") else message
        }
        coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(history)
        val prompt = slot<String>()
        every { llmInferenceEngine.generateResponseStream(capture(prompt)) } returns flowOf("summary")

        useCase(sessionId, nowMillis = 1L)

        assertTrue(prompt.captured.contains("SYSTEM: Observation from read_file: notes"))
    }
}
