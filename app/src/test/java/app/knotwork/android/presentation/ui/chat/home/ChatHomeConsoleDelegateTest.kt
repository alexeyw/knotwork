package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConsoleEvent
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.design.components.console.ConsoleFilter
import app.knotwork.design.components.console.ConsoleLevel
import app.knotwork.design.components.console.ConsoleLine
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.ConsoleTab
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Isolated unit tests for [ChatHomeConsoleDelegate] — the console-pane
 * responsibility extracted from [ChatHomeViewModel].
 *
 * The delegate is constructed directly over a bare
 * `MutableStateFlow<ChatHomeScreenState>` (the same shared reducer the
 * ViewModel hands it) and mocked domain dependencies, so these tests pin the
 * extracted logic without spinning up the full ViewModel graph — demonstrating
 * the testability the delegation pattern buys. End-to-end coverage through the
 * ViewModel forwarders lives in `ChatHomeConsoleStreamingTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatHomeConsoleDelegateTest {

    // Unconfined so coroutines launched on the shared scope (preferred-tab
    // observer, tab persistence, snackbar collector) run eagerly — these tests
    // assert on the result of that launched work, not on virtual-time ordering.
    private val testDispatcher = UnconfinedTestDispatcher()

    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val runTraceRepository: RunTraceRepository = mockk()
    private val pipelineRunRepository: PipelineRunRepository = mockk()

    private val state = MutableStateFlow(ChatHomeScreenState())

    /** Builds the delegate riding the test scope so launched work stays on the test scheduler. */
    private fun CoroutineScope.delegate(): ChatHomeConsoleDelegate {
        coEvery { runTraceRepository.getTraceForRun(any()) } returns emptyList()
        coEvery { pipelineRunRepository.getDescendantRuns(any()) } returns emptyList()
        return ChatHomeConsoleDelegate(
            scope = this,
            state = state,
            appStateSettings = settingsRepository,
            runTraceRepository = runTraceRepository,
            pipelineRunRepository = pipelineRunRepository,
            traceProjectionDispatcher = { testDispatcher },
        )
    }

    // ──────────────────────────── Pane intents ───────────────────────────

    @Test
    fun `given closed pane when openConsole then snap defaults to Partial`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()

        delegate.openConsole()

        assertEquals(ConsoleSnap.Partial, state.value.console.snap)
    }

    @Test
    fun `given closed pane when setConsoleSnap then it is a no-op`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()

        delegate.setConsoleSnap(ConsoleSnap.Full)

        assertNull(state.value.console.snap)
    }

    @Test
    fun `given open pane when setConsoleSnap then snap updates`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()
        delegate.openConsole(ConsoleSnap.Partial)

        delegate.setConsoleSnap(ConsoleSnap.Full)

        assertEquals(ConsoleSnap.Full, state.value.console.snap)
    }

    @Test
    fun `given open pane when closeConsole then snap is cleared`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()
        delegate.openConsole()

        delegate.closeConsole()

        assertNull(state.value.console.snap)
    }

    @Test
    fun `when toggleConsoleSearch then it cycles null to empty to null`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()

        delegate.toggleConsoleSearch()
        assertEquals("", state.value.console.searchQuery)

        delegate.toggleConsoleSearch()
        assertNull(state.value.console.searchQuery)
    }

    @Test
    fun `when onConsoleSearchQueryChange then query is set`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()

        delegate.onConsoleSearchQueryChange("router")

        assertEquals("router", state.value.console.searchQuery)
    }

    @Test
    fun `when onConsoleFilterChange then the filter replaces the active one`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()
        val filter = ConsoleFilter(sources = setOf(ConsoleSource.TOOL))

        delegate.onConsoleFilterChange(filter)

        assertEquals(filter, state.value.console.filter)
    }

    @Test
    fun `when filterConsoleByLineSource then only that source remains`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()

        delegate.filterConsoleByLineSource(ConsoleSource.MEMORY)

        assertEquals(setOf(ConsoleSource.MEMORY), state.value.console.filter.sources)
    }

    @Test
    fun `when onConsoleTabChange then tab updates and is persisted`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()

        delegate.onConsoleTabChange(ConsoleTab.Traces)
        advanceUntilIdle()

        assertEquals(ConsoleTab.Traces, state.value.console.tab)
        coVerify { settingsRepository.setConsolePreferredConsoleTabName("Traces") }
    }

    @Test
    fun `given already-selected tab when onConsoleTabChange then it is a no-op`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()

        delegate.onConsoleTabChange(ConsoleTab.Logs)
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setConsolePreferredConsoleTabName(any()) }
    }

    @Test
    fun `given persisted tab when startObservers then tab hydrates from settings`() = runTest(testDispatcher) {
        every { settingsRepository.consolePreferredConsoleTabName } returns MutableStateFlow("Vars")
        val delegate = backgroundScope.delegate()

        delegate.startObservers()
        advanceUntilIdle()

        assertEquals(ConsoleTab.Vars, state.value.console.tab)
    }

    // ──────────────────────────── Clear flow ─────────────────────────────

    @Test
    fun `given no logs when requestConsoleClear then the dialog is not raised`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()

        delegate.requestConsoleClear()

        assertFalse(state.value.consoleClearConfirmRequested)
    }

    @Test
    fun `given visible logs when requestConsoleClear then the dialog is raised and dismissable`() =
        runTest(testDispatcher) {
            val delegate = backgroundScope.delegate()
            delegate.onConsoleLog(listOf(ConsoleEvent(0L, ConsoleEventType.NodeExecution, "a")), runId = null)

            delegate.requestConsoleClear()
            assertTrue(state.value.consoleClearConfirmRequested)

            delegate.dismissConsoleClear()
            assertFalse(state.value.consoleClearConfirmRequested)
        }

    @Test
    fun `given cleared logs when next cumulative snapshot arrives then only new rows survive`() =
        runTest(testDispatcher) {
            val delegate = backgroundScope.delegate()
            val a = ConsoleEvent(0L, ConsoleEventType.NodeExecution, "a")
            val b = ConsoleEvent(0L, ConsoleEventType.NodeExecution, "b")
            delegate.onConsoleLog(listOf(a, b), runId = null)

            delegate.confirmConsoleClear()
            assertTrue(state.value.console.logs.isEmpty())
            assertFalse(state.value.consoleClearConfirmRequested)

            val c = ConsoleEvent(0L, ConsoleEventType.NodeExecution, "c")
            delegate.onConsoleLog(listOf(a, b, c), runId = null)

            assertEquals(listOf("c"), state.value.console.logs.map { it.text })
        }

    @Test
    fun `when signalConsoleLineCopied then a LineCopied event is emitted`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()
        val received = mutableListOf<ConsoleSnackbarEvent>()
        backgroundScope.launch { delegate.consoleSnackbarEvents.toList(received) }
        advanceUntilIdle()

        delegate.signalConsoleLineCopied()
        delegate.signalConsoleAllCopied()
        advanceUntilIdle()

        assertEquals(
            listOf(ConsoleSnackbarEvent.LineCopied, ConsoleSnackbarEvent.AllCopied),
            received,
        )
    }

    // ──────────────────────────── Stream handlers ────────────────────────

    @Test
    fun `given root and nested ConsoleLog when streamed then logs interleave by time and carry depth`() =
        runTest(testDispatcher) {
            val delegate = backgroundScope.delegate()
            val rootStart = ConsoleEvent(10L, ConsoleEventType.NodeExecution, "▶ PIPELINE", seq = 0, depth = 0)
            val rootEnd = ConsoleEvent(30L, ConsoleEventType.NodeExecution, "✓ PIPELINE", seq = 1, depth = 0)
            val nested = ConsoleEvent(20L, ConsoleEventType.NodeExecution, "[Sub] ▶ LITE_RT", seq = 0, depth = 1)

            delegate.onConsoleLog(listOf(rootStart), runId = "root")
            delegate.onConsoleLog(listOf(nested), runId = "root::p::0")
            delegate.onConsoleLog(listOf(rootStart, rootEnd), runId = "root")

            val logs = state.value.console.logs
            assertEquals(listOf("▶ PIPELINE", "[Sub] ▶ LITE_RT", "✓ PIPELINE"), logs.map { it.text })
            assertEquals(listOf(0, 1, 0), logs.map { it.depth })
        }

    @Test
    fun `given repeated NodeIO for one node when streamed then vars overwrite instead of duplicating`() =
        runTest(testDispatcher) {
            val delegate = backgroundScope.delegate()

            delegate.onNodeIo(AgentOrchestratorState.NodeIO("n1", "LITE_RT", "in1", "out1"))
            assertEquals(2, state.value.console.vars.size)

            delegate.onNodeIo(AgentOrchestratorState.NodeIO("n1", "LITE_RT", "in2", "out2"))
            val rewritten = state.value.console.vars
            assertEquals(2, rewritten.size)
            assertEquals("\"in2\"", rewritten[0].valueJson)

            delegate.onNodeIo(AgentOrchestratorState.NodeIO("n2", "TOOL", "x", "y"))
            assertEquals(4, state.value.console.vars.size)
        }

    @Test
    fun `given PipelineTrace when streamed then the traces tab reflects spans`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()
        val step = AgentOrchestratorState.TraceStep("LITE_RT", "out", durationMs = 100L, tokenCount = null)

        delegate.onPipelineTrace(listOf(step))

        val traces = state.value.console.traces
        assertEquals(1, traces.size)
        assertEquals("LITE_RT", traces.single().name)
        assertEquals(100L, traces.single().durationMs)
    }

    // ──────────────────────────── Replay & reset ─────────────────────────

    @Test
    fun `given a persisted trace when replayTrace then all three tabs hydrate`() = runTest(testDispatcher) {
        val delegate = backgroundScope.delegate()
        coEvery { runTraceRepository.getTraceForRun("run-replay") } returns replayTrace()

        delegate.replayTrace(completedRun())
        advanceUntilIdle()

        val console = state.value.console
        assertEquals(listOf("▶ INPUT", "✓ LITE_RT in 100ms"), console.logs.map { it.text })
        assertEquals(2, console.vars.size)
        assertEquals("\"in\"", console.vars[0].valueJson)
        assertEquals(1, console.traces.size)
        assertEquals("LITE_RT", console.traces.single().name)
    }

    @Test
    fun `given a replayed baseline when resetForNewRun then a different-run snapshot renders alone`() =
        runTest(testDispatcher) {
            val delegate = backgroundScope.delegate()
            coEvery { runTraceRepository.getTraceForRun("run-replay") } returns replayTrace()
            delegate.replayTrace(completedRun())
            advanceUntilIdle()
            assertEquals(2, state.value.console.logs.size)

            delegate.resetForNewRun()
            // The pure projection clear is the ViewModel's atomic-update concern;
            // emulate it here so the slice matches the post-reset render.
            state.value = state.value.withConsoleProjectionsCleared()

            delegate.onConsoleLog(
                listOf(ConsoleEvent(50L, ConsoleEventType.NodeExecution, "▶ INPUT", seq = 0)),
                runId = "run-next",
            )

            assertEquals(listOf("▶ INPUT"), state.value.console.logs.map { it.text })
            assertEquals(1, state.value.console.logs.size)
        }

    @Test
    fun `when withConsoleProjectionsCleared then run-scoped projections drop but pane chrome survives`() {
        val seeded = ChatHomeScreenState(
            console = ChatHomeScreenState().console.copy(
                snap = ConsoleSnap.Full,
                tab = ConsoleTab.Vars,
                searchQuery = "q",
                logs = listOf(ConsoleLine("t", ConsoleSource.NODE, ConsoleLevel.Trace, "x")),
            ),
        )

        val cleared = seeded.withConsoleProjectionsCleared()

        assertTrue(cleared.console.logs.isEmpty())
        assertTrue(cleared.console.vars.isEmpty())
        assertTrue(cleared.console.traces.isEmpty())
        // Pane chrome (snap / tab / search) is untouched.
        assertEquals(ConsoleSnap.Full, cleared.console.snap)
        assertEquals(ConsoleTab.Vars, cleared.console.tab)
        assertEquals("q", cleared.console.searchQuery)
    }

    private fun completedRun(): PipelineRun = PipelineRun(
        id = "run-replay",
        sessionId = "s1",
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
            sessionId = "s1",
            seq = 0,
            timestamp = 10L,
            type = ConsoleEventType.NodeExecution,
            message = "▶ INPUT",
        ),
        RunTraceRecord.NodeIo(
            runId = "run-replay", sessionId = "s1", seq = 1, timestamp = 20L,
            nodeId = "llm_1", nodeType = "LITE_RT", inputText = "in", outputText = "out",
            durationMs = 100L, tokenCount = 5,
        ),
        RunTraceRecord.ConsoleEntry(
            runId = "run-replay",
            sessionId = "s1",
            seq = 2,
            timestamp = 30L,
            type = ConsoleEventType.NodeExecution,
            message = "✓ LITE_RT in 100ms",
        ),
    )
}
