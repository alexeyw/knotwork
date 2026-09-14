package app.knotwork.android.presentation.ui.more

import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.MemoryStats
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.domain.models.WorkspaceListing
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.models.WorkspaceUsage
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.PipelinePresetRepository
import app.knotwork.android.domain.repositories.PromptPresetRepository
import app.knotwork.android.domain.usecases.workspace.ListWorkspaceUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * Unit tests for [MoreViewModel] — drives the `combine` + `reduceUiState`
 * projection that powers the More tab's live subtitle counters and footer
 * privacy pill. Closes the `presentation.ui.more` coverage gap recorded in
 * `docs/coverage-baseline.md` (the ViewModel sat at ~23 % earlier;
 * only the pure formatter helpers were covered by `MoreFormattersTest`).
 *
 * `MoreViewModel` runs an infinite wall-clock `statusTicker()` inside its
 * `stateIn` upstream, so the tests subscribe via an [UnconfinedTestDispatcher]
 * and read `uiState.value` eagerly — they never call `advanceUntilIdle()`
 * while subscribed, which would otherwise spin the ticker's `delay` loop
 * forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoreViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var memoryRepository: MemoryRepository
    private lateinit var localModelRepository: LocalModelRepository
    private lateinit var promptPresetRepository: PromptPresetRepository
    private lateinit var taskQueueManager: TaskQueueManager
    private lateinit var networkActivityTracker: NetworkActivityTracker
    private lateinit var pipelinePresetRepository: PipelinePresetRepository
    private lateinit var listWorkspaceUseCase: ListWorkspaceUseCase
    private lateinit var chatRepository: ChatRepository

    private lateinit var memoryFlow: MutableStateFlow<MemoryStats>
    private lateinit var modelsFlow: MutableStateFlow<List<LocalModel>>
    private lateinit var bundledPromptsFlow: MutableStateFlow<List<PromptPreset>>
    private lateinit var userPromptsFlow: MutableStateFlow<List<PromptPreset>>
    private lateinit var sessionsFlow: MutableStateFlow<Map<String, AgentOrchestratorState>>
    private lateinit var lastOutboundFlow: MutableStateFlow<Long?>
    private lateinit var userPipelinePresetsFlow: MutableStateFlow<List<PipelinePreset>>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        memoryRepository = mockk()
        localModelRepository = mockk()
        promptPresetRepository = mockk()
        taskQueueManager = mockk()
        networkActivityTracker = mockk()
        pipelinePresetRepository = mockk()
        listWorkspaceUseCase = mockk()
        chatRepository = mockk()
        every { chatRepository.getArchivedSessionsFlow() } returns flowOf(emptyList())
        coEvery { listWorkspaceUseCase() } returns
            WorkspaceResult.Success(WorkspaceListing(emptyList(), WorkspaceUsage(usedBytes = 0L, limitBytes = 0L)))

        memoryFlow = MutableStateFlow(MemoryStats.EMPTY)
        modelsFlow = MutableStateFlow(emptyList())
        bundledPromptsFlow = MutableStateFlow(emptyList())
        userPromptsFlow = MutableStateFlow(emptyList())
        sessionsFlow = MutableStateFlow(emptyMap())
        lastOutboundFlow = MutableStateFlow(null)
        userPipelinePresetsFlow = MutableStateFlow(emptyList())

        every { memoryRepository.observeStats() } returns memoryFlow
        every { localModelRepository.getAllModels() } returns modelsFlow
        every { promptPresetRepository.getBundledPresets() } returns bundledPromptsFlow
        every { promptPresetRepository.getUserPresets() } returns userPromptsFlow
        every { taskQueueManager.activeSessionsState } returns sessionsFlow
        every { networkActivityTracker.lastOutboundAt } returns lastOutboundFlow
        every { pipelinePresetRepository.getUserPresets() } returns userPipelinePresetsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): MoreViewModel = MoreViewModel(
        memoryRepository = memoryRepository,
        localModelRepository = localModelRepository,
        promptPresetRepository = promptPresetRepository,
        taskQueueManager = taskQueueManager,
        networkActivityTracker = networkActivityTracker,
        pipelinePresetRepository = pipelinePresetRepository,
        listWorkspaceUseCase = listWorkspaceUseCase,
        chatRepository = chatRepository,
    )

    @Test
    fun `given empty repositories when subscribed then state shows idle defaults`() = runTest {
        val viewModel = buildViewModel()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        // Run pending work at the current virtual time so the `stateIn` upstream
        // produces its first projection — without advancing the clock, so the
        // infinite `statusTicker()` delay never fires.
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("no model installed", state.modelsSubtitle)
        assertEquals("none", state.tasksSubtitle)
        assertEquals(0, state.tasksBadge)
        assertEquals("no saved presets", state.librarySubtitle)
        assertEquals("empty", state.filesSubtitle)
        assertEquals("on-device · no network calls yet", state.networkStatusText)
        assertTrue(state.networkStatusOk)

        job.cancel()
    }

    @Test
    fun `given workspace files when subscribed then files subtitle reflects count and size`() = runTest {
        coEvery { listWorkspaceUseCase() } returns WorkspaceResult.Success(
            WorkspaceListing(
                files = listOf(
                    WorkspaceFile("a.md", sizeBytes = 0, lastModified = 0, isDirectory = false, isText = true),
                    WorkspaceFile("b.md", sizeBytes = 0, lastModified = 0, isDirectory = false, isText = true),
                ),
                usage = WorkspaceUsage(usedBytes = 2_000_000L, limitBytes = 64_000_000L),
            ),
        )

        val viewModel = buildViewModel()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        assertEquals("2 files · 1.9 MB", viewModel.uiState.value.filesSubtitle)

        job.cancel()
    }

    @Test
    fun `given active model and mixed sessions when subscribed then subtitles reflect counts`() = runTest {
        modelsFlow.value = listOf(
            LocalModel(id = 1L, name = "Gemma", path = "/m/gemma", size = 0L, isActive = true),
            LocalModel(id = 2L, name = "Phi", path = "/m/phi", size = 0L, isActive = false),
        )
        sessionsFlow.value = mapOf(
            "s1" to AgentOrchestratorState.Thinking(partialText = ""),
            "s2" to AgentOrchestratorState.Answering(partialText = ""),
            "s3" to AgentOrchestratorState.Idle,
            "s4" to AgentOrchestratorState.Queued,
            "s5" to AgentOrchestratorState.Queued,
        )

        val viewModel = buildViewModel()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        // Run pending work at the current virtual time so the `stateIn` upstream
        // produces its first projection — without advancing the clock, so the
        // infinite `statusTicker()` delay never fires.
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("Gemma · active", state.modelsSubtitle)
        // Two running (Thinking + Answering), two queued; the resting Idle chat is neither.
        assertEquals("2 running · 2 queued", state.tasksSubtitle)
        assertEquals(2, state.tasksBadge)

        job.cancel()
    }

    @Test
    fun `given runs loading, in a tool and on a stage when subscribed then all of them count as running`() = runTest {
        sessionsFlow.value = mapOf(
            "loading" to AgentOrchestratorState.Loading,
            "tool" to AgentOrchestratorState.ExecutingTool(toolName = "search", arguments = "{}"),
            "stage" to AgentOrchestratorState.PipelineStage(AgentOrchestratorState.PipelineStepInfo(1, 2, "Router")),
            "queued" to AgentOrchestratorState.Queued,
            "done" to AgentOrchestratorState.Completed("ok"),
            "failed" to AgentOrchestratorState.Error("boom"),
        )

        val viewModel = buildViewModel()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        // Before: only Thinking / Answering counted, so a run in any other status read as none
        // and the tab's badge stayed hidden.
        val state = viewModel.uiState.value
        assertEquals("3 running · 1 queued", state.tasksSubtitle)
        assertEquals(3, state.tasksBadge)

        job.cancel()
    }

    @Test
    fun `given prompt and pipeline presets when subscribed then subtitles reflect catalogue`() = runTest {
        bundledPromptsFlow.value = listOf(
            promptPreset(id = "b1", nodeType = NodeType.LITE_RT),
        )
        userPromptsFlow.value = listOf(
            promptPreset(id = "u1", nodeType = NodeType.CLOUD),
            promptPreset(id = "u2", nodeType = NodeType.CLOUD),
        )
        memoryFlow.value = MemoryStats(
            chunkCount = 12,
            totalBytes = 2_000_000L,
            threadCount = 1,
        )

        val viewModel = buildViewModel()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        // Run pending work at the current virtual time so the `stateIn` upstream
        // produces its first projection — without advancing the clock, so the
        // infinite `statusTicker()` delay never fires.
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        // Distinct node types: LITE_RT + CLOUD = 2 categories; 3 prompts total.
        assertEquals("2 categories · 3 prompts", state.promptsSubtitle)
        assertEquals("12 chunks · 1.9 MB", state.memorySubtitle)

        job.cancel()
    }

    @Test
    fun `given recent outbound call when subscribed then footer flips to online`() = runTest {
        lastOutboundFlow.value = System.currentTimeMillis()

        val viewModel = buildViewModel()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        // Run pending work at the current virtual time so the `stateIn` upstream
        // produces its first projection — without advancing the clock, so the
        // infinite `statusTicker()` delay never fires.
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("online · cloud enabled", state.networkStatusText)
        assertFalse(state.networkStatusOk)

        job.cancel()
    }

    @Test
    fun `given stale outbound call when subscribed then footer reads on-device`() = runTest {
        // Far enough in the past that the freshness window has elapsed.
        lastOutboundFlow.value = 0L

        val viewModel = buildViewModel()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        // Run pending work at the current virtual time so the `stateIn` upstream
        // produces its first projection — without advancing the clock, so the
        // infinite `statusTicker()` delay never fires.
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.networkStatusText.startsWith("on-device · no network calls in last"))
        assertTrue(state.networkStatusOk)

        job.cancel()
    }

    private fun promptPreset(id: String, nodeType: NodeType): PromptPreset = PromptPreset(
        id = id,
        name = "Preset $id",
        description = "",
        nodeType = nodeType,
        systemPrompt = "Do the thing.",
        isBundled = false,
    )
}
