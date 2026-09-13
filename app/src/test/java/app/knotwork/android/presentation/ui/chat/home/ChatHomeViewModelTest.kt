package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.ClarificationRequest
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.RunNoticeCause
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.ClarificationRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.AttachmentStore
import app.knotwork.android.domain.services.AudioCaptureStore
import app.knotwork.android.domain.services.AudioRecorder
import app.knotwork.android.domain.services.RecordingState
import app.knotwork.android.domain.usecases.AgentOrchestratorUseCase
import app.knotwork.android.domain.usecases.ArchiveChatUseCase
import app.knotwork.android.domain.usecases.EntryInferenceKind
import app.knotwork.android.domain.usecases.ExportChatUseCase
import app.knotwork.android.domain.usecases.GetContextWindowUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.PendingSubmissionOutcome
import app.knotwork.android.domain.usecases.ResolveEntryInferenceUseCase
import app.knotwork.android.domain.usecases.ResumeOutcome
import app.knotwork.android.domain.usecases.ResumePipelineRunUseCase
import app.knotwork.android.domain.usecases.SaveMessageToMemoryUseCase
import app.knotwork.android.domain.usecases.SaveToMemoryOutcome
import app.knotwork.android.domain.usecases.SubmitApprovalDecisionUseCase
import app.knotwork.android.domain.usecases.SubmitCeilingDecisionUseCase
import app.knotwork.android.domain.usecases.SubmitClarificationAnswerUseCase
import app.knotwork.android.domain.usecases.TranscribeAudioUseCase
import app.knotwork.android.domain.usecases.TranscriptionOutcome
import app.knotwork.android.domain.usecases.UnarchiveChatUseCase
import app.knotwork.android.presentation.state.ActiveSessionTracker
import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMessageStatus
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chat.ComposerVoiceNotice
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.components.console.ConsoleSnap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit-tests for [ChatHomeViewModel].
 *
 * Covers the orchestrator-driven send/stop cycle, session initialisation,
 * thread switching, the pipeline-binding deleted-fallback Snackbar event,
 * auto-rename of new chats, model-not-loaded error gate, and the
 * `ChatMessage → ChatHomeMessageRow` mapping on the companion.
 *
 * Out of scope here (later tasks):
 *  - HITL `WaitingForApproval` / `AwaitingClarification`
 *  - Console `ConsoleLog` aggregation
 *  - Drawer / overflow / model-picker callbacks
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress(
    // Reason: chat home tests cover a 12-method ViewModel surface — every
    // public entry-point gets at least one happy-path assertion.
    "LargeClass",
    "LongMethod",
)
class ChatHomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var agentOrchestratorUseCase: AgentOrchestratorUseCase
    private lateinit var chatRepository: ChatRepository
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var getContextWindowUseCase: GetContextWindowUseCase
    private lateinit var llmInferenceEngine: LlmInferenceEngine
    private lateinit var clarificationRepository: ClarificationRepository
    private lateinit var localModelRepository: LocalModelRepository
    private lateinit var loadModelUseCase: LoadModelUseCase
    private lateinit var saveMessageToMemoryUseCase: SaveMessageToMemoryUseCase
    private lateinit var pipelineRunRepository: PipelineRunRepository
    private lateinit var runTraceRepository: RunTraceRepository
    private lateinit var resumePipelineRunUseCase: ResumePipelineRunUseCase
    private lateinit var pendingInteractionRepository: PendingInteractionRepository
    private lateinit var submitApprovalDecisionUseCase: SubmitApprovalDecisionUseCase
    private lateinit var submitCeilingDecisionUseCase: SubmitCeilingDecisionUseCase
    private lateinit var submitClarificationAnswerUseCase: SubmitClarificationAnswerUseCase
    private lateinit var attachmentStore: AttachmentStore
    private lateinit var resolveEntryInferenceUseCase: ResolveEntryInferenceUseCase
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioCaptureStore: AudioCaptureStore
    private lateinit var transcribeAudioUseCase: TranscribeAudioUseCase

    private lateinit var sessionsFlow: MutableStateFlow<List<ChatSession>>
    private lateinit var localModelsFlow: MutableStateFlow<List<LocalModel>>
    private lateinit var pipelinesFlow: MutableStateFlow<List<PipelineGraph>>
    private lateinit var messagesFlow: MutableStateFlow<List<ChatMessage>>
    private lateinit var savedSessionIdFlow: MutableStateFlow<String?>
    private lateinit var defaultPipelineIdFlow: MutableStateFlow<String?>
    private lateinit var maxContextLengthFlow: MutableStateFlow<Int>
    private lateinit var consolePreferredConsoleTabNameFlow: MutableStateFlow<String>
    private lateinit var activeRunsFlow: MutableStateFlow<Set<String>>

    private lateinit var viewModel: ChatHomeViewModel
    private lateinit var activeSessionTracker: ActiveSessionTracker
    private lateinit var archiveChatUseCase: ArchiveChatUseCase
    private lateinit var unarchiveChatUseCase: UnarchiveChatUseCase
    private lateinit var exportChatUseCase: ExportChatUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        activeSessionTracker = ActiveSessionTracker()
        agentOrchestratorUseCase = mockk(relaxed = true)
        chatRepository = mockk()
        // Real use cases over the mocked repository: they are thin
        // validate-and-delegate wrappers, so stubbing them would test the stub.
        archiveChatUseCase = ArchiveChatUseCase(chatRepository)
        unarchiveChatUseCase = UnarchiveChatUseCase(chatRepository)
        pipelineRepository = mockk()
        settingsRepository = mockk(relaxed = true)
        getContextWindowUseCase = mockk()
        llmInferenceEngine = mockk(relaxed = true)
        clarificationRepository = mockk(relaxed = true)
        localModelRepository = mockk(relaxed = true)
        loadModelUseCase = mockk()
        saveMessageToMemoryUseCase = mockk()
        pipelineRunRepository = mockk()
        exportChatUseCase = ExportChatUseCase(chatRepository, pipelineRunRepository)
        runTraceRepository = mockk()
        resumePipelineRunUseCase = mockk()
        pendingInteractionRepository = mockk(relaxed = true)
        coEvery { pendingInteractionRepository.getForSession(any()) } returns null
        submitApprovalDecisionUseCase = mockk(relaxed = true)
        submitCeilingDecisionUseCase = mockk(relaxed = true)
        attachmentStore = mockk(relaxed = true)
        resolveEntryInferenceUseCase = mockk()
        audioRecorder = mockk(relaxed = true)
        every { audioRecorder.state } returns MutableStateFlow(RecordingState.Idle)
        audioCaptureStore = mockk(relaxed = true)
        transcribeAudioUseCase = mockk(relaxed = true)
        // Default: the bound pipeline starts on-device, so the attachment
        // pre-flight only depends on the active model's vision flag. Tests that
        // exercise the CLOUD-entry block override this.
        coEvery { resolveEntryInferenceUseCase(any()) } returns EntryInferenceKind.LOCAL
        coEvery { submitApprovalDecisionUseCase(any(), any(), any()) } returns PendingSubmissionOutcome.LiveResumed
        submitClarificationAnswerUseCase = mockk(relaxed = true)
        coEvery {
            submitClarificationAnswerUseCase(any(), any(), any())
        } returns PendingSubmissionOutcome.LiveResumed
        coEvery { pipelineRunRepository.getActiveRunForSession(any()) } returns null
        coEvery { pipelineRunRepository.getLatestRunForSession(any()) } returns null
        coEvery { pipelineRunRepository.getDescendantRuns(any()) } returns emptyList()
        coEvery { runTraceRepository.getTraceForRun(any()) } returns emptyList()
        every { settingsRepository.resumeMaxAgeHours } returns
            MutableStateFlow(SettingsDefaults.RESUME_MAX_AGE_HOURS_DEFAULT)
        activeRunsFlow = MutableStateFlow(emptySet())
        every { pipelineRunRepository.observeActiveRunSessionIds() } returns activeRunsFlow
        every { pipelineRunRepository.observeRunsForSession(any()) } returns flowOf(emptyList())
        coEvery { clarificationRepository.submitClarification(any(), any()) } returns true

        sessionsFlow = MutableStateFlow(emptyList())
        localModelsFlow = MutableStateFlow(emptyList())
        pipelinesFlow = MutableStateFlow(emptyList())
        messagesFlow = MutableStateFlow(emptyList())
        savedSessionIdFlow = MutableStateFlow(null)
        defaultPipelineIdFlow = MutableStateFlow(null)
        maxContextLengthFlow = MutableStateFlow(DEFAULT_TOKENS_MAX)
        consolePreferredConsoleTabNameFlow = MutableStateFlow("Logs")

        every { llmInferenceEngine.isInitialized } returns true
        every { localModelRepository.getAllModels() } returns localModelsFlow
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        coEvery { chatRepository.renameSession(any(), any()) } answers {
            val id = firstArg<String>()
            val name = secondArg<String>()
            sessionsFlow.value = sessionsFlow.value.map { if (it.id == id) it.copy(name = name) else it }
            Unit
        }
        coEvery { chatRepository.setSessionFavorite(any(), any()) } returns Unit
        coEvery { chatRepository.deleteSession(any()) } returns Unit
        coEvery { chatRepository.importChat(any()) } returns "imported-session-id"
        coEvery { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
        coEvery { chatRepository.getSessionById(any()) } returns null
        every { chatRepository.getSessionsFlow(any()) } returns sessionsFlow
        every { chatRepository.getDisplayMessagesForSession(any()) } returns messagesFlow
        coEvery { chatRepository.saveSession(any()) } answers {
            val saved = firstArg<ChatSession>()
            sessionsFlow.value = sessionsFlow.value.filterNot { it.id == saved.id } + saved
            Unit
        }
        coEvery { chatRepository.saveMessage(any()) } returns Unit
        every { pipelineRepository.getAllPipelines() } returns pipelinesFlow
        every { settingsRepository.currentChatSessionId } returns savedSessionIdFlow
        every { settingsRepository.defaultPipelineId } returns defaultPipelineIdFlow
        every { settingsRepository.maxContextLength } returns maxContextLengthFlow
        every { settingsRepository.audioMaxDurationSec } returns flowOf(AUDIO_LIMIT_SEC)
        every { settingsRepository.consolePreferredConsoleTabName } returns consolePreferredConsoleTabNameFlow
        coEvery { settingsRepository.setConsolePreferredConsoleTabName(any()) } answers {
            consolePreferredConsoleTabNameFlow.value = firstArg()
        }
        coEvery { settingsRepository.setCurrentChatSessionId(any()) } answers {
            savedSessionIdFlow.value = firstArg()
        }
        coEvery { getContextWindowUseCase(any()) } returns ""
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatHomeViewModel = ChatHomeViewModel(
        agentOrchestratorUseCase,
        chatRepository,
        pipelineRepository,
        settingsRepository,
        getContextWindowUseCase,
        llmInferenceEngine,
        clarificationRepository,
        localModelRepository,
        loadModelUseCase,
        mockk(relaxed = true),
        mockk(relaxed = true),
        saveMessageToMemoryUseCase,
        pipelineRunRepository,
        runTraceRepository,
        resumePipelineRunUseCase,
        pendingInteractionRepository,
        submitApprovalDecisionUseCase,
        submitClarificationAnswerUseCase,
        submitCeilingDecisionUseCase,
        attachmentStore,
        resolveEntryInferenceUseCase,
        audioRecorder,
        audioCaptureStore,
        transcribeAudioUseCase,
        activeSessionTracker,
        archiveChatUseCase,
        exportChatUseCase,
        unarchiveChatUseCase,
    ).also { vm ->
        // Keep the replay projection on the test scheduler so
        // advanceUntilIdle() deterministically covers it.
        vm.traceProjectionDispatcher = testDispatcher
    }

    @Test
    fun `init generates session id when none persisted`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        val sessionId = viewModel.state.value.thread.currentSessionId
        assertTrue("Generated session id must not be blank", sessionId.isNotBlank())
        coVerify { settingsRepository.setCurrentChatSessionId(any()) }
        coVerify { chatRepository.saveSession(match { it.id == sessionId }) }
    }

    @Test
    fun `init restores session id from settings without generating a new one`() = runTest(testDispatcher) {
        val saved = "saved-session-42"
        savedSessionIdFlow.value = saved
        sessionsFlow.value = listOf(ChatSession(id = saved, name = "Existing", updatedAt = 0))

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(saved, viewModel.state.value.thread.currentSessionId)
        coVerify(exactly = 0) { settingsRepository.setCurrentChatSessionId(any()) }
    }

    @Test
    fun `initial state is Empty with no messages`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(ChatHomeUiState.Empty, viewModel.state.value.visual)
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun `messages flow projects ChatMessage rows when display flow emits`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId

        messagesFlow.value = listOf(
            ChatMessage(id = 1, sessionId = sessionId, role = Role.USER, content = "hi", timestamp = 0),
            ChatMessage(id = 2, sessionId = sessionId, role = Role.AGENT, content = "hello", timestamp = 0),
        )
        advanceUntilIdle()

        val rows = viewModel.state.value.messages
        assertEquals(2, rows.size)
        assertEquals(ChatRole.User, rows[0].role)
        assertEquals(ChatRole.Assistant, rows[1].role)
        assertEquals("hi", (rows[0].content as ChatContent.Text).text)
        // Agent rows now carry Markdown
        // content so the host-supplied markdown renderer formats them.
        assertEquals("hello", (rows[1].content as ChatContent.Markdown).source)
        assertEquals(ChatHomeUiState.Idle, viewModel.state.value.visual)
    }

    @Test
    fun `saveMessageToMemory persists the row text and emits a Saved event`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        messagesFlow.value = listOf(
            ChatMessage(id = 1, sessionId = sessionId, role = Role.USER, content = "remember me", timestamp = 0),
        )
        advanceUntilIdle()
        val rowId = viewModel.state.value.messages.first().id
        coEvery { saveMessageToMemoryUseCase("remember me") } returns SaveToMemoryOutcome.Saved(id = 1L)

        val events = mutableListOf<MemorySaveEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.transfer.memorySaveEvents.collect { events.add(it) }
        }

        viewModel.transfer.saveMessageToMemory(rowId)
        advanceUntilIdle()

        coVerify(exactly = 1) { saveMessageToMemoryUseCase("remember me") }
        assertEquals(listOf(MemorySaveEvent.Saved), events)
    }

    @Test
    fun `saveMessageToMemory emits a Failed event when the use case fails`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        messagesFlow.value = listOf(
            ChatMessage(id = 1, sessionId = sessionId, role = Role.USER, content = "remember me", timestamp = 0),
        )
        advanceUntilIdle()
        val rowId = viewModel.state.value.messages.first().id
        coEvery { saveMessageToMemoryUseCase(any()) } returns SaveToMemoryOutcome.Failed(RuntimeException("boom"))

        val events = mutableListOf<MemorySaveEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.transfer.memorySaveEvents.collect { events.add(it) }
        }

        viewModel.transfer.saveMessageToMemory(rowId)
        advanceUntilIdle()

        assertEquals(listOf(MemorySaveEvent.Failed), events)
    }

    @Test
    fun `composer value is hoisted via onComposerValueChange`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onComposerValueChange("hello")
        assertEquals("hello", viewModel.state.value.composer.value)
    }

    @Test
    fun `typed-confirm value is hoisted via onTypedConfirmChange`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.hitl.onTypedConfirmChange("yes")
        assertEquals("yes", viewModel.state.value.composer.typedConfirm)
    }

    @Test
    fun `sendMessage with blank composer is a no-op`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onComposerValueChange("   ")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(ChatHomeUiState.Empty, viewModel.state.value.visual)
        coVerify(exactly = 0) { agentOrchestratorUseCase(any(), any(), any()) }
    }

    @Test
    fun `given a cloud node streaming then the status names the cloud not the device backend`() =
        runTest(testDispatcher) {
            // The status pill exists to say where the prompt is being processed. While a
            // CLOUD node streams, nothing is decoding on this device, so reporting the
            // local GPU/CPU backend would be a false claim about exactly that.
            every { llmInferenceEngine.activeBackend } returns LocalBackend.GPU

            viewModel = createViewModel()
            advanceUntilIdle()
            val sessionId = viewModel.state.value.thread.currentSessionId
            coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
                emit(
                    AgentOrchestratorState.PipelineStage(
                        AgentOrchestratorState.PipelineStepInfo(1, 3, "CLOUD"),
                    ),
                )
                emit(AgentOrchestratorState.Answering("some streamed text"))
            }

            viewModel.onComposerValueChange("hi")
            viewModel.sendMessage()
            advanceUntilIdle()

            assertEquals("cloud", viewModel.state.value.tokens.backend)
        }

    @Test
    fun `given a local node streaming then the status still names the device backend`() = runTest(testDispatcher) {
        // The counter-case: the backend hint exists to expose a silent CPU fallback,
        // so it must survive for on-device nodes.
        every { llmInferenceEngine.activeBackend } returns LocalBackend.GPU

        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(
                AgentOrchestratorState.PipelineStage(
                    AgentOrchestratorState.PipelineStepInfo(1, 3, "LITE_RT"),
                ),
            )
            emit(AgentOrchestratorState.Answering("some streamed text"))
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(LocalBackend.GPU.key, viewModel.state.value.tokens.backend)
    }

    @Test
    fun `given a typed stop then the cause reaches the surface, not only the record`() = runTest(testDispatcher) {
        // The seam between the engine's typed cause and the sentence the user
        // reads. Dropping `state.reason` here compiles, keeps the run record
        // correct, and silently restores the defect this change exists to fix:
        // the user sees a raw diagnostic in a destructive tile.
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            // A live state first: the send collector drops a leading terminal
            // state as a stale replay of the previous run.
            emit(AgentOrchestratorState.PipelineStage(AgentOrchestratorState.PipelineStepInfo(1, 3, "CLOUD")))
            emit(
                AgentOrchestratorState.Error(
                    message = "step-ceiling: 15/15 steps",
                    reason = RunTerminationReason.StepCeiling(limit = 15, spent = 15),
                ),
            )
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        val visual = viewModel.state.value.visual
        assertTrue("expected an Error visual, got $visual", visual is ChatHomeUiState.Error)
        assertEquals(
            RunTerminationReason.StepCeiling(limit = 15, spent = 15),
            (visual as ChatHomeUiState.Error).reason,
        )
    }

    @Test
    fun `given a run advisory when the run is stopped then the strip goes with it`() = runTest(testDispatcher) {
        // A notice belongs to the run that raised it. Left behind, it advertises
        // a limit above a composer with nothing running.
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.PipelineStage(AgentOrchestratorState.PipelineStepInfo(1, 3, "CLOUD")))
            emit(
                AgentOrchestratorState.RunNotice(
                    RunNoticeCause.ApproachingCeiling(axis = RunCeilingAxis.STEPS, spent = 12, hardLimit = 15),
                ),
            )
            // Never completes: the run is still going when the user taps Stop.
            awaitCancellation()
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertNotNull("the advisory should be showing while the run is live", viewModel.state.value.runNotice)

        viewModel.stopGeneration()
        advanceUntilIdle()

        assertNull("the advisory outlived its run", viewModel.state.value.runNotice)
    }

    @Test
    fun `sendMessage auto-loads the model then sends when it was not initialized`() = runTest(testDispatcher) {
        // The model starts unloaded and becomes ready once the load succeeds —
        // the send must then proceed automatically, in a single user tap.
        var modelLoaded = false
        every { llmInferenceEngine.isInitialized } answers { modelLoaded }
        coEvery { loadModelUseCase() } answers {
            modelLoaded = true
            Result.Success(Unit)
        }

        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.Completed("done"))
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify { loadModelUseCase() }
        coVerify { agentOrchestratorUseCase(sessionId, "hi", null) }
        // The composer clears only because the message was actually sent.
        assertEquals("", viewModel.state.value.composer.value)
    }

    @Test
    fun `sendMessage surfaces an error and keeps the draft when the model load fails`() = runTest(testDispatcher) {
        every { llmInferenceEngine.isInitialized } returns false
        coEvery { loadModelUseCase() } returns Result.Error(
            error = object : AppError.System {},
            message = "no active model",
        )

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onComposerValueChange("hello")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.state.value.visual
        assertTrue("Expected Error, got $state", state is ChatHomeUiState.Error)
        // The draft survives so Retry (or a manual resend) can deliver it.
        assertEquals("hello", viewModel.state.value.composer.value)
        coVerify(exactly = 0) { agentOrchestratorUseCase(any(), any(), any()) }
    }

    @Test
    fun `retryAfterError loads the model and resends the retained draft`() = runTest(testDispatcher) {
        // First send fails to load; Retry then succeeds and must deliver the
        // draft that is still sitting in the composer.
        var modelLoaded = false
        every { llmInferenceEngine.isInitialized } answers { modelLoaded }
        coEvery { loadModelUseCase() } returns Result.Error(
            error = object : AppError.System {},
            message = "no active model",
        )

        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.Completed("done"))
        }
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.visual is ChatHomeUiState.Error)

        // Now the model can load; Retry must load-and-send.
        coEvery { loadModelUseCase() } answers {
            modelLoaded = true
            Result.Success(Unit)
        }
        viewModel.retryAfterError()
        advanceUntilIdle()

        coVerify { agentOrchestratorUseCase(sessionId, "hi", null) }
        assertEquals("", viewModel.state.value.composer.value)
    }

    @Test
    fun `retryAfterError on a healthy engine re-runs the failed turn and leaves the draft alone`() =
        runTest(testDispatcher) {
            // A non-model error is showing; the user types new text while reading
            // it and taps Retry. Retry must re-run the turn that FAILED — not the
            // text in the composer, which is a different message the user has not
            // pressed Send on, and which must survive untouched.
            every { llmInferenceEngine.isInitialized } returns true
            viewModel = createViewModel()
            advanceUntilIdle()
            val sessionId = viewModel.state.value.thread.currentSessionId
            every { chatRepository.getMessagesForSession(sessionId) } returns flowOf(
                listOf(
                    ChatMessage(sessionId = sessionId, role = Role.USER, content = "the failed turn", timestamp = 1L),
                ),
            )
            viewModel.forceState(ChatHomeUiState.Error("boom"))
            viewModel.onComposerValueChange("typed while reading the error")

            viewModel.retryAfterError()
            advanceUntilIdle()

            coVerify {
                agentOrchestratorUseCase(
                    sessionId = sessionId,
                    userPrompt = "the failed turn",
                    pipelineId = any(),
                    attachment = any(),
                    displayContent = any(),
                    // The failed attempt already persisted the user row; re-running
                    // must not put a second copy of it in the thread.
                    persistUserMessage = false,
                )
            }
            assertEquals("typed while reading the error", viewModel.state.value.composer.value)
        }

    @Test
    fun `retryAfterError with no user turn to repeat clears the error instead of stranding the screen`() =
        runTest(testDispatcher) {
            every { llmInferenceEngine.isInitialized } returns true
            viewModel = createViewModel()
            advanceUntilIdle()
            val sessionId = viewModel.state.value.thread.currentSessionId
            every { chatRepository.getMessagesForSession(sessionId) } returns flowOf(emptyList())
            viewModel.forceState(ChatHomeUiState.Error("boom"))

            viewModel.retryAfterError()
            advanceUntilIdle()

            coVerify(exactly = 0) { agentOrchestratorUseCase(any(), any(), any()) }
            assertTrue(viewModel.state.value.visual !is ChatHomeUiState.Error)
        }

    @Test
    fun `selectThread cancels an in-flight load-then-send so it never fires on the new chat`() =
        runTest(testDispatcher) {
            savedSessionIdFlow.value = "chat-A"
            sessionsFlow.value = listOf(
                ChatSession(id = "chat-A", name = "A", updatedAt = 0),
                ChatSession(id = "chat-B", name = "B", updatedAt = 0),
            )
            // Model starts cold; the load suspends on a gate so it is still in
            // flight when the user switches chats.
            var modelLoaded = false
            every { llmInferenceEngine.isInitialized } answers { modelLoaded }
            val loadGate = CompletableDeferred<Unit>()
            coEvery { loadModelUseCase() } coAnswers {
                loadGate.await()
                modelLoaded = true
                Result.Success(Unit)
            }
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onComposerValueChange("hello from A")
            viewModel.sendMessage()
            runCurrent()
            assertEquals(
                ChatHomeUiState.Generating(preparingModel = true),
                viewModel.state.value.visual,
            )

            // Switch chats while the load is still suspended → the pending send
            // must be cancelled.
            viewModel.selectThread("chat-B")
            advanceUntilIdle()
            // Even if the load now completes, no send may fire.
            loadGate.complete(Unit)
            advanceUntilIdle()

            coVerify(exactly = 0) { agentOrchestratorUseCase(any(), any(), any()) }
        }

    @Test
    fun `sendMessage flips to Generating then Idle when orchestrator completes`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.Loading)
            emit(AgentOrchestratorState.Completed("done"))
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.composer.value)
        assertEquals(ChatHomeUiState.Empty, viewModel.state.value.visual) // no messages persisted in this stub
        coVerify { agentOrchestratorUseCase(sessionId, "hi", null) }
    }

    @Test
    fun `send drops the replayed previous-run terminal and stays Generating`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        // Reproduces the second-send case: the per-session replay-1 flow hands the
        // new collector the PREVIOUS run's Completed first. With no live state yet,
        // the fresh run must remain Generating rather than settling immediately.
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.Completed("previous answer"))
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.visual is ChatHomeUiState.Generating)
    }

    @Test
    fun `send still settles after dropping the stale terminal when the new run completes`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        // Stale Completed (dropped) followed by the fresh run's own lifecycle:
        // the real terminal still settles the surface out of Generating.
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.Completed("stale replay"))
            emit(AgentOrchestratorState.Loading)
            emit(AgentOrchestratorState.Completed("fresh answer"))
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.visual !is ChatHomeUiState.Generating)
    }

    @Test
    fun `onAttachClicked shows chooser and dismissSourceChooser hides it`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.attachments.onAttachClicked()
        assertTrue(viewModel.state.value.sourceChooserVisible)

        viewModel.attachments.dismissSourceChooser()
        assertFalse(viewModel.state.value.sourceChooserVisible)
    }

    @Test
    fun `onImagePicked ingests and settles composer attachment to Ready`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val stored = MessageAttachment(path = "p.jpg", mimeType = "image/jpeg", width = 712, height = 1536)
        coEvery { attachmentStore.ingestUri("content://pick") } returns kotlin.Result.success(stored)
        every { attachmentStore.absolutePathFor("p.jpg") } returns "/tmp/p.jpg"

        viewModel.attachments.onImagePicked("content://pick")
        advanceUntilIdle()

        val draft = viewModel.state.value.composer.attachment
        assertTrue(draft is ComposerAttachmentDraft.Ready)
        val ready = draft as ComposerAttachmentDraft.Ready
        assertEquals(stored, ready.attachment)
        assertTrue("detail should carry dimensions", ready.detail.contains("712×1536"))
        assertFalse(viewModel.state.value.sourceChooserVisible)
    }

    @Test
    fun `replacing an attachment announces it only once the replacement is in hand`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val first = MessageAttachment(path = "first.jpg", mimeType = "image/jpeg", width = 10, height = 10)
        coEvery { attachmentStore.ingestUri("content://first") } returns kotlin.Result.success(first)
        every { attachmentStore.absolutePathFor(any()) } returns "/tmp/x.jpg"
        val replaced = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.attachments.attachmentReplacedEvents.collect { replaced.add(it) }
        }

        viewModel.attachments.onImagePicked("content://first")
        advanceUntilIdle()
        assertTrue("the first attachment replaces nothing", replaced.isEmpty())

        val second = MessageAttachment(path = "second.jpg", mimeType = "image/jpeg", width = 20, height = 20)
        coEvery { attachmentStore.ingestUri("content://second") } coAnswers {
            // Asserted mid-flight: counting events at the end would pass just as
            // well if the announcement fired before the ingest, which is the
            // bug this test is named for.
            assertTrue("nothing may be announced while the ingest is in flight", replaced.isEmpty())
            kotlin.Result.success(second)
        }
        viewModel.attachments.onImagePicked("content://second")
        advanceUntilIdle()

        assertEquals("the replacement is announced once", 1, replaced.size)
    }

    @Test
    fun `a replacement that fails to ingest is not announced as a replacement`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val first = MessageAttachment(path = "first.jpg", mimeType = "image/jpeg", width = 10, height = 10)
        coEvery { attachmentStore.ingestUri("content://first") } returns kotlin.Result.success(first)
        every { attachmentStore.absolutePathFor(any()) } returns "/tmp/x.jpg"
        val replaced = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.attachments.attachmentReplacedEvents.collect { replaced.add(it) }
        }
        viewModel.attachments.onImagePicked("content://first")
        advanceUntilIdle()

        coEvery { attachmentStore.ingestUri("content://bad") } returns kotlin.Result.failure(RuntimeException("bad"))
        viewModel.attachments.onImagePicked("content://bad")
        advanceUntilIdle()

        // A failed re-pick replaces nothing, says nothing about replacing, and —
        // the part that actually mattered — costs the user nothing: the image
        // that was already attached is still attached.
        assertTrue("a failed ingest replaced nothing", replaced.isEmpty())
        val draft = viewModel.state.value.composer.attachment
        assertTrue("the previous attachment must survive a failed re-pick", draft is ComposerAttachmentDraft.Ready)
        assertEquals(first, (draft as ComposerAttachmentDraft.Ready).attachment)
        coVerify(exactly = 0) { attachmentStore.delete("first.jpg") }
    }

    @Test
    fun `removing the draft while a pick is in flight is not undone by the pick landing`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val first = MessageAttachment(path = "first.jpg", mimeType = "image/jpeg", width = 10, height = 10)
        coEvery { attachmentStore.ingestUri("content://first") } returns kotlin.Result.success(first)
        every { attachmentStore.absolutePathFor(any()) } returns "/tmp/x.jpg"
        viewModel.attachments.onImagePicked("content://first")
        advanceUntilIdle()

        // Pick a second image and drop the whole draft before the ingest lands.
        // The remove button is live during Processing, so this is reachable.
        val second = MessageAttachment(path = "second.jpg", mimeType = "image/jpeg", width = 20, height = 20)
        coEvery { attachmentStore.ingestUri("content://second") } returns kotlin.Result.success(second)
        viewModel.attachments.onImagePicked("content://second")
        viewModel.attachments.removeAttachment()
        advanceUntilIdle()

        // Neither image comes back: the user said remove, and a pick landing
        // afterwards must not overrule that.
        assertNull(viewModel.state.value.composer.attachment)
        coVerify { attachmentStore.delete("second.jpg") }
    }

    @Test
    fun `when two picks race the later one wins`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { attachmentStore.absolutePathFor(any()) } returns "/tmp/x.jpg"
        val first = MessageAttachment(path = "first.jpg", mimeType = "image/jpeg", width = 10, height = 10)
        val second = MessageAttachment(path = "second.jpg", mimeType = "image/jpeg", width = 20, height = 20)
        // Both ingests are in flight at once and the EARLIER pick finishes
        // first — the only ordering in which the bug shows. (With the later pick
        // finishing first the slot is already `Ready`, and even a bare type
        // check rejects the straggler, which is why the first version of this
        // test passed against the broken code.)
        coEvery { attachmentStore.ingestUri("content://earlier") } coAnswers {
            delay(EARLIER_INGEST_MS)
            kotlin.Result.success(first)
        }
        coEvery { attachmentStore.ingestUri("content://later") } coAnswers {
            delay(LATER_INGEST_MS)
            kotlin.Result.success(second)
        }

        viewModel.attachments.onImagePicked("content://earlier")
        viewModel.attachments.onImagePicked("content://later")
        advanceUntilIdle()

        // Ownership is the pick's identity, not the draft's shape: both picks see
        // a `Processing` slot, so a type check alone let whichever finished first
        // win — which for the user means the image they picked second vanishes.
        val draft = viewModel.state.value.composer.attachment
        assertTrue(draft is ComposerAttachmentDraft.Ready)
        assertEquals(second, (draft as ComposerAttachmentDraft.Ready).attachment)
        coVerify { attachmentStore.delete("first.jpg") }
    }

    @Test
    fun `onImagePicked failure clears draft and emits a transient error event without clobbering visual`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            val visualBefore = viewModel.state.value.visual
            coEvery { attachmentStore.ingestUri(any()) } returns kotlin.Result.failure(RuntimeException("bad"))
            val events = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.attachments.attachmentErrorEvents.collect { events.add(it) }
            }

            viewModel.attachments.onImagePicked("content://bad")
            advanceUntilIdle()

            assertNull(viewModel.state.value.composer.attachment)
            assertEquals(1, events.size)
            // The surface's main visual axis is untouched (no Error clobber).
            assertEquals(visualBefore, viewModel.state.value.visual)
        }

    @Test
    fun `removeAttachment clears the pending draft`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val stored = MessageAttachment(path = "p.jpg", mimeType = "image/jpeg", width = 100, height = 100)
        coEvery { attachmentStore.ingestUri(any()) } returns kotlin.Result.success(stored)
        every { attachmentStore.absolutePathFor(any()) } returns "/tmp/p.jpg"
        viewModel.attachments.onImagePicked("content://pick")
        advanceUntilIdle()

        viewModel.attachments.removeAttachment()

        assertNull(viewModel.state.value.composer.attachment)
    }

    @Test
    fun `sendMessage with image and no caption sends the default instruction with empty display`() =
        runTest(testDispatcher) {
            // The active model must be vision-capable or the multimodal pre-flight
            // blocks the send.
            localModelsFlow.value = listOf(
                LocalModel(id = 1L, name = "Vision", path = "/v", size = 0L, isActive = true, supportsVision = true),
            )
            viewModel = createViewModel()
            advanceUntilIdle()
            val sessionId = viewModel.state.value.thread.currentSessionId
            val stored = MessageAttachment(path = "p.jpg", mimeType = "image/jpeg", width = 712, height = 1536)
            coEvery { attachmentStore.ingestUri(any()) } returns kotlin.Result.success(stored)
            every { attachmentStore.absolutePathFor(any()) } returns "/tmp/p.jpg"
            coEvery {
                agentOrchestratorUseCase(
                    sessionId,
                    DefaultPrompts.IMAGE_ONLY_DEFAULT_INSTRUCTION,
                    null,
                    stored,
                    "",
                )
            } returns flow { emit(AgentOrchestratorState.Completed("done")) }

            viewModel.attachments.onImagePicked("content://pick")
            advanceUntilIdle()
            viewModel.sendMessage()
            advanceUntilIdle()

            coVerify {
                agentOrchestratorUseCase(
                    sessionId,
                    DefaultPrompts.IMAGE_ONLY_DEFAULT_INSTRUCTION,
                    null,
                    stored,
                    "",
                )
            }
            assertNull(viewModel.state.value.composer.attachment)
        }

    @Test
    fun `image-only send does not rename the chat to the internal default instruction`() = runTest(testDispatcher) {
        localModelsFlow.value = listOf(
            LocalModel(id = 1L, name = "Vision", path = "/v", size = 0L, isActive = true, supportsVision = true),
        )
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        val stored = MessageAttachment(path = "p.jpg", mimeType = "image/jpeg", width = 712, height = 1536)
        coEvery { attachmentStore.ingestUri(any()) } returns kotlin.Result.success(stored)
        every { attachmentStore.absolutePathFor(any()) } returns "/tmp/p.jpg"
        coEvery { agentOrchestratorUseCase(sessionId, any(), any(), any(), any()) } returns
            flow { emit(AgentOrchestratorState.Completed("done")) }

        viewModel.attachments.onImagePicked("content://pick")
        advanceUntilIdle()
        viewModel.sendMessage()
        advanceUntilIdle()

        // The chat keeps its default name — the instruction text must not become the title.
        assertEquals(
            ChatHomeThreadsDelegate.DEFAULT_NEW_CHAT_NAME,
            sessionsFlow.value.first { it.id == sessionId }.name,
        )
    }

    @Test
    fun `sendMessage with image blocks and surfaces vision error when active model is text-only`() =
        runTest(testDispatcher) {
            // Active model is NOT vision-capable.
            localModelsFlow.value = listOf(
                LocalModel(id = 1L, name = "Text", path = "/t", size = 0L, isActive = true, supportsVision = false),
            )
            viewModel = createViewModel()
            advanceUntilIdle()
            val stored = MessageAttachment(path = "p.jpg", mimeType = "image/jpeg", width = 100, height = 100)
            coEvery { attachmentStore.ingestUri(any()) } returns kotlin.Result.success(stored)
            every { attachmentStore.absolutePathFor(any()) } returns "/tmp/p.jpg"

            viewModel.attachments.onImagePicked("content://pick")
            advanceUntilIdle()
            viewModel.sendMessage()
            advanceUntilIdle()

            val visual = viewModel.state.value.visual
            assertTrue(visual is ChatHomeUiState.Error)
            assertEquals(ChatHomeAttachmentDelegate.MODEL_NO_VISION_MESSAGE, (visual as ChatHomeUiState.Error).message)
            // The run never starts and the draft attachment is preserved.
            coVerify(exactly = 0) { agentOrchestratorUseCase(any(), any(), any(), any(), any()) }
            assertNotNull(viewModel.state.value.composer.attachment)
        }

    @Test
    fun `sendMessage with image blocks when the pipeline has no on-device vision sink`() = runTest(testDispatcher) {
        localModelsFlow.value = listOf(
            LocalModel(id = 1L, name = "Vision", path = "/v", size = 0L, isActive = true, supportsVision = true),
        )
        coEvery { resolveEntryInferenceUseCase(any()) } returns EntryInferenceKind.NONE
        viewModel = createViewModel()
        advanceUntilIdle()
        val stored = MessageAttachment(path = "p.jpg", mimeType = "image/jpeg", width = 100, height = 100)
        coEvery { attachmentStore.ingestUri(any()) } returns kotlin.Result.success(stored)
        every { attachmentStore.absolutePathFor(any()) } returns "/tmp/p.jpg"

        viewModel.attachments.onImagePicked("content://pick")
        advanceUntilIdle()
        viewModel.sendMessage()
        advanceUntilIdle()

        val visual = viewModel.state.value.visual
        assertTrue(visual is ChatHomeUiState.Error)
        assertEquals(ChatHomeAttachmentDelegate.PIPELINE_NO_VISION_MESSAGE, (visual as ChatHomeUiState.Error).message)
        coVerify(exactly = 0) { agentOrchestratorUseCase(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `two rapid image sends enqueue only one run (no double-send race)`() = runTest(testDispatcher) {
        localModelsFlow.value = listOf(
            LocalModel(id = 1L, name = "Vision", path = "/v", size = 0L, isActive = true, supportsVision = true),
        )
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        val stored = MessageAttachment(path = "p.jpg", mimeType = "image/jpeg", width = 100, height = 100)
        coEvery { attachmentStore.ingestUri(any()) } returns kotlin.Result.success(stored)
        every { attachmentStore.absolutePathFor(any()) } returns "/tmp/p.jpg"
        coEvery { agentOrchestratorUseCase(sessionId, any(), any(), any(), any()) } returns
            flow { emit(AgentOrchestratorState.Completed("done")) }

        viewModel.attachments.onImagePicked("content://pick")
        advanceUntilIdle()
        // Both taps land before the async pre-flight resolves; the in-flight guard
        // must reject the second so only one run is enqueued.
        viewModel.sendMessage()
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 1) { agentOrchestratorUseCase(sessionId, any(), any(), any(), any()) }
    }

    @Test
    fun `sendMessage with image blocks and surfaces cloud error when pipeline starts on a cloud node`() =
        runTest(testDispatcher) {
            // Active model is vision-capable, so only the CLOUD-entry guard can block.
            localModelsFlow.value = listOf(
                LocalModel(id = 1L, name = "Vision", path = "/v", size = 0L, isActive = true, supportsVision = true),
            )
            coEvery { resolveEntryInferenceUseCase(any()) } returns EntryInferenceKind.CLOUD
            viewModel = createViewModel()
            advanceUntilIdle()
            val stored = MessageAttachment(path = "p.jpg", mimeType = "image/jpeg", width = 100, height = 100)
            coEvery { attachmentStore.ingestUri(any()) } returns kotlin.Result.success(stored)
            every { attachmentStore.absolutePathFor(any()) } returns "/tmp/p.jpg"

            viewModel.attachments.onImagePicked("content://pick")
            advanceUntilIdle()
            viewModel.sendMessage()
            advanceUntilIdle()

            val visual = viewModel.state.value.visual
            assertTrue(visual is ChatHomeUiState.Error)
            assertEquals(
                ChatHomeAttachmentDelegate.CLOUD_ATTACHMENT_BLOCKED_MESSAGE,
                (visual as ChatHomeUiState.Error).message,
            )
            coVerify(exactly = 0) { agentOrchestratorUseCase(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `sendMessage clears pending approval and flips to Generating in one atomic emission`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            val sessionId = viewModel.state.value.thread.currentSessionId
            // Drive the surface into HitlConfirm with a captured pending tool.
            coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
                emit(
                    AgentOrchestratorState.WaitingForApproval(
                        toolName = "fs.write_file",
                        arguments = "{}",
                        risk = ToolRisk.SENSITIVE,
                    ),
                )
                delay(10_000)
            }
            viewModel.onComposerValueChange("hi")
            viewModel.sendMessage()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.visual is ChatHomeUiState.HitlConfirm)
            assertNotNull(viewModel.state.value.pending.tool)

            // Record every emission while a new send supersedes the paused run.
            val emissions = mutableListOf<ChatHomeScreenState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect { emissions.add(it) }
            }
            coEvery { agentOrchestratorUseCase(sessionId, "again", null) } returns flow { delay(10_000) }
            viewModel.onComposerValueChange("again")
            viewModel.sendMessage()
            advanceUntilIdle()

            // The Generating flip and the pending-snapshot clear must land in
            // the same flow emission — no observer may ever see the new run's
            // Generating surface still carrying the previous run's tool card.
            assertTrue(
                "Expected no emission with Generating + stale pending tool",
                emissions.none { it.visual is ChatHomeUiState.Generating && it.pending.tool != null },
            )
            assertNull(viewModel.state.value.pending.tool)
        }

    // region The user's message is on screen before the run is (pending bubble)

    @Test
    fun `given a first message in a new chat when sent then no frame shows generating without the message`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            val sessionId = viewModel.state.value.thread.currentSessionId
            // The queue has not written the row yet: the display flow stays empty.
            coEvery { agentOrchestratorUseCase(sessionId, "are we still meeting tomorrow?", null) } returns
                flow { awaitCancellation() }
            val emissions = mutableListOf<ChatHomeScreenState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect { emissions.add(it) }
            }

            viewModel.onComposerValueChange("are we still meeting tomorrow?")
            viewModel.sendMessage()
            advanceUntilIdle()

            // Before the fix the send emitted Generating over an empty thread and the
            // user's own bubble arrived only after the queue's write came back from Room.
            val generating = emissions.filter { it.visual is ChatHomeUiState.Generating }
            assertTrue("expected the send to reach Generating", generating.isNotEmpty())
            assertTrue(
                "a Generating frame showed no user message",
                generating.all { state -> state.toViewState().messages.any { it.role == ChatRole.User } },
            )
            val bubble = viewModel.state.value.toViewState().messages.single { it.role == ChatRole.User }
            assertEquals(ChatContent.Text("are we still meeting tomorrow?"), bubble.content)
            assertEquals(ChatMessageStatus.Pending, bubble.metadata.status)
        }

    @Test
    fun `given the stored row arrives when observed then it replaces the bubble instead of doubling it`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            val sessionId = viewModel.state.value.thread.currentSessionId
            coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow { awaitCancellation() }
            viewModel.onComposerValueChange("hi")
            viewModel.sendMessage()
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.sendingUserTurn)

            // The queue stamps the row when it writes it — after the send.
            messagesFlow.value = listOf(
                ChatMessage(
                    id = 7L,
                    sessionId = sessionId,
                    role = Role.USER,
                    content = "hi",
                    timestamp = System.currentTimeMillis() + 1_000L,
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.state.value.sendingUserTurn)
            val userRows = viewModel.state.value.toViewState().messages.filter { it.role == ChatRole.User }
            assertEquals(1, userRows.size)
            assertEquals(ChatMessageStatus.Sent, userRows.single().metadata.status)
        }

    @Test
    fun `given an earlier identical message is already stored when the thread updates then the bubble stays`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            val sessionId = viewModel.state.value.thread.currentSessionId
            coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow { awaitCancellation() }
            viewModel.onComposerValueChange("hi")
            viewModel.sendMessage()
            advanceUntilIdle()

            // Same text, stored before this send: it is the previous turn, not this one.
            messagesFlow.value = listOf(
                ChatMessage(id = 1L, sessionId = sessionId, role = Role.USER, content = "hi", timestamp = 1L),
            )
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.sendingUserTurn)
            assertEquals(2, viewModel.state.value.toViewState().messages.count { it.role == ChatRole.User })
        }

    @Test
    fun `given a retry when the failed turn re-runs then no second bubble is shown`() = runTest(testDispatcher) {
        every { llmInferenceEngine.isInitialized } returns true
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        every { chatRepository.getMessagesForSession(sessionId) } returns flowOf(
            listOf(ChatMessage(sessionId = sessionId, role = Role.USER, content = "the failed turn", timestamp = 1L)),
        )
        coEvery {
            agentOrchestratorUseCase(sessionId, "the failed turn", any(), any(), any(), persistUserMessage = false)
        } returns flow { awaitCancellation() }
        viewModel.forceState(ChatHomeUiState.Error("boom"))

        viewModel.retryAfterError()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.visual is ChatHomeUiState.Generating)
        assertNull("the retried row is already stored", viewModel.state.value.sendingUserTurn)
    }

    @Test
    fun `given a bubble when the run fails or is stopped then the bubble is dropped`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "fails", null) } returns flow {
            // A leading terminal emission is dropped as the previous run's replay, so
            // the run shows it is live before it fails — as a real one does.
            emit(AgentOrchestratorState.Loading)
            emit(AgentOrchestratorState.Error("no pipeline"))
        }
        viewModel.onComposerValueChange("fails")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertNull(
            "a run that failed before storing the message must not leave it pending",
            viewModel.state.value.sendingUserTurn,
        )

        coEvery { agentOrchestratorUseCase(sessionId, "stopped", null) } returns flow { awaitCancellation() }
        viewModel.onComposerValueChange("stopped")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.sendingUserTurn)
        viewModel.stopGeneration()
        advanceUntilIdle()
        assertNull(viewModel.state.value.sendingUserTurn)
    }

    @Test
    fun `given a new chat the session list has not observed yet when the first message is sent then it is renamed`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            val sessionId = viewModel.state.value.thread.currentSessionId
            // The stored row exists, but the observed list does not carry it yet —
            // the window a message typed straight into a new chat lands in.
            val stored = sessionsFlow.value.first { it.id == sessionId }
            sessionsFlow.value = sessionsFlow.value.filterNot { it.id == sessionId }
            advanceUntilIdle()
            coEvery { chatRepository.getSessionById(sessionId) } returns stored
            coEvery { agentOrchestratorUseCase(sessionId, "plan the trip", null) } returns
                flowOf(AgentOrchestratorState.Completed("ok"))

            viewModel.onComposerValueChange("plan the trip")
            viewModel.sendMessage()
            advanceUntilIdle()

            coVerify(exactly = 1) { chatRepository.renameSession(sessionId, "plan the trip") }
            // A targeted rename, never a whole-row save built from a stale copy.
            coVerify(exactly = 0) {
                chatRepository.saveSession(match { it.id == sessionId && it.name == "plan the trip" })
            }
        }

    // endregion

    @Test
    fun `sendMessage flips to Error when orchestrator throws`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            throw RuntimeException("boom")
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.state.value.visual
        assertTrue("Expected Error, got $state", state is ChatHomeUiState.Error)
        assertEquals("boom", (state as ChatHomeUiState.Error).message)
    }

    @Test
    fun `sendMessage forwards the pipelineId bound to the active session`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        val expectedPipeline = "pipeline-abc"
        // Publish the pipeline first so the deleted-fallback handler keeps the binding intact.
        pipelinesFlow.value = listOf(PipelineGraph(id = expectedPipeline, name = "Bound"))
        sessionsFlow.value = listOf(
            ChatSession(id = sessionId, name = "S", updatedAt = 0, pipelineId = expectedPipeline),
        )
        advanceUntilIdle()
        coEvery { agentOrchestratorUseCase(sessionId, "hi", expectedPipeline) } returns flowOf(
            AgentOrchestratorState.Completed("ok"),
        )

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify { agentOrchestratorUseCase(sessionId, "hi", expectedPipeline) }
    }

    @Test
    fun `sendMessage auto-renames a new chat after the first user prompt`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        // initializeSession() created a session named DEFAULT_NEW_CHAT_NAME
        assertEquals(
            ChatHomeThreadsDelegate.DEFAULT_NEW_CHAT_NAME,
            sessionsFlow.value.first { it.id == sessionId }.name,
        )
        coEvery { agentOrchestratorUseCase(sessionId, any(), any()) } returns flowOf(
            AgentOrchestratorState.Completed("ok"),
        )

        viewModel.onComposerValueChange("hello world")
        viewModel.sendMessage()
        advanceUntilIdle()

        val renamed = sessionsFlow.value.first { it.id == sessionId }
        assertEquals("hello world", renamed.name)
    }

    @Test
    fun `sendMessage truncates an over-long prompt when auto-renaming`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        val long = "x".repeat(ChatHomeThreadsDelegate.AUTO_RENAME_CHAR_LIMIT + 5)
        coEvery { agentOrchestratorUseCase(sessionId, any(), any()) } returns flowOf(
            AgentOrchestratorState.Completed("ok"),
        )

        viewModel.onComposerValueChange(long)
        viewModel.sendMessage()
        advanceUntilIdle()

        val renamed = sessionsFlow.value.first { it.id == sessionId }.name
        assertEquals(
            "x".repeat(ChatHomeThreadsDelegate.AUTO_RENAME_CHAR_LIMIT) + ChatHomeThreadsDelegate.AUTO_RENAME_SUFFIX,
            renamed,
        )
    }

    @Test
    fun `sendMessage collapses whitespace into a single-line title when auto-renaming`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, any(), any()) } returns flowOf(
            AgentOrchestratorState.Completed("ok"),
        )

        viewModel.onComposerValueChange("  Plan a trip\n\nto   Rome  ")
        viewModel.sendMessage()
        advanceUntilIdle()

        val renamed = sessionsFlow.value.first { it.id == sessionId }.name
        assertEquals("Plan a trip to Rome", renamed)
    }

    @Test
    fun `stopGeneration cancels the in-flight job and returns to a resting state`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.Loading)
            delay(10_000)
            emit(AgentOrchestratorState.Completed("never"))
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        testScheduler.advanceTimeBy(100)
        assertEquals(ChatHomeUiState.Generating(), viewModel.state.value.visual)

        viewModel.stopGeneration()
        advanceUntilIdle()

        assertEquals(ChatHomeUiState.Empty, viewModel.state.value.visual)
        // The screen settling is not the point — it always did that. Stop has to
        // reach the run, or the control goes on meaning something other than
        // what it says.
        verify { agentOrchestratorUseCase.cancelRun(sessionId) }
    }

    @Test
    fun `selectThread does not stop the run it is leaving`() = runTest(testDispatcher) {
        // Leaving a chat is not a decision to end its run — the whole reattach
        // protocol exists because a background run outlives the screen. Stop is
        // the only control that ends one.
        val target = "thread-other"
        viewModel = createViewModel()
        advanceUntilIdle()
        val leaving = viewModel.state.value.thread.currentSessionId
        sessionsFlow.value = sessionsFlow.value + ChatSession(id = target, name = "Other", updatedAt = 0)
        every { chatRepository.getDisplayMessagesForSession(target) } returns
            MutableStateFlow<List<ChatMessage>>(emptyList())

        viewModel.selectThread(target)
        advanceUntilIdle()

        verify(exactly = 0) { agentOrchestratorUseCase.cancelRun(leaving) }
    }

    @Test
    fun `stopGeneration is a no-op when state is not Generating`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.forceState(ChatHomeUiState.HitlConfirm(Risk.Sensitive))
        viewModel.stopGeneration()
        assertEquals(ChatHomeUiState.HitlConfirm(Risk.Sensitive), viewModel.state.value.visual)
    }

    @Test
    fun `selectThread persists the id, re-subscribes the message stream, and settles state`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            val target = "thread-xyz"
            sessionsFlow.value = sessionsFlow.value + ChatSession(id = target, name = "Other", updatedAt = 0)
            val targetMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
            every { chatRepository.getDisplayMessagesForSession(target) } returns targetMessages

            viewModel.selectThread(target)
            advanceUntilIdle()

            assertEquals(target, viewModel.state.value.thread.currentSessionId)
            assertEquals("Other", viewModel.state.value.thread.title)
            coVerify { settingsRepository.setCurrentChatSessionId(target) }
            verify { chatRepository.getDisplayMessagesForSession(target) }
        }

    @Test
    fun `selectThread preserves each chat's unsent draft independently`() = runTest(testDispatcher) {
        val first = "chat-A"
        savedSessionIdFlow.value = first
        sessionsFlow.value = listOf(
            ChatSession(id = first, name = "A", updatedAt = 0),
            ChatSession(id = "chat-B", name = "B", updatedAt = 0),
        )
        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(first, viewModel.state.value.thread.currentSessionId)

        // Type a draft in A, switch to B: B starts with an empty composer.
        viewModel.onComposerValueChange("half-written A")
        viewModel.selectThread("chat-B")
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.composer.value)

        // Type a draft in B, switch back to A: A's draft is restored.
        viewModel.onComposerValueChange("half-written B")
        viewModel.selectThread(first)
        advanceUntilIdle()
        assertEquals("half-written A", viewModel.state.value.composer.value)

        // Return to B: its own draft is restored, not A's.
        viewModel.selectThread("chat-B")
        advanceUntilIdle()
        assertEquals("half-written B", viewModel.state.value.composer.value)
    }

    @Test
    fun `sending a message clears that chat's stored draft`() = runTest(testDispatcher) {
        savedSessionIdFlow.value = "chat-A"
        sessionsFlow.value = listOf(
            ChatSession(id = "chat-A", name = "A", updatedAt = 0),
            ChatSession(id = "chat-B", name = "B", updatedAt = 0),
        )
        viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { agentOrchestratorUseCase("chat-A", "hi", null) } returns flow {
            emit(AgentOrchestratorState.Completed("done"))
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.composer.value)

        // Leaving and returning must not resurrect the already-sent draft.
        viewModel.selectThread("chat-B")
        advanceUntilIdle()
        viewModel.selectThread("chat-A")
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.composer.value)
    }

    @Test
    fun `creating a new chat then deleting removes the new chat, not the previously-active one`() =
        runTest(testDispatcher) {
            // Given an app that auto-restored the last active chat on launch.
            val previousId = "previous-active-chat"
            savedSessionIdFlow.value = previousId
            sessionsFlow.value = listOf(ChatSession(id = previousId, name = "Previous", updatedAt = 100))
            viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(previousId, viewModel.state.value.thread.currentSessionId)

            // Model the real-device race window: the new chat's persistence write is
            // still in flight (suspended on IO) when the user taps delete. The gate
            // keeps `saveSession` suspended so the create coroutine cannot run past it.
            val saveGate = CompletableDeferred<Unit>()
            coEvery { chatRepository.saveSession(any()) } coAnswers {
                val saved = firstArg<ChatSession>()
                saveGate.await()
                sessionsFlow.value = sessionsFlow.value.filterNot { it.id == saved.id } + saved
            }
            val deletedSlot = slot<String>()
            coEvery { chatRepository.deleteSession(capture(deletedSlot)) } answers {
                sessionsFlow.value = sessionsFlow.value.filterNot { it.id == deletedSlot.captured }
            }

            // When the user creates a new empty chat…
            viewModel.threads.createNewSessionWithPipeline(pipelineId = null)
            runCurrent()
            // …and immediately deletes it via the overflow menu, before the new-chat
            // write has settled.
            viewModel.threads.deleteCurrentSession()
            runCurrent()
            saveGate.complete(Unit)
            advanceUntilIdle()

            // The previously-active chat must survive; the just-created empty chat is
            // the one removed.
            assertNotEquals(
                "Delete must target the new chat, never the previously-active one",
                previousId,
                deletedSlot.captured,
            )
            assertTrue(
                "Previously-active chat must NOT be deleted",
                sessionsFlow.value.any { it.id == previousId },
            )
            // …and the new chat must actually be gone: delete joins the in-flight
            // create-write, so the late insert cannot resurrect it as an orphan.
            val newId = deletedSlot.captured
            assertFalse(
                "The just-created chat must not linger after deletion",
                sessionsFlow.value.any { it.id == newId },
            )
        }

    @Test
    fun `deleted bound pipeline triggers default fallback and emits one-shot event`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        // Bind the session to a pipeline that exists initially…
        pipelinesFlow.value = listOf(PipelineGraph(id = "deleted-id", name = "Doomed"))
        sessionsFlow.value = listOf(
            ChatSession(id = sessionId, name = "S", updatedAt = 0, pipelineId = "deleted-id"),
        )
        advanceUntilIdle()
        // …then delete it from the library while the chat is active.
        pipelinesFlow.value = listOf(PipelineGraph(id = "still-here", name = "Default"))
        advanceUntilIdle()

        val rebound = sessionsFlow.value.first { it.id == sessionId }
        assertNull("Session must be rebound to null after deletion", rebound.pipelineId)
    }

    @Test
    fun `tokensMax reflects the configured context-window cap`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        maxContextLengthFlow.value = ALT_TOKENS_MAX
        advanceUntilIdle()
        assertEquals(ALT_TOKENS_MAX, viewModel.state.value.tokens.max)
    }

    @Test
    fun `tokensUsed reflects rough chars-per-token estimate of the context window`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { getContextWindowUseCase(sessionId) } returns "x".repeat(40)

        messagesFlow.value = listOf(
            ChatMessage(id = 1, sessionId = sessionId, role = Role.USER, content = "x".repeat(40), timestamp = 0),
        )
        advanceUntilIdle()

        assertEquals(40 / ChatHomeViewModel.TOKEN_CHARS_PER_TOKEN, viewModel.state.value.tokens.used)
    }

    @Test
    fun `pipelineName resolves to the bound pipeline display name`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        pipelinesFlow.value = listOf(
            PipelineGraph(id = "p1", name = "Knot Default"),
            PipelineGraph(id = "p2", name = "Research"),
        )
        sessionsFlow.value = listOf(
            ChatSession(id = sessionId, name = "S", updatedAt = 0, pipelineId = "p2"),
        )
        advanceUntilIdle()

        assertEquals("Research", viewModel.state.value.pipelineName)
    }

    @Test
    fun `pipelineName falls back to default pipeline when session is unbound`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        pipelinesFlow.value = listOf(
            PipelineGraph(id = "p1", name = "First"),
            PipelineGraph(id = "p2", name = "Default"),
        )
        defaultPipelineIdFlow.value = "p2"
        advanceUntilIdle()

        assertEquals("Default", viewModel.state.value.pipelineName)
    }

    @Test
    fun `pipelineName names the run's pipeline when the session is unbound`() = runTest(testDispatcher) {
        // The reported defect. A session created by a trigger, by the scheduler
        // or by external automation carries no binding — deliberately, so a
        // follow-up typed into it uses the default — and the title therefore
        // named the default above a conversation another pipeline produced.
        val sessionId = "session-triggered"
        seedSavedSession(sessionId)
        every { pipelineRunRepository.observeRunsForSession(sessionId) } returns
            flowOf(listOf(runRecord(sessionId, PipelineRunStatus.COMPLETED, pipelineId = "p2")))

        viewModel = createViewModel()
        advanceUntilIdle()
        pipelinesFlow.value = listOf(
            PipelineGraph(id = "p1", name = "Default"),
            PipelineGraph(id = "p2", name = "Nightly digest"),
        )
        defaultPipelineIdFlow.value = "p1"
        advanceUntilIdle()

        assertEquals("Nightly digest", viewModel.state.value.pipelineName)
    }

    @Test
    fun `pipelineName keeps the binding even when the last run used another pipeline`() = runTest(testDispatcher) {
        // The binding is the user's own choice — picking a pipeline opens a new
        // chat carrying it — so a run must never overrule it. Without this the
        // title of a bound chat would drift to whatever last happened to run.
        val sessionId = "session-bound"
        savedSessionIdFlow.value = sessionId
        sessionsFlow.value = listOf(
            ChatSession(id = sessionId, name = "Existing", updatedAt = 0, pipelineId = "p1"),
        )
        every { pipelineRunRepository.observeRunsForSession(sessionId) } returns
            flowOf(listOf(runRecord(sessionId, PipelineRunStatus.COMPLETED, pipelineId = "p2")))

        viewModel = createViewModel()
        advanceUntilIdle()
        pipelinesFlow.value = listOf(
            PipelineGraph(id = "p1", name = "Bound"),
            PipelineGraph(id = "p2", name = "Nightly digest"),
        )
        advanceUntilIdle()

        assertEquals("Bound", viewModel.state.value.pipelineName)
    }

    @Test
    fun `pipelineName is null when session is unbound and no default is marked`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        pipelinesFlow.value = listOf(
            PipelineGraph(id = "p1", name = "First"),
            PipelineGraph(id = "p2", name = "Second"),
        )
        defaultPipelineIdFlow.value = null
        advanceUntilIdle()

        // No order-dependent "first in the library" fallback: the subtitle
        // must not advertise a pipeline that execution would never pick.
        assertNull(viewModel.state.value.pipelineName)
    }

    @Test
    fun `selectThread rebinds a stale pipeline binding and emits the fallback event`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        pipelinesFlow.value = listOf(PipelineGraph(id = "real-id", name = "Real"))
        advanceUntilIdle()
        // A thread whose binding points at an id that no longer exists in
        // the library. While it is not the active session the observers
        // leave it untouched.
        val target = "thread-stale"
        sessionsFlow.value = sessionsFlow.value +
            ChatSession(id = target, name = "Stale", updatedAt = 0, pipelineId = "ghost-id")
        advanceUntilIdle()
        // Await the one-shot event on the foreground test scope. Subscribe
        // before the switch — the event flow has no replay, so a late
        // subscriber would miss it.
        val fallbackEvent = async { viewModel.pipelineBinding.pipelineFallbackEvents.first() }
        testScheduler.runCurrent()

        viewModel.selectThread(target)
        advanceUntilIdle()

        val rebound = sessionsFlow.value.first { it.id == target }
        assertNull("Stale binding must be cleared on thread switch", rebound.pipelineId)
        assertTrue("Fallback Snackbar event must fire on rebind", fallbackEvent.isCompleted)
    }

    @Test
    fun `openDrawer + closeDrawer with no messages settles back on Empty`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.threads.openDrawer()
        assertEquals(ChatHomeUiState.DrawerOpen, viewModel.state.value.visual)
        viewModel.threads.closeDrawer()
        assertEquals(ChatHomeUiState.Empty, viewModel.state.value.visual)
    }

    @Test
    fun `openConsole flips consoleSnap without touching chat state`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val stateBefore = viewModel.state.value.visual
        viewModel.console.openConsole(ConsoleSnap.Full)
        assertEquals(ConsoleSnap.Full, viewModel.state.value.console.snap)
        assertEquals(stateBefore, viewModel.state.value.visual)
    }

    @Test
    fun `closeConsole clears consoleSnap without touching chat state`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.console.openConsole(ConsoleSnap.Partial)
        val stateBefore = viewModel.state.value.visual
        viewModel.console.closeConsole()
        assertNull(viewModel.state.value.console.snap)
        assertEquals(stateBefore, viewModel.state.value.visual)
    }

    @Test
    fun `setConsoleSnap updates an open console pane`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.console.openConsole(ConsoleSnap.Partial)
        viewModel.console.setConsoleSnap(ConsoleSnap.Full)
        assertEquals(ConsoleSnap.Full, viewModel.state.value.console.snap)
    }

    @Test
    fun `setConsoleSnap is a no-op when console is closed`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.console.setConsoleSnap(ConsoleSnap.Full)
        assertNull(viewModel.state.value.console.snap)
    }

    @Test
    fun `console pane survives terminal Completed orchestrator emission`() = runTest(testDispatcher) {
        // Regression: in the pre-refactor sealed state, every terminal
        // emit (Completed / Error / WaitingForApproval) overwrote the
        // pane state and closed the overlay before the user could read
        // any of the streamed events.
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.console.openConsole(ConsoleSnap.Partial)
        viewModel.forceState(ChatHomeUiState.Idle)
        assertEquals(ConsoleSnap.Partial, viewModel.state.value.console.snap)
    }

    @Test
    fun `forceState flips state without side effects`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.forceState(ChatHomeUiState.HitlConfirm(Risk.Destructive))
        assertEquals(ChatHomeUiState.HitlConfirm(Risk.Destructive), viewModel.state.value.visual)
    }

    @Test
    fun `sendMessage emits HitlConfirm when orchestrator waits for approval`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(
                AgentOrchestratorState.WaitingForApproval(
                    toolName = "fs.write_file",
                    arguments = "{\"path\":\"/tmp/x\"}",
                    risk = ToolRisk.SENSITIVE,
                ),
            )
            delay(10_000)
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.state.value.visual
        assertTrue("Expected HitlConfirm, got $state", state is ChatHomeUiState.HitlConfirm)
        assertEquals(Risk.Sensitive, (state as ChatHomeUiState.HitlConfirm).risk)
        val pending = viewModel.state.value.pending.tool
        assertNotNull(pending)
        assertEquals("fs.write_file", pending!!.toolName)
        assertEquals(ToolRisk.SENSITIVE, pending.risk)
    }

    @Test
    fun `approveTool routes the decision and flips to Generating`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(
                AgentOrchestratorState.WaitingForApproval(
                    toolName = "calendar.create_event",
                    arguments = "{}",
                    risk = ToolRisk.SENSITIVE,
                ),
            )
            delay(10_000)
        }
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.hitl.approveTool()
        advanceUntilIdle()

        coVerify { submitApprovalDecisionUseCase(sessionId, true, null) }
        assertEquals(ChatHomeUiState.Generating(), viewModel.state.value.visual)
        assertNull(viewModel.state.value.pending.tool)
    }

    @Test
    fun `rejectTool routes the denial and appends system denial message`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(
                AgentOrchestratorState.WaitingForApproval(
                    toolName = "fs.delete_file",
                    arguments = "{}",
                    risk = ToolRisk.DESTRUCTIVE,
                ),
            )
            delay(10_000)
        }
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.hitl.rejectTool()
        advanceUntilIdle()

        coVerify { submitApprovalDecisionUseCase(sessionId, false, null) }
        coVerify {
            chatRepository.saveMessage(
                match { msg ->
                    msg.role == Role.SYSTEM &&
                        msg.content.contains("fs.delete_file") &&
                        msg.content.contains("denied")
                },
            )
        }
        assertNull(viewModel.state.value.pending.tool)
        // Resuming the pipeline restarts orchestrator emission — the surface stays
        // in Generating until the next state (or a terminal Completed / Error) lands.
        assertEquals(ChatHomeUiState.Generating(), viewModel.state.value.visual)
    }

    @Test
    fun `approveTool destructive is a no-op without typed yes`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(
                AgentOrchestratorState.WaitingForApproval(
                    toolName = "fs.delete_file",
                    arguments = "{}",
                    risk = ToolRisk.DESTRUCTIVE,
                ),
            )
            delay(10_000)
        }
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Empty typed-confirm — Allow must be refused.
        viewModel.hitl.approveTool()
        advanceUntilIdle()
        coVerify(exactly = 0) { submitApprovalDecisionUseCase(any(), true, any()) }
        assertTrue(viewModel.state.value.visual is ChatHomeUiState.HitlConfirm)

        // Typing the canonical magic word unlocks the gate.
        viewModel.hitl.onTypedConfirmChange("yes")
        viewModel.hitl.approveTool()
        advanceUntilIdle()
        coVerify { submitApprovalDecisionUseCase(sessionId, true, null) }
        assertEquals(ChatHomeUiState.Generating(), viewModel.state.value.visual)
    }

    @Test
    fun `AwaitingClarification emits Clarification state and captures the request`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        val request = ClarificationRequest(
            id = "req-1",
            sessionId = "session-1",
            question = "Which calendar?",
            options = listOf("Work", "Personal"),
            timeoutMs = 0,
        )
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.AwaitingClarification(request))
            delay(10_000)
        }

        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(ChatHomeUiState.Clarification, viewModel.state.value.visual)
        assertEquals(request, viewModel.state.value.pending.clarification)
    }

    @Test
    fun `submitClarificationReply routes the answer and flips to Generating`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        val request = ClarificationRequest(
            id = "req-7",
            sessionId = "session-1",
            question = "Q?",
            options = listOf("Yes", "No"),
            timeoutMs = 0,
        )
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.AwaitingClarification(request))
            delay(10_000)
        }
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.hitl.submitClarificationReply(" Yes  ")
        advanceUntilIdle()

        coVerify { submitClarificationAnswerUseCase(sessionId, "req-7", "Yes") }
        assertNull(viewModel.state.value.pending.clarification)
        assertEquals(ChatHomeUiState.Generating(), viewModel.state.value.visual)
    }

    @Test
    fun `clarification card outlives its live timeout without auto-submitting a default`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        val request = ClarificationRequest(
            id = "req-timeout",
            sessionId = "session-1",
            question = "Pick one",
            options = listOf("Alpha", "Beta"),
            timeoutMs = 500L,
        )
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.AwaitingClarification(request))
            delay(10_000)
        }
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        testScheduler.runCurrent()
        assertEquals(ChatHomeUiState.Clarification, viewModel.state.value.visual)

        // Drive virtual time well past the live window: the repository owns
        // that timeout (the run then parks persistently) — the UI must NOT
        // fabricate a default answer, and the card must stay answerable so
        // the reply can route through the parked-run submission path.
        testScheduler.advanceTimeBy(600L)
        testScheduler.runCurrent()

        coVerify(exactly = 0) { submitClarificationAnswerUseCase(any(), any(), any()) }
        coVerify(exactly = 0) { clarificationRepository.submitClarification(any(), any()) }
        assertEquals(request, viewModel.state.value.pending.clarification)
        assertEquals(ChatHomeUiState.Clarification, viewModel.state.value.visual)
    }

    @Test
    fun `submitClarificationReply forwards an empty reply for free-form requests`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        val request = ClarificationRequest(
            id = "req-blank",
            sessionId = "session-1",
            question = "Anything else?",
            options = null,
            timeoutMs = 0,
        )
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.AwaitingClarification(request))
            delay(10_000)
        }
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.hitl.submitClarificationReply("   ")
        advanceUntilIdle()

        coVerify { submitClarificationAnswerUseCase(sessionId, "req-blank", "") }
        assertNull(viewModel.state.value.pending.clarification)
        assertEquals(ChatHomeUiState.Generating(), viewModel.state.value.visual)
    }

    @Test
    fun `chatMessageToRow maps user messages without a model label`() {
        val msg = ChatMessage(id = 7L, sessionId = "s", role = Role.USER, content = "hello", timestamp = 0)
        val row = ChatHomeViewModel.chatMessageToRow(msg, "M")
        assertEquals(ChatRole.User, row.role)
        assertNull(row.metadata.model)
        assertEquals("hello", (row.content as ChatContent.Text).text)
        assertTrue(row.id.startsWith("u-"))
    }

    @Test
    fun `chatMessageToRow maps assistant messages with the active model label`() {
        val msg = ChatMessage(id = 11L, sessionId = "s", role = Role.AGENT, content = "ok", timestamp = 0)
        val row = ChatHomeViewModel.chatMessageToRow(msg, "Gemma 2B")
        assertEquals(ChatRole.Assistant, row.role)
        assertEquals("Gemma 2B", row.metadata.model)
        assertNotNull(row.metadata.timestamp)
        assertTrue(row.id.startsWith("a-"))
        // Agent rows carry Markdown so the
        // host-supplied renderer formats headings, lists, code fences, etc.
        assertEquals("ok", (row.content as ChatContent.Markdown).source)
    }

    // -----------------------------------------------------------------
    // Drawer / overflow / model-picker / favorites.
    // -----------------------------------------------------------------

    @Test
    fun `createNewSessionWithPipeline persists session with picked pipeline and switches`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.createNewSessionWithPipeline(pipelineId = "pipe-42")
        advanceUntilIdle()

        coVerify { chatRepository.saveSession(match { it.pipelineId == "pipe-42" }) }
        // After save, the new session id is propagated as the active session.
        assertTrue(viewModel.state.value.thread.currentSessionId.isNotBlank())
    }

    @Test
    fun `renameSession trims input and forwards to repository`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.renameSession("thread-x", "   New name   ")
        advanceUntilIdle()

        coVerify { chatRepository.renameSession("thread-x", "New name") }
    }

    @Test
    fun `renameSession with blank input is a no-op`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.renameSession("thread-x", "    ")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepository.renameSession(any(), any()) }
    }

    @Test
    fun `toggleFavoriteCurrent flips persisted isStarred flag on the active session`() = runTest(testDispatcher) {
        val sessionId = "fav-session"
        savedSessionIdFlow.value = sessionId
        sessionsFlow.value = listOf(
            ChatSession(id = sessionId, name = "S", updatedAt = 0, isStarred = false),
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.toggleFavoriteCurrent()
        advanceUntilIdle()

        coVerify { chatRepository.setSessionFavorite(sessionId, true) }
    }

    @Test
    fun `toggleFavoriteCurrent reflects current isStarred via favorite StateFlow`() = runTest(testDispatcher) {
        val sessionId = "fav-session"
        savedSessionIdFlow.value = sessionId
        sessionsFlow.value = listOf(
            ChatSession(id = sessionId, name = "S", updatedAt = 0, isStarred = true),
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(true, viewModel.state.value.thread.favorite)
    }

    @Test
    fun `installedModels mirrors LocalModelRepository getAllModels emissions`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        localModelsFlow.value = listOf(
            LocalModel(id = 1L, name = "Gemma 2B", path = "/g", size = 0L, isActive = true),
            LocalModel(id = 2L, name = "Other", path = "/o", size = 0L, isActive = false),
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.model.installed.size)
        assertEquals(1L, viewModel.state.value.model.activeId)
        assertEquals("Gemma 2B", viewModel.state.value.model.name)
    }

    @Test
    fun `deleteCurrentSession deletes and auto-selects the next available thread`() = runTest(testDispatcher) {
        val sessionId = "active-id"
        val other = "other-id"
        savedSessionIdFlow.value = sessionId
        sessionsFlow.value = listOf(
            ChatSession(id = sessionId, name = "A", updatedAt = 2L),
            ChatSession(id = other, name = "B", updatedAt = 1L),
        )
        // Simulate the repository removing the row when delete is called.
        coEvery { chatRepository.deleteSession(sessionId) } answers {
            sessionsFlow.value = sessionsFlow.value.filterNot { it.id == sessionId }
            Unit
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.deleteCurrentSession()
        advanceUntilIdle()

        coVerify { chatRepository.deleteSession(sessionId) }
        assertEquals(other, viewModel.state.value.thread.currentSessionId)
    }

    @Test
    fun `deleteCurrentSession creates fresh session when no thread remains`() = runTest(testDispatcher) {
        val sessionId = "only-id"
        savedSessionIdFlow.value = sessionId
        sessionsFlow.value = listOf(ChatSession(id = sessionId, name = "only", updatedAt = 0))
        coEvery { chatRepository.deleteSession(sessionId) } answers {
            sessionsFlow.value = emptyList()
            Unit
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.deleteCurrentSession()
        advanceUntilIdle()

        coVerify { chatRepository.deleteSession(sessionId) }
        // After delete + auto-create, the active session id is the newly-created one.
        assertTrue(viewModel.state.value.thread.currentSessionId.isNotBlank())
        coVerify(atLeast = 1) { chatRepository.saveSession(any()) }
    }

    @Test
    fun `importChatFromJson delegates to repository and selects the imported session`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.transfer.importChatFromJson("""[{"role":"USER","text":"hi","timestamp":1}]""")
        advanceUntilIdle()

        coVerify { chatRepository.importChat(any()) }
        assertEquals("imported-session-id", viewModel.state.value.thread.currentSessionId)
    }

    @Test
    fun `importChatFromJson emits importErrorEvents when repository throws`() = runTest(testDispatcher) {
        coEvery { chatRepository.importChat(any()) } throws org.json.JSONException("bad shape")
        viewModel = createViewModel()
        advanceUntilIdle()

        // Use async to suspend on the first emission. `runCurrent` parks the
        // collector inside `first()`, then the trigger runs, then `await`
        // resumes once `tryEmit` lands. MutableSharedFlow(replay=0) only
        // delivers to subscribers active at emit time, so the await-then-emit
        // ordering is mandatory.
        val received = async { viewModel.transfer.importErrorEvents.first() }
        runCurrent()
        viewModel.transfer.importChatFromJson("not json")
        advanceUntilIdle()

        assertEquals("bad shape", received.await())
    }

    @Test
    fun `exportCurrentSession emits payload carrying session name and JSON`() = runTest(testDispatcher) {
        val sessionId = "exp-id"
        savedSessionIdFlow.value = sessionId
        sessionsFlow.value = listOf(ChatSession(id = sessionId, name = "Trip plan", updatedAt = 0))
        coEvery { chatRepository.getSessionById(sessionId) } returns
            ChatSession(id = sessionId, name = "Trip plan", updatedAt = 0)
        coEvery { chatRepository.getMessagesForSession(sessionId) } returns flowOf(
            listOf(
                ChatMessage(id = 1L, sessionId = sessionId, role = Role.USER, content = "hi", timestamp = 1L),
            ),
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        val payload = async { viewModel.transfer.exportEvents.first() }
        runCurrent()
        viewModel.transfer.exportCurrentSession()
        advanceUntilIdle()

        val captured = payload.await()
        assertEquals("Trip plan", captured.sessionName)
        assertTrue(captured.json.contains("\"role\""))
        assertTrue(captured.json.contains("\"USER\""))
    }

    @Test
    fun `threadRows projects sessions with favorited at the top`() = runTest(testDispatcher) {
        val a = "id-a"
        val b = "id-b"
        sessionsFlow.value = listOf(
            ChatSession(id = a, name = "Older", updatedAt = 100L, isStarred = false),
            ChatSession(id = b, name = "Pinned", updatedAt = 50L, isStarred = true),
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        val rows = viewModel.state.value.thread.rows
        assertEquals(b, rows.first().id)
        assertTrue(rows.first().starred)
        assertEquals(a, rows.last().id)
    }

    // region Chat reattach protocol

    /** Builds a [PipelineRun] fixture for the reattach-protocol scenarios. */
    private fun runRecord(
        sessionId: String,
        status: PipelineRunStatus,
        id: String = "run-1",
        pipelineId: String? = null,
        currentNodeId: String? = null,
        finishedAt: Long? = null,
        userPrompt: String? = null,
    ): PipelineRun = PipelineRun(
        id = id,
        sessionId = sessionId,
        pipelineId = pipelineId,
        origin = RunOrigin.CHAT,
        status = status,
        currentNodeId = currentNodeId,
        startedAt = 0L,
        finishedAt = finishedAt,
        errorMessage = null,
        graphContentHash = null,
        userPrompt = userPrompt,
    )

    /** Interrupted-run record fresh and complete enough to offer the Resume CTA. */
    private fun resumableRunRecord(sessionId: String, id: String): PipelineRun = runRecord(
        sessionId = sessionId,
        status = PipelineRunStatus.INTERRUPTED,
        id = id,
        finishedAt = System.currentTimeMillis(),
        userPrompt = "original prompt",
    )

    /** Persists [sessionId] as the saved session so `initializeSession` restores it. */
    private fun seedSavedSession(sessionId: String) {
        savedSessionIdFlow.value = sessionId
        sessionsFlow.value = listOf(ChatSession(id = sessionId, name = "Existing", updatedAt = 0))
    }

    @Test
    fun `given active running run when session opens then UI reattaches without enqueueing`() =
        runTest(testDispatcher) {
            val sessionId = "session-running"
            seedSavedSession(sessionId)
            coEvery { pipelineRunRepository.getActiveRunForSession(sessionId) } returns
                runRecord(sessionId, PipelineRunStatus.RUNNING)
            every { agentOrchestratorUseCase.observe(sessionId) } returns
                flowOf(AgentOrchestratorState.Thinking("working"))

            viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(ChatHomeUiState.Generating(), viewModel.state.value.visual)
            verify { agentOrchestratorUseCase.observe(sessionId) }
            verify(exactly = 0) { agentOrchestratorUseCase.invoke(any(), any(), any()) }
        }

    @Test
    fun `given scheduler run starting in the open session then UI attaches to the live stream`() =
        runTest(testDispatcher) {
            val sessionId = "session-bg"
            seedSavedSession(sessionId)
            val runsFlow = MutableStateFlow<List<PipelineRun>>(emptyList())
            every { pipelineRunRepository.observeRunsForSession(sessionId) } returns runsFlow
            every { agentOrchestratorUseCase.observe(sessionId) } returns
                flowOf(AgentOrchestratorState.Thinking("working"))

            viewModel = createViewModel()
            advanceUntilIdle()
            // Session opened idle: no run yet, nothing to attach to.
            verify(exactly = 0) { agentOrchestratorUseCase.observe(any()) }

            // A scheduler-origin run fires into the already-open session.
            runsFlow.value = listOf(
                runRecord(sessionId, PipelineRunStatus.RUNNING, id = "bg-run-1")
                    .copy(origin = RunOrigin.SCHEDULER),
            )
            advanceUntilIdle()

            assertEquals(ChatHomeUiState.Generating(), viewModel.state.value.visual)
            verify { agentOrchestratorUseCase.observe(sessionId) }
            verify(exactly = 0) { agentOrchestratorUseCase.invoke(any(), any(), any()) }
        }

    @Test
    fun `given same background run emits again then UI does not re-attach`() = runTest(testDispatcher) {
        val sessionId = "session-bg-2"
        seedSavedSession(sessionId)
        val runsFlow = MutableStateFlow<List<PipelineRun>>(emptyList())
        every { pipelineRunRepository.observeRunsForSession(sessionId) } returns runsFlow
        every { agentOrchestratorUseCase.observe(sessionId) } returns
            flowOf(AgentOrchestratorState.Thinking("working"))

        viewModel = createViewModel()
        advanceUntilIdle()
        runsFlow.value = listOf(
            runRecord(sessionId, PipelineRunStatus.RUNNING, id = "bg-run-2")
                .copy(origin = RunOrigin.SCHEDULER),
        )
        advanceUntilIdle()
        // Same run transitions status — the id-keyed dedup must not re-subscribe.
        runsFlow.value = listOf(
            runRecord(sessionId, PipelineRunStatus.WAITING_APPROVAL, id = "bg-run-2")
                .copy(origin = RunOrigin.SCHEDULER),
        )
        advanceUntilIdle()

        verify(exactly = 1) { agentOrchestratorUseCase.observe(sessionId) }
    }

    @Test
    fun `given waiting approval run when session opens then HITL card restored from pending snapshot`() =
        runTest(testDispatcher) {
            val sessionId = "session-hitl"
            seedSavedSession(sessionId)
            coEvery { pipelineRunRepository.getActiveRunForSession(sessionId) } returns
                runRecord(sessionId, PipelineRunStatus.WAITING_APPROVAL)
            // The live replay cache holds a console snapshot, NOT the
            // WaitingForApproval emission — the card must come from the
            // persistent-status branch, never from the flow.
            every { agentOrchestratorUseCase.observe(sessionId) } returns
                flowOf(AgentOrchestratorState.ConsoleLog(events = emptyList(), runId = "run-1"))
            every { agentOrchestratorUseCase.pendingApprovalFor(sessionId) } returns
                AgentOrchestratorState.WaitingForApproval(
                    toolName = "fs.delete_file",
                    arguments = "{}",
                    risk = ToolRisk.SENSITIVE,
                )

            viewModel = createViewModel()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.visual is ChatHomeUiState.HitlConfirm)
            assertEquals("fs.delete_file", viewModel.state.value.pending.tool?.toolName)
        }

    @Test
    fun `given a run waiting on a ceiling when session opens then the pause is restored from the record`() =
        runTest(testDispatcher) {
            // The record IS the authority here, with no live snapshot to prefer:
            // a ceiling pause has no in-process waiting phase at all, so the
            // process that raised it is routinely gone by the time it is read.
            val sessionId = "session-ceiling"
            seedSavedSession(sessionId)
            coEvery { pipelineRunRepository.getActiveRunForSession(sessionId) } returns
                runRecord(sessionId, PipelineRunStatus.WAITING_CEILING)
            every { agentOrchestratorUseCase.observe(sessionId) } returns
                flowOf(AgentOrchestratorState.ConsoleLog(events = emptyList(), runId = "run-1"))
            coEvery { pendingInteractionRepository.getForSession(sessionId) } returns PendingInteraction(
                runId = "child-run",
                sessionId = sessionId,
                kind = PendingInteractionKind.CEILING,
                ceilingAxis = RunCeilingAxis.TOKENS,
                ceilingLimit = 100_000,
                ceilingSpent = 100_400,
                requestedAt = 1_700_000_000_000L,
            )

            viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(ChatHomeUiState.CeilingPause, viewModel.state.value.visual)
            val pending = viewModel.state.value.pending.ceiling
            // The run id comes off the record, not from the session's active
            // run: a pause raised inside a sub-pipeline is recorded on the
            // child, while the active run is the parent.
            assertEquals("child-run", pending?.runId)
            assertEquals(
                HardCeilingBreach(RunCeilingAxis.TOKENS, limit = 100_000, spent = 100_400),
                pending?.breach,
            )
        }

    @Test
    fun `given a ceiling park missing its numbers then no card is restored`() = runTest(testDispatcher) {
        // A card with no numbers cannot state what continuing costs, and the
        // decision turns on nothing else. Better to show none and let the
        // maintenance window settle the run than to ask a question with a hole
        // in it.
        val sessionId = "session-ceiling-partial"
        seedSavedSession(sessionId)
        coEvery { pipelineRunRepository.getActiveRunForSession(sessionId) } returns
            runRecord(sessionId, PipelineRunStatus.WAITING_CEILING)
        every { agentOrchestratorUseCase.observe(sessionId) } returns
            flowOf(AgentOrchestratorState.ConsoleLog(events = emptyList(), runId = "run-1"))
        coEvery { pendingInteractionRepository.getForSession(sessionId) } returns PendingInteraction(
            runId = "run-1",
            sessionId = sessionId,
            kind = PendingInteractionKind.CEILING,
            ceilingAxis = RunCeilingAxis.STEPS,
            ceilingLimit = null,
            ceilingSpent = null,
            requestedAt = 1_700_000_000_000L,
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        assertNull(viewModel.state.value.pending.ceiling)
    }

    @Test
    fun `given waiting clarification run when session opens then clarification card restored from repository`() =
        runTest(testDispatcher) {
            val sessionId = "session-clar"
            seedSavedSession(sessionId)
            coEvery { pipelineRunRepository.getActiveRunForSession(sessionId) } returns
                runRecord(sessionId, PipelineRunStatus.WAITING_CLARIFICATION)
            every { agentOrchestratorUseCase.observe(sessionId) } returns
                flowOf(AgentOrchestratorState.ConsoleLog(events = emptyList(), runId = "run-1"))
            val request = ClarificationRequest(
                id = "req-77",
                sessionId = sessionId,
                question = "Which calendar?",
                options = listOf("Work"),
                timeoutMs = 0,
            )
            val otherSessions = ClarificationRequest(
                id = "req-foreign",
                sessionId = "other-session",
                question = "Unrelated?",
                options = null,
                timeoutMs = 0,
            )
            every { clarificationRepository.pendingRequests } returns flowOf(listOf(otherSessions, request))

            viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(ChatHomeUiState.Clarification, viewModel.state.value.visual)
            assertEquals("req-77", viewModel.state.value.pending.clarification?.id)
        }

    @Test
    fun `given latest run interrupted when session opens then interrupted card surfaces with node label`() =
        runTest(testDispatcher) {
            val sessionId = "session-int"
            seedSavedSession(sessionId)
            coEvery { pipelineRunRepository.getLatestRunForSession(sessionId) } returns runRecord(
                sessionId,
                PipelineRunStatus.INTERRUPTED,
                id = "run-int",
                pipelineId = "p1",
                currentNodeId = "n2",
            )
            coEvery { pipelineRepository.getPipelineById("p1") } returns PipelineGraph(
                id = "p1",
                name = "Pipeline",
                nodes = listOf(NodeModel(id = "n2", type = NodeType.LITE_RT, x = 0f, y = 0f, label = "Summarise")),
            )

            viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(ChatHomeUiState.Interrupted, viewModel.state.value.visual)
            val pending = viewModel.state.value.pending.interrupted
            assertEquals("run-int", pending?.runId)
            assertEquals("Summarise", pending?.nodeLabel)
        }

    @Test
    fun `given latest run interrupted with deleted pipeline then card falls back to unknown step`() =
        runTest(testDispatcher) {
            val sessionId = "session-int-deleted"
            seedSavedSession(sessionId)
            coEvery { pipelineRunRepository.getLatestRunForSession(sessionId) } returns runRecord(
                sessionId,
                PipelineRunStatus.INTERRUPTED,
                id = "run-int",
                pipelineId = "gone",
                currentNodeId = "n2",
            )
            coEvery { pipelineRepository.getPipelineById("gone") } returns null

            viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(
                ChatHomeReattachDelegate.INTERRUPTED_UNKNOWN_NODE_LABEL,
                viewModel.state.value.pending.interrupted?.nodeLabel,
            )
        }

    @Test
    fun `given latest run completed when session opens then no reattach happens`() = runTest(testDispatcher) {
        val sessionId = "session-done"
        seedSavedSession(sessionId)
        coEvery { pipelineRunRepository.getLatestRunForSession(sessionId) } returns
            runRecord(sessionId, PipelineRunStatus.COMPLETED)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(ChatHomeUiState.Empty, viewModel.state.value.visual)
        assertNull(viewModel.state.value.pending.interrupted)
        verify(exactly = 0) { agentOrchestratorUseCase.observe(any()) }
    }

    @Test
    fun `selectThread reattaches to the new thread's active run`() = runTest(testDispatcher) {
        val first = "session-a"
        val second = "session-b"
        seedSavedSession(first)
        sessionsFlow.value = listOf(
            ChatSession(id = first, name = "A", updatedAt = 0),
            ChatSession(id = second, name = "B", updatedAt = 0),
        )
        coEvery { pipelineRunRepository.getActiveRunForSession(second) } returns
            runRecord(second, PipelineRunStatus.RUNNING, id = "run-b")
        every { agentOrchestratorUseCase.observe(second) } returns
            flowOf(AgentOrchestratorState.Thinking("working"))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectThread(second)
        advanceUntilIdle()

        assertEquals(ChatHomeUiState.Generating(), viewModel.state.value.visual)
        verify { agentOrchestratorUseCase.observe(second) }
    }

    @Test
    fun `discardInterruptedRun settles the run and hides the card`() = runTest(testDispatcher) {
        val sessionId = "session-discard"
        seedSavedSession(sessionId)
        coEvery { pipelineRunRepository.getLatestRunForSession(sessionId) } returns
            runRecord(sessionId, PipelineRunStatus.INTERRUPTED, id = "run-d")
        coEvery { pipelineRunRepository.discardInterruptedRun("run-d") } returns Unit

        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(ChatHomeUiState.Interrupted, viewModel.state.value.visual)

        viewModel.reattach.discardInterruptedRun()
        advanceUntilIdle()

        assertNull(viewModel.state.value.pending.interrupted)
        assertEquals(ChatHomeUiState.Empty, viewModel.state.value.visual)
        coVerify { pipelineRunRepository.discardInterruptedRun("run-d") }
    }

    @Test
    fun `resumeInterruptedRun on success clears the card and flips to Generating`() = runTest(testDispatcher) {
        val sessionId = "session-resume"
        seedSavedSession(sessionId)
        coEvery { pipelineRunRepository.getLatestRunForSession(sessionId) } returns
            resumableRunRecord(sessionId, id = "run-r")
        coEvery { resumePipelineRunUseCase("run-r") } returns ResumeOutcome.Resumed

        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(ChatHomeUiState.Interrupted, viewModel.state.value.visual)
        assertTrue(viewModel.state.value.pending.interrupted?.resumable == true)

        viewModel.reattach.resumeInterruptedRun()
        advanceUntilIdle()

        assertNull(viewModel.state.value.pending.interrupted)
        assertEquals(ChatHomeUiState.Generating(), viewModel.state.value.visual)
        coVerify { resumePipelineRunUseCase("run-r") }
    }

    @Test
    fun `resumeInterruptedRun surfaces GraphChanged feedback and keeps the card`() = runTest(testDispatcher) {
        val sessionId = "session-resume-gc"
        seedSavedSession(sessionId)
        coEvery { pipelineRunRepository.getLatestRunForSession(sessionId) } returns
            resumableRunRecord(sessionId, id = "run-gc")
        coEvery { resumePipelineRunUseCase("run-gc") } returns ResumeOutcome.GraphChanged

        viewModel = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<ResumeFeedbackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.reattach.resumeFeedbackEvents.collect { events.add(it) }
        }
        runCurrent()

        viewModel.reattach.resumeInterruptedRun()
        advanceUntilIdle()

        assertEquals(listOf(ResumeFeedbackEvent.GraphChanged), events)
        assertNotNull(viewModel.state.value.pending.interrupted)
        assertEquals(ChatHomeUiState.Interrupted, viewModel.state.value.visual)
    }

    @Test
    fun `resumeInterruptedRun on Expired demotes the card to discard-only`() = runTest(testDispatcher) {
        val sessionId = "session-resume-exp"
        seedSavedSession(sessionId)
        coEvery { pipelineRunRepository.getLatestRunForSession(sessionId) } returns
            resumableRunRecord(sessionId, id = "run-exp")
        coEvery { resumePipelineRunUseCase("run-exp") } returns ResumeOutcome.Expired

        viewModel = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<ResumeFeedbackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.reattach.resumeFeedbackEvents.collect { events.add(it) }
        }
        runCurrent()

        viewModel.reattach.resumeInterruptedRun()
        advanceUntilIdle()

        assertEquals(listOf(ResumeFeedbackEvent.Expired), events)
        assertEquals(false, viewModel.state.value.pending.interrupted?.resumable)
    }

    @Test
    fun `interrupted record without the original prompt presents a discard-only card`() = runTest(testDispatcher) {
        val sessionId = "session-legacy"
        seedSavedSession(sessionId)
        coEvery { pipelineRunRepository.getLatestRunForSession(sessionId) } returns
            runRecord(sessionId, PipelineRunStatus.INTERRUPTED, id = "run-legacy", userPrompt = null)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(ChatHomeUiState.Interrupted, viewModel.state.value.visual)
        assertEquals(false, viewModel.state.value.pending.interrupted?.resumable)
    }

    @Test
    fun `interrupted card survives opening and closing the drawer`() = runTest(testDispatcher) {
        val sessionId = "session-drawer"
        seedSavedSession(sessionId)
        coEvery { pipelineRunRepository.getLatestRunForSession(sessionId) } returns
            runRecord(sessionId, PipelineRunStatus.INTERRUPTED, id = "run-drw")

        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(ChatHomeUiState.Interrupted, viewModel.state.value.visual)

        viewModel.threads.openDrawer()
        assertEquals(ChatHomeUiState.DrawerOpen, viewModel.state.value.visual)
        viewModel.threads.closeDrawer()

        // The resting visual must resolve back to Interrupted while the
        // snapshot is pending — otherwise Resume / Discard become
        // unreachable until the next thread switch.
        assertEquals(ChatHomeUiState.Interrupted, viewModel.state.value.visual)
        assertNotNull(viewModel.state.value.pending.interrupted)
    }

    @Test
    fun `interrupted card surfaces after the drawer closes when reattach resolved mid-overlay`() =
        runTest(testDispatcher) {
            val sessionId = "session-drawer-open"
            seedSavedSession(sessionId)
            coEvery { pipelineRunRepository.getLatestRunForSession(sessionId) } returns
                runRecord(sessionId, PipelineRunStatus.INTERRUPTED, id = "run-mid")

            viewModel = createViewModel()
            // Drawer goes up before the reattach lookup lands.
            viewModel.threads.openDrawer()
            advanceUntilIdle()

            // The overlay is never yanked away…
            assertEquals(ChatHomeUiState.DrawerOpen, viewModel.state.value.visual)
            // …but the pending snapshot is installed, so closing the drawer
            // settles straight onto the interrupted card.
            assertNotNull(viewModel.state.value.pending.interrupted)
            viewModel.threads.closeDrawer()
            assertEquals(ChatHomeUiState.Interrupted, viewModel.state.value.visual)
        }

    @Test
    fun `clarification restore does not re-arm the UI watchdog`() = runTest(testDispatcher) {
        val sessionId = "session-clar-watchdog"
        seedSavedSession(sessionId)
        coEvery { pipelineRunRepository.getActiveRunForSession(sessionId) } returns
            runRecord(sessionId, PipelineRunStatus.WAITING_CLARIFICATION)
        every { agentOrchestratorUseCase.observe(sessionId) } returns
            flowOf(AgentOrchestratorState.ConsoleLog(events = emptyList(), runId = "run-1"))
        val request = ClarificationRequest(
            id = "req-wd",
            sessionId = sessionId,
            question = "Which calendar?",
            options = listOf("Work"),
            timeoutMs = 60_000L,
        )
        every { clarificationRepository.pendingRequests } returns flowOf(listOf(request))

        viewModel = createViewModel()
        // advanceUntilIdle would run a re-armed 60s watchdog to completion —
        // the repository's authoritative timeout has been ticking since the
        // request was raised, so the restore path must not start a second,
        // desynchronized clock that later submits a spurious default.
        advanceUntilIdle()

        assertEquals(ChatHomeUiState.Clarification, viewModel.state.value.visual)
        coVerify(exactly = 0) { clarificationRepository.submitClarification(any(), any()) }
    }

    @Test
    fun `given stale clarification when reply not delivered then SYSTEM row records it`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        val request = ClarificationRequest(
            id = "req-stale",
            sessionId = sessionId,
            question = "Which calendar?",
            options = listOf("Work"),
            timeoutMs = 0,
        )
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.AwaitingClarification(request))
            delay(10_000)
        }
        // Neither a live deferred nor a parked record consumed the reply —
        // the request resolved before the user's tap arrived.
        coEvery {
            submitClarificationAnswerUseCase(sessionId, "req-stale", "Work")
        } returns PendingSubmissionOutcome.NothingPending
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        runCurrent()

        viewModel.hitl.submitClarificationReply("Work")
        advanceUntilIdle()

        coVerify {
            chatRepository.saveMessage(
                match {
                    it.role == Role.SYSTEM &&
                        it.content == ChatHomeHitlDelegate.SYSTEM_MESSAGE_CLARIFICATION_REPLY_NOT_DELIVERED
                },
            )
        }
    }

    @Test
    fun `thread rows flag sessions with active background runs`() = runTest(testDispatcher) {
        val foreground = "session-fg"
        val background = "session-bg"
        seedSavedSession(foreground)
        sessionsFlow.value = listOf(
            ChatSession(id = foreground, name = "Foreground", updatedAt = 100L),
            ChatSession(id = background, name = "Background", updatedAt = 50L),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        activeRunsFlow.value = setOf(background)
        advanceUntilIdle()

        val rows = viewModel.state.value.thread.rows
        assertTrue(rows.first { it.id == background }.running)
        assertTrue(!rows.first { it.id == foreground }.running)
    }

    @Test
    fun `archived sessions are excluded from the drawer but still counted`() = runTest(testDispatcher) {
        val active = "session-active"
        seedSavedSession(active)
        sessionsFlow.value = listOf(
            ChatSession(id = active, name = "Active", updatedAt = 100L),
            ChatSession(id = "session-archived", name = "Archived", updatedAt = 50L, isArchived = true),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        val thread = viewModel.state.value.thread
        assertEquals(listOf(active), thread.rows.map { it.id })
        // The count drives the drawer's archive footer entry, which only
        // appears once something is actually archived.
        assertEquals(1, thread.archivedCount)
    }

    @Test
    fun `opening an archived chat marks it read-only and refuses a send`() = runTest(testDispatcher) {
        val archived = "session-archived"
        seedSavedSession(archived)
        sessionsFlow.value = listOf(
            ChatSession(id = archived, name = "Archived", updatedAt = 50L, isArchived = true),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue("An archived chat must open read-only", viewModel.state.value.thread.archived)

        viewModel.onComposerValueChange("this must not be sent")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Accepting the message would silently un-archive a chat the user
        // deliberately put away - only the user reverses the flag.
        assertEquals("this must not be sent", viewModel.state.value.composer.value)
        coVerify(exactly = 0) { chatRepository.saveMessage(any()) }
    }

    @Test
    fun `switching to an archived thread turns the surface read-only`() = runTest(testDispatcher) {
        val active = "session-active"
        val archived = "session-archived"
        seedSavedSession(active)
        sessionsFlow.value = listOf(
            ChatSession(id = active, name = "Active", updatedAt = 100L),
            ChatSession(id = archived, name = "Archived", updatedAt = 50L, isArchived = true),
        )

        viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(!viewModel.state.value.thread.archived)

        // This is the path the archive screen takes: it posts an open-thread
        // request rather than un-archiving, so the switch itself has to carry
        // the read-only flag.
        viewModel.selectThread(archived)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.thread.archived)
        assertEquals("Archived", viewModel.state.value.thread.title)
    }

    @Test
    fun `archiving the active chat switches to the next non-archived thread`() = runTest(testDispatcher) {
        val active = "session-active"
        val other = "session-other"
        seedSavedSession(active)
        sessionsFlow.value = listOf(
            ChatSession(id = active, name = "Active", updatedAt = 100L),
            ChatSession(id = other, name = "Other", updatedAt = 50L),
        )
        coEvery { chatRepository.setSessionArchived(active, archived = true) } answers {
            sessionsFlow.value = sessionsFlow.value.map {
                if (it.id == active) it.copy(isArchived = true) else it
            }
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.archiveThread(active)
        advanceUntilIdle()

        // Leaving the user staring at the chat they just took out of the list
        // would contradict the action they asked for.
        assertEquals(other, viewModel.state.value.thread.currentSessionId)
        coVerify(exactly = 1) { chatRepository.setSessionArchived(active, archived = true) }
    }

    @Test
    fun `a failed archive leaves the user on the chat instead of pretending it worked`() = runTest(testDispatcher) {
        val active = "session-active"
        val other = "session-other"
        seedSavedSession(active)
        sessionsFlow.value = listOf(
            ChatSession(id = active, name = "Active", updatedAt = 100L),
            ChatSession(id = other, name = "Other", updatedAt = 50L),
        )
        coEvery { chatRepository.setSessionArchived(active, archived = true) } throws
            IllegalStateException("disk full")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.archiveThread(active)
        advanceUntilIdle()

        // Switching away would report success for a write that never landed.
        assertEquals(active, viewModel.state.value.thread.currentSessionId)
    }

    @Test
    fun `archiving a chat that is not open leaves the active thread alone`() = runTest(testDispatcher) {
        val active = "session-active"
        val other = "session-other"
        seedSavedSession(active)
        sessionsFlow.value = listOf(
            ChatSession(id = active, name = "Active", updatedAt = 100L),
            ChatSession(id = other, name = "Other", updatedAt = 50L),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.archiveThread(other)
        advanceUntilIdle()

        assertEquals(active, viewModel.state.value.thread.currentSessionId)
    }

    @Test
    fun `unarchiveThread restores through the use case`() = runTest(testDispatcher) {
        seedSavedSession("session-active")
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.unarchiveThread("session-archived")
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepository.setSessionArchived("session-archived", archived = false) }
    }

    @Test
    fun `deleteThread removes an arbitrary row without switching when it is not open`() = runTest(testDispatcher) {
        val active = "session-active"
        val other = "session-other"
        seedSavedSession(active)
        sessionsFlow.value = listOf(
            ChatSession(id = active, name = "Active", updatedAt = 100L),
            ChatSession(id = other, name = "Other", updatedAt = 50L),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.deleteThread(other)
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepository.deleteSession(other) }
        assertEquals(active, viewModel.state.value.thread.currentSessionId)
    }

    @Test
    fun `drawer row menu state opens and dismisses`() = runTest(testDispatcher) {
        seedSavedSession("session-active")
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.threads.openThreadMenu("session-other")
        assertEquals("session-other", viewModel.state.value.thread.openMenuId)

        viewModel.threads.dismissThreadMenu()
        assertEquals(null, viewModel.state.value.thread.openMenuId)
    }

    // endregion

    // region Voice input

    @Test
    fun `onMicClicked opens the audio chooser with the configured duration`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.voice.onMicClicked()
        advanceUntilIdle()

        val composer = viewModel.state.value.composer
        assertTrue(composer.audioChooserVisible)
        assertEquals(AUDIO_LIMIT_SEC, composer.audioMaxDurationSec)
    }

    @Test
    fun `onMicPermissionDenied surfaces the permission notice and closes the chooser`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.voice.onMicPermissionDenied()

        val composer = viewModel.state.value.composer
        assertEquals(ComposerVoiceNotice.PermissionDenied, composer.voiceNotice)
        assertFalse(composer.audioChooserVisible)
    }

    @Test
    fun `onAudioFilePicked transcribes the clip into the composer`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        coEvery { audioCaptureStore.importFromUri("content://clip") } returns kotlin.Result.success("/cache/clip.wav")
        coEvery { transcribeAudioUseCase("/cache/clip.wav") } returns TranscriptionOutcome.Success("hello world")
        advanceUntilIdle()

        viewModel.voice.onAudioFilePicked("content://clip")
        advanceUntilIdle()

        val composer = viewModel.state.value.composer
        assertEquals("hello world", composer.value)
        assertEquals(VoiceInputState.Idle, composer.voice)
    }

    @Test
    fun `transcription against a non-audio model surfaces the no-audio notice`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        coEvery { audioCaptureStore.importFromUri(any()) } returns kotlin.Result.success("/cache/clip.wav")
        coEvery { transcribeAudioUseCase(any()) } returns TranscriptionOutcome.ModelNotAudioCapable
        advanceUntilIdle()

        viewModel.voice.onAudioFilePicked("content://clip")
        advanceUntilIdle()

        assertEquals(ComposerVoiceNotice.NoAudioModel, viewModel.state.value.composer.voiceNotice)
    }

    @Test
    fun `onDiscardRecording cancels the recorder and resets the voice state`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.voice.onDiscardRecording()

        verify { audioRecorder.cancel() }
        assertEquals(VoiceInputState.Idle, viewModel.state.value.composer.voice)
    }

    @Test
    fun `startRecording transcribes the finished clip`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        every { audioRecorder.state } returns MutableStateFlow(RecordingState.Finished("/cache/clip.wav"))
        coEvery { transcribeAudioUseCase("/cache/clip.wav") } returns TranscriptionOutcome.Success("hi there")
        advanceUntilIdle()

        viewModel.voice.startRecording()
        advanceUntilIdle()

        val composer = viewModel.state.value.composer
        assertEquals("hi there", composer.value)
        assertEquals(VoiceInputState.Idle, composer.voice)
    }

    @Test
    fun `startRecording resets the composer when capture fails`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        // A failed capture emits the terminal Failed state (not Finished); the VM
        // must stop collecting and reset rather than hang forever.
        every { audioRecorder.state } returns MutableStateFlow(RecordingState.Failed)
        advanceUntilIdle()

        viewModel.voice.startRecording()
        advanceUntilIdle()

        assertEquals(VoiceInputState.Idle, viewModel.state.value.composer.voice)
        coVerify(exactly = 0) { transcribeAudioUseCase(any()) }
    }

    // endregion

    // region active-session tracking (HITL notification suppression)

    @Test
    fun `given chat visible when onChatScreenVisible then active session id is published`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId

        viewModel.onChatScreenVisible()

        assertEquals(sessionId, activeSessionTracker.activeSessionId.value)
    }

    @Test
    fun `given chat visible when onChatScreenHidden then active session id is cleared`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onChatScreenVisible()

        viewModel.onChatScreenHidden()

        assertNull(activeSessionTracker.activeSessionId.value)
    }

    // endregion

    private companion object {
        const val EARLIER_INGEST_MS = 30L
        const val LATER_INGEST_MS = 60L
        const val DEFAULT_TOKENS_MAX: Int = 4096
        const val ALT_TOKENS_MAX: Int = 8192
        const val AUDIO_LIMIT_SEC: Int = 30
    }

    @Test
    fun `a ceiling pause surfaces the card with the run's own numbers`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.WaitingForCeilingRaise(RunCeilingAxis.STEPS, limit = 15, spent = 15))
            delay(10_000)
        }
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(ChatHomeUiState.CeilingPause, viewModel.state.value.visual)
        val pending = viewModel.state.value.pending.ceiling
        assertEquals(HardCeilingBreach(RunCeilingAxis.STEPS, limit = 15, spent = 15), pending?.breach)
    }

    @Test
    fun `continuePastCeiling grants a portion and flips to Generating`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.WaitingForCeilingRaise(RunCeilingAxis.STEPS, limit = 15, spent = 15))
            delay(10_000)
        }
        coEvery { submitCeilingDecisionUseCase(any(), any(), any()) } returns PendingSubmissionOutcome.Resumed
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.hitl.continuePastCeiling()
        advanceUntilIdle()

        // No run id from the live path on purpose: a pause raised inside a
        // sub-pipeline is recorded on the child, while the session's active run
        // is the parent — so the submission looks the record up by session.
        coVerify { submitCeilingDecisionUseCase(sessionId, true, null) }
        // The card is dropped before the submission returns: leaving it up
        // through a resume invites a second tap on a decision already made.
        assertNull(viewModel.state.value.pending.ceiling)
    }

    @Test
    fun `stopAtCeiling settles the run and explains the stop where the user is looking`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        val sessionId = viewModel.state.value.thread.currentSessionId
        coEvery { agentOrchestratorUseCase(sessionId, "hi", null) } returns flow {
            emit(AgentOrchestratorState.WaitingForCeilingRaise(RunCeilingAxis.TOKENS, limit = 100, spent = 140))
            delay(10_000)
        }
        coEvery { submitCeilingDecisionUseCase(any(), any(), any()) } returns
            PendingSubmissionOutcome.NothingPending
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.hitl.stopAtCeiling()
        advanceUntilIdle()

        coVerify { submitCeilingDecisionUseCase(sessionId, false, null) }
        assertNull(viewModel.state.value.pending.ceiling)
        // The tile has to be raised here, not awaited: the engine coroutine
        // ended when the run parked, so no terminal orchestrator state is
        // coming, and without this the chat would look as if nothing happened.
        val stopped = viewModel.state.value.visual as ChatHomeUiState.Error
        assertEquals(RunTerminationReason.TokenCeiling(limit = 100, spent = 140), stopped.reason)
        // Settling the run writes its own outcome line, so the tile explains
        // without repeating it.
        assertTrue(stopped.announcedInThread)
    }

    @Test
    fun `continuePastCeiling with no pause showing is a no-op`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.hitl.continuePastCeiling()
        advanceUntilIdle()

        coVerify(exactly = 0) { submitCeilingDecisionUseCase(any(), any(), any()) }
    }
}
