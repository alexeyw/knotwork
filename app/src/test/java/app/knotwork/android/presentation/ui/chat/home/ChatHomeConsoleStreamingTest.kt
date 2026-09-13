package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.ConsoleEvent
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.ClarificationRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.usecases.AgentOrchestratorUseCase
import app.knotwork.android.domain.usecases.GetContextWindowUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.design.components.console.ConsoleLevel
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.ConsoleTab
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coverage for [ChatHomeViewModel] console pane
 * aggregation: how the orchestrator-emitted `ConsoleLog` / `PipelineTrace`
 * / `NodeIO` states are projected into the three console-pane tabs, and
 * how Clear / Copy / Tab callbacks interact with the resulting flows.
 *
 * The orchestrator flow is fed by a `MutableSharedFlow` so each test can
 * drive a deterministic emission sequence and inspect the VM state after
 * every step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongMethod")
class ChatHomeConsoleStreamingTest {

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
    private lateinit var pipelineRunRepository: PipelineRunRepository
    private lateinit var runTraceRepository: RunTraceRepository

    private lateinit var sessionsFlow: MutableStateFlow<List<ChatSession>>
    private lateinit var localModelsFlow: MutableStateFlow<List<LocalModel>>
    private lateinit var pipelinesFlow: MutableStateFlow<List<PipelineGraph>>
    private lateinit var messagesFlow: MutableStateFlow<List<ChatMessage>>
    private lateinit var savedSessionIdFlow: MutableStateFlow<String?>
    private lateinit var defaultPipelineIdFlow: MutableStateFlow<String?>
    private lateinit var maxContextLengthFlow: MutableStateFlow<Int>
    private lateinit var consolePreferredConsoleTabNameFlow: MutableStateFlow<String>
    private lateinit var orchestratorStateFlow: MutableSharedFlow<AgentOrchestratorState>

    private lateinit var viewModel: ChatHomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        agentOrchestratorUseCase = mockk(relaxed = true)
        chatRepository = mockk()
        pipelineRepository = mockk()
        settingsRepository = mockk(relaxed = true)
        getContextWindowUseCase = mockk()
        llmInferenceEngine = mockk(relaxed = true)
        clarificationRepository = mockk(relaxed = true)
        localModelRepository = mockk(relaxed = true)
        loadModelUseCase = mockk()
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        pipelineRunRepository = mockk()
        runTraceRepository = mockk()
        coEvery { pipelineRunRepository.getActiveRunForSession(any()) } returns null
        coEvery { pipelineRunRepository.getLatestRunForSession(any()) } returns null
        coEvery { pipelineRunRepository.getDescendantRuns(any()) } returns emptyList()
        every { pipelineRunRepository.observeActiveRunSessionIds() } returns MutableStateFlow(emptySet())
        every { pipelineRunRepository.observeRunsForSession(any()) } returns flowOf(emptyList())
        coEvery { runTraceRepository.getTraceForRun(any()) } returns emptyList()

        sessionsFlow = MutableStateFlow(emptyList())
        localModelsFlow = MutableStateFlow(emptyList())
        pipelinesFlow = MutableStateFlow(emptyList())
        messagesFlow = MutableStateFlow(emptyList())
        savedSessionIdFlow = MutableStateFlow(null)
        defaultPipelineIdFlow = MutableStateFlow(null)
        maxContextLengthFlow = MutableStateFlow(4096)
        consolePreferredConsoleTabNameFlow = MutableStateFlow("Logs")
        orchestratorStateFlow = MutableSharedFlow(extraBufferCapacity = 64)

        every { llmInferenceEngine.isInitialized } returns true
        every { localModelRepository.getAllModels() } returns localModelsFlow
        every { chatRepository.getSessionsFlow(any()) } returns sessionsFlow
        every { chatRepository.getDisplayMessagesForSession(any()) } returns messagesFlow
        coEvery { chatRepository.saveSession(any()) } answers {
            val saved = firstArg<ChatSession>()
            sessionsFlow.value = sessionsFlow.value.filterNot { it.id == saved.id } + saved
            Unit
        }
        // A new chat is renamed from its first message with a targeted rename.
        coEvery { chatRepository.renameSession(any(), any()) } returns Unit
        coEvery { chatRepository.getSessionById(any()) } returns null
        coEvery { chatRepository.saveMessage(any()) } returns Unit
        every { pipelineRepository.getAllPipelines() } returns pipelinesFlow
        every { settingsRepository.currentChatSessionId } returns savedSessionIdFlow
        every { settingsRepository.defaultPipelineId } returns defaultPipelineIdFlow
        every { settingsRepository.maxContextLength } returns maxContextLengthFlow
        every { settingsRepository.consolePreferredConsoleTabName } returns consolePreferredConsoleTabNameFlow
        coEvery { settingsRepository.setCurrentChatSessionId(any()) } answers {
            savedSessionIdFlow.value = firstArg()
        }
        coEvery { settingsRepository.setConsolePreferredConsoleTabName(any()) } answers {
            consolePreferredConsoleTabNameFlow.value = firstArg()
        }
        coEvery { getContextWindowUseCase(any()) } returns ""
        every { agentOrchestratorUseCase(any(), any(), any()) } returns orchestratorStateFlow.asSharedFlow()
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
        mockk(relaxed = true),
        pipelineRunRepository,
        runTraceRepository,
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
    ).also { vm ->
        // Keep the replay projection on the test scheduler so
        // advanceUntilIdle() deterministically covers it.
        vm.traceProjectionDispatcher = testDispatcher
    }

    @Test
    fun `given ConsoleLog emissions when sendMessage then VM aggregates mapped lines in order`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.onComposerValueChange("hello")
            viewModel.sendMessage()
            advanceUntilIdle()

            val first = ConsoleEvent(0L, ConsoleEventType.NodeExecution, "▶ INPUT")
            val second = ConsoleEvent(0L, ConsoleEventType.ToolCall, "calendar.create_event")
            orchestratorStateFlow.emit(AgentOrchestratorState.ConsoleLog(listOf(first)))
            orchestratorStateFlow.emit(AgentOrchestratorState.ConsoleLog(listOf(first, second)))
            advanceUntilIdle()

            val lines = viewModel.state.value.console.logs
            assertEquals(2, lines.size)
            assertEquals(ConsoleSource.NODE, lines[0].source)
            assertEquals(ConsoleLevel.Trace, lines[0].level)
            assertEquals("▶ INPUT", lines[0].text)
            assertEquals(ConsoleSource.TOOL, lines[1].source)
            assertEquals("calendar.create_event", lines[1].text)
        }

    @Test
    fun `given a nested sub-pipeline ConsoleLog when streamed then logs interleave by time and carry depth`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.onComposerValueChange("hello")
            viewModel.sendMessage()
            advanceUntilIdle()

            // Root run (depth 0) brackets the sub-pipeline; the nested run
            // (depth 1) is forwarded under a distinct child run id.
            val rootStart = ConsoleEvent(10L, ConsoleEventType.NodeExecution, "▶ PIPELINE", seq = 0, depth = 0)
            val rootEnd = ConsoleEvent(30L, ConsoleEventType.NodeExecution, "✓ PIPELINE", seq = 1, depth = 0)
            val nested = ConsoleEvent(20L, ConsoleEventType.NodeExecution, "[Sub] ▶ LITE_RT", seq = 0, depth = 1)

            orchestratorStateFlow.emit(AgentOrchestratorState.ConsoleLog(listOf(rootStart), runId = "root"))
            orchestratorStateFlow.emit(AgentOrchestratorState.ConsoleLog(listOf(nested), runId = "root::p::0"))
            orchestratorStateFlow.emit(AgentOrchestratorState.ConsoleLog(listOf(rootStart, rootEnd), runId = "root"))
            advanceUntilIdle()

            val lines = viewModel.state.value.console.logs
            assertEquals(3, lines.size)
            // Ordered by wall-clock time: the nested line falls between the
            // root's PIPELINE start and end.
            assertEquals("▶ PIPELINE", lines[0].text)
            assertEquals(0, lines[0].depth)
            assertEquals("[Sub] ▶ LITE_RT", lines[1].text)
            assertEquals(1, lines[1].depth)
            assertEquals("✓ PIPELINE", lines[2].text)
            assertEquals(0, lines[2].depth)
        }

    @Test
    fun `given PipelineTrace emissions when streamed then traces tab reflects spans`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        val step = AgentOrchestratorState.TraceStep(
            nodeName = "LITE_RT",
            outputText = "out",
            durationMs = 100L,
            tokenCount = null,
        )
        orchestratorStateFlow.emit(AgentOrchestratorState.PipelineTrace(listOf(step)))
        advanceUntilIdle()

        val traces = viewModel.state.value.console.traces
        assertEquals(1, traces.size)
        assertEquals("LITE_RT", traces[0].name)
        assertEquals(100L, traces[0].durationMs)
    }

    @Test
    fun `given NodeIO emissions when streamed then vars tab has two rows per node`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        orchestratorStateFlow.emit(
            AgentOrchestratorState.NodeIO(
                nodeId = "n1",
                nodeType = "LITE_RT",
                input = "in1",
                output = "out1",
            ),
        )
        advanceUntilIdle()

        val varsAfterFirst = viewModel.state.value.console.vars
        assertEquals(2, varsAfterFirst.size)
        assertEquals(CONSOLE_VAR_KEY_INPUT, varsAfterFirst[0].key)
        assertEquals(CONSOLE_VAR_KEY_OUTPUT, varsAfterFirst[1].key)

        // Second emission for the same node id overwrites — still two rows total.
        orchestratorStateFlow.emit(
            AgentOrchestratorState.NodeIO(nodeId = "n1", nodeType = "LITE_RT", input = "in2", output = "out2"),
        )
        advanceUntilIdle()
        val rewritten = viewModel.state.value.console.vars
        assertEquals(2, rewritten.size)
        assertEquals("\"in2\"", rewritten[0].valueJson)
        assertEquals("\"out2\"", rewritten[1].valueJson)

        // Distinct nodeId appends another pair.
        orchestratorStateFlow.emit(
            AgentOrchestratorState.NodeIO(nodeId = "n2", nodeType = "TOOL", input = "x", output = "y"),
        )
        advanceUntilIdle()
        assertEquals(4, viewModel.state.value.console.vars.size)
    }

    @Test
    fun `given prior console events when sendMessage then VM resets the log baseline`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onComposerValueChange("hi")
        viewModel.sendMessage()
        advanceUntilIdle()
        orchestratorStateFlow.emit(
            AgentOrchestratorState.ConsoleLog(
                listOf(ConsoleEvent(0L, ConsoleEventType.NodeExecution, "▶ INPUT")),
            ),
        )
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.console.logs.size)

        // The first run must terminate before a second `sendMessage` is
        // accepted — otherwise the VM's "already generating" guard turns
        // the second call into a no-op and the baseline is never reset.
        orchestratorStateFlow.emit(AgentOrchestratorState.Completed("done"))
        advanceUntilIdle()

        viewModel.onComposerValueChange("again")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Fresh run = empty log + empty vars + empty traces, regardless of prior buffer.
        assertTrue(viewModel.state.value.console.logs.isEmpty())
        assertTrue(viewModel.state.value.console.vars.isEmpty())
        assertTrue(viewModel.state.value.console.traces.isEmpty())
    }

    @Test
    fun `given visible lines when confirmConsoleClear then VM hides them but next snapshot survives`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.onComposerValueChange("hi")
            viewModel.sendMessage()
            advanceUntilIdle()
            val a = ConsoleEvent(0L, ConsoleEventType.NodeExecution, "a")
            val b = ConsoleEvent(0L, ConsoleEventType.NodeExecution, "b")
            orchestratorStateFlow.emit(AgentOrchestratorState.ConsoleLog(listOf(a, b)))
            advanceUntilIdle()

            viewModel.console.requestConsoleClear()
            assertTrue(viewModel.state.value.consoleClearConfirmRequested)
            viewModel.console.confirmConsoleClear()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.console.logs.isEmpty())
            assertFalse(viewModel.state.value.consoleClearConfirmRequested)

            // Next cumulative snapshot still carries [a, b] plus a new event;
            // baseline must trim leading 2 rows so only the new one shows.
            val c = ConsoleEvent(0L, ConsoleEventType.NodeExecution, "c")
            orchestratorStateFlow.emit(AgentOrchestratorState.ConsoleLog(listOf(a, b, c)))
            advanceUntilIdle()
            val visible = viewModel.state.value.console.logs
            assertEquals(1, visible.size)
            assertEquals("c", visible[0].text)
        }

    @Test
    fun `given pending tab change when onConsoleTabChange then VM persists and updates flow`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(ConsoleTab.Logs, viewModel.state.value.console.tab)

            viewModel.console.onConsoleTabChange(ConsoleTab.Traces)
            advanceUntilIdle()

            assertEquals(ConsoleTab.Traces, viewModel.state.value.console.tab)
            coVerify { settingsRepository.setConsolePreferredConsoleTabName("Traces") }
        }

    @Test
    fun `given persisted tab when VM initialises then consoleTab hydrates from settings`() = runTest(testDispatcher) {
        consolePreferredConsoleTabNameFlow.value = "Vars"
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(ConsoleTab.Vars, viewModel.state.value.console.tab)
    }

    private fun completedRun(sessionId: String): PipelineRun = PipelineRun(
        id = "run-replay",
        sessionId = sessionId,
        pipelineId = "p1",
        origin = RunOrigin.CHAT,
        status = PipelineRunStatus.COMPLETED,
        currentNodeId = "output_1",
        startedAt = 1_000L,
        finishedAt = 2_000L,
        errorMessage = null,
        graphContentHash = "hash",
    )

    private fun replayTrace(): List<RunTraceRecord> = listOf(
        RunTraceRecord.ConsoleEntry(
            runId = "run-replay",
            sessionId = SAVED_SESSION,
            seq = 0,
            timestamp = 10L,
            type = ConsoleEventType.NodeExecution,
            message = "▶ INPUT",
        ),
        RunTraceRecord.NodeIo(
            runId = "run-replay", sessionId = SAVED_SESSION, seq = 1, timestamp = 20L,
            nodeId = "llm_1", nodeType = "LITE_RT", inputText = "in", outputText = "out",
            durationMs = 100L, tokenCount = 5,
        ),
        RunTraceRecord.ConsoleEntry(
            runId = "run-replay",
            sessionId = SAVED_SESSION,
            seq = 2,
            timestamp = 30L,
            type = ConsoleEventType.NodeExecution,
            message = "✓ LITE_RT in 100ms",
        ),
    )

    @Test
    fun `given finished run with persisted trace when session opens then console replays all three tabs`() =
        runTest(testDispatcher) {
            savedSessionIdFlow.value = SAVED_SESSION
            coEvery { pipelineRunRepository.getActiveRunForSession(SAVED_SESSION) } returns null
            coEvery { pipelineRunRepository.getLatestRunForSession(SAVED_SESSION) } returns
                completedRun(SAVED_SESSION)
            coEvery { runTraceRepository.getTraceForRun("run-replay") } returns replayTrace()

            viewModel = createViewModel()
            advanceUntilIdle()

            val console = viewModel.state.value.console
            assertEquals(listOf("▶ INPUT", "✓ LITE_RT in 100ms"), console.logs.map { it.text })
            assertEquals(2, console.vars.size)
            assertEquals("\"in\"", console.vars[0].valueJson)
            assertEquals("\"out\"", console.vars[1].valueJson)
            assertEquals(1, console.traces.size)
            assertEquals("LITE_RT", console.traces.single().name)
            assertEquals(100L, console.traces.single().durationMs)
        }

    @Test
    fun `given replayed baseline when a new run starts then live events replace the baseline without duplicates`() =
        runTest(testDispatcher) {
            savedSessionIdFlow.value = SAVED_SESSION
            coEvery { pipelineRunRepository.getLatestRunForSession(SAVED_SESSION) } returns
                completedRun(SAVED_SESSION)
            coEvery { runTraceRepository.getTraceForRun("run-replay") } returns replayTrace()

            viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(2, viewModel.state.value.console.logs.size)

            // A fresh send invalidates the baseline; the new run's cumulative
            // snapshots (different runId, seq restarting at 0) must render
            // alone — no stale replayed rows ahead of them.
            viewModel.onComposerValueChange("next question")
            viewModel.sendMessage()
            advanceUntilIdle()
            orchestratorStateFlow.emit(
                AgentOrchestratorState.ConsoleLog(
                    listOf(ConsoleEvent(50L, ConsoleEventType.NodeExecution, "▶ INPUT", seq = 0)),
                    runId = "run-next",
                ),
            )
            advanceUntilIdle()

            val logs = viewModel.state.value.console.logs
            assertEquals(1, logs.size)
            assertEquals("▶ INPUT", logs.single().text)
        }

    @Test
    fun `given replayed logs when user clears console then replayed rows stay hidden`() = runTest(testDispatcher) {
        savedSessionIdFlow.value = SAVED_SESSION
        coEvery { pipelineRunRepository.getLatestRunForSession(SAVED_SESSION) } returns
            completedRun(SAVED_SESSION)
        coEvery { runTraceRepository.getTraceForRun("run-replay") } returns replayTrace()

        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.console.logs.size)

        viewModel.console.requestConsoleClear()
        viewModel.console.confirmConsoleClear()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.console.logs.isEmpty())
    }

    private companion object {
        const val SAVED_SESSION = "saved-session-replay"
    }
}
