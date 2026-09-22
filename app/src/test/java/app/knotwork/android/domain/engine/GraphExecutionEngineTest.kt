package app.knotwork.android.domain.engine

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.data.engine.KoogClientFactory
import app.knotwork.android.data.engine.KoogCloudLlmModelResolver
import app.knotwork.android.data.repositories.ClarificationRepositoryImpl
import app.knotwork.android.domain.engine.executors.ClarificationNodeExecutor
import app.knotwork.android.domain.engine.executors.CloudLlmNodeExecutor
import app.knotwork.android.domain.engine.executors.IfConditionNodeExecutor
import app.knotwork.android.domain.engine.executors.InputNodeExecutor
import app.knotwork.android.domain.engine.executors.LiteRtNodeExecutor
import app.knotwork.android.domain.engine.executors.NodeExecutorFactory
import app.knotwork.android.domain.engine.executors.OutputNodeExecutor
import app.knotwork.android.domain.engine.executors.PipelineNodeExecutor
import app.knotwork.android.domain.engine.executors.QueueProcessorNodeExecutor
import app.knotwork.android.domain.engine.executors.SkillNodeExecutor
import app.knotwork.android.domain.engine.executors.SummaryNodeExecutor
import app.knotwork.android.domain.engine.executors.SystemNodeExecutor
import app.knotwork.android.domain.engine.executors.ToolInvocationGate
import app.knotwork.android.domain.engine.executors.ToolNodeExecutor
import app.knotwork.android.domain.engine.structured.CloudStructuredInferenceClientFactory
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.engine.stuck.GraphStuckDetector
import app.knotwork.android.domain.engine.stuck.StuckSignal
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ClarificationOutcome
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.ConsoleEvent
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.EngineImageInput
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.RunNoticeCause
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.ClarificationRepository
import app.knotwork.android.domain.repositories.CrashReportingRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.services.NativeMemorySampler
import app.knotwork.android.domain.usecases.EvaluateIfConditionUseCase
import app.knotwork.android.domain.usecases.GetContextWindowUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

class GraphExecutionEngineTest {

    private lateinit var llmEngine: LlmInferenceEngine
    private lateinit var toolRepository: ToolRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var getContextWindowUseCase: GetContextWindowUseCase
    private lateinit var retrieveRelevantMemoryUseCase: RetrieveRelevantMemoryUseCase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var metricsRepository: MetricsRepository
    private lateinit var approvalNotifier: ApprovalNotifier
    private lateinit var pendingInteractionRepository: PendingInteractionRepository
    private lateinit var ceilingNotifier: CeilingNotifier
    private lateinit var clarificationNotifier: ClarificationNotifier
    private lateinit var koogClientFactory: KoogClientFactory
    private lateinit var cloudLlmModelResolver: KoogCloudLlmModelResolver
    private lateinit var networkActivityTracker: NetworkActivityTracker
    private lateinit var evaluateIfConditionUseCase: EvaluateIfConditionUseCase
    private lateinit var loadModelUseCase: LoadModelUseCase
    private lateinit var clarificationRepository: ClarificationRepository
    private lateinit var crashReportingRepository: CrashReportingRepository
    private lateinit var localModelRepository: LocalModelRepository
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var pipelineRunRepository: PipelineRunRepository
    private lateinit var runTraceRepository: RunTraceRepository
    private lateinit var pipelineRepository: PipelineRepository

    private lateinit var engine: GraphExecutionEngine

    // Retained from setup() so a test that needs a differently-wired engine
    // (e.g. with prompt-variable providers) can rebuild just the engine instead
    // of re-assembling the whole executor factory.
    private lateinit var nodeExecutorFactory: NodeExecutorFactory
    private lateinit var toolNodeExecutor: ToolNodeExecutor
    private lateinit var promptTemplateEngine: PromptTemplateEngine
    private var promptVariableProviders: Set<PromptVariableProvider> = emptySet()

    // Stubbable in tests that route a SKILL node through the engine; the real
    // SkillNodeExecutor is covered by SkillNodeExecutorTest.
    private lateinit var skillNodeExecutor: SkillNodeExecutor

    private val sessionId = "test-session"

    @Before
    fun setup() {
        llmEngine = mockk()
        every { llmEngine.currentModelPath } returns null
        toolRepository = mockk(relaxed = true)
        chatRepository = mockk(relaxed = true)
        getContextWindowUseCase = mockk()
        retrieveRelevantMemoryUseCase = mockk()
        settingsRepository = mockk()
        apiKeyRepository = mockk(relaxed = true)
        metricsRepository = mockk(relaxed = true)
        approvalNotifier = mockk(relaxed = true)
        pendingInteractionRepository = mockk(relaxed = true)
        ceilingNotifier = mockk(relaxed = true)
        coEvery { pendingInteractionRepository.getForRun(any()) } returns null
        coEvery { pendingInteractionRepository.save(any()) } returns true
        clarificationNotifier = mockk(relaxed = true)
        koogClientFactory = mockk()
        cloudLlmModelResolver = mockk()
        networkActivityTracker = mockk(relaxed = true)
        evaluateIfConditionUseCase = mockk()
        loadModelUseCase = mockk()
        crashReportingRepository = mockk(relaxed = true)
        localModelRepository = mockk(relaxed = true)
        memoryRepository = mockk(relaxed = true)
        pipelineRunRepository = mockk(relaxed = true)
        runTraceRepository = mockk(relaxed = true)
        pipelineRepository = mockk()
        clarificationRepository = mockk()
        // The resolver is exercised whenever a CLOUD node fires; default to a sensible
        // Koog model so each individual test does not have to wire it up.
        coEvery { cloudLlmModelResolver.resolveModel(any()) } returns AnthropicModels.Sonnet_4_5
        // Provider-keyed dispatch — tests that exercise CLOUD configure Anthropic.
        coEvery { koogClientFactory.createClient(any(), any()) } coAnswers {
            when (firstArg<CloudProvider>()) {
                CloudProvider.ANTHROPIC -> koogClientFactory.createAnthropicExecutor()
                CloudProvider.OPENAI -> koogClientFactory.createOpenAIExecutor()
                CloudProvider.GOOGLE -> koogClientFactory.createGoogleExecutor()
                CloudProvider.DEEPSEEK -> koogClientFactory.createDeepSeekExecutor()
                CloudProvider.OLLAMA -> koogClientFactory.createOllamaExecutor()
            }
        }

        val inputNodeExecutor = InputNodeExecutor()
        val outputNodeExecutor = OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true))
        val ifConditionNodeExecutor = IfConditionNodeExecutor(evaluateIfConditionUseCase)
        val queueProcessorNodeExecutor = QueueProcessorNodeExecutor()

        toolNodeExecutor = ToolNodeExecutor(
            llmEngine,
            loadModelUseCase,
            toolRepository,
            ToolInvocationGate(
                toolRepository,
                settingsRepository,
                approvalNotifier,
                chatRepository,
                pendingInteractionRepository,
                recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
            ),
            StructuredOutputGate(),
            settingsRepository,
            CloudStructuredInferenceClientFactory { _, _ -> null },
        )

        val liteRtNodeExecutor = LiteRtNodeExecutor(
            llmEngine,
            settingsRepository,
            metricsRepository,
            mockk(relaxed = true),
            NativeMemorySampler { 0L },
            loadModelUseCase,
        )

        val cloudLlmNodeExecutor = CloudLlmNodeExecutor(
            settingsRepository,
            apiKeyRepository,
            metricsRepository,
            koogClientFactory,
            cloudLlmModelResolver,
            networkActivityTracker,
        )

        val systemNodeExecutor = SystemNodeExecutor(
            llmEngine,
            loadModelUseCase,
            chatRepository,
            StructuredOutputGate(),
            settingsRepository,
            CloudStructuredInferenceClientFactory { _, _ -> null },
        )

        val summaryNodeExecutor = SummaryNodeExecutor(
            llmEngine,
            loadModelUseCase,
        )

        val clarificationNodeExecutor = ClarificationNodeExecutor(
            llmEngine,
            loadModelUseCase,
            clarificationRepository,
            pendingInteractionRepository,
            clarificationNotifier,
            recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
        )

        // The recursive engine reference is captured lazily via Provider: it is
        // only dereferenced when a PIPELINE node actually executes, by which
        // point `engine` below is assigned.
        val pipelineNodeExecutor = PipelineNodeExecutor(
            pipelineRepository,
            settingsRepository,
            pipelineRunRepository,
            runTraceRepository,
            Provider { engine },
        )

        skillNodeExecutor = mockk(relaxed = true)

        nodeExecutorFactory = NodeExecutorFactory(
            inputNodeExecutor, outputNodeExecutor, ifConditionNodeExecutor,
            toolNodeExecutor, liteRtNodeExecutor, cloudLlmNodeExecutor,
            systemNodeExecutor, queueProcessorNodeExecutor, summaryNodeExecutor,
            clarificationNodeExecutor, pipelineNodeExecutor,
            skillNodeExecutor,
        )

        promptTemplateEngine = PromptTemplateEngine()
        promptVariableProviders = emptySet()
        engine = GraphExecutionEngine(
            nodeExecutorFactory,
            toolNodeExecutor,
            chatRepository,
            settingsRepository,
            metricsRepository,
            promptTemplateEngine,
            promptVariableProviders,
            NodeContextBuilder(),
            ChatHistoryWindowPlanner(),
            retrieveRelevantMemoryUseCase,
            crashReportingRepository,
            localModelRepository,
            memoryRepository,
            pipelineRunRepository,
            runTraceRepository,
            ResolveRunCeilingsUseCase(settingsRepository),
            pendingInteractionRepository,
            ceilingNotifier,
        )

        coEvery { getContextWindowUseCase(sessionId) } returns ""
        coEvery { retrieveRelevantMemoryUseCase(any()) } returns emptyList()
        coEvery { retrieveRelevantMemoryUseCase.retrieveScored(any()) } returns emptyList()
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)
        every { settingsRepository.verboseMemoryLoggingEnabled } returns flowOf(false)
        every { settingsRepository.chatHistoryCompressionEnabled } returns flowOf(false)
        every { settingsRepository.chatHistoryCompressionThresholdTokens } returns flowOf(3_500)
        every { settingsRepository.chatHistoryLiveWindowSize } returns flowOf(10)
        coEvery { chatRepository.getHistorySummary(any()) } returns null
        every { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
        every { settingsRepository.systemPromptPrefix } returns flowOf("")
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(15)
        every { settingsRepository.runMaxTokens } returns flowOf(1_000_000)
        every { settingsRepository.runMaxTokensBackground } returns flowOf(100_000)
        coEvery { pipelineRunRepository.getSpend(any()) } returns RunSpend()
        every { settingsRepository.pipelineMaxNestingDepth } returns flowOf(3)
        coEvery { toolRepository.getAvailableTools() } returns emptyList()

        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
    }

    @Test
    fun `PIPELINE node runs the target sub-pipeline and forwards its output`() = runTest {
        // Sub-pipeline: INPUT -> OUTPUT (echo). Echoes its prompt back verbatim.
        val subGraph = PipelineGraph(
            id = "sub-pipe",
            name = "Sub",
            nodes = listOf(
                NodeModel("sub_in", NodeType.INPUT, 0f, 0f),
                NodeModel("sub_out", NodeType.OUTPUT, 10f, 0f, systemPrompt = null),
            ),
            connections = listOf(ConnectionModel("sc", "sub_in", "sub_out")),
        )
        coEvery { pipelineRepository.getPipelineById("sub-pipe") } returns subGraph

        // Main pipeline: INPUT -> PIPELINE(target = sub) -> OUTPUT (echo).
        val mainGraph = PipelineGraph(
            id = "main-pipe",
            name = "Main",
            nodes = listOf(
                NodeModel("main_in", NodeType.INPUT, 0f, 0f),
                NodeModel("pipe_node", NodeType.PIPELINE, 10f, 0f, targetPipelineId = "sub-pipe"),
                NodeModel("main_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("mc1", "main_in", "pipe_node"),
                ConnectionModel("mc2", "pipe_node", "main_out"),
            ),
        )

        val states = engine(sessionId, "echo me", mainGraph).toList()

        val completed = states.last() as AgentOrchestratorState.Completed
        assertEquals("echo me", completed.finalResponse)
        // The sub-pipeline must have been loaded exactly once for the single PIPELINE node.
        coVerify(exactly = 1) { pipelineRepository.getPipelineById("sub-pipe") }
    }

    @Test
    fun `PIPELINE sub-run shares the parent step budget so depth exhaustion fails the whole stack`() = runTest {
        // Budget of 3, shared across the tree: main INPUT + main PIPELINE consume
        // 2, leaving 1 for the sub-pipeline — not enough for its INPUT + LLM, so
        // the sub-run exhausts the shared budget and the failure bubbles up.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(3)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("partial")
        val subGraph = PipelineGraph(
            id = "sub-pipe",
            name = "Sub",
            nodes = listOf(
                NodeModel("sub_in", NodeType.INPUT, 0f, 0f),
                NodeModel("sub_llm", NodeType.LITE_RT, 5f, 0f),
                NodeModel("sub_out", NodeType.OUTPUT, 10f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("sc1", "sub_in", "sub_llm"),
                ConnectionModel("sc2", "sub_llm", "sub_out"),
            ),
        )
        coEvery { pipelineRepository.getPipelineById("sub-pipe") } returns subGraph
        val mainGraph = PipelineGraph(
            id = "main-pipe",
            name = "Main",
            nodes = listOf(
                NodeModel("main_in", NodeType.INPUT, 0f, 0f),
                NodeModel("pipe_node", NodeType.PIPELINE, 10f, 0f, targetPipelineId = "sub-pipe"),
                NodeModel("main_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("mc1", "main_in", "pipe_node"),
                ConnectionModel("mc2", "pipe_node", "main_out"),
            ),
        )

        val states = engine(sessionId, "go", mainGraph).toList()

        val last = states.last()
        assertTrue("Expected a run-level error, got: $last", last is AgentOrchestratorState.Error)
        // Asserted on the typed cause, not on the sentence. The wording moved to
        // presentation resources precisely so it could be changed without a test
        // like this one going quietly green against prose nobody ships any more.
        assertTrue(
            "a depth-exhausted budget must surface as a step ceiling",
            (last as AgentOrchestratorState.Error).reason is RunTerminationReason.StepCeiling,
        )
    }

    @Test
    fun `persisted PIPELINE node creates a child run linked to the parent and settles it`() = runTest {
        coEvery { pipelineRunRepository.getRun(any()) } returns null
        val subGraph = PipelineGraph(
            id = "sub-pipe",
            name = "Sub",
            nodes = listOf(
                NodeModel("sub_in", NodeType.INPUT, 0f, 0f),
                NodeModel("sub_out", NodeType.OUTPUT, 10f, 0f, systemPrompt = null),
            ),
            connections = listOf(ConnectionModel("sc", "sub_in", "sub_out")),
        )
        coEvery { pipelineRepository.getPipelineById("sub-pipe") } returns subGraph
        val mainGraph = PipelineGraph(
            id = "main-pipe",
            name = "Main",
            nodes = listOf(
                NodeModel("main_in", NodeType.INPUT, 0f, 0f),
                NodeModel("pipe_node", NodeType.PIPELINE, 10f, 0f, targetPipelineId = "sub-pipe"),
                NodeModel("main_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("mc1", "main_in", "pipe_node"),
                ConnectionModel("mc2", "pipe_node", "main_out"),
            ),
        )

        engine(sessionId, "echo me", mainGraph, runId = "root").toList()

        // The sub-run is persisted as a child of the parent run, keyed by the
        // deterministic id, and settled COMPLETED by the executor.
        coVerify {
            pipelineRunRepository.createRun(
                match { it.id == "root::pipe_node::0" && it.parentRunId == "root" && it.pipelineId == "sub-pipe" },
            )
            pipelineRunRepository.finishRun("root::pipe_node::0", PipelineRunStatus.COMPLETED, null)
        }
    }

    @Test
    fun `sets active_pipeline_id and active_model crash custom keys at start`() = runTest {
        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val outputNode = NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)
        val graph = PipelineGraph(
            id = "graph-xyz",
            name = "Simple",
            nodes = listOf(inputNode, outputNode),
            connections = listOf(ConnectionModel("c1", "input_1", "output_1")),
        )
        coEvery { localModelRepository.getActiveModel() } returns null

        engine(sessionId, "Hi", graph).toList()

        coVerify { crashReportingRepository.setCustomKey("active_pipeline_id", "graph-xyz") }
        coVerify { crashReportingRepository.setCustomKey("active_model", "none") }
    }

    @Test
    fun `routes user reply through CLARIFICATION node into OUTPUT`() = runTest {
        // Arrange a pipeline INPUT → CLARIFICATION → OUTPUT.
        // The clarification node generates a JSON question, the repository returns
        // "user reply", and the OUTPUT node (no systemPrompt) echoes its input. The
        // clarification output pairs the asked question with the answer, so the echo
        // is the full "Q: <question>\nA: <answer>" exchange, not the bare answer.
        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val clarificationNode = NodeModel(
            id = "clar_1",
            type = NodeType.CLARIFICATION,
            x = 0f,
            y = 0f,
            systemPrompt = "Ask user for clarification.",
            clarificationTimeoutMs = 5_000L,
        )
        val outputNode = NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val graph = PipelineGraph(
            id = "g1",
            name = "Clarification Graph",
            nodes = listOf(inputNode, clarificationNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input_1", "clar_1"),
                ConnectionModel("c2", "clar_1", "output_1"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returns flowOf(
            "{\"question\":\"Confirm?\",\"options\":[\"yes\",\"no\"]}",
        )
        coEvery { clarificationRepository.requestAnswer(any()) } returns ClarificationOutcome.Answered("user reply")

        // Act
        val states = engine(sessionId, "User prompt", graph).toList()

        // Assert: AwaitingClarification was emitted with the parsed question/options.
        val awaiting = states.filterIsInstance<AgentOrchestratorState.AwaitingClarification>().single()
        assertEquals("Confirm?", awaiting.request.question)
        assertEquals(listOf("yes", "no"), awaiting.request.options)
        assertEquals(5_000L, awaiting.request.timeoutMs)

        // The user's reply propagates downstream and ends up in the final Completed state,
        // paired with the question that was asked.
        val completed = states.last() as AgentOrchestratorState.Completed
        assertEquals("Q: Confirm?\nA: user reply", completed.finalResponse)
        coVerify { clarificationRepository.requestAnswer(any()) }
    }

    @Test
    fun `successful traversal from INPUT to OUTPUT`() = runTest {
        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val llmNode = NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output_1", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Test Graph",
            nodes = listOf(inputNode, llmNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returns flowOf("LLM ", "Response")

        val states = engine(sessionId, "User prompt", graph).toList()

        val completedState = states.last() as AgentOrchestratorState.Completed
        assertEquals("LLM Response", completedState.finalResponse)
    }

    @Test
    fun `SKILL node output is forwarded to OUTPUT`() = runTest {
        val graph = PipelineGraph(
            id = "g-skill",
            name = "Skill Graph",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("skill_1", NodeType.SKILL, 0f, 0f, skillId = "skill-1"),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "skill_1"),
                ConnectionModel("c2", "skill_1", "output_1"),
            ),
        )
        every {
            skillNodeExecutor.execute(any(), any(), any(), any(), any(), any())
        } returns flowOf(
            NodeOutput.State(AgentOrchestratorState.Thinking("…")),
            NodeOutput.Result(NodeExecutionResult(outputText = "Translated text")),
        )

        val states = engine(sessionId, "Перевод", graph).toList()

        val completed = states.last() as AgentOrchestratorState.Completed
        assertEquals("Translated text", completed.finalResponse)
    }

    /**
     * Cooperative-cancellation contract of the per-node catch in the engine
     * loop: a [CancellationException] escaping a node executor must propagate
     * out of the engine flow unchanged and must never be collapsed into an
     * [AgentOrchestratorState.Error] emission.
     */
    @Test
    fun `given executor throws CancellationException then engine rethrows without Error emission`() = runTest {
        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val llmNode = NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output_1", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Test Graph",
            nodes = listOf(inputNode, llmNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returns flow {
            throw CancellationException("user stop")
        }

        val states = mutableListOf<AgentOrchestratorState>()
        var cancellation: CancellationException? = null
        try {
            engine(sessionId, "User prompt", graph).collect { states.add(it) }
        } catch (e: CancellationException) {
            cancellation = e
        }

        assertNotNull("CancellationException must propagate out of the engine flow", cancellation)
        assertTrue(
            "No Error state may be emitted on cancellation, got $states",
            states.none { it is AgentOrchestratorState.Error },
        )
    }

    @Test
    fun `evaluates IF_CONDITION and branches correctly`() = runTest {
        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val ifNode = NodeModel("if_1", NodeType.IF_CONDITION, 0f, 0f)
        val outputTrue = NodeModel("out_true", NodeType.OUTPUT, 0f, 0f)
        val outputFalse = NodeModel("out_false", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Test Graph",
            nodes = listOf(inputNode, ifNode, outputTrue, outputFalse),
            connections = listOf(
                ConnectionModel("c1", "input_1", "if_1"),
                ConnectionModel("c2", "if_1", "out_true", label = "True"),
                ConnectionModel("c3", "if_1", "out_false", label = "False"),
            ),
        )

        // Evaluate to true
        coEvery { evaluateIfConditionUseCase(ifNode, "Test prompt", any(), any()) } returns
            EvaluateIfConditionUseCase.Outcome(value = true)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Test prompt")

        val statesTrue = engine(sessionId, "Test prompt", graph).toList()
        assertTrue(statesTrue.last() is AgentOrchestratorState.Completed)

        // Output text is preserved as input text across nodes if not modified
        assertEquals("Test prompt", (statesTrue.last() as AgentOrchestratorState.Completed).finalResponse)
    }

    @Test
    fun `IF_CONDITION false verdict with only a True edge terminates without OUTPUT`() = runTest {
        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val ifNode = NodeModel("if_1", NodeType.IF_CONDITION, 0f, 0f)
        val outputTrue = NodeModel("out_true", NodeType.OUTPUT, 0f, 0f)

        // Only the True branch is wired; the False port is left unconnected.
        val graph = PipelineGraph(
            id = "g1",
            name = "Half-wired IF",
            nodes = listOf(inputNode, ifNode, outputTrue),
            connections = listOf(
                ConnectionModel("c1", "input_1", "if_1"),
                ConnectionModel("c2", "if_1", "out_true", label = "True"),
            ),
        )

        coEvery { evaluateIfConditionUseCase(ifNode, "Test prompt", any(), any()) } returns
            EvaluateIfConditionUseCase.Outcome(value = false)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Test prompt")

        val states = engine(sessionId, "Test prompt", graph).toList()

        // A false verdict must NOT silently fall through to the only wired (True)
        // edge — the run terminates without reaching OUTPUT instead.
        val last = states.last()
        assertTrue("Expected Error, got: $last", last is AgentOrchestratorState.Error)
        assertTrue((last as AgentOrchestratorState.Error).message.contains("without reaching OUTPUT"))
    }

    @Test
    fun `INTENT_ROUTER with a fallback class routes an unmatched answer to that branch`() = runTest {
        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        // The wrong branch is wired FIRST on purpose: without a fallback class
        // the run would take it, which is the behaviour this field exists to
        // replace — and the reason the skill guide told authors to order their
        // edges by hand.
        val routerNode = NodeModel("router", NodeType.INTENT_ROUTER, 0f, 0f, fallbackClass = "other")
        val deadNode = NodeModel("dead", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Router fallback",
            nodes = listOf(inputNode, routerNode, deadNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "router"),
                ConnectionModel("c2", "router", "dead", label = "billing"),
                ConnectionModel("c3", "router", "output", label = "other"),
            ),
        )
        // The gate constrains the model to the wired labels, so an unroutable run
        // is one where it never produced a usable verdict at all.
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("nonsense")

        val states = engine(sessionId, "query", graph).toList()

        assertTrue(
            "Expected Completed via the fallback branch, got: ${states.last()}",
            states.last() is AgentOrchestratorState.Completed,
        )
    }

    @Test
    fun `INTENT_ROUTER with a fallback class whose branch is unwired terminates`() = runTest {
        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val routerNode = NodeModel("router", NodeType.INTENT_ROUTER, 0f, 0f, fallbackClass = "other")
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Router fallback unwired",
            nodes = listOf(inputNode, routerNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "router"),
                ConnectionModel("c2", "router", "output", label = "billing"),
            ),
        )
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("nonsense")

        val states = engine(sessionId, "query", graph).toList()

        // Terminating beats running the wrong branch, which is the same call
        // IF_CONDITION already makes: an author who named a fallback and left it
        // unconnected asked for neither of the branches that do exist.
        val last = states.last()
        assertTrue("Expected Error, got: $last", last is AgentOrchestratorState.Error)
        assertTrue((last as AgentOrchestratorState.Error).message.contains("without reaching OUTPUT"))
    }

    @Test
    fun `INTENT_ROUTER without a fallback class keeps taking the first wired branch`() = runTest {
        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val routerNode = NodeModel("router", NodeType.INTENT_ROUTER, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Router legacy fallthrough",
            nodes = listOf(inputNode, routerNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "router"),
                ConnectionModel("c2", "router", "output", label = "billing"),
            ),
        )
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("nonsense")

        val states = engine(sessionId, "query", graph).toList()

        // Every pipeline saved before the field existed relies on this. Changing
        // it for them would be a silent re-route of graphs whose authors never
        // made the decision.
        assertTrue(
            "Expected Completed via the first branch, got: ${states.last()}",
            states.last() is AgentOrchestratorState.Completed,
        )
    }

    @Test
    fun `INTENT_ROUTER fuzzy fallback matches a whole word not an incidental substring`() = runTest {
        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val routerNode = NodeModel("router", NodeType.INTENT_ROUTER, 0f, 0f)
        // "can" branch is a dead end (LITE_RT with no outgoing edge); "Cancel"
        // branch reaches OUTPUT. A correct whole-word match routes to OUTPUT;
        // the old unanchored `contains` would route "…Cancel" to the "can" port.
        val deadNode = NodeModel("dead", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Router word-boundary",
            nodes = listOf(inputNode, routerNode, deadNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "router"),
                ConnectionModel("c2", "router", "dead", label = "can"),
                ConnectionModel("c3", "router", "output", label = "Cancel"),
            ),
        )

        // Router routing key is not an exact port label but contains "Cancel" as
        // a whole word; it must match the "Cancel" port, not "can".
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Please Cancel")

        val states = engine(sessionId, "query", graph).toList()

        assertTrue(
            "Expected Completed via the Cancel branch, got: ${states.last()}",
            states.last() is AgentOrchestratorState.Completed,
        )
    }

    @Test
    fun `INTENT_ROUTER fuzzy fallback matches a label that ends with a non-word character`() = runTest {
        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val routerNode = NodeModel("router", NodeType.INTENT_ROUTER, 0f, 0f)
        // Label "C#" ends in a non-word char: a `\b`-anchored regex would fail to
        // match it, falling through to the first edge. The lookaround form matches.
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)
        val deadNode = NodeModel("dead", NodeType.LITE_RT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Router non-word label",
            nodes = listOf(inputNode, routerNode, deadNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "router"),
                ConnectionModel("c2", "router", "dead", label = "Java"),
                ConnectionModel("c3", "router", "output", label = "C#"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returns flowOf("Use C# here")

        val states = engine(sessionId, "query", graph).toList()

        assertTrue(
            "Expected Completed via the C# branch, got: ${states.last()}",
            states.last() is AgentOrchestratorState.Completed,
        )
    }

    @Test
    fun `output node uses systemPrompt and llmEngine if prompt is provided`() = runTest {
        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val outputNode = NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = "Format this text:")

        val graph = PipelineGraph(
            id = "g1",
            name = "Output Test",
            nodes = listOf(inputNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input_1", "output_1"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returns flowOf("Formatted Response")

        val states = engine(sessionId, "Raw User Input", graph).toList()

        val completedState = states.last() as AgentOrchestratorState.Completed
        assertEquals("Formatted Response", completedState.finalResponse)

        io.mockk.verify {
            llmEngine.generateResponseStream(
                match {
                    it.contains("Format this text:") && it.contains("Raw User Input")
                },
            )
        }
    }

    @Test
    fun `prevents infinite cycles via DAG validation`() = runTest {
        val n1 = NodeModel("n1", NodeType.INPUT, 0f, 0f)
        val n2 = NodeModel("n2", NodeType.LITE_RT, 0f, 0f)

        val cyclicGraph = PipelineGraph(
            id = "g1",
            name = "Cyclic",
            nodes = listOf(n1, n2),
            connections = listOf(
                ConnectionModel("c1", "n1", "n2"),
                ConnectionModel("c2", "n2", "n1"), // cycle back
            ),
        )

        val states = engine(sessionId, "Test", cyclicGraph).toList()

        // The terminal orchestrator state must be Error so observers reading
        // the latest value of `globalState` (e.g. `TaskQueueManagerImpl`) see
        // the failure rather than a trailing ConsoleLog snapshot.
        val last = states.last()
        assertTrue(last is AgentOrchestratorState.Error)
        assertTrue((last as AgentOrchestratorState.Error).message.contains("cycles"))
    }

    @Test
    fun `emits Error if pipeline ends without reaching OUTPUT`() = runTest {
        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val llmNode = NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Incomplete",
            nodes = listOf(inputNode, llmNode),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                // No connection to an output node
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returns flowOf("Response")

        val states = engine(sessionId, "Test", graph).toList()

        val lastState = states.last()
        assertTrue(lastState is AgentOrchestratorState.Error)
        assertTrue((lastState as AgentOrchestratorState.Error).message.contains("without reaching OUTPUT"))
    }

    @Test
    fun `resumeWithApproval delegates to toolNodeExecutor`() {
        val mockToolNodeExecutor = mockk<ToolNodeExecutor>(relaxed = true)
        val mockFactory = mockk<NodeExecutorFactory>()
        val mockSettings = mockk<SettingsRepository>(relaxed = true)
        every { mockSettings.pipelineMaxSteps } returns flowOf(15)
        val engineWithMock = GraphExecutionEngine(
            mockFactory,
            mockToolNodeExecutor,
            mockk(relaxed = true),
            mockSettings,
            mockk(relaxed = true),
            PromptTemplateEngine(),
            emptySet(),
            NodeContextBuilder(),
            ChatHistoryWindowPlanner(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            ResolveRunCeilingsUseCase(settingsRepository),
            pendingInteractionRepository,
            ceilingNotifier,
        )

        engineWithMock.resumeWithApproval("session_id_123", true)

        io.mockk.verify { mockToolNodeExecutor.resumeWithApproval("session_id_123", true) }
    }

    @Test
    fun `given maxSteps exceeded when pipeline loops then emits error`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(2)

        // A chain longer than the step budget: the budget exhausts while a node
        // is still pending, so the run fails with the shared-budget error rather
        // than running out of nodes.
        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val llmNode = NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output_1", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Long Graph",
            nodes = listOf(inputNode, llmNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", graph).toList()

        val last = states.last()
        assertTrue(last is AgentOrchestratorState.Error)
        assertEquals(
            RunTerminationReason.StepCeiling(limit = 2, spent = 2),
            (last as AgentOrchestratorState.Error).reason,
        )
    }

    @Test
    fun `given maxSteps from settings when pipeline completes within limit then succeeds`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(5)

        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val outputNode = NodeModel("output_1", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Short Graph",
            nodes = listOf(inputNode, outputNode),
            connections = listOf(ConnectionModel("c1", "input_1", "output_1")),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("done")

        val states = engine(sessionId, "prompt", graph).toList()

        assertTrue(states.last() is AgentOrchestratorState.Completed)
    }

    // ─── Dynamic progress (totalSteps) tests ─────────────────────────────────

    @Test
    fun `given linear graph with no branching then totalSteps is known from first step`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val liteRtNode = NodeModel("lite_rt", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Linear",
            nodes = listOf(inputNode, liteRtNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "lite_rt"),
                ConnectionModel("c2", "lite_rt", "output"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("answer")

        val stages = engine(sessionId, "query", graph).toList()
            .filterIsInstance<AgentOrchestratorState.PipelineStage>()

        // All stages should have totalSteps = 3 (graph.nodes.size) from the start
        assertTrue("First stage should have known total", stages.first().stepInfo.totalSteps == 3)
        assertTrue("All stages must have same totalSteps = 3", stages.all { it.stepInfo.totalSteps == 3 })
    }

    @Test
    fun `given branching graph then totalSteps is null before routing and concrete after`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val routerNode = NodeModel("router", NodeType.INTENT_ROUTER, 0f, 0f)
        val liteRtNode = NodeModel("lite_rt", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Branching",
            nodes = listOf(inputNode, routerNode, liteRtNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "router"),
                ConnectionModel("c2", "router", "lite_rt", label = "Data"),
                ConnectionModel("c3", "lite_rt", "output"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("Data"),
            flowOf("answer"),
            flowOf("final"),
        )

        val stages = engine(sessionId, "query", graph).toList()
            .filterIsInstance<AgentOrchestratorState.PipelineStage>()

        // INPUT and INTENT_ROUTER stages must show unknown total
        val unknownStages = stages.take(2)
        assertTrue(
            "INPUT and INTENT_ROUTER should have null totalSteps",
            unknownStages.all { it.stepInfo.totalSteps == null },
        )

        // After routing resolves, LITE_RT and OUTPUT stages must have a concrete total
        val knownStages = stages.drop(2)
        assertTrue(
            "Post-routing stages should have concrete totalSteps",
            knownStages.all { it.stepInfo.totalSteps != null },
        )
        // stepIndex must never exceed totalSteps
        knownStages.forEach { stage ->
            assertTrue(
                "stepIndex ${stage.stepInfo.stepIndex} must not exceed totalSteps ${stage.stepInfo.totalSteps}",
                stage.stepInfo.stepIndex <= stage.stepInfo.totalSteps!!,
            )
        }
    }

    // ─── INTENT_ROUTER tests ─────────────────────────────────────────────────

    @Test
    fun `given INTENT_ROUTER when routing key emitted then downstream node receives original query`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val routerNode = NodeModel("router", NodeType.INTENT_ROUTER, 0f, 0f)
        val liteRtNode = NodeModel("lite_rt", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Router Fix Test",
            nodes = listOf(inputNode, routerNode, liteRtNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "router"),
                ConnectionModel("c2", "router", "lite_rt", label = "Data"),
                ConnectionModel("c3", "lite_rt", "output"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("Data"), // INTENT_ROUTER routing decision
            flowOf("Correct answer"), // LITE_RT processes original prompt
            flowOf("Final"), // OUTPUT formats the response
        )

        val states = engine(sessionId, "fuel consumption query", graph).toList()

        assertTrue(
            "Expected Completed but got: ${states.last()}",
            states.last() is AgentOrchestratorState.Completed,
        )

        // LITE_RT must receive the original user query (now wrapped by NodeContextBuilder),
        // not the routing key. The cleanup removed the legacy "USER/INPUT:" prefix
        // because the assembled context already carries `--- Previous Node Output ---`,
        // so we assert via the context-builder header instead.
        io.mockk.verify {
            llmEngine.generateResponseStream(
                match {
                    it.contains("--- Previous Node Output ---") && it.contains("fuel consumption query")
                },
            )
        }
        // No downstream node must receive the routing key as its previous-node-output payload.
        io.mockk.verify(exactly = 0) {
            llmEngine.generateResponseStream(match { it.contains("Previous Node Output ---\nData") })
        }
    }

    // ─── EVALUATION tests ─────────────────────────────────────────────────────

    /**
     * Builds an INPUT → EVALUATION → {Pass, Fail} → OUTPUT graph where each
     * branch carries a distinct LITE_RT marker prompt, so a routing assertion
     * can tell which output port the verdict selected.
     */
    private fun evaluationBranchGraph(): PipelineGraph = PipelineGraph(
        id = "ge",
        name = "Evaluation routing",
        nodes = listOf(
            NodeModel("input", NodeType.INPUT, 0f, 0f),
            NodeModel("eval", NodeType.EVALUATION, 0f, 0f),
            NodeModel("pass", NodeType.LITE_RT, 0f, 0f, systemPrompt = "PASS_BRANCH_MARKER"),
            NodeModel("fail", NodeType.LITE_RT, 0f, 0f, systemPrompt = "FAIL_BRANCH_MARKER"),
            NodeModel("output", NodeType.OUTPUT, 0f, 0f),
        ),
        connections = listOf(
            ConnectionModel("c1", "input", "eval"),
            ConnectionModel("c2", "eval", "pass", label = "Pass"),
            ConnectionModel("c3", "eval", "fail", label = "Fail"),
            ConnectionModel("c4", "pass", "output"),
            ConnectionModel("c5", "fail", "output"),
        ),
    )

    @Test
    fun `given EVALUATION verdict PASS then routes through the Pass output port`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("PASS — the subtask result satisfies the goal."), // EVALUATION verdict
            flowOf("pass-branch answer"), // LITE_RT on the Pass branch
            flowOf("Final"), // OUTPUT
        )

        val states = engine(sessionId, "evaluate this", evaluationBranchGraph()).toList()

        assertTrue("Expected Completed but got: ${states.last()}", states.last() is AgentOrchestratorState.Completed)
        io.mockk.verify { llmEngine.generateResponseStream(match { it.contains("PASS_BRANCH_MARKER") }) }
        io.mockk.verify(exactly = 0) {
            llmEngine.generateResponseStream(match { it.contains("FAIL_BRANCH_MARKER") })
        }
    }

    @Test
    fun `given EVALUATION verdict FAIL then routes through the Fail output port`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("FAIL: the result is incorrect and cannot be repaired."), // EVALUATION verdict
            flowOf("fail-branch answer"), // LITE_RT on the Fail branch
            flowOf("Final"), // OUTPUT
        )

        val states = engine(sessionId, "evaluate this", evaluationBranchGraph()).toList()

        assertTrue("Expected Completed but got: ${states.last()}", states.last() is AgentOrchestratorState.Completed)
        io.mockk.verify { llmEngine.generateResponseStream(match { it.contains("FAIL_BRANCH_MARKER") }) }
        io.mockk.verify(exactly = 0) {
            llmEngine.generateResponseStream(match { it.contains("PASS_BRANCH_MARKER") })
        }
    }

    // ─── QUEUE_PROCESSOR tests ────────────────────────────────────────────────

    @Test
    fun `given QUEUE_PROCESSOR when LLM returns JSON list then each item is processed and pipeline completes`() =
        runTest {
            every { settingsRepository.pipelineMaxSteps } returns flowOf(20)

            val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
            val listGenNode = NodeModel("list_gen", NodeType.LITE_RT, 0f, 0f)
            val queueNode = NodeModel("queue", NodeType.QUEUE_PROCESSOR, 0f, 0f)
            val itemProcNode = NodeModel("item_proc", NodeType.LITE_RT, 0f, 0f)
            val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

            // item_proc has no outgoing connection — engine treats it as end-of-subtask
            val graph = PipelineGraph(
                id = "g1",
                name = "Queue Test",
                nodes = listOf(inputNode, listGenNode, queueNode, itemProcNode, outputNode),
                connections = listOf(
                    ConnectionModel("c1", "input", "list_gen"),
                    ConnectionModel("c2", "list_gen", "queue"),
                    ConnectionModel("c3", "queue", "item_proc", label = "Item"),
                    ConnectionModel("c4", "queue", "output", label = "Done"),
                ),
            )

            every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
                flowOf("""["subtask_one", "subtask_two"]"""),
                flowOf("result_one"),
                flowOf("result_two"),
            )

            val states = engine(sessionId, "Process the list", graph).toList()

            assertTrue(
                "Expected Completed but got: ${states.last()}",
                states.last() is AgentOrchestratorState.Completed,
            )
        }

    @Test
    fun `given QUEUE_PROCESSOR when maxSteps exceeded during queue iteration then emits error`() = runTest {
        // maxSteps=3 is not enough to process INPUT + list_gen + queue + item_proc × 2 + output
        every { settingsRepository.pipelineMaxSteps } returns flowOf(3)

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val listGenNode = NodeModel("list_gen", NodeType.LITE_RT, 0f, 0f)
        val queueNode = NodeModel("queue", NodeType.QUEUE_PROCESSOR, 0f, 0f)
        val itemProcNode = NodeModel("item_proc", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g2",
            name = "Queue MaxSteps",
            nodes = listOf(inputNode, listGenNode, queueNode, itemProcNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "list_gen"),
                ConnectionModel("c2", "list_gen", "queue"),
                ConnectionModel("c3", "queue", "item_proc", label = "Item"),
                ConnectionModel("c4", "queue", "output", label = "Done"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("""["item_a", "item_b"]"""),
            flowOf("result_a"),
            flowOf("result_b"),
        )

        val states = engine(sessionId, "Too many steps", graph).toList()

        val last = states.last()
        assertTrue("Expected Error but got: $last", last is AgentOrchestratorState.Error)
        assertEquals(
            RunTerminationReason.StepCeiling(limit = 3, spent = 3),
            (last as AgentOrchestratorState.Error).reason,
        )
    }

    // ─── Per-node metrics tests ───────────────────────────────────────────────

    @Test
    fun `given pipeline executes LITE_RT node then metricsRepository records node execution with tokenCount`() =
        runTest {
            every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

            val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
            val llmNode = NodeModel("llm", NodeType.LITE_RT, 0f, 0f)
            val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

            val graph = PipelineGraph(
                id = "g1",
                name = "Metrics Test",
                nodes = listOf(inputNode, llmNode, outputNode),
                connections = listOf(
                    ConnectionModel("c1", "input", "llm"),
                    ConnectionModel("c2", "llm", "output"),
                ),
            )

            // LITE_RT should emit 3 tokens, OUTPUT executor also calls generateResponseStream.
            every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
                flowOf("one ", "two ", "three"),
                flowOf("final"),
            )

            engine(sessionId, "prompt", graph).toList()

            // INPUT, LITE_RT, OUTPUT — three nodes, three recordings
            verify(exactly = 1) { metricsRepository.recordNodeExecution(NodeType.INPUT, any(), any()) }
            verify(exactly = 1) {
                metricsRepository.recordNodeExecution(NodeType.LITE_RT, any(), match { it != null && it > 0 })
            }
            verify(exactly = 1) { metricsRepository.recordNodeExecution(NodeType.OUTPUT, any(), any()) }
        }

    @Test
    fun `given pipeline executes node then trace step includes durationMs and tokenCount`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val llmNode = NodeModel("llm", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Trace Timing",
            nodes = listOf(inputNode, llmNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "llm"),
                ConnectionModel("c2", "llm", "output"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("tok1 tok2"),
            flowOf("final"),
        )

        val states = engine(sessionId, "prompt", graph, runId = "run-trace-1").toList()

        val trace = states.filterIsInstance<AgentOrchestratorState.PipelineTrace>().last()
        assertTrue(trace.steps.any { it.nodeName == "LITE_RT" && (it.tokenCount ?: 0) > 0 })
        assertTrue(trace.steps.all { it.durationMs >= 0 })
        coVerify(atLeast = 1) {
            runTraceRepository.append(
                match { record ->
                    record is RunTraceRecord.NodeIo &&
                        record.runId == "run-trace-1" &&
                        record.nodeType == "LITE_RT" &&
                        (record.tokenCount ?: 0) > 0 &&
                        record.durationMs >= 0
                },
            )
        }
    }

    @Test
    fun `given QUEUE_PROCESSOR when item processor throws exception then emits error`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(20)

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val listGenNode = NodeModel("list_gen", NodeType.LITE_RT, 0f, 0f)
        val queueNode = NodeModel("queue", NodeType.QUEUE_PROCESSOR, 0f, 0f)
        val itemProcNode = NodeModel("item_proc", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g3",
            name = "Queue Error",
            nodes = listOf(inputNode, listGenNode, queueNode, itemProcNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "list_gen"),
                ConnectionModel("c2", "list_gen", "queue"),
                ConnectionModel("c3", "queue", "item_proc", label = "Item"),
                ConnectionModel("c4", "queue", "output", label = "Done"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("""["item_x"]"""),
            kotlinx.coroutines.flow.flow { throw RuntimeException("item processor crashed") },
        )

        val states = engine(sessionId, "Will crash", graph).toList()

        val last = states.last()
        assertTrue("Expected Error but got: $last", last is AgentOrchestratorState.Error)
        assertTrue((last as AgentOrchestratorState.Error).message.contains("item processor crashed"))
    }

    @Test
    fun `given a queue with stopOnError off when an item fails then the run continues to the next`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(20)

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val listGenNode = NodeModel("list_gen", NodeType.LITE_RT, 0f, 0f)
        val queueNode = NodeModel("queue", NodeType.QUEUE_PROCESSOR, 0f, 0f, stopOnError = false)
        val itemProcNode = NodeModel("item_proc", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g3b",
            name = "Queue survives a failing item",
            nodes = listOf(inputNode, listGenNode, queueNode, itemProcNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "list_gen"),
                ConnectionModel("c2", "list_gen", "queue"),
                ConnectionModel("c3", "queue", "item_proc", label = "Item"),
                ConnectionModel("c4", "queue", "output", label = "Done"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("""["first", "second"]"""),
            kotlinx.coroutines.flow.flow { throw RuntimeException("item processor crashed") },
            flowOf("second done"),
            flowOf("final answer"),
        )

        val states = engine(sessionId, "Two items, one crashes", graph).toList()

        // The failure becomes that item's result rather than the run's outcome,
        // and the second item still runs. Opt-in: `null` and `true` both keep
        // the historical behaviour, which the test above still pins.
        assertTrue(
            "Expected Completed despite one failing item, got: ${states.last()}",
            states.last() is AgentOrchestratorState.Completed,
        )
    }

    @Test
    fun `given a queue with stopOnError off when the run hits its step ceiling then it still ends`() = runTest {
        // The ceiling is what makes the queue's survival opt-in safe: without
        // this, a queue set to carry on would keep spending the budget a breach
        // has already reported as gone.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(4)

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val listGenNode = NodeModel("list_gen", NodeType.LITE_RT, 0f, 0f)
        val queueNode = NodeModel("queue", NodeType.QUEUE_PROCESSOR, 0f, 0f, stopOnError = false)
        val itemProcNode = NodeModel("item_proc", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g3c",
            name = "Queue meets the ceiling",
            nodes = listOf(inputNode, listGenNode, queueNode, itemProcNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "list_gen"),
                ConnectionModel("c2", "list_gen", "queue"),
                ConnectionModel("c3", "queue", "item_proc", label = "Item"),
                ConnectionModel("c4", "queue", "output", label = "Done"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("""["a", "b", "c", "d", "e", "f"]"""),
        ) + List(10) { flowOf("done") }

        val states = engine(sessionId, "Many items, small ceiling", graph).toList()

        assertTrue(
            "Expected the ceiling to end the run, got: ${states.last()}",
            states.last() is AgentOrchestratorState.Error,
        )
    }

    // ─── PromptTemplateEngine integration ────────────────────────────────────

    @Test
    fun `given LITE_RT node with DATE placeholder when execute then substitutes value before LLM call`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val dateProvider = mockk<PromptVariableProvider>()
        every { dateProvider.key() } returns "DATE"
        coEvery { dateProvider.resolve() } returns "01 May 2026"

        // Rebuild the engine with the provider set wired in. We construct a fresh
        // executor factory so the engine instance is completely owned by this test
        // and there is no risk of state from setUp() leaking in.
        val realFactory = NodeExecutorFactory(
            InputNodeExecutor(),
            OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true)),
            IfConditionNodeExecutor(evaluateIfConditionUseCase),
            ToolNodeExecutor(
                llmEngine,
                loadModelUseCase,
                toolRepository,
                ToolInvocationGate(
                    toolRepository,
                    settingsRepository,
                    approvalNotifier,
                    chatRepository,
                    pendingInteractionRepository,
                    recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
                ),
                StructuredOutputGate(),
                settingsRepository,
                CloudStructuredInferenceClientFactory { _, _ -> null },
            ),
            LiteRtNodeExecutor(
                llmEngine,
                settingsRepository,
                metricsRepository,
                mockk(relaxed = true),
                NativeMemorySampler { 0L },
                loadModelUseCase,
            ),
            CloudLlmNodeExecutor(
                settingsRepository,
                apiKeyRepository,
                metricsRepository,
                koogClientFactory,
                cloudLlmModelResolver,
                networkActivityTracker,
            ),
            SystemNodeExecutor(
                llmEngine,
                loadModelUseCase,
                chatRepository,
                StructuredOutputGate(),
                settingsRepository,
                CloudStructuredInferenceClientFactory {
                        _,
                        _,
                    ->
                    null
                },
            ),
            QueueProcessorNodeExecutor(),
            SummaryNodeExecutor(llmEngine, loadModelUseCase),
            ClarificationNodeExecutor(
                llmEngine,
                loadModelUseCase,
                clarificationRepository,
                pendingInteractionRepository,
                clarificationNotifier,
                recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
            ),
            PipelineNodeExecutor(
                pipelineRepository,
                settingsRepository,
                pipelineRunRepository,
                runTraceRepository,
                Provider { engine },
            ),
            mockk<SkillNodeExecutor>(relaxed = true),
        )
        val engineWithProvider = GraphExecutionEngine(
            realFactory,
            ToolNodeExecutor(
                llmEngine,
                loadModelUseCase,
                toolRepository,
                ToolInvocationGate(
                    toolRepository,
                    settingsRepository,
                    approvalNotifier,
                    chatRepository,
                    pendingInteractionRepository,
                    recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
                ),
                StructuredOutputGate(),
                settingsRepository,
                CloudStructuredInferenceClientFactory { _, _ -> null },
            ),
            chatRepository,
            settingsRepository,
            metricsRepository,
            PromptTemplateEngine(),
            setOf(dateProvider),
            NodeContextBuilder(),
            ChatHistoryWindowPlanner(),
            retrieveRelevantMemoryUseCase,
            crashReportingRepository,
            localModelRepository,
            memoryRepository,
            pipelineRunRepository,
            runTraceRepository,
            ResolveRunCeilingsUseCase(settingsRepository),
            pendingInteractionRepository,
            ceilingNotifier,
        )

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val llmNode = NodeModel("llm", NodeType.LITE_RT, 0f, 0f, systemPrompt = "Today is \$DATE.")
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Render Test",
            nodes = listOf(inputNode, llmNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "llm"),
                ConnectionModel("c2", "llm", "output"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")

        engineWithProvider(sessionId, "user query", graph).toList()

        // The rendered prompt — and ONLY the rendered prompt — must reach the LLM.
        verify { llmEngine.generateResponseStream(match { it.contains("Today is 01 May 2026.") }) }
        verify(exactly = 0) { llmEngine.generateResponseStream(match { it.contains("Today is \$DATE") }) }
    }

    // ─── End-to-end clarification scenarios ──────────────────────────────────

    @Test
    fun `given INPUT-CLARIFICATION-CLOUD-OUTPUT when user replies then answer flows into response`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        // Wire CLOUD to a mocked Anthropic client so we can capture the prompt it
        // receives and verify that the user's clarification reply flows downstream.
        val mockAnthropicClient: LLMClient = mockk(relaxed = true)
        val capturedPrompt = slot<Prompt>()
        coEvery {
            mockAnthropicClient.executeStreaming(capture(capturedPrompt), any<LLModel>())
        } returns flowOf(StreamFrame.TextDelta("cloud_response_for_user_reply"))
        coEvery { koogClientFactory.createAnthropicExecutor() } returns mockAnthropicClient

        every { apiKeyRepository.getAnthropicKey() } returns flowOf("anthropic-test-key")
        every { apiKeyRepository.getAnthropicModel() } returns flowOf("claude-sonnet-4-5")
        // Other providers are unconfigured so the auto-selection path also lands on Anthropic.
        every { apiKeyRepository.getOpenAIKey() } returns flowOf(null)
        every { apiKeyRepository.getGoogleKey() } returns flowOf(null)
        every { apiKeyRepository.getDeepSeekKey() } returns flowOf(null)

        // CLARIFICATION uses llmEngine to generate the JSON question; OUTPUT has no
        // systemPrompt so it just echoes its input — that lets us assert that the
        // CLOUD response is what the user finally sees.
        every { llmEngine.generateResponseStream(any()) } returns flowOf(
            "{\"question\":\"Confirm?\",\"options\":[\"yes\",\"no\"]}",
        )
        coEvery { clarificationRepository.requestAnswer(any()) } returns ClarificationOutcome.Answered("user reply")

        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val clarificationNode = NodeModel(
            id = "clar_1",
            type = NodeType.CLARIFICATION,
            x = 0f,
            y = 0f,
            systemPrompt = "Ask user for clarification.",
            clarificationTimeoutMs = 5_000L,
        )
        val cloudNode = NodeModel(
            id = "cloud_1",
            type = NodeType.CLOUD,
            x = 0f,
            y = 0f,
            cloudProvider = "anthropic",
            systemPrompt = "Answer the user.",
        )
        val outputNode = NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val graph = PipelineGraph(
            id = "g1",
            name = "Clarification → Cloud Graph",
            nodes = listOf(inputNode, clarificationNode, cloudNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input_1", "clar_1"),
                ConnectionModel("c2", "clar_1", "cloud_1"),
                ConnectionModel("c3", "cloud_1", "output_1"),
            ),
        )

        val states = engine(sessionId, "User prompt", graph).toList()

        // The clarification request reached the user.
        val awaiting = states.filterIsInstance<AgentOrchestratorState.AwaitingClarification>().single()
        assertEquals("Confirm?", awaiting.request.question)
        assertEquals(listOf("yes", "no"), awaiting.request.options)
        coVerify { clarificationRepository.requestAnswer(any()) }

        // The CLOUD node was invoked with the user's reply embedded in its prompt.
        coVerify { mockAnthropicClient.executeStreaming(any<Prompt>(), any<LLModel>()) }
        val cloudPromptText = capturedPrompt.captured.messages.joinToString("\n") { it.textContent() }
        assertTrue(
            "Cloud prompt must contain the clarification reply, was: $cloudPromptText",
            cloudPromptText.contains("user reply"),
        )

        // OUTPUT (no systemPrompt) echoes its input verbatim, so the cloud's response
        // is what propagates to the final Completed state.
        val completed = states.last() as AgentOrchestratorState.Completed
        assertEquals("cloud_response_for_user_reply", completed.finalResponse)
    }

    @Test
    fun `given CLARIFICATION node when no answer arrives before timeout then pipeline continues with default option`() =
        runTest {
            every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

            // Use a real ClarificationRepositoryImpl to exercise the actual withTimeout
            // behaviour: when no reply arrives, the suspended request resolves with the
            // first option and the pipeline keeps going. A mock would only prove that
            // the executor consumes whatever the repository returns — not that the
            // timeout machinery itself works under the engine.
            val realClarificationRepository = ClarificationRepositoryImpl()

            val realFactory = NodeExecutorFactory(
                InputNodeExecutor(),
                OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true)),
                IfConditionNodeExecutor(evaluateIfConditionUseCase),
                ToolNodeExecutor(
                    llmEngine,
                    loadModelUseCase,
                    toolRepository,
                    ToolInvocationGate(
                        toolRepository,
                        settingsRepository,
                        approvalNotifier,
                        chatRepository,
                        pendingInteractionRepository,
                        recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
                    ),
                    StructuredOutputGate(),
                    settingsRepository,
                    CloudStructuredInferenceClientFactory { _, _ -> null },
                ),
                LiteRtNodeExecutor(
                    llmEngine,
                    settingsRepository,
                    metricsRepository,
                    mockk(relaxed = true),
                    NativeMemorySampler { 0L },
                    loadModelUseCase,
                ),
                CloudLlmNodeExecutor(
                    settingsRepository,
                    apiKeyRepository,
                    metricsRepository,
                    koogClientFactory,
                    cloudLlmModelResolver,
                    networkActivityTracker,
                ),
                SystemNodeExecutor(
                    llmEngine,
                    loadModelUseCase,
                    chatRepository,
                    StructuredOutputGate(),
                    settingsRepository,
                    CloudStructuredInferenceClientFactory { _, _ -> null },
                ),
                QueueProcessorNodeExecutor(),
                SummaryNodeExecutor(llmEngine, loadModelUseCase),
                ClarificationNodeExecutor(
                    llmEngine,
                    loadModelUseCase,
                    realClarificationRepository,
                    pendingInteractionRepository,
                    clarificationNotifier,
                    recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
                ),
                PipelineNodeExecutor(
                    pipelineRepository,
                    settingsRepository,
                    pipelineRunRepository,
                    runTraceRepository,
                    Provider { engine },
                ),
                mockk<SkillNodeExecutor>(relaxed = true),
            )
            val engineWithRealRepo = GraphExecutionEngine(
                realFactory,
                ToolNodeExecutor(
                    llmEngine,
                    loadModelUseCase,
                    toolRepository,
                    ToolInvocationGate(
                        toolRepository,
                        settingsRepository,
                        approvalNotifier,
                        chatRepository,
                        pendingInteractionRepository,
                        recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
                    ),
                    StructuredOutputGate(),
                    settingsRepository,
                    CloudStructuredInferenceClientFactory { _, _ -> null },
                ),
                chatRepository,
                settingsRepository,
                metricsRepository,
                PromptTemplateEngine(),
                emptySet(),
                NodeContextBuilder(),
                ChatHistoryWindowPlanner(),
                retrieveRelevantMemoryUseCase,
                crashReportingRepository,
                localModelRepository,
                memoryRepository,
                pipelineRunRepository,
                runTraceRepository,
                ResolveRunCeilingsUseCase(settingsRepository),
                pendingInteractionRepository,
                ceilingNotifier,
            )

            // Generate a question with two options; the first one is the default the
            // repository falls back to on timeout.
            every { llmEngine.generateResponseStream(any()) } returns flowOf(
                "{\"question\":\"Pick one\",\"options\":[\"default-option\",\"other\"]}",
            )

            val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
            val clarificationNode = NodeModel(
                id = "clar_1",
                type = NodeType.CLARIFICATION,
                x = 0f,
                y = 0f,
                systemPrompt = "Ask user for clarification.",
                // Short timeout — runTest virtual time advances past it without any
                // submitClarification call, so the repository returns the default.
                clarificationTimeoutMs = 50L,
            )
            val outputNode = NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

            val graph = PipelineGraph(
                id = "g1",
                name = "Clarification Timeout Graph",
                nodes = listOf(inputNode, clarificationNode, outputNode),
                connections = listOf(
                    ConnectionModel("c1", "input_1", "clar_1"),
                    ConnectionModel("c2", "clar_1", "output_1"),
                ),
            )

            val states = engineWithRealRepo(sessionId, "User prompt", graph).toList()

            // The clarification was published before timing out.
            val awaiting = states.filterIsInstance<AgentOrchestratorState.AwaitingClarification>().single()
            assertEquals("Pick one", awaiting.request.question)
            assertEquals(50L, awaiting.request.timeoutMs)

            // The default option (first in the list) reached OUTPUT and surfaced as the
            // final completed response — proving the pipeline did NOT stall on the
            // unanswered request. The clarification output pairs the question with the
            // (defaulted) answer, so the echoed response carries both.
            val completed = states.last() as AgentOrchestratorState.Completed
            assertEquals("Q: Pick one\nA: default-option", completed.finalResponse)
        }

    // ─── NodeContextBuilder integration ──────────────────────────────────────

    @Test
    fun `given LITE_RT with default ALL_ENABLED config when executed then assembled context reaches LLM`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val llmNode = NodeModel("llm", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val graph = PipelineGraph(
            id = "g1",
            name = "Context Wrap Test",
            nodes = listOf(inputNode, llmNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "llm"),
                ConnectionModel("c2", "llm", "output"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")

        engine(sessionId, "what's the weather?", graph).toList()

        // The Original Task header must wrap the user message; Previous Node Output
        // header must carry INPUT's echoed payload (also "what's the weather?").
        io.mockk.verify {
            llmEngine.generateResponseStream(
                match {
                    it.contains("--- Original Task ---") &&
                        it.contains("what's the weather?") &&
                        it.contains("--- Previous Node Output ---")
                },
            )
        }
    }

    @Test
    fun `given two history nodes when a message is written between them then the later node sees it`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        // Compression is off (setup default), so the planner returns the full
        // history. The engine must re-read the message list per node — a message
        // written mid-run (e.g. a TOOL observation persisted as isFinal=false)
        // must reach a later history-enabled node, not be frozen at the value the
        // first history node saw.
        val historyConfig = NodeContextConfig(
            chatHistory = true,
            originalTask = false,
            nodeInput = true,
            longTermMemory = false,
            toolResults = false,
        )
        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val llm1 = NodeModel("llm1", NodeType.LITE_RT, 0f, 0f, contextConfig = historyConfig)
        val llm2 = NodeModel("llm2", NodeType.LITE_RT, 0f, 0f, contextConfig = historyConfig)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)
        val graph = PipelineGraph(
            id = "g-fresh-history",
            name = "Fresh history",
            nodes = listOf(inputNode, llm1, llm2, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "llm1"),
                ConnectionModel("c2", "llm1", "llm2"),
                ConnectionModel("c3", "llm2", "output"),
            ),
        )

        // First history node sees no prior messages; the observation is appended
        // before the second node reads the history again.
        val observation = ChatMessage(
            id = 9,
            sessionId = sessionId,
            role = Role.SYSTEM,
            content = "tool observation XYZ",
            timestamp = 5L,
            isFinal = false,
        )
        every { chatRepository.getMessagesForSession(sessionId) } returns flowOf(emptyList()) andThen
            flowOf(listOf(observation))
        every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")

        engine(sessionId, "hi", graph).toList()

        // The later node's assembled context must include the freshly written
        // observation — proving the live history is re-read per node, not frozen.
        verify {
            llmEngine.generateResponseStream(match { it.contains("tool observation XYZ") })
        }
    }

    @Test
    fun `given LITE_RT with only originalTask flag when executed then disabled blocks are absent from prompt`() =
        runTest {
            every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

            val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
            val llmNode = NodeModel(
                id = "llm",
                type = NodeType.LITE_RT,
                x = 0f,
                y = 0f,
                contextConfig = NodeContextConfig(
                    chatHistory = false,
                    originalTask = true,
                    nodeInput = false,
                    longTermMemory = false,
                    toolResults = false,
                ),
            )
            val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

            val graph = PipelineGraph(
                id = "g1",
                name = "Single-Flag Config Test",
                nodes = listOf(inputNode, llmNode, outputNode),
                connections = listOf(
                    ConnectionModel("c1", "input", "llm"),
                    ConnectionModel("c2", "llm", "output"),
                ),
            )

            every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")

            engine(sessionId, "the question", graph).toList()

            // Only the Original Task block (with the user prompt) is allowed in the prompt;
            // the headers for disabled blocks must not appear.
            io.mockk.verify {
                llmEngine.generateResponseStream(
                    match {
                        it.contains("--- Original Task ---") &&
                            it.contains("the question") &&
                            !it.contains("--- Chat History ---") &&
                            !it.contains("--- Long-Term Memory ---") &&
                            !it.contains("--- Tool Results ---") &&
                            !it.contains("--- Previous Node Output ---")
                    },
                )
            }
        }

    @Test
    fun `given LITE_RT with longTermMemory flag when executed then retrieved chunk reaches the prompt`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        // The retrieval use case is resolved once per run, keyed off the user
        // prompt; stub it to surface exactly one relevant chunk (with a score —
        // the engine consumes the score-preserving variant for the console).
        coEvery { retrieveRelevantMemoryUseCase.retrieveScored(any()) } returns listOf(
            MemoryChunk(
                id = 7L,
                text = "user prefers dark mode",
                embedding = FloatArray(0),
                timestamp = 0L,
            ) to 0.83f,
        )

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val llmNode = NodeModel(
            id = "llm",
            type = NodeType.LITE_RT,
            x = 0f,
            y = 0f,
            // Only Long-Term Memory is enabled, so the assembled prompt must
            // carry that block and nothing else.
            contextConfig = NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = false,
                longTermMemory = true,
                toolResults = false,
            ),
        )
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val graph = PipelineGraph(
            id = "g1",
            name = "Long-Term Memory Flag Test",
            nodes = listOf(inputNode, llmNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "llm"),
                ConnectionModel("c2", "llm", "output"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")

        engine(sessionId, "what's my UI preference?", graph).toList()

        // The LITE_RT prompt must carry the Long-Term Memory block with the
        // retrieved chunk text, and no other context block headers.
        io.mockk.verify {
            llmEngine.generateResponseStream(
                match {
                    it.contains("--- Long-Term Memory ---") &&
                        it.contains("user prefers dark mode") &&
                        !it.contains("--- Original Task ---") &&
                        !it.contains("--- Chat History ---") &&
                        !it.contains("--- Tool Results ---") &&
                        !it.contains("--- Previous Node Output ---")
                },
            )
        }
    }

    @Test
    fun `given TOOL node configured as auto when executed then resolved tool name reaches downstream`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)

        // Two tools registered; the LITE_RT used by ToolNodeExecutor for auto-selection
        // returns a JSON object naming "web.search" — so the observation must be
        // attributed to "web.search", not to the configured placeholder "auto".
        coEvery { toolRepository.getAvailableTools() } returns listOf(
            AgentTool("web.search", "Search the web", "{}"),
            AgentTool("calendar.read", "Read the calendar", "{}"),
        )
        coEvery { toolRepository.executeTool("web.search", any(), any()) } returns "search-result"

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val toolNode = NodeModel(
            id = "tool",
            type = NodeType.TOOL,
            x = 0f,
            y = 0f,
            toolName = "auto",
            // Sparse config: only Tool Results — proves that downstream nodes see
            // the resolved tool, not the literal "auto" placeholder.
            contextConfig = NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = false,
                longTermMemory = false,
                toolResults = true,
            ),
        )
        val downstreamNode = NodeModel(
            id = "llm",
            type = NodeType.LITE_RT,
            x = 0f,
            y = 0f,
            contextConfig = NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = false,
                longTermMemory = false,
                toolResults = true,
            ),
        )
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val graph = PipelineGraph(
            id = "g1",
            name = "Auto Tool Attribution",
            nodes = listOf(inputNode, toolNode, downstreamNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "tool"),
                ConnectionModel("c2", "tool", "llm"),
                ConnectionModel("c3", "llm", "output"),
            ),
        )

        // Two LLM consumers fire in order:
        //   1. ToolNodeExecutor's auto-selection LLM → returns the JSON tool choice
        //   2. The downstream LITE_RT node → return value is irrelevant for this test
        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("{\"tool\":\"web.search\",\"arguments\":{\"q\":\"weather\"}}"),
            flowOf("downstream answer"),
        )

        engine(sessionId, "find weather", graph).toList()

        // The downstream LITE_RT prompt must carry the Tool Results block attributed
        // to the resolved tool name, NOT the literal "auto" or the node label.
        io.mockk.verify {
            llmEngine.generateResponseStream(
                match {
                    it.contains("--- Tool Results ---") &&
                        it.contains("web.search: search-result") &&
                        !it.contains("auto: search-result") &&
                        !it.contains("TOOL: search-result")
                },
            )
        }
    }

    @Test
    fun `given control-flow nodes when executed then their input is not wrapped with context headers`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        // INPUT echoes its raw input; if the engine wrapped it, the OUTPUT (echo mode)
        // would surface the wrapped string as the final response. The fact that the
        // pipeline below produces "raw passthrough" verifies INPUT is left untouched.
        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val graph = PipelineGraph(
            id = "g1",
            name = "Passthrough Test",
            nodes = listOf(inputNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "output"),
            ),
        )

        val states = engine(sessionId, "raw passthrough", graph).toList()

        val completed = states.last() as AgentOrchestratorState.Completed
        assertEquals("raw passthrough", completed.finalResponse)
    }

    @Test
    fun `given INPUT-TOOL-CLOUD-OUTPUT with distinct contexts when run then TOOL is nodeInput-only`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)

        // Pre-seed the pipeline-scoped data sources so every block in
        // ALL_ENABLED has something visible to render. Without these stubs the
        // builder would correctly drop empty blocks and we could not
        // distinguish "block omitted because flag is false" from "block
        // omitted because data is empty".
        every { chatRepository.getMessagesForSession(sessionId) } returns flowOf(
            listOf(
                ChatMessage(
                    id = 1L,
                    sessionId = sessionId,
                    role = Role.USER,
                    content = "earlier question",
                    timestamp = 0L,
                ),
                ChatMessage(
                    id = 2L,
                    sessionId = sessionId,
                    role = Role.AGENT,
                    content = "earlier answer",
                    timestamp = 1L,
                ),
            ),
        )
        coEvery { retrieveRelevantMemoryUseCase.retrieveScored(any()) } returns listOf(
            MemoryChunk(
                id = 99L,
                text = "user lives in Berlin",
                embedding = FloatArray(0),
                timestamp = 0L,
            ) to 0.77f,
        )

        // ToolRepository: one available tool plus a deterministic execution
        // result. The auto-selector LLM call (see returnsMany below) names
        // "web.search", so this is the tool the engine actually invokes.
        coEvery { toolRepository.getAvailableTools() } returns listOf(
            AgentTool("web.search", "Search the web", "{}"),
        )
        coEvery { toolRepository.executeTool("web.search", any(), any()) } returns "search-result"

        // Cloud client mock — captures the prompt the CLOUD node receives so we
        // can assert that the full-context wrap reached the cloud LLM.
        val mockAnthropicClient: LLMClient = mockk(relaxed = true)
        val capturedCloudPrompt = slot<Prompt>()
        coEvery {
            mockAnthropicClient.executeStreaming(capture(capturedCloudPrompt), any<LLModel>())
        } returns flowOf(StreamFrame.TextDelta("cloud_answer"))
        coEvery { koogClientFactory.createAnthropicExecutor() } returns mockAnthropicClient

        every { apiKeyRepository.getAnthropicKey() } returns flowOf("anthropic-test-key")
        every { apiKeyRepository.getAnthropicModel() } returns flowOf("claude-sonnet-4-5")
        every { apiKeyRepository.getOpenAIKey() } returns flowOf(null)
        every { apiKeyRepository.getGoogleKey() } returns flowOf(null)
        every { apiKeyRepository.getDeepSeekKey() } returns flowOf(null)

        // Two LITE_RT consumers fire in order:
        //   1. ToolNodeExecutor's auto-selection LLM → returns the JSON tool choice
        //   2. OUTPUT (with systemPrompt set) → returns the final formatted reply
        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("{\"tool\":\"web.search\",\"arguments\":{\"q\":\"weather\"}}"),
            flowOf("final_formatted_reply"),
        )

        // ─── Pipeline definition ───
        // TOOL: nodeInput-only — must NOT see chat history, memory or tool
        //                        results (none yet) in its assembled input.
        // CLOUD: ALL_ENABLED   — should see every block, including the tool
        //                        result produced by the upstream TOOL node.
        // OUTPUT: ALL_ENABLED + systemPrompt — should also see every block.
        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val toolNode = NodeModel(
            id = "tool_1",
            type = NodeType.TOOL,
            x = 0f,
            y = 0f,
            toolName = "auto",
            contextConfig = NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = true,
                longTermMemory = false,
                toolResults = false,
            ),
        )
        val cloudNode = NodeModel(
            id = "cloud_1",
            type = NodeType.CLOUD,
            x = 0f,
            y = 0f,
            cloudProvider = "anthropic",
            systemPrompt = "Answer the user.",
            contextConfig = NodeContextConfig.ALL_ENABLED,
        )
        val outputNode = NodeModel(
            id = "output_1",
            type = NodeType.OUTPUT,
            x = 0f,
            y = 0f,
            systemPrompt = "Format reply:",
            contextConfig = NodeContextConfig.ALL_ENABLED,
        )

        val graph = PipelineGraph(
            id = "g1",
            name = "Integration test",
            nodes = listOf(inputNode, toolNode, cloudNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input_1", "tool_1"),
                ConnectionModel("c2", "tool_1", "cloud_1"),
                ConnectionModel("c3", "cloud_1", "output_1"),
            ),
        )

        // ─── Act ───
        val states = engine(sessionId, "user prompt", graph).toList()

        // ─── Assert: pipeline ran end-to-end ───
        val completed = states.last() as AgentOrchestratorState.Completed
        assertEquals("final_formatted_reply", completed.finalResponse)

        // ─── Assert: TOOL's auto-selector LITE_RT prompt carries ONLY the
        // Previous Node Output block — no chat history, memory or tool
        // results bleed in (TOOL has not produced any results at that point
        // anyway, but the configuration must also block the other blocks).
        io.mockk.verify {
            llmEngine.generateResponseStream(
                match {
                    it.contains("--- Previous Node Output ---") &&
                        it.contains("user prompt") &&
                        !it.contains("--- Chat History ---") &&
                        !it.contains("--- Long-Term Memory ---") &&
                        !it.contains("--- Tool Results ---") &&
                        !it.contains("--- Original Task ---")
                },
            )
        }

        // ─── Assert: OUTPUT's LITE_RT prompt carries the full ALL_ENABLED
        // wrap, including the tool result accumulated upstream.
        io.mockk.verify {
            llmEngine.generateResponseStream(
                match {
                    it.contains("--- Original Task ---") &&
                        it.contains("user prompt") &&
                        it.contains("--- Chat History ---") &&
                        it.contains("USER: earlier question") &&
                        it.contains("--- Long-Term Memory ---") &&
                        it.contains("user lives in Berlin") &&
                        it.contains("--- Tool Results ---") &&
                        it.contains("web.search: search-result") &&
                        it.contains("--- Previous Node Output ---")
                },
            )
        }

        // ─── Assert: CLOUD received the full context wrap (ALL_ENABLED). The
        // cloud client serialises every prompt message into its own field, so
        // we collapse them into a single string for substring assertions.
        coVerify { mockAnthropicClient.executeStreaming(any<Prompt>(), any<LLModel>()) }
        val cloudPromptText = capturedCloudPrompt.captured.messages.joinToString("\n") { it.textContent() }
        assertTrue(
            "CLOUD prompt missing Original Task block: $cloudPromptText",
            cloudPromptText.contains("--- Original Task ---") && cloudPromptText.contains("user prompt"),
        )
        assertTrue(
            "CLOUD prompt missing Chat History block: $cloudPromptText",
            cloudPromptText.contains("--- Chat History ---") && cloudPromptText.contains("earlier question"),
        )
        assertTrue(
            "CLOUD prompt missing Long-Term Memory block: $cloudPromptText",
            cloudPromptText.contains(
                "--- Long-Term Memory ---",
            ) &&
                cloudPromptText.contains("user lives in Berlin"),
        )
        assertTrue(
            "CLOUD prompt missing Tool Results block: $cloudPromptText",
            cloudPromptText.contains("--- Tool Results ---") &&
                cloudPromptText.contains("web.search: search-result"),
        )
        assertTrue(
            "CLOUD prompt missing Previous Node Output block: $cloudPromptText",
            cloudPromptText.contains("--- Previous Node Output ---"),
        )
    }

    // ─── Agent console event emissions ──────────────────────────

    @Test
    fun `given linear pipeline when run completes then console log spans memory and node lifecycle events`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val llmNode = NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f)
        val outputNode = NodeModel("output_1", NodeType.OUTPUT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "Console Linear",
            nodes = listOf(inputNode, llmNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("answer")

        // The latest ConsoleLog snapshot contains the full event sequence by
        // construction (the engine accumulates and emits a copy on every push).
        val finalLog = engine(sessionId, "User prompt", graph).toList()
            .filterIsInstance<AgentOrchestratorState.ConsoleLog>()
            .last()
            .events

        // Memory access is reported even when the corpus is empty, carrying the
        // truncated query and a zero-hit count.
        val memEvent = finalLog.single { it.type == ConsoleEventType.MemoryAccess }
        assertTrue("Missing query echo: ${memEvent.message}", memEvent.message.contains("query='User prompt'"))
        assertTrue("Missing zero-hit count: ${memEvent.message}", memEvent.message.contains("0 hits"))

        // Every non-OUTPUT node yields a paired ▶ / ✓ NodeExecution event;
        // OUTPUT only gets a ▶ because its own Completed state already serves
        // as the success marker (engine deliberately suppresses the trailing
        // "✓" so Completed remains the last orchestrator state).
        val nodeMessages = finalLog.filter { it.type == ConsoleEventType.NodeExecution }
            .map { it.message }
        assertTrue("Missing INPUT start", nodeMessages.any { it.startsWith("▶") && it.contains("INPUT") })
        assertTrue("Missing INPUT done", nodeMessages.any { it.startsWith("✓") && it.contains("INPUT") })
        assertTrue("Missing LITE_RT start", nodeMessages.any { it.startsWith("▶") && it.contains("LITE_RT") })
        assertTrue("Missing LITE_RT done", nodeMessages.any { it.startsWith("✓") && it.contains("LITE_RT") })
        assertTrue("Missing OUTPUT start", nodeMessages.any { it.startsWith("▶") && it.contains("OUTPUT") })
        assertTrue("OUTPUT must not push ✓", nodeMessages.none { it.startsWith("✓") && it.contains("OUTPUT") })
    }

    @Test
    fun `given an image input when run executes then only the first LITE_RT node receives it`() = runTest {
        val graph = PipelineGraph(
            id = "g1",
            name = "Multimodal",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_a", NodeType.LITE_RT, 0f, 0f, systemPrompt = "NODE_A"),
                NodeModel("llm_b", NodeType.LITE_RT, 0f, 0f, systemPrompt = "NODE_B"),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_a"),
                ConnectionModel("c2", "llm_a", "llm_b"),
                ConnectionModel("c3", "llm_b", "output_1"),
            ),
        )
        coEvery { loadModelUseCase(any(), any()) } returns Result.Success(Unit)
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("out")

        val image = EngineImageInput(absolutePath = "/abs/photo.jpg", width = 800, height = 600, sizeBytes = 4096)
        val states = engine(sessionId, "Describe", graph, imageInput = image).toList()

        assertTrue(states.last() is AgentOrchestratorState.Completed)

        // Exactly one generation carried the image, and it was the FIRST LITE_RT node.
        verify(exactly = 1) { llmEngine.generateResponseStream(any(), "/abs/photo.jpg", any()) }
        verify { llmEngine.generateResponseStream(match { it.contains("NODE_A") }, "/abs/photo.jpg", any()) }
        // The downstream LITE_RT node sees only text — the contract is "image belongs to userPrompt".
        verify { llmEngine.generateResponseStream(match { it.contains("NODE_B") }, null, any()) }
        // The receiving node loaded the engine in vision mode.
        coVerify { loadModelUseCase(any(), requireVision = true) }

        // The run announces the image once at start.
        val consoleLines = states.filterIsInstance<AgentOrchestratorState.ConsoleLog>()
            .last().events.map { it.message }
        assertTrue(
            "Missing image-input console line: $consoleLines",
            consoleLines.any { it.contains("Image input: 800×600, 4 KB") },
        )
    }

    @Test
    fun `given an image input when the only LITE_RT lives in a sub-pipeline then the nested node receives it`() =
        runTest {
            val subGraph = PipelineGraph(
                id = "sub-pipe",
                name = "Sub",
                nodes = listOf(
                    NodeModel("sub_in", NodeType.INPUT, 0f, 0f),
                    NodeModel("sub_lite", NodeType.LITE_RT, 10f, 0f, systemPrompt = "SUB_NODE"),
                    NodeModel("sub_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
                ),
                connections = listOf(
                    ConnectionModel("s1", "sub_in", "sub_lite"),
                    ConnectionModel("s2", "sub_lite", "sub_out"),
                ),
            )
            coEvery { pipelineRepository.getPipelineById("sub-pipe") } returns subGraph
            val mainGraph = PipelineGraph(
                id = "main-pipe",
                name = "Main",
                nodes = listOf(
                    NodeModel("main_in", NodeType.INPUT, 0f, 0f),
                    NodeModel("pipe_node", NodeType.PIPELINE, 10f, 0f, targetPipelineId = "sub-pipe"),
                    NodeModel("main_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
                ),
                connections = listOf(
                    ConnectionModel("m1", "main_in", "pipe_node"),
                    ConnectionModel("m2", "pipe_node", "main_out"),
                ),
            )
            coEvery { loadModelUseCase(any(), any()) } returns Result.Success(Unit)
            every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("answer")

            val image = EngineImageInput(absolutePath = "/abs/y.jpg", width = 640, height = 480, sizeBytes = 4096)
            val states = engine(sessionId, "Describe", mainGraph, imageInput = image).toList()

            assertTrue(states.last() is AgentOrchestratorState.Completed)
            // The image was forwarded into the sub-pipeline and consumed by its LITE_RT node.
            verify(exactly = 1) { llmEngine.generateResponseStream(any(), "/abs/y.jpg", any()) }
            verify { llmEngine.generateResponseStream(match { it.contains("SUB_NODE") }, "/abs/y.jpg", any()) }
            val consoleLines = states.filterIsInstance<AgentOrchestratorState.ConsoleLog>()
                .last().events.map { it.message }
            assertTrue(
                "Unexpected 'Image not used' note: $consoleLines",
                consoleLines.none { it.contains("Image not used") },
            )
        }

    @Test
    fun `given an image input but no vision-sink node when run completes then emits an Image not used note`() =
        runTest {
            // INPUT -> OUTPUT (echo): no LITE_RT-with-originalTask node, so the
            // image can never be delivered. The engine must say so on the console.
            val graph = PipelineGraph(
                id = "g1",
                name = "No sink",
                nodes = listOf(
                    NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                    NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
                ),
                connections = listOf(ConnectionModel("c1", "input_1", "output_1")),
            )
            val image = EngineImageInput(absolutePath = "/abs/x.jpg", width = 10, height = 10, sizeBytes = 2048)

            val states = engine(sessionId, "hi", graph, imageInput = image).toList()

            assertTrue(states.last() is AgentOrchestratorState.Completed)
            val consoleLines = states.filterIsInstance<AgentOrchestratorState.ConsoleLog>()
                .last().events.map { it.message }
            assertTrue(
                "Missing 'Image not used' note: $consoleLines",
                consoleLines.any {
                    it.contains("Image not used")
                },
            )
        }

    @Test
    fun `given retrieved hits when run completes then MemoryAccess event carries query and scores`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        coEvery { retrieveRelevantMemoryUseCase.retrieveScored(any()) } returns listOf(
            MemoryChunk(id = 1L, text = "user prefers dark mode", embedding = FloatArray(0), timestamp = 0L) to 0.9f,
            MemoryChunk(id = 2L, text = "user lives in Berlin", embedding = FloatArray(0), timestamp = 0L) to 0.4f,
        )

        val graph = PipelineGraph(
            id = "g1",
            name = "Memory Console",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("answer")

        val memEvent = engine(sessionId, "what is my UI preference", graph).toList()
            .filterIsInstance<AgentOrchestratorState.ConsoleLog>()
            .last()
            .events
            .single { it.type == ConsoleEventType.MemoryAccess }

        // Terse format (verbose off): single line with query echo, hit count and scores.
        assertEquals(
            "Memory: query='what is my UI preference' [user prompt] → 2 hits (0.90, 0.40)",
            memEvent.message,
        )
    }

    @Test
    fun `given verbose memory logging when run completes then MemoryAccess event expands per-hit snippets`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.verboseMemoryLoggingEnabled } returns flowOf(true)
        coEvery { retrieveRelevantMemoryUseCase.retrieveScored(any()) } returns listOf(
            MemoryChunk(id = 1L, text = "user prefers dark mode", embedding = FloatArray(0), timestamp = 0L) to 0.9f,
        )

        val graph = PipelineGraph(
            id = "g1",
            name = "Memory Console Verbose",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("answer")

        val memEvent = engine(sessionId, "prefs", graph).toList()
            .filterIsInstance<AgentOrchestratorState.ConsoleLog>()
            .last()
            .events
            .single { it.type == ConsoleEventType.MemoryAccess }

        assertTrue(
            "Missing header line: ${memEvent.message}",
            memEvent.message.startsWith("Memory: query='prefs' [user prompt] → 1 hits (0.90)"),
        )
        assertTrue(
            "Missing per-hit snippet: ${memEvent.message}",
            memEvent.message.contains("1. [0.90] user prefers dark mode"),
        )
    }

    @Test
    fun `given pipeline ending without OUTPUT when run terminates then last console event is Error`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val inputNode = NodeModel("input_1", NodeType.INPUT, 0f, 0f)
        val llmNode = NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f)

        val graph = PipelineGraph(
            id = "g1",
            name = "No Output",
            nodes = listOf(inputNode, llmNode),
            connections = listOf(ConnectionModel("c1", "input_1", "llm_1")),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Response")

        val finalLog = engine(sessionId, "Prompt", graph).toList()
            .filterIsInstance<AgentOrchestratorState.ConsoleLog>()
            .last()
            .events

        assertEquals(ConsoleEventType.Error, finalLog.last().type)
        assertTrue(
            "Last error should mention missing OUTPUT: ${finalLog.last().message}",
            finalLog.last().message.contains("OUTPUT", ignoreCase = true),
        )
    }

    // ─── Risk-based HITL gate end-to-end ──────────────────────

    @Test
    fun `given pipeline with READ_ONLY tool node when run then completes without HITL pause`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)

        // READ_ONLY + global override OFF must skip the HITL gate entirely: no
        // WaitingForApproval emission, no notifier call, and the pipeline
        // reaches OUTPUT without intervention.
        coEvery { toolRepository.getRisk("web.search", any()) } returns ToolRisk.READ_ONLY
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool("web.search", "Search", "{}"))
        coEvery { toolRepository.executeTool("web.search", any(), any()) } returns "search-result"

        val inputNode = NodeModel("input", NodeType.INPUT, 0f, 0f)
        val toolNode = NodeModel(
            id = "tool",
            type = NodeType.TOOL,
            x = 0f,
            y = 0f,
            toolName = "web.search",
        )
        val outputNode = NodeModel("output", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val graph = PipelineGraph(
            id = "g1",
            name = "Read-only Tool Without HITL",
            nodes = listOf(inputNode, toolNode, outputNode),
            connections = listOf(
                ConnectionModel("c1", "input", "tool"),
                ConnectionModel("c2", "tool", "output"),
            ),
        )

        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool":"web.search","arguments":"q=weather"}""")

        val emissions = engine(sessionId, "find weather", graph).toList()

        val sawApproval = emissions.any { it is AgentOrchestratorState.WaitingForApproval }
        assertTrue("READ_ONLY tool must not pause for approval", !sawApproval)
        verify(exactly = 0) { approvalNotifier.sendApprovalRequest(any(), any(), any(), any()) }
        assertTrue(
            "Pipeline should reach Completed when HITL is skipped",
            emissions.any { it is AgentOrchestratorState.Completed },
        )
    }

    // ─── Persistent run write-through ──────────────────────────

    @Test
    fun `given runId then currentNodeId is written for every executed node`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val graph = PipelineGraph(
            id = "g1",
            name = "Linear",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("answer")

        engine(sessionId, "prompt", graph, "run-42").toList()

        coVerifyOrder {
            pipelineRunRepository.updateCurrentNode("run-42", "input_1")
            pipelineRunRepository.updateCurrentNode("run-42", "llm_1")
            pipelineRunRepository.updateCurrentNode("run-42", "output_1")
        }
    }

    @Test
    fun `given null runId then run persistence is never touched`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val graph = PipelineGraph(
            id = "g1",
            name = "Linear",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(ConnectionModel("c1", "input_1", "output_1")),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("answer")

        engine(sessionId, "prompt", graph).toList()

        coVerify(exactly = 0) { pipelineRunRepository.updateCurrentNode(any(), any()) }
        coVerify(exactly = 0) { pipelineRunRepository.updateStatus(any(), any()) }
    }

    /**
     * A CLARIFICATION suspension persists WAITING_CLARIFICATION when the
     * question is surfaced, and the record flips back to RUNNING on the first
     * state forwarded after the user's reply resolves the suspension.
     */
    @Test
    fun `given clarification suspension then record is WAITING_CLARIFICATION then RUNNING`() = runTest {
        val graph = PipelineGraph(
            id = "g1",
            name = "Clarification",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel(
                    id = "clar_1",
                    type = NodeType.CLARIFICATION,
                    x = 0f,
                    y = 0f,
                    systemPrompt = "Ask user for clarification.",
                    clarificationTimeoutMs = 5_000L,
                ),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "clar_1"),
                ConnectionModel("c2", "clar_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf(
            "{\"question\":\"Confirm?\",\"options\":[\"yes\",\"no\"]}",
        )
        coEvery { clarificationRepository.requestAnswer(any()) } returns ClarificationOutcome.Answered("user reply")

        engine(sessionId, "prompt", graph, "run-43").toList()

        coVerifyOrder {
            pipelineRunRepository.updateStatus("run-43", PipelineRunStatus.WAITING_CLARIFICATION)
            pipelineRunRepository.updateStatus("run-43", PipelineRunStatus.RUNNING)
        }
    }

    /**
     * A SENSITIVE tool's HITL gate persists WAITING_APPROVAL while the run
     * is suspended on the user, and the record flips back to RUNNING once
     * the approval resolves and execution proceeds.
     */
    @Test
    fun `given approval suspension then record is WAITING_APPROVAL then RUNNING`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(5_000L)
        coEvery { toolRepository.getRisk("sens.tool", any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool("sens.tool", "Desc", "{}"))
        coEvery { toolRepository.executeTool("sens.tool", any(), any()) } returns "tool-result"

        val graph = PipelineGraph(
            id = "g1",
            name = "Sensitive Tool",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("tool_1", NodeType.TOOL, 0f, 0f, toolName = "sens.tool"),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "tool_1"),
                ConnectionModel("c2", "tool_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool":"sens.tool","arguments":"a=1"}""")

        val job = launch {
            engine(sessionId, "prompt", graph, "run-44").toList()
        }
        // Advance past the per-node prewarm delays (but stay well below the
        // 5s approval timeout) so the TOOL executor registers its deferred
        // before the resume call — a resume issued earlier is silently
        // dropped and the gate would then time out and park the run.
        advanceTimeBy(2_000)
        runCurrent()
        engine.resumeWithApproval(sessionId, true)
        advanceUntilIdle()

        coVerifyOrder {
            pipelineRunRepository.updateStatus("run-44", PipelineRunStatus.WAITING_APPROVAL)
            pipelineRunRepository.updateStatus("run-44", PipelineRunStatus.RUNNING)
        }
        job.cancel()
    }

    /**
     * A sub-pipeline forwards its child's console traffic to the parent as
     * ordinary states, so `ConsoleLog` / `NodeIO` keep arriving while a HITL
     * gate is still open. They are observations *about* the run, not progress
     * of it, and must leave the WAITING_* status alone.
     *
     * Treating them as "the wait ended" left a nested run's root in RUNNING
     * while its child sat in WAITING_APPROVAL, and `ResumePipelineRunUseCase`
     * — which requires a resumable *root* — then rejected every answer to the
     * parked notification, stranding the run behind a permanent "generating…"
     * (reproduced on device).
     *
     * The state sequence is injected through the SKILL executor rather than by
     * standing up a real sub-pipeline: the defect lives in the engine's
     * state → run-status mapping, and this drives exactly the sequence a
     * PIPELINE node forwards.
     */
    @Test
    fun `given console traffic while a gate waits then the run keeps its WAITING_APPROVAL status`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { skillNodeExecutor.execute(any(), any(), any(), any(), any(), any()) } returns flowOf(
            NodeOutput.State(
                AgentOrchestratorState.WaitingForApproval("sens.tool", "a=1", ToolRisk.SENSITIVE),
            ),
            // The poison: a child console line arriving mid-wait.
            NodeOutput.State(
                AgentOrchestratorState.ConsoleLog(
                    events = listOf(
                        ConsoleEvent(
                            seq = 1,
                            type = ConsoleEventType.NodeExecution,
                            message = "child chatter",
                            timestamp = 0L,
                        ),
                    ),
                ),
            ),
            NodeOutput.State(
                AgentOrchestratorState.SuspendedInBackground(PendingInteractionKind.APPROVAL),
            ),
        )

        val graph = PipelineGraph(
            id = "g-park",
            name = "Parking",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("skill_1", NodeType.SKILL, 10f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "skill_1"),
                ConnectionModel("c2", "skill_1", "output_1"),
            ),
        )

        engine(sessionId, "prompt", graph, "run-park").toList()

        coVerify { pipelineRunRepository.updateStatus("run-park", PipelineRunStatus.WAITING_APPROVAL) }
        coVerify(exactly = 0) { pipelineRunRepository.updateStatus("run-park", PipelineRunStatus.RUNNING) }
    }

    /**
     * The engine is the one point every node's failure passes through on its way to
     * the run record, the console (and its *Copy all*), the persisted trace and the
     * surface. A provider error that quotes a credential — Google authenticates by
     * query parameter — must leave it scrubbed whichever executor produced it and
     * however it arrived: as a forwarded `Error` state, as the node result's error,
     * as a console line, or as an exception thrown out of the executor. The node is
     * a stubbed SKILL executor on purpose: the guarantee must not depend on the
     * executor remembering to scrub.
     */
    @Test
    fun `given a node reports a credential-bearing error then everything the engine emits is scrubbed`() = runTest {
        every { skillNodeExecutor.execute(any(), any(), any(), any(), any(), any()) } returns flowOf(
            NodeOutput.Console(ConsoleEventType.Error, "provider said: $LEAKING_PROVIDER_ERROR"),
            NodeOutput.State(AgentOrchestratorState.Error(LEAKING_PROVIDER_ERROR)),
            NodeOutput.Result(NodeExecutionResult(error = LEAKING_PROVIDER_ERROR)),
        )
        val appended = mutableListOf<RunTraceRecord>()
        coEvery { runTraceRepository.append(capture(appended)) } returns Unit

        val states = engine(sessionId, "prompt", singleSkillGraph(), "run-leak").toList()

        assertNothingCarriesTheKey(states, appended)
    }

    @Test
    fun `given a node throws a credential-bearing exception then everything the engine emits is scrubbed`() = runTest {
        every { skillNodeExecutor.execute(any(), any(), any(), any(), any(), any()) } returns flow {
            throw IllegalStateException(LEAKING_PROVIDER_ERROR)
        }
        val appended = mutableListOf<RunTraceRecord>()
        coEvery { runTraceRepository.append(capture(appended)) } returns Unit

        val states = engine(sessionId, "prompt", singleSkillGraph(), "run-leak").toList()

        assertNothingCarriesTheKey(states, appended)
    }

    private fun singleSkillGraph(): PipelineGraph = PipelineGraph(
        id = "g-leak",
        name = "Leak",
        nodes = listOf(
            NodeModel("input_1", NodeType.INPUT, 0f, 0f),
            NodeModel("skill_1", NodeType.SKILL, 10f, 0f),
            NodeModel("output_1", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
        ),
        connections = listOf(
            ConnectionModel("c1", "input_1", "skill_1"),
            ConnectionModel("c2", "skill_1", "output_1"),
        ),
    )

    private fun assertNothingCarriesTheKey(states: List<AgentOrchestratorState>, trace: List<RunTraceRecord>) {
        val errors = states.filterIsInstance<AgentOrchestratorState.Error>().map { it.message }
        val console = states.filterIsInstance<AgentOrchestratorState.ConsoleLog>()
            .flatMap { log -> log.events.map { it.message } }
        val traced = trace.filterIsInstance<RunTraceRecord.ConsoleEntry>().map { it.message }
        for (text in errors + console + traced) {
            assertFalse("key leaked: $text", text.contains(LEAKED_KEY))
        }
        assertTrue("the run must still fail: $states", errors.isNotEmpty())
        assertTrue("the failure must still reach the console: $console", console.any { it.contains("key=***") })
    }

    /**
     * The persistent run trace must be complete after a run: every console
     * event and every per-node I/O snapshot reaches the trace repository,
     * attributed to the run, with a strictly monotonic per-run seq shared
     * across both record kinds, and the buffer is force-flushed at the
     * terminal point.
     */
    @Test
    fun `given persisted run when pipeline completes then full trace lands in repository`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        val appended = mutableListOf<RunTraceRecord>()
        coEvery { runTraceRepository.append(capture(appended)) } returns Unit

        val graph = PipelineGraph(
            id = "g-trace",
            name = "Trace Completeness",
            nodes = listOf(
                NodeModel("input", NodeType.INPUT, 0f, 0f),
                NodeModel("llm", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input", "llm"),
                ConnectionModel("c2", "llm", "output"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returnsMany listOf(
            flowOf("answer"),
            flowOf("final"),
        )

        val states = engine(sessionId, "prompt", graph, runId = "run-full").toList()

        // Every console event emitted to the UI also landed in the trace.
        val emittedConsoleCount =
            states.filterIsInstance<AgentOrchestratorState.ConsoleLog>().maxOf { it.events.size }
        val persistedConsole = appended.filterIsInstance<RunTraceRecord.ConsoleEntry>()
        assertEquals(emittedConsoleCount, persistedConsole.size)
        assertTrue(persistedConsole.any { it.message == "▶ LITE_RT" })
        // The LITE_RT node's I/O snapshot landed with its full input/output pair.
        val nodeIo = appended.filterIsInstance<RunTraceRecord.NodeIo>().single { it.nodeId == "llm" }
        assertEquals("LITE_RT", nodeIo.nodeType)
        assertEquals("answer", nodeIo.outputText)
        assertTrue(nodeIo.inputText.isNotBlank())
        // Run attribution and strictly monotonic seq across both record kinds.
        assertTrue(appended.all { it.runId == "run-full" && it.sessionId == sessionId })
        val seqs = appended.map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.size, seqs.distinct().size)
        // Terminal flush makes the trace durable the moment the run ends.
        coVerify(atLeast = 1) { runTraceRepository.flush() }
    }

    /**
     * `runId = null` (editor test runs, legacy flows) disables trace
     * persistence entirely — no appends, no flushes.
     */
    @Test
    fun `given no runId when pipeline completes then no trace records are appended`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)

        val graph = PipelineGraph(
            id = "g-no-run",
            name = "No Run Id",
            nodes = listOf(
                NodeModel("input", NodeType.INPUT, 0f, 0f),
                NodeModel("output", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(ConnectionModel("c1", "input", "output")),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("final")

        engine(sessionId, "prompt", graph).toList()

        coVerify(exactly = 0) { runTraceRepository.append(any()) }
        coVerify(exactly = 0) { runTraceRepository.flush() }
    }

    /**
     * Entering a HITL suspension force-flushes the buffered trace right
     * after the WAITING_APPROVAL status write — the process may die while
     * waiting, so the persisted trace must already cover everything up to
     * the suspension point.
     */
    @Test
    fun `given approval suspension then trace is flushed at the suspension point`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(5_000L)
        coEvery { toolRepository.getRisk("sens.tool", any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool("sens.tool", "Desc", "{}"))
        coEvery { toolRepository.executeTool("sens.tool", any(), any()) } returns "tool-result"

        val graph = PipelineGraph(
            id = "g-susp-flush",
            name = "Suspension Flush",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("tool_1", NodeType.TOOL, 0f, 0f, toolName = "sens.tool"),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "tool_1"),
                ConnectionModel("c2", "tool_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool":"sens.tool","arguments":"a=1"}""")

        val job = launch {
            engine(sessionId, "prompt", graph, "run-45").toList()
        }
        // Advance past the per-node prewarm delays (but stay well below the
        // 5s approval timeout) so the TOOL executor registers its deferred
        // before the resume call — a resume issued earlier is silently
        // dropped and the gate would then time out and park the run.
        advanceTimeBy(2_000)
        runCurrent()
        engine.resumeWithApproval(sessionId, true)
        advanceUntilIdle()

        // The suspension flush must land between the WAITING_APPROVAL write
        // and the RUNNING flip — the terminal flush (after RUNNING) cannot
        // satisfy this order, so the assertion pins the suspension-point
        // flush specifically.
        coVerifyOrder {
            pipelineRunRepository.updateStatus("run-45", PipelineRunStatus.WAITING_APPROVAL)
            runTraceRepository.flush()
            pipelineRunRepository.updateStatus("run-45", PipelineRunStatus.RUNNING)
        }
        job.cancel()
    }

    /**
     * A tool call is the one node whose re-execution is not free — it acted on
     * the world — so its trace record must be durable the moment it lands, not
     * whenever the write buffer next drains.
     *
     * Found on the reference device: the process killed
     * 112 ms after a tool returned lost the buffered record, and the resumed
     * run invoked the same tool a second time — visible as a second
     * `tools/call` on the wire. Killed 1.2 s after (past the buffer's 500 ms
     * timer) the same run replayed the record as designed.
     */
    @Test
    fun `given a completed tool node then its trace record is flushed immediately`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.NeverPrompt)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(5_000L)
        coEvery { toolRepository.getRisk("safe.tool", any()) } returns ToolRisk.READ_ONLY
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool("safe.tool", "Desc", "{}"))
        coEvery { toolRepository.executeTool("safe.tool", any(), any()) } returns "tool-result"

        // Call order, not merely call presence: the terminal flush would satisfy
        // a plain "flush was called" assertion even without the fix.
        val calls = mutableListOf<String>()
        coEvery { runTraceRepository.append(any()) } answers {
            val record = firstArg<RunTraceRecord>()
            calls += if (record is RunTraceRecord.NodeIo) "append:${record.nodeId}" else "append:console"
        }
        coEvery { runTraceRepository.flush() } answers { calls += "flush" }

        val graph = PipelineGraph(
            id = "g-tool-flush",
            name = "Tool Flush",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("tool_1", NodeType.TOOL, 0f, 0f, toolName = "safe.tool"),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "tool_1"),
                ConnectionModel("c2", "tool_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool":"safe.tool","arguments":"a=1"}""")

        engine(sessionId, "prompt", graph, "run-tool-flush").toList()

        val recordIndex = calls.indexOf("append:tool_1")
        assertTrue("the tool node's I/O record never reached the trace: $calls", recordIndex >= 0)
        assertEquals(
            "the tool record must be flushed before anything else is written: $calls",
            "flush",
            calls.getOrNull(recordIndex + 1),
        )
    }

    /**
     * The same durability rule for a CLOUD node, for a different reason: repeating a
     * billed API call costs money and rate-limit budget, so its record must survive a
     * process death in the write buffer's 500 ms window exactly as a tool's does.
     *
     * The original rule covered only TOOL, on the reasoning that a repeat elsewhere is
     * "lost time, not a side effect" — true for on-device nodes, false for a paid one.
     */
    @Test
    fun `given a completed cloud node then its trace record is flushed immediately`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        // A cloud node that cannot reach a provider now fails before writing its I/O
        // record, so the call has to actually succeed for this test to be about
        // durability rather than about the failure path. The End frame carries a finish
        // reason because a stream without one is treated as truncated.
        val cloudClient: LLMClient = mockk(relaxed = true)
        coEvery { cloudClient.executeStreaming(any(), any<LLModel>()) } returns flowOf(
            StreamFrame.TextDelta("cloud answer"),
            StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty),
        )
        coEvery { koogClientFactory.createDeepSeekExecutor() } returns cloudClient

        val calls = mutableListOf<String>()
        coEvery { runTraceRepository.append(any()) } answers {
            val record = firstArg<RunTraceRecord>()
            calls += if (record is RunTraceRecord.NodeIo) "append:${record.nodeId}" else "append:console"
        }
        coEvery { runTraceRepository.flush() } answers { calls += "flush" }

        val graph = PipelineGraph(
            id = "g-cloud-flush",
            name = "Cloud Flush",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("cloud_1", NodeType.CLOUD, 0f, 0f, cloudProvider = "deepseek"),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "cloud_1"),
                ConnectionModel("c2", "cloud_1", "output_1"),
            ),
        )

        engine(sessionId, "prompt", graph, "run-cloud-flush").toList()

        val recordIndex = calls.indexOf("append:cloud_1")
        assertTrue("the cloud node's I/O record never reached the trace: $calls", recordIndex >= 0)
        assertEquals(
            "the cloud record must be flushed before anything else is written: $calls",
            "flush",
            calls.getOrNull(recordIndex + 1),
        )
    }

    // ─── Checkpoint resume ──────────────────────────────────────────────────

    /** Recorded NodeIo snapshot shorthand for the resume tests. */
    private fun nodeIoRecord(
        runId: String,
        seq: Long,
        nodeId: String,
        nodeType: NodeType,
        outputText: String,
        conditionResult: Boolean? = null,
        routingKey: String? = null,
        resolvedToolName: String? = null,
    ): RunTraceRecord.NodeIo = RunTraceRecord.NodeIo(
        runId = runId,
        sessionId = sessionId,
        seq = seq,
        timestamp = 0L,
        nodeId = nodeId,
        nodeType = nodeType.name,
        inputText = "recorded-input",
        outputText = outputText,
        durationMs = 5L,
        tokenCount = null,
        conditionResult = conditionResult,
        routingKey = routingKey,
        resolvedToolName = resolvedToolName,
    )

    @Test
    fun `given resume with full prefix then completed nodes replay without executor calls`() = runTest {
        val graph = PipelineGraph(
            id = "g-resume",
            name = "Resume",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )
        val resume = ResumeContext(
            records = listOf(nodeIoRecord("run-r1", 3L, "llm_1", NodeType.LITE_RT, "Recorded")),
            memorySnapshot = null,
            nextSeq = 4L,
        )

        val states = engine(sessionId, "prompt", graph, "run-r1", resume).toList()

        // The LITE_RT node replays from the checkpoint: zero LLM calls, the
        // recorded output reaches OUTPUT (echo mode), and the compact replay
        // event lands in the console.
        verify(exactly = 0) { llmEngine.generateResponseStream(any()) }
        val completed = states.last() as AgentOrchestratorState.Completed
        assertEquals("Recorded", completed.finalResponse)
        val consoleLines = states.filterIsInstance<AgentOrchestratorState.ConsoleLog>()
            .last().events.map { it.message }
        assertTrue(consoleLines.any { it.contains("replayed from checkpoint") })
        // The replayed node appends no second NodeIo record.
        coVerify(exactly = 0) {
            runTraceRepository.append(match { it is RunTraceRecord.NodeIo && it.nodeId == "llm_1" })
        }
    }

    @Test
    fun `given resume with partial prefix then first unrecorded node executes live with continued seq`() = runTest {
        val graph = PipelineGraph(
            id = "g-resume-partial",
            name = "Resume Partial",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
                NodeModel("llm_2", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "llm_2"),
                ConnectionModel("c3", "llm_2", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Live")
        val resume = ResumeContext(
            records = listOf(nodeIoRecord("run-r2", 2L, "llm_1", NodeType.LITE_RT, "Recorded")),
            memorySnapshot = null,
            nextSeq = 3L,
        )

        val states = engine(sessionId, "prompt", graph, "run-r2", resume).toList()

        // Exactly one live LLM call (llm_2); llm_1 replayed.
        verify(exactly = 1) { llmEngine.generateResponseStream(any()) }
        assertEquals("Live", (states.last() as AgentOrchestratorState.Completed).finalResponse)
        // The live node's NodeIo continues the persisted seq numbering.
        val appended = mutableListOf<RunTraceRecord>()
        coVerify { runTraceRepository.append(capture(appended)) }
        val liveNodeIo = appended.filterIsInstance<RunTraceRecord.NodeIo>().single { it.nodeId == "llm_2" }
        assertTrue("live record seq must continue after nextSeq", liveNodeIo.seq >= 3L)
    }

    @Test
    fun `given resume with recorded IF_CONDITION verdict then branch is restored without re-evaluation`() = runTest {
        val graph = PipelineGraph(
            id = "g-resume-if",
            name = "Resume If",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("if_1", NodeType.IF_CONDITION, 0f, 0f),
                NodeModel("llm_true", NodeType.LITE_RT, 0f, 0f),
                NodeModel("llm_false", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "if_1"),
                ConnectionModel("c2", "if_1", "llm_true", label = "True"),
                ConnectionModel("c3", "if_1", "llm_false", label = "False"),
                ConnectionModel("c4", "llm_true", "output_1"),
                ConnectionModel("c5", "llm_false", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("FalseBranch")
        val resume = ResumeContext(
            records = listOf(
                nodeIoRecord(
                    runId = "run-r3",
                    seq = 1L,
                    nodeId = "if_1",
                    nodeType = NodeType.IF_CONDITION,
                    outputText = "prompt",
                    conditionResult = false,
                ),
            ),
            memorySnapshot = null,
            nextSeq = 2L,
        )

        val states = engine(sessionId, "prompt", graph, "run-r3", resume).toList()

        // The condition is never re-evaluated (the strict mock would throw),
        // and the recorded False verdict routes into llm_false.
        coVerify(exactly = 0) { evaluateIfConditionUseCase(any(), any(), any(), any()) }
        assertEquals("FalseBranch", (states.last() as AgentOrchestratorState.Completed).finalResponse)
    }

    @Test
    fun `given resume with completed TOOL record then observation replays without HITL`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        val graph = PipelineGraph(
            id = "g-resume-tool-done",
            name = "Resume Tool Done",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("tool_1", NodeType.TOOL, 0f, 0f, toolName = "sens.tool"),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "tool_1"),
                ConnectionModel("c2", "tool_1", "output_1"),
            ),
        )
        val resume = ResumeContext(
            records = listOf(
                nodeIoRecord(
                    runId = "run-r4",
                    seq = 1L,
                    nodeId = "tool_1",
                    nodeType = NodeType.TOOL,
                    outputText = "tool-observation",
                    resolvedToolName = "sens.tool",
                ),
            ),
            memorySnapshot = null,
            nextSeq = 2L,
        )

        val states = engine(sessionId, "prompt", graph, "run-r4", resume).toList()

        // Completed before the interruption → replayed: no approval gate, no
        // tool execution, the recorded observation flows to OUTPUT.
        assertTrue(states.filterIsInstance<AgentOrchestratorState.WaitingForApproval>().isEmpty())
        coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
        assertEquals("tool-observation", (states.last() as AgentOrchestratorState.Completed).finalResponse)
    }

    @Test
    fun `given resume stopping at TOOL node then tool executes live with fresh HITL`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(5_000L)
        coEvery { toolRepository.getRisk("sens.tool", any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool("sens.tool", "Desc", "{}"))
        coEvery { toolRepository.executeTool("sens.tool", any(), any()) } returns "fresh-result"

        val graph = PipelineGraph(
            id = "g-resume-tool-live",
            name = "Resume Tool Live",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
                NodeModel("tool_1", NodeType.TOOL, 0f, 0f, toolName = "sens.tool"),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "tool_1"),
                ConnectionModel("c3", "tool_1", "output_1"),
            ),
        )
        // The TOOL executor's planning step goes through the LLM; the replayed
        // llm_1 never calls it, so the single stream stub serves tool planning.
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool":"sens.tool","arguments":"a=1"}""")
        val resume = ResumeContext(
            records = listOf(
                nodeIoRecord("run-r5", 1L, "llm_1", NodeType.LITE_RT, """{"tool":"sens.tool","arguments":"a=1"}"""),
            ),
            memorySnapshot = null,
            nextSeq = 2L,
        )

        val states = mutableListOf<AgentOrchestratorState>()
        val job = launch {
            engine(sessionId, "prompt", graph, "run-r5", resume).toList(states)
        }
        // Walk past the per-node prewarm delays (INPUT + TOOL, 500 ms each;
        // the replayed llm_1 skips its delay) without reaching the 5 s
        // approval timeout, so the gate is raised but still pending.
        advanceTimeBy(1_500L)
        runCurrent()
        // Interrupted at the TOOL node → never replayed: a fresh approval
        // gate must be raised even though the run is a resume.
        assertTrue(states.filterIsInstance<AgentOrchestratorState.WaitingForApproval>().isNotEmpty())
        engine.resumeWithApproval(sessionId, true)
        advanceUntilIdle()

        coVerify(exactly = 1) { toolRepository.executeTool("sens.tool", any(), any()) }
        assertEquals("fresh-result", (states.last() as AgentOrchestratorState.Completed).finalResponse)
        job.cancel()
    }

    @Test
    fun `given resume whose trace diverges from the graph walk then run fails with explicit error`() = runTest {
        val graph = PipelineGraph(
            id = "g-resume-diverged",
            name = "Resume Diverged",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )
        val resume = ResumeContext(
            records = listOf(nodeIoRecord("run-r6", 1L, "some_other_node", NodeType.LITE_RT, "stale")),
            memorySnapshot = null,
            nextSeq = 2L,
        )

        val states = engine(sessionId, "prompt", graph, "run-r6", resume).toList()

        val error = states.last() as AgentOrchestratorState.Error
        assertTrue(error.message.contains("no longer matches"))
        verify(exactly = 0) { llmEngine.generateResponseStream(any()) }
    }

    @Test
    fun `given resume with memory snapshot then retrieval is not re-run and the block is rebuilt`() = runTest {
        val graph = PipelineGraph(
            id = "g-resume-mem",
            name = "Resume Memory",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel(
                    id = "llm_1",
                    type = NodeType.LITE_RT,
                    x = 0f,
                    y = 0f,
                    contextConfig = NodeContextConfig(
                        chatHistory = false,
                        originalTask = false,
                        nodeInput = false,
                        longTermMemory = true,
                        toolResults = false,
                    ),
                ),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")
        val resume = ResumeContext(
            records = emptyList(),
            memorySnapshot = listOf(
                MemoryChunk(id = 7L, text = "user prefers dark mode", embedding = FloatArray(0), timestamp = 0L),
            ),
            nextSeq = 1L,
        )

        engine(sessionId, "prompt", graph, "run-r7", resume).toList()

        // The snapshot seeds the memoized list: no fresh retrieval, no usage
        // re-count, and the snapshot chunk reaches the LLM prompt.
        coVerify(exactly = 0) { retrieveRelevantMemoryUseCase.retrieveScored(any()) }
        coVerify(exactly = 0) { memoryRepository.recordUsage(any(), any()) }
        verify {
            llmEngine.generateResponseStream(
                match { it.contains("--- Long-Term Memory ---") && it.contains("user prefers dark mode") },
            )
        }
    }

    @Test
    fun `given fresh memory resolution with runId then memory snapshot record lands in the trace`() = runTest {
        coEvery { retrieveRelevantMemoryUseCase.retrieveScored(any()) } returns listOf(
            MemoryChunk(id = 7L, text = "user prefers dark mode", embedding = FloatArray(0), timestamp = 0L) to 0.8f,
        )
        val graph = PipelineGraph(
            id = "g-mem-snap",
            name = "Memory Snapshot",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel(
                    id = "llm_1",
                    type = NodeType.LITE_RT,
                    x = 0f,
                    y = 0f,
                    contextConfig = NodeContextConfig(
                        chatHistory = false,
                        originalTask = false,
                        nodeInput = false,
                        longTermMemory = true,
                        toolResults = false,
                    ),
                ),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "output_1"),
            ),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")

        engine(sessionId, "prompt", graph, "run-snap").toList()

        coVerify {
            runTraceRepository.append(
                match {
                    it is RunTraceRecord.MemorySnapshot &&
                        it.runId == "run-snap" &&
                        it.entries.single().text == "user prefers dark mode"
                },
            )
        }
    }

    // region Long-term-memory retrieval key (RunOrigin contract, DESCRIPTION.md 6.10.1)
    //
    // An interactive run keys retrieval off the user's message, as it always
    // has. A background run's prompt is authored once and describes no
    // particular firing, so it prefers the pipeline's declared query and then
    // the first memory-aware node's own input.

    /** Graph shape: INPUT -> LITE_RT(memory) -> OUTPUT, optionally declaring a retrieval query. */
    private fun memoryAwareGraph(id: String, declaredQuery: String? = null): PipelineGraph = PipelineGraph(
        id = id,
        name = "Memory Key",
        nodes = listOf(
            NodeModel("input_1", NodeType.INPUT, 0f, 0f),
            NodeModel(
                id = "llm_1",
                type = NodeType.LITE_RT,
                x = 10f,
                y = 0f,
                contextConfig = NodeContextConfig(
                    chatHistory = false,
                    originalTask = false,
                    nodeInput = false,
                    longTermMemory = true,
                    toolResults = false,
                ),
            ),
            NodeModel("output_1", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
        ),
        connections = listOf(
            ConnectionModel("c1", "input_1", "llm_1"),
            ConnectionModel("c2", "llm_1", "output_1"),
        ),
        memoryRetrievalQuery = declaredQuery,
    )

    @Test
    fun `given an interactive run when memory is resolved then it keys off the user prompt`() = runTest {
        // Regression guard: the interactive path must be byte-for-byte what it
        // was before the origin-aware key existed, declared query or not.
        every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")
        val graph = memoryAwareGraph("g-interactive", declaredQuery = "journal entries")

        engine(sessionId, "what did I say about Berlin?", graph).toList()

        coVerify(exactly = 1) { retrieveRelevantMemoryUseCase.retrieveScored("what did I say about Berlin?") }
    }

    @Test
    fun `given a trigger run with a declared query when memory is resolved then it keys off the declaration`() =
        runTest {
            every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")
            val graph = memoryAwareGraph("g-trigger", declaredQuery = "evening journal entries, mood")

            engine(
                sessionId,
                "write the evening journal entry",
                graph,
                origin = RunOrigin.TRIGGER,
            ).toList()

            coVerify(exactly = 1) {
                retrieveRelevantMemoryUseCase.retrieveScored("evening journal entries, mood")
            }
            coVerify(exactly = 0) {
                retrieveRelevantMemoryUseCase.retrieveScored("write the evening journal entry")
            }
        }

    @Test
    fun `given a trigger run without a declared query when memory is resolved then it keys off the node input`() =
        runTest {
            // The memory-aware node sits behind another LITE_RT, so its input is
            // that node's output — richer than the generic authored prompt.
            every { llmEngine.generateResponseStream(any()) } returns flowOf("today: shipped the journal, ran 8 km")
            val graph = PipelineGraph(
                id = "g-node-input",
                name = "Node Input Key",
                nodes = listOf(
                    NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                    // Explicitly memory-free: NodeModel defaults to ALL_ENABLED,
                    // which would make *this* node the first memory-aware one.
                    NodeModel(
                        id = "llm_a",
                        type = NodeType.LITE_RT,
                        x = 10f,
                        y = 0f,
                        contextConfig = NodeContextConfig(
                            chatHistory = false,
                            originalTask = false,
                            nodeInput = true,
                            longTermMemory = false,
                            toolResults = false,
                        ),
                    ),
                    NodeModel(
                        id = "llm_b",
                        type = NodeType.LITE_RT,
                        x = 20f,
                        y = 0f,
                        contextConfig = NodeContextConfig(
                            chatHistory = false,
                            originalTask = false,
                            nodeInput = false,
                            longTermMemory = true,
                            toolResults = false,
                        ),
                    ),
                    NodeModel("output_1", NodeType.OUTPUT, 30f, 0f, systemPrompt = null),
                ),
                connections = listOf(
                    ConnectionModel("c1", "input_1", "llm_a"),
                    ConnectionModel("c2", "llm_a", "llm_b"),
                    ConnectionModel("c3", "llm_b", "output_1"),
                ),
            )

            engine(sessionId, "write the evening journal entry", graph, origin = RunOrigin.TRIGGER).toList()

            coVerify(exactly = 1) {
                retrieveRelevantMemoryUseCase.retrieveScored("today: shipped the journal, ran 8 km")
            }
        }

    @Test
    fun `given a trigger run with two memory-aware nodes when executed then retrieval still runs exactly once`() =
        runTest {
            // The cost guarantee: the origin-aware key is resolved inside the
            // existing memoization, so a run never pays for a second embedding.
            every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")
            val memoryContext = NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = false,
                longTermMemory = true,
                toolResults = false,
            )
            val graph = PipelineGraph(
                id = "g-once",
                name = "Once",
                nodes = listOf(
                    NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                    NodeModel("llm_a", NodeType.LITE_RT, 10f, 0f, contextConfig = memoryContext),
                    NodeModel("llm_b", NodeType.LITE_RT, 20f, 0f, contextConfig = memoryContext),
                    NodeModel("output_1", NodeType.OUTPUT, 30f, 0f, systemPrompt = null),
                ),
                connections = listOf(
                    ConnectionModel("c1", "input_1", "llm_a"),
                    ConnectionModel("c2", "llm_a", "llm_b"),
                    ConnectionModel("c3", "llm_b", "output_1"),
                ),
                memoryRetrievalQuery = "evening journal entries",
            )

            engine(sessionId, "write the entry", graph, origin = RunOrigin.TRIGGER).toList()

            coVerify(exactly = 1) { retrieveRelevantMemoryUseCase.retrieveScored(any()) }
        }

    @Test
    fun `given a declared query with a placeholder when a trigger run resolves memory then it is rendered`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")
        val dateProvider = mockk<PromptVariableProvider>()
        every { dateProvider.key() } returns "DATE"
        coEvery { dateProvider.resolve() } returns "01 May 2026"
        // Same collaborators, only the provider set differs — the declared
        // query is a prompt template like any other.
        val engineWithProviders = GraphExecutionEngine(
            nodeExecutorFactory,
            toolNodeExecutor,
            chatRepository,
            settingsRepository,
            metricsRepository,
            PromptTemplateEngine(),
            setOf(dateProvider),
            NodeContextBuilder(),
            ChatHistoryWindowPlanner(),
            retrieveRelevantMemoryUseCase,
            crashReportingRepository,
            localModelRepository,
            memoryRepository,
            pipelineRunRepository,
            runTraceRepository,
            ResolveRunCeilingsUseCase(settingsRepository),
            pendingInteractionRepository,
            ceilingNotifier,
        )
        val graph = memoryAwareGraph("g-placeholder", declaredQuery = "journal entries around \$DATE")

        engineWithProviders(sessionId, "write the entry", graph, origin = RunOrigin.TRIGGER).toList()

        coVerify(exactly = 1) {
            retrieveRelevantMemoryUseCase.retrieveScored("journal entries around 01 May 2026")
        }
    }

    @Test
    fun `given a trigger run with a sub-pipeline when the nested node resolves memory then origin is inherited`() =
        runTest {
            every { llmEngine.generateResponseStream(any()) } returns flowOf("ok")
            // The memory-aware node and the declared query both live in the child.
            val subGraph = memoryAwareGraph("sub-memory", declaredQuery = "nested declared query")
            coEvery { pipelineRepository.getPipelineById("sub-memory") } returns subGraph
            val mainGraph = PipelineGraph(
                id = "main-memory",
                name = "MainMemory",
                nodes = listOf(
                    NodeModel("main_in", NodeType.INPUT, 0f, 0f),
                    NodeModel("pipe", NodeType.PIPELINE, 10f, 0f, targetPipelineId = "sub-memory"),
                    NodeModel("main_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
                ),
                connections = listOf(
                    ConnectionModel("mc1", "main_in", "pipe"),
                    ConnectionModel("mc2", "pipe", "main_out"),
                ),
            )

            engine(sessionId, "write the entry", mainGraph, origin = RunOrigin.TRIGGER).toList()

            // Had the sub-run defaulted to CHAT, it would have keyed off the prompt.
            coVerify(exactly = 1) { retrieveRelevantMemoryUseCase.retrieveScored("nested declared query") }
        }

    // endregion

    // region Multimodal image delivery (end-to-end pipeline contract)
    //
    // These tests assert the *orchestration* contract that the unit-level
    // `LiteRtNodeExecutorTest` cannot: that the engine resolves the run's single
    // image to the FIRST vision-eligible `LITE_RT` node, marks the shared
    // delivery consumed so no later node (and no `CLOUD` node) sees it, and
    // threads the delivery into nested sub-pipeline runs. The on-device LLM is
    // mocked, so this is the task's "contract variant with a fake engine
    // verifying image delivery" — no real multimodal model is required.

    @Test
    fun `given an image run when the first LITE_RT vision node executes then it receives the image path`() = runTest {
        val imagePaths = mutableListOf<String?>()
        coEvery { loadModelUseCase(any(), any()) } returns Result.Success(Unit)
        every {
            llmEngine.generateResponseStream(any(), captureNullable(imagePaths), any())
        } returns flowOf("vision answer")
        val graph = PipelineGraph(
            id = "vision-pipe",
            name = "Vision",
            nodes = listOf(
                NodeModel("in", NodeType.INPUT, 0f, 0f),
                NodeModel("llm", NodeType.LITE_RT, 10f, 0f),
                NodeModel("out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "in", "llm"),
                ConnectionModel("c2", "llm", "out"),
            ),
        )
        val image = EngineImageInput(absolutePath = "/abs/photo.jpg", width = 800, height = 600, sizeBytes = 12_345L)

        val states = engine(sessionId, "what is in this photo", graph, imageInput = image).toList()

        // The run completes — vision tokens / the image do not break the graph.
        assertTrue("Expected Completed, got ${states.last()}", states.last() is AgentOrchestratorState.Completed)
        // The sole vision-eligible node received the absolute image path.
        assertEquals(listOf("/abs/photo.jpg"), imagePaths)
    }

    @Test
    fun `given an image run with two LITE_RT nodes when executed then only the first receives the image`() = runTest {
        val imagePaths = mutableListOf<String?>()
        coEvery { loadModelUseCase(any(), any()) } returns Result.Success(Unit)
        every {
            llmEngine.generateResponseStream(any(), captureNullable(imagePaths), any())
        } returns flowOf("ok")
        val graph = PipelineGraph(
            id = "two-vision-pipe",
            name = "TwoVision",
            nodes = listOf(
                NodeModel("in", NodeType.INPUT, 0f, 0f),
                NodeModel("llm1", NodeType.LITE_RT, 10f, 0f),
                NodeModel("llm2", NodeType.LITE_RT, 20f, 0f),
                NodeModel("out", NodeType.OUTPUT, 30f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "in", "llm1"),
                ConnectionModel("c2", "llm1", "llm2"),
                ConnectionModel("c3", "llm2", "out"),
            ),
        )
        val image = EngineImageInput(absolutePath = "/abs/photo.jpg", width = 640, height = 480, sizeBytes = 9_000L)

        engine(sessionId, "describe", graph, imageInput = image).toList()

        // Consumed exactly once: the first LITE_RT node takes the image, the
        // second sees text only.
        assertEquals(listOf("/abs/photo.jpg", null), imagePaths)
    }

    @Test
    fun `given a text run with no image when a LITE_RT node executes then the engine receives a null image path`() =
        runTest {
            val imagePaths = mutableListOf<String?>()
            every {
                llmEngine.generateResponseStream(any(), captureNullable(imagePaths), any())
            } returns flowOf("ok")
            val graph = PipelineGraph(
                id = "text-pipe",
                name = "Text",
                nodes = listOf(
                    NodeModel("in", NodeType.INPUT, 0f, 0f),
                    NodeModel("llm", NodeType.LITE_RT, 10f, 0f),
                    NodeModel("out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
                ),
                connections = listOf(
                    ConnectionModel("c1", "in", "llm"),
                    ConnectionModel("c2", "llm", "out"),
                ),
            )

            // No imageInput → the established text-only behaviour is unchanged.
            val states = engine(sessionId, "plain question", graph).toList()

            assertTrue(states.last() is AgentOrchestratorState.Completed)
            assertEquals(listOf<String?>(null), imagePaths)
        }

    @Test
    fun `given a nested sub-pipeline vision sink when image run then the sub node receives the image`() = runTest {
        val imagePaths = mutableListOf<String?>()
        coEvery { loadModelUseCase(any(), any()) } returns Result.Success(Unit)
        every {
            llmEngine.generateResponseStream(any(), captureNullable(imagePaths), any())
        } returns flowOf("ok")
        // Sub-pipeline: INPUT -> LITE_RT (the vision sink) -> OUTPUT (echo).
        val subGraph = PipelineGraph(
            id = "sub-vision",
            name = "SubVision",
            nodes = listOf(
                NodeModel("sub_in", NodeType.INPUT, 0f, 0f),
                NodeModel("sub_llm", NodeType.LITE_RT, 10f, 0f),
                NodeModel("sub_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("sc1", "sub_in", "sub_llm"),
                ConnectionModel("sc2", "sub_llm", "sub_out"),
            ),
        )
        coEvery { pipelineRepository.getPipelineById("sub-vision") } returns subGraph
        // Main pipeline has no LITE_RT node of its own — only the PIPELINE node.
        val mainGraph = PipelineGraph(
            id = "main-vision",
            name = "MainVision",
            nodes = listOf(
                NodeModel("main_in", NodeType.INPUT, 0f, 0f),
                NodeModel("pipe", NodeType.PIPELINE, 10f, 0f, targetPipelineId = "sub-vision"),
                NodeModel("main_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("mc1", "main_in", "pipe"),
                ConnectionModel("mc2", "pipe", "main_out"),
            ),
        )
        val image =
            EngineImageInput(absolutePath = "/abs/nested.jpg", width = 512, height = 512, sizeBytes = 7_000L)

        engine(sessionId, "look inside", mainGraph, imageInput = image).toList()

        // The shared delivery is threaded into the sub-run, so the nested
        // vision sink — not any main-pipeline node — consumes the image.
        assertEquals(listOf("/abs/nested.jpg"), imagePaths)
    }
    // endregion

    // region Autonomous-run ceilings

    /**
     * Two-node graph used by the ceiling tests: INPUT -> LITE_RT -> OUTPUT.
     */
    private fun ceilingGraph() = PipelineGraph(
        id = "g-ceiling",
        name = "Ceiling",
        nodes = listOf(
            NodeModel("input_1", NodeType.INPUT, 0f, 0f),
            NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
            NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
        ),
        connections = listOf(
            ConnectionModel("c1", "input_1", "llm_1"),
            ConnectionModel("c2", "llm_1", "output_1"),
        ),
    )

    @Test
    fun `given the step ceiling binds then the Error carries a typed StepCeiling reason`() = runTest {
        // Before the typed reason existed the only way to tell a protective stop
        // from a defect was to read the message text, which is what two
        // consumers had resorted to.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(2)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", ceilingGraph()).toList()

        val error = states.last() as AgentOrchestratorState.Error
        assertEquals(RunTerminationReason.StepCeiling(limit = 2, spent = 2), error.reason)
    }

    @Test
    fun `given the token ceiling binds then the Error carries a typed TokenCeiling reason`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { settingsRepository.runMaxTokens } returns flowOf(10)
        // The LITE_RT node meters one "token" per stream chunk, so this run
        // charges 4 against a ceiling of 10 on its first node — under the hard
        // limit, and over it after the second.
        every { llmEngine.generateResponseStream(any()) } returns flowOf("a", "b", "c", "d")

        val graph = PipelineGraph(
            id = "g-tokens",
            name = "Tokens",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
                NodeModel("llm_2", NodeType.LITE_RT, 0f, 0f),
                NodeModel("llm_3", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "llm_2"),
                ConnectionModel("c3", "llm_2", "llm_3"),
                ConnectionModel("c4", "llm_3", "output_1"),
            ),
        )

        val states = engine(sessionId, "prompt", graph).toList()

        val error = states.last() as AgentOrchestratorState.Error
        assertTrue("expected a token ceiling, got ${error.reason}", error.reason is RunTerminationReason.TokenCeiling)
    }

    @Test
    fun `given the soft threshold is crossed then the run is warned once and keeps going`() = runTest {
        // A soft limit must not stop the run — it warns and keeps going.
        // softFor(3) == 2, and the graph walks INPUT -> LITE_RT -> OUTPUT, so
        // the crossing is announced on the LITE_RT node. Delivery of the note
        // itself is covered separately: this OUTPUT is a pass-through, which
        // composes no prompt, so nothing is handed to it.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(3)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", ceilingGraph()).toList()

        assertTrue("the run must still complete", states.last() is AgentOrchestratorState.Completed)
        val ceilingLines = states.filterIsInstance<AgentOrchestratorState.ConsoleLog>()
            .last().events
            .filter { it.type == ConsoleEventType.RunCeiling }
        // Once — not once per remaining node, which would teach the reader to
        // ignore it.
        assertEquals(1, ceilingLines.size)
        assertTrue(ceilingLines.single().message.contains("steps"))

        // The console line is a diagnostic; the sentence the user reads is
        // resolved from this typed notice instead. Asserted positively — a test
        // suite that only ever checks the notice is *absent* is satisfied by
        // never emitting one at all.
        val notices = states.filterIsInstance<AgentOrchestratorState.RunNotice>()
        assertEquals(1, notices.size)
        assertEquals(
            RunNoticeCause.ApproachingCeiling(axis = RunCeilingAxis.STEPS, spent = 2, hardLimit = 3),
            notices.single().cause,
        )
    }

    @Test
    fun `given the soft threshold is crossed then the next prompt-composing node is warned`() = runTest {
        // The positive half of the contract. Both leak tests assert the notice is
        // *absent* from the answer, and "never produced" satisfies that perfectly
        // — so without this, deleting the injection outright would leave the
        // suite green.
        //
        // hard = 6 gives softFor(6) = 4, and the six nodes charge 1..6, so the
        // crossing is claimed on node C and the note is handed to D, the next
        // node that composes a prompt.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(6)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("answer")

        val graph = PipelineGraph(
            id = "g-soft-delivery",
            name = "SoftDelivery",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("a", NodeType.LITE_RT, 0f, 0f),
                NodeModel("b", NodeType.LITE_RT, 0f, 0f),
                NodeModel("c", NodeType.LITE_RT, 0f, 0f),
                NodeModel("d", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "a"),
                ConnectionModel("c2", "a", "b"),
                ConnectionModel("c3", "b", "c"),
                ConnectionModel("c4", "c", "d"),
                ConnectionModel("c5", "d", "output_1"),
            ),
        )

        val states = engine(sessionId, "prompt", graph).toList()

        val nodeInputs = states.filterIsInstance<AgentOrchestratorState.NodeIO>().associate { it.nodeId to it.input }
        assertTrue(
            "the node after the crossing must be told to wind up; got: ${nodeInputs["d"]}",
            nodeInputs.getValue("d").contains("SYSTEM NOTICE"),
        )
        // And only that one: the warning fires once per axis, not on every node.
        assertFalse("a node before the crossing must not be warned", nodeInputs.getValue("b").contains("SYSTEM NOTICE"))
        // The answer is still the model's, not the engine's.
        assertEquals("answer", (states.last() as AgentOrchestratorState.Completed).finalResponse)
    }

    @Test
    fun `given a pass-through OUTPUT then the soft-ceiling notice never reaches the answer`() = runTest {
        // `currentInputText` is a prompt only for a node that composes one. An
        // OUTPUT node with no systemPrompt is in pass-through mode and persists
        // its input verbatim as the agent's chat message, so injecting the
        // engine's internal notice there printed it to the user as the answer.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(3)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("the real answer")

        val states = engine(sessionId, "prompt", ceilingGraph()).toList()

        val completed = states.last() as AgentOrchestratorState.Completed
        assertEquals("the real answer", completed.finalResponse)
        assertFalse(
            "the internal notice must not be part of the answer",
            completed.finalResponse.contains("SYSTEM NOTICE"),
        )
    }

    @Test
    fun `given an INTENT_ROUTER after the crossing then the notice still never reaches the answer`() = runTest {
        // The router is the case that defeated a guard on "which node receives
        // the note": it composes a prompt, so it was handed the note, but its
        // walk arm deliberately forwards `currentInputText` unchanged — so the
        // pollution outlived it and reached a pass-through OUTPUT anyway.
        //
        // Arithmetic that puts the crossing one node ahead of the router:
        // hard = 5 gives softFor(5) = 3, and the five nodes charge 1..5, so the
        // third (llm_2) raises the warning, the router receives it, and OUTPUT
        // still runs because 4 < 5.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(5)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Blue")

        val graph = PipelineGraph(
            id = "g-router-soft",
            name = "RouterSoft",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
                NodeModel("llm_2", NodeType.LITE_RT, 0f, 0f),
                NodeModel("router_1", NodeType.INTENT_ROUTER, 0f, 0f, systemPrompt = "route"),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "llm_2"),
                ConnectionModel("c3", "llm_2", "router_1"),
                ConnectionModel("c4", "router_1", "output_1", label = "Blue"),
            ),
        )

        val states = engine(sessionId, "prompt", graph).toList()

        val completed = states.last() as AgentOrchestratorState.Completed
        assertFalse(
            "the internal notice must not survive the router into the answer",
            completed.finalResponse.contains("SYSTEM NOTICE"),
        )
        // Not vacuous: the run really did walk through the router to OUTPUT, and
        // the soft warning really did fire. Without both, "no notice in the
        // answer" would be true for the wrong reason.
        val consoleEvents = states.filterIsInstance<AgentOrchestratorState.ConsoleLog>().last().events
        assertTrue(
            "the soft threshold must actually have been crossed",
            consoleEvents.any { it.type == ConsoleEventType.RunCeiling },
        )
        assertTrue(
            "the router must actually have executed",
            consoleEvents.any { it.message.contains(NodeType.INTENT_ROUTER.name) },
        )
    }

    @Test
    fun `given the crossing lands on OUTPUT then no console line is pushed after Completed`() = runTest {
        // OUTPUT's executor has already emitted `Completed` by the time the
        // charge happens, so a console push there would move the terminal state
        // off the tail of the flow — the same rule the "check" event and the
        // NodeIO emission follow. This test watches the terminal state, not the
        // absence of the line, because that is the property that matters.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(4)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", ceilingGraph()).toList()

        // softFor(4) == 3 and the walk is exactly three nodes, so the crossing
        // is on OUTPUT.
        assertTrue(states.last() is AgentOrchestratorState.Completed)
    }

    @Test
    fun `given a persisted run then the spend is written to the root record as the tree executes`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        engine(sessionId, "prompt", ceilingGraph(), "run-spend").toList()

        // Three nodes execute on a fresh attempt, so the last write records
        // three steps against the root — the number a resume will read back.
        coVerify { pipelineRunRepository.recordSpend("run-spend", 3, any()) }
    }

    @Test
    fun `given a previous attempt already spent the ceiling then the resumed run does no further work`() = runTest {
        // The regression the shared ledger exists for. Every answered background
        // approval comes back through the resume path, and a run can park an
        // unbounded number of times; with a per-attempt budget a nightly loop
        // received a full fresh ceiling after every answer and the ceiling never
        // bound. Mutate `getSpend` back to RunSpend() and this test passes while
        // the defect is present — three nodes run and the pipeline completes.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        coEvery { pipelineRunRepository.getSpend("run-resumed") } returns RunSpend(steps = 15, tokens = 0)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", ceilingGraph(), "run-resumed").toList()

        // The ceiling now pauses rather than ends the run, so the guard is no
        // longer "it failed" — it is that nothing further was spent.
        assertEquals(
            AgentOrchestratorState.SuspendedInBackground(PendingInteractionKind.CEILING),
            states.last(),
        )
        verify(exactly = 0) { llmEngine.generateResponseStream(any()) }
    }

    @Test
    fun `given a spent ceiling on a persisted run then it parks and asks instead of ending the run`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        coEvery { pipelineRunRepository.getSpend("run-pause") } returns RunSpend(steps = 15, tokens = 0)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", ceilingGraph(), "run-pause").toList()

        val pause = states.filterIsInstance<AgentOrchestratorState.WaitingForCeilingRaise>().single()
        assertEquals(RunCeilingAxis.STEPS, pause.axis)
        assertEquals(15, pause.limit)
        assertEquals(15, pause.spent)
        // A pause is not a failure: the run keeps its checkpoint and its record
        // stays non-terminal, so nothing downstream may read it as a stop.
        assertTrue(states.none { it is AgentOrchestratorState.Error })
        coVerify { pipelineRunRepository.updateStatus("run-pause", PipelineRunStatus.WAITING_CEILING) }

        val parked = slot<PendingInteraction>()
        coVerify { pendingInteractionRepository.save(capture(parked)) }
        assertEquals(PendingInteractionKind.CEILING, parked.captured.kind)
        assertEquals(RunCeilingAxis.STEPS, parked.captured.ceilingAxis)
        assertEquals(15, parked.captured.ceilingLimit)
        assertEquals(15, parked.captured.ceilingSpent)
        verify {
            ceilingNotifier.sendCeilingPauseRequest(
                "run-pause",
                sessionId,
                HardCeilingBreach(RunCeilingAxis.STEPS, 15, 15),
            )
        }
    }

    @Test
    fun `given a spent ceiling on a non-persisted run then it stops, because no answer could reach it`() = runTest {
        // An editor test run has no record to park on. A pause raised here would
        // end with the coroutine and leave a card nothing can settle, so the
        // old fail-fast behaviour is the honest one.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(2)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", ceilingGraph(), runId = null).toList()

        val error = states.last() as AgentOrchestratorState.Error
        assertEquals(RunTerminationReason.StepCeiling(limit = 2, spent = 2), error.reason)
        coVerify(exactly = 0) { pendingInteractionRepository.save(any()) }
    }

    @Test
    fun `given the pending store refuses the park then the run stops rather than waiting silently`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        coEvery { pipelineRunRepository.getSpend("run-nostore") } returns RunSpend(steps = 15, tokens = 0)
        coEvery { pendingInteractionRepository.save(any()) } returns false
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", ceilingGraph(), "run-nostore").toList()

        val error = states.last() as AgentOrchestratorState.Error
        assertEquals(RunTerminationReason.StepCeiling(limit = 15, spent = 15), error.reason)
        // No notification either: a shade entry deep-linking to a pause with no
        // record behind it is worse than none.
        verify(exactly = 0) { ceilingNotifier.sendCeilingPauseRequest(any(), any(), any()) }
    }

    @Test
    fun `given a granted extension then the run continues past the base ceiling`() = runTest {
        // The answer's whole effect. Drop `stepCeilingExtensions` from the seed
        // and the run pauses again on its first node — re-asking the question
        // the user has just answered.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        coEvery { pipelineRunRepository.getSpend("run-granted") } returns
            RunSpend(steps = 15, tokens = 0, stepCeilingExtensions = 1)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", ceilingGraph(), "run-granted").toList()

        assertTrue(states.none { it is AgentOrchestratorState.WaitingForCeilingRaise })
        assertTrue(states.last() is AgentOrchestratorState.Completed)
    }

    @Test
    fun `given a resumed run then the ceiling park it answered is consumed`() = runTest {
        // Nothing else consumes it: the other two kinds are consumed by the node
        // executor that raised them, and a ceiling belongs to no node. Left
        // behind, the maintenance sweep would later fail a run that is running.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        coEvery { pendingInteractionRepository.getForRun("run-consume") } returns PendingInteraction(
            runId = "run-consume",
            sessionId = sessionId,
            kind = PendingInteractionKind.CEILING,
            ceilingAxis = RunCeilingAxis.STEPS,
            ceilingLimit = 15,
            ceilingSpent = 15,
            requestedAt = 0L,
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        engine(sessionId, "prompt", ceilingGraph(), "run-consume").toList()

        coVerify { pendingInteractionRepository.delete("run-consume") }
        verify { ceilingNotifier.cancelCeilingNotification(sessionId) }
    }

    @Test
    fun `given a resumed run then replayed nodes are not charged a second time`() = runTest {
        // Replayed nodes were charged when they really ran. Charging them again
        // would make a run that parks often die earlier than one that never
        // parks — the inverse of what persisting the counter is for.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        coEvery { pipelineRunRepository.getSpend("run-replay") } returns RunSpend(steps = 2, tokens = 0)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Live")

        val graph = PipelineGraph(
            id = "g-replay",
            name = "Replay",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f),
                NodeModel("llm_2", NodeType.LITE_RT, 0f, 0f),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "llm_2"),
                ConnectionModel("c3", "llm_2", "output_1"),
            ),
        )
        val resume = ResumeContext(
            records = listOf(nodeIoRecord("run-replay", 3L, "llm_1", NodeType.LITE_RT, "Recorded")),
            memorySnapshot = null,
            nextSeq = 4L,
        )

        engine(sessionId, "prompt", graph, "run-replay", resume).toList()

        // Four nodes are walked. llm_1 replays from the checkpoint and is not
        // charged; INPUT and OUTPUT re-run but were already charged on the first
        // attempt, so a resume does not charge them again. Only llm_2 is new.
        // The seed was 2, so the tree lands on 3 — not 6, which is what charging
        // the replayed prefix and the pass-through nodes again would produce.
        coVerify { pipelineRunRepository.recordSpend("run-replay", 3, any()) }
        coVerify(exactly = 0) { pipelineRunRepository.recordSpend("run-replay", 6, any()) }
    }

    @Test
    fun `given a ceiling breach inside a sub-pipeline then the typed reason reaches the root run`() = runTest {
        // The reason has to survive the sub-pipeline boundary. Dropping it there
        // settles the ROOT run as an ordinary failure, so a trigger whose loop
        // lives one nesting level down would still redden its health badge for a
        // guard that worked — the exact misreading this vocabulary prevents.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(3)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("partial")
        val subGraph = PipelineGraph(
            id = "sub-pipe",
            name = "Sub",
            nodes = listOf(
                NodeModel("sub_in", NodeType.INPUT, 0f, 0f),
                NodeModel("sub_llm", NodeType.LITE_RT, 5f, 0f),
                NodeModel("sub_out", NodeType.OUTPUT, 10f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("sc1", "sub_in", "sub_llm"),
                ConnectionModel("sc2", "sub_llm", "sub_out"),
            ),
        )
        coEvery { pipelineRepository.getPipelineById("sub-pipe") } returns subGraph
        val mainGraph = PipelineGraph(
            id = "main-pipe",
            name = "Main",
            nodes = listOf(
                NodeModel("main_in", NodeType.INPUT, 0f, 0f),
                NodeModel("pipe_node", NodeType.PIPELINE, 10f, 0f, targetPipelineId = "sub-pipe"),
                NodeModel("main_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("mc1", "main_in", "pipe_node"),
                ConnectionModel("mc2", "pipe_node", "main_out"),
            ),
        )

        val states = engine(sessionId, "go", mainGraph).toList()

        val error = states.last() as AgentOrchestratorState.Error
        assertTrue(
            "the root run must carry the child's typed cause, got ${error.reason}",
            error.reason is RunTerminationReason.StepCeiling,
        )
    }

    @Test
    fun `given repeated resumes then the pass-through nodes are not re-charged each time`() = runTest {
        // INPUT and OUTPUT are never written to the trace, so they re-run on
        // every attempt. Charging them again on each resume would let a run that
        // parks often exhaust its ceiling on pass-through nodes alone — the same
        // class of drift the persisted counter exists to remove.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        coEvery { pipelineRunRepository.getSpend("run-drift") } returns RunSpend(steps = 4, tokens = 0)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Live")

        val resume = ResumeContext(
            records = listOf(nodeIoRecord("run-drift", 3L, "llm_1", NodeType.LITE_RT, "Recorded")),
            memorySnapshot = null,
            nextSeq = 4L,
        )

        engine(sessionId, "prompt", ceilingGraph(), "run-drift", resume).toList()

        // Nothing new ran: llm_1 replayed, INPUT and OUTPUT were already paid
        // for. The counter must be exactly where the previous attempt left it.
        coVerify { pipelineRunRepository.recordSpend("run-drift", 4, any()) }
        coVerify(exactly = 0) { pipelineRunRepository.recordSpend("run-drift", 5, any()) }
        coVerify(exactly = 0) { pipelineRunRepository.recordSpend("run-drift", 6, any()) }
    }

    @Test
    fun `given a background origin then its own conservative ceiling applies`() = runTest {
        // Nobody is watching a trigger run, so it is bounded by the background
        // number rather than the interactive one.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(2)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", ceilingGraph(), origin = RunOrigin.TRIGGER).toList()

        val error = states.last() as AgentOrchestratorState.Error
        assertEquals(RunTerminationReason.StepCeiling(limit = 2, spent = 2), error.reason)
    }

    @Test
    fun `given an interactive origin then the background ceiling does not apply to it`() = runTest {
        // The mirror of the test above: a tight background number must not leak
        // onto a run the user is sitting in front of.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(2)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("response")

        val states = engine(sessionId, "prompt", ceilingGraph(), origin = RunOrigin.CHAT).toList()

        assertTrue(states.last() is AgentOrchestratorState.Completed)
    }

    // endregion

    // region Graph stuck-detector

    /**
     * A straight chain of prompt-composing nodes. Nothing about the graph loops
     * — the repetition under test is in what the model keeps *saying*, which is
     * the shape the detector actually has to recognise: the one structural
     * cycle the validator permits (a `QUEUE_PROCESSOR` back-edge) only becomes
     * a defect when its iterations stop differing.
     *
     * @param count How many `LITE_RT` nodes sit between INPUT and OUTPUT.
     * @return The graph.
     */
    private fun repeatingChainGraph(count: Int): PipelineGraph {
        val llmIds = (1..count).map { "llm_$it" }
        val ids = listOf("input_1") + llmIds + "output_1"
        return PipelineGraph(
            id = "g-stuck",
            name = "Stuck",
            nodes = listOf(NodeModel("input_1", NodeType.INPUT, 0f, 0f)) +
                llmIds.map { NodeModel(it, NodeType.LITE_RT, 0f, 0f, systemPrompt = "work") } +
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            connections = ids.zipWithNext().mapIndexed { i, (from, to) ->
                ConnectionModel("c$i", from, to)
            },
        )
    }

    @Test
    fun `given a run that keeps saying the same thing then it is warned once and keeps going`() = runTest {
        // Only a nudge: the grace period is set beyond the length of the run,
        // so this proves the first stage does not end anything.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("the same answer")

        val states = engine(
            sessionId,
            "prompt",
            repeatingChainGraph(count = 5),
            stuckDetector = GraphStuckDetector(staleStreak = 2, graceSteps = 99),
        ).toList()

        assertTrue("a nudged run must still finish", states.last() is AgentOrchestratorState.Completed)

        // Asserted positively. A suite that only checks the notice is *absent*
        // is satisfied by an implementation that never emits one at all.
        val notices = states.filterIsInstance<AgentOrchestratorState.RunNotice>()
        assertEquals(1, notices.size)
        assertEquals(RunNoticeCause.LooksStuck(StuckSignal.NO_NEW_OUTPUT), notices.single().cause)

        val stuckLines = states.filterIsInstance<AgentOrchestratorState.ConsoleLog>()
            .last().events
            .filter { it.type == ConsoleEventType.StuckDetector }
        // Once, not once per remaining node.
        assertEquals(1, stuckLines.size)
        assertTrue(stuckLines.single().message, stuckLines.single().message.contains("looks-stuck"))
    }

    @Test
    fun `given a nudge that changes nothing then the run is stopped with NoProgress`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("the same answer")

        val states = engine(
            sessionId,
            "prompt",
            repeatingChainGraph(count = 9),
            stuckDetector = GraphStuckDetector(staleStreak = 2, graceSteps = 1),
        ).toList()

        val error = states.last() as AgentOrchestratorState.Error
        // The shared vocabulary, not a second one invented for the detector.
        assertEquals(RunTerminationReason.NoProgress, error.reason)
        assertEquals("no-progress", error.message)
        // And the console files it under the detector, not under the ceilings —
        // a reader who greps for a limit must not find a loop.
        //
        // Asserted on the terminal line specifically. "A detector line exists"
        // is satisfied by the *nudge* that preceded it, so that weaker check
        // stayed green when the stop was filed under the ceiling channel.
        val events = states.filterIsInstance<AgentOrchestratorState.ConsoleLog>().last().events
        val terminalLines = events.filter { it.message == "no-progress" }
        assertEquals("the stop writes exactly one terminal line", 1, terminalLines.size)
        assertEquals(ConsoleEventType.StuckDetector, terminalLines.single().type)
        assertTrue(
            "no ceiling bound this run, so no ceiling line may claim it",
            events.none { it.type == ConsoleEventType.RunCeiling },
        )
    }

    @Test
    fun `given the detector stops a run then the step it stopped on is in the trace`() = runTest {
        // The stop's entire user-facing promise is **Open console**, "where the
        // repetition is visible". Leaving the walk before the trace append
        // dropped the one step the verdict was actually reached on, so the
        // console was missing exactly the evidence the reader was sent to find.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("the same answer")
        val appended = mutableListOf<RunTraceRecord>()
        coEvery { runTraceRepository.append(capture(appended)) } returns Unit

        // Which node the walk was standing on is recorded at the TOP of each
        // iteration, so it survives the `break` whatever the trace does. That
        // independence is the point: comparing the trace against the NodeIO
        // emissions instead compared two things that disappear together, and
        // passed with the bug in place.
        val visited = mutableListOf<String>()
        coEvery { pipelineRunRepository.updateCurrentNode(any(), capture(visited)) } returns Unit

        val states = engine(
            sessionId,
            "prompt",
            repeatingChainGraph(count = 9),
            "run-stuck-trace",
            stuckDetector = GraphStuckDetector(staleStreak = 2, graceSteps = 1),
        ).toList()

        val error = states.last() as AgentOrchestratorState.Error
        assertEquals(RunTerminationReason.NoProgress, error.reason)

        val tracedNodes = appended.filterIsInstance<RunTraceRecord.NodeIo>().map { it.nodeId }
        assertEquals(
            "the step the detector stopped on must be in the console it sends the user to",
            visited.last(),
            tracedNodes.last(),
        )
    }

    @Test
    fun `given a straight chain that merely repeats itself then the ceiling stops it, not the detector`() = runTest {
        // A characterisation test, and a deliberate limit rather than a gap.
        //
        // A straight chain never visits a node twice, so REPEATED_STEP — the
        // detector's fast signal, and the one that beats the ceiling on a real
        // cycle — cannot fire on it at all. What is left is NO_NEW_OUTPUT,
        // which needs the run to have stopped being asked anything new as well
        // as having stopped saying anything new; on a chain where every node
        // composes a slightly different prompt, and where the nudge's own note
        // makes one more prompt look new, that takes longer than the default
        // fifteen steps.
        //
        // This is the right way round to be wrong: the run is spending, and
        // spending is precisely what the ceilings measure. Pinned here so the
        // claim in the docs stays the one the code can keep.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("the same answer")

        val states = engine(sessionId, "prompt", repeatingChainGraph(count = 14)).toList()

        val error = states.last() as AgentOrchestratorState.Error
        assertEquals(RunTerminationReason.StepCeiling(limit = 15, spent = 15), error.reason)
    }

    @Test
    fun `given the detector nudges then the next prompt-composing node is told to change course`() = runTest {
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("the same answer")

        val states = engine(
            sessionId,
            "prompt",
            repeatingChainGraph(count = 5),
            stuckDetector = GraphStuckDetector(staleStreak = 2, graceSteps = 99),
        ).toList()

        val nodeInputs = states.filterIsInstance<AgentOrchestratorState.NodeIO>().associate { it.nodeId to it.input }
        // llm_1 answers first (novel output, and the first prompt of its
        // shape); llm_2 repeats the answer but is still being handed something
        // new; llm_3 and llm_4 repeat both, which is where the progress streak
        // and the input-staleness counter both reach 2. So llm_4 raises the
        // nudge and llm_5 is the node that gets told.
        assertTrue(
            "the node after the nudge must be told to change course; got: ${nodeInputs["llm_5"]}",
            nodeInputs.getValue("llm_5").contains("repeating itself"),
        )
        assertFalse(
            "a node before the nudge must not be warned",
            nodeInputs.getValue("llm_2").contains("SYSTEM NOTICE"),
        )
    }

    @Test
    fun `given a pass-through OUTPUT then the stuck notice never reaches the answer`() = runTest {
        // The invariant the soft-ceiling note had to learn twice: the note goes
        // into one node's composed prompt, never into the text travelling
        // between nodes, which a pass-through OUTPUT persists verbatim as the
        // agent's chat message.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("the same answer")

        val states = engine(
            sessionId,
            "prompt",
            // Four, not five: the nudge lands on the LAST llm node, so the very
            // next node is the echo OUTPUT. That adjacency is the whole test —
            // with a spare node in between, the note is consumed before OUTPUT
            // is ever reached and the assertion below cannot fail.
            repeatingChainGraph(count = 4),
            stuckDetector = GraphStuckDetector(staleStreak = 2, graceSteps = 99),
        ).toList()

        // Positively: a notice must actually have been raised, or "the answer
        // is clean" is satisfied by never producing a note at all.
        assertEquals(1, states.filterIsInstance<AgentOrchestratorState.RunNotice>().size)
        val completed = states.last() as AgentOrchestratorState.Completed
        assertEquals("the same answer", completed.finalResponse)
        assertFalse(
            "the internal notice must not be part of the answer",
            completed.finalResponse.contains("SYSTEM NOTICE"),
        )
    }

    @Test
    fun `given a generating OUTPUT that produces nothing then no note becomes the answer`() = runTest {
        // The third shape of a defect this project has now shipped twice: the
        // note is correctly kept out of `currentInputText`, and correctly
        // survives no router — but an OUTPUT node *with* a system prompt
        // composes context like any other node, so it could be handed the note
        // directly. `OutputNodeExecutor` then falls back to persisting its own
        // input verbatim whenever the model returns nothing, and the engine's
        // internal notice becomes the agent's chat message.
        //
        // The detector's nudge is what must not reach OUTPUT here. Both notes
        // go through the same drain, so the OUTPUT exclusion covers them
        // equally — the soft ceiling's separate suppression is only about where
        // a crossing is *announced*, not about where a note is delivered, and
        // this fixture deliberately does not depend on where the crossing
        // lands.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(8)
        // Every node's model returns the same text, except the OUTPUT node's,
        // which returns nothing at all — the fallback path.
        every { llmEngine.generateResponseStream(any()) } answers {
            val prompt = firstArg<String>()
            if (prompt.contains("FINAL ANSWER")) flowOf("") else flowOf("the same answer")
        }

        val graph = PipelineGraph(
            id = "g-output-note",
            name = "OutputNote",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_2", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_3", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_4", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_5", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                // A *generating* OUTPUT — the case a pass-through OUTPUT test
                // cannot reach, because a pass-through composes nothing.
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = "FINAL ANSWER: format it"),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "llm_2"),
                ConnectionModel("c3", "llm_2", "llm_3"),
                ConnectionModel("c4", "llm_3", "llm_4"),
                ConnectionModel("c5", "llm_4", "llm_5"),
                ConnectionModel("c6", "llm_5", "output_1"),
            ),
        )

        val states = engine(
            sessionId,
            "prompt",
            graph,
            stuckDetector = GraphStuckDetector(staleStreak = 3, graceSteps = 99),
        ).toList()

        val answer = states.filterIsInstance<AgentOrchestratorState.Completed>().last().finalResponse
        assertFalse("the engine's own notice must never be the answer; got: $answer", answer.contains("SYSTEM NOTICE"))
        // The DETECTOR must have spoken, not merely "some guard": with
        // `isNotEmpty()` the soft ceiling alone satisfied this, so raising
        // STALE_STREAK would have quietly removed the test's actual subject.
        assertTrue(
            "the detector must have raised its notice, or there is nothing to leak",
            states.filterIsInstance<AgentOrchestratorState.RunNotice>()
                .any { it.cause is RunNoticeCause.LooksStuck },
        )
        // The answer IS the fallback: an empty generation makes OUTPUT persist
        // its own input, so `finalResponse` is the composed prompt. Asserting
        // that proves the fallback fired rather than assuming it.
        assertTrue(
            "the empty-generation fallback must be the path under test; got: $answer",
            answer.contains("FINAL ANSWER") || answer.contains("Original Task"),
        )
    }

    @Test
    fun `given a nested generating OUTPUT then it is excluded from the note too`() = runTest {
        // The exclusion is on the node TYPE, not on the depth, and this pins
        // that. A sub-pipeline's OUTPUT does not write to the chat itself — so
        // narrowing the guard to `depth == 0` reads as a tidy-up — but its text
        // becomes the PIPELINE node's result and travels on, and a pass-through
        // OUTPUT at the root then persists it verbatim as the answer.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } answers {
            val prompt = firstArg<String>()
            if (prompt.contains("CHILD FORMAT")) flowOf("") else flowOf("the same answer")
        }
        coEvery { pipelineRunRepository.getRun(any()) } returns null

        val subGraph = PipelineGraph(
            id = "sub-out",
            name = "Sub",
            nodes = listOf(
                NodeModel("sub_in", NodeType.INPUT, 0f, 0f),
                // Generating OUTPUT whose model returns nothing: the executor
                // falls back to persisting its own input.
                NodeModel("sub_out", NodeType.OUTPUT, 0f, 0f, systemPrompt = "CHILD FORMAT"),
            ),
            connections = listOf(ConnectionModel("s1", "sub_in", "sub_out")),
        )
        coEvery { pipelineRepository.getPipelineById("sub-out") } returns subGraph

        val mainGraph = PipelineGraph(
            id = "main-out",
            name = "Main",
            nodes = listOf(
                NodeModel("main_in", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_2", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_3", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_4", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("pipe_node", NodeType.PIPELINE, 0f, 0f, targetPipelineId = "sub-out"),
                // Pass-through root OUTPUT: whatever the child produced becomes
                // the agent's chat message verbatim.
                NodeModel("main_out", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("m1", "main_in", "llm_1"),
                ConnectionModel("m2", "llm_1", "llm_2"),
                ConnectionModel("m3", "llm_2", "llm_3"),
                ConnectionModel("m4", "llm_3", "llm_4"),
                ConnectionModel("m5", "llm_4", "pipe_node"),
                ConnectionModel("m6", "pipe_node", "main_out"),
            ),
        )

        val states = engine(
            sessionId,
            "prompt",
            mainGraph,
            stuckDetector = GraphStuckDetector(staleStreak = 2, graceSteps = 99),
        ).toList()

        assertTrue(
            "the detector must have raised its notice, or there is nothing to leak",
            states.filterIsInstance<AgentOrchestratorState.RunNotice>()
                .any { it.cause is RunNoticeCause.LooksStuck },
        )
        // And the nested OUTPUT must genuinely be a prompt-composing node —
        // otherwise it was never a candidate to receive the note and this
        // guards nothing. An OUTPUT node emits no NodeIO by design, so the
        // evidence is the inference call it made with its own system prompt.
        verify { llmEngine.generateResponseStream(match { it.contains("CHILD FORMAT") }) }
        // The note also has to have been *pending* when the child ran, or the
        // child being skipped proves nothing. Nothing in this graph consumes
        // it: the nudge lands on the last node before the PIPELINE, the child's
        // only composing node is the OUTPUT under test, and the root OUTPUT is
        // a pass-through. So no node's prompt may contain it — if an
        // intermediate node were added and drained it first, this fails.
        val everyInput = states.filterIsInstance<AgentOrchestratorState.NodeIO>().map { it.input }
        assertTrue(
            "the note must still have been pending when the nested OUTPUT ran",
            everyInput.none { it.contains("SYSTEM NOTICE") },
        )
        val answer = states.filterIsInstance<AgentOrchestratorState.Completed>().last().finalResponse
        assertFalse(
            "a nested OUTPUT must not carry the note out to the user; got: $answer",
            answer.contains("SYSTEM NOTICE"),
        )
    }

    @Test
    fun `given an INTENT_ROUTER after the nudge then the notice still never reaches the answer`() = runTest {
        // The router is the case that defeated a guard on "which node receives
        // the note": it composes a prompt, so it is handed one, but its walk arm
        // forwards `currentInputText` unchanged — so a note written there
        // outlives it and reaches a pass-through OUTPUT.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Blue")

        // Four LLM nodes before the router, so the nudge lands on the last of
        // them and the router is the node that receives the note. With fewer,
        // no nudge is raised at all and this test asserts nothing — which is
        // exactly what it did until the assertion below was added.
        val graph = PipelineGraph(
            id = "g-router-stuck",
            name = "RouterStuck",
            nodes = listOf(
                NodeModel("input_1", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_2", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_3", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_4", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("router_1", NodeType.INTENT_ROUTER, 0f, 0f, systemPrompt = "route"),
                NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("c1", "input_1", "llm_1"),
                ConnectionModel("c2", "llm_1", "llm_2"),
                ConnectionModel("c3", "llm_2", "llm_3"),
                ConnectionModel("c4", "llm_3", "llm_4"),
                ConnectionModel("c5", "llm_4", "router_1"),
                ConnectionModel("c6", "router_1", "output_1"),
            ),
        )

        val states = engine(
            sessionId,
            "prompt",
            graph,
            stuckDetector = GraphStuckDetector(staleStreak = 2, graceSteps = 99),
        ).toList()

        // The router must actually have been handed the note, or the leak this
        // test guards has nothing to leak.
        val routerInput = states.filterIsInstance<AgentOrchestratorState.NodeIO>()
            .first { it.nodeId == "router_1" }.input
        assertTrue("the router must receive the note; got: $routerInput", routerInput.contains("SYSTEM NOTICE"))

        val completed = states.last() as AgentOrchestratorState.Completed
        assertFalse(
            "the internal notice must not survive the router and become the answer",
            completed.finalResponse.contains("SYSTEM NOTICE"),
        )
    }

    @Test
    fun `given both a soft ceiling and the detector speak then neither note erases the other`() = runTest {
        // The single-slot bug this list replaced: a long repeating run crosses
        // its soft ceiling *and* trips the detector, and whichever spoke second
        // used to silently overwrite the other's advice.
        //
        // Arranged so both guards speak in the SAME gap, which is the only way
        // one can erase the other: hard = 8 gives softFor(8) = 6, and the walk
        // charges INPUT plus llm_1..llm_5 to reach 6 on llm_5 — the same node
        // where the progress streak (4) and the input-staleness counter (3)
        // both clear a stale streak of 3. Both notes are queued there and both
        // must reach llm_6.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(8)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("the same answer")

        val states = engine(
            sessionId,
            "prompt",
            repeatingChainGraph(count = 6),
            stuckDetector = GraphStuckDetector(staleStreak = 3, graceSteps = 99),
        ).toList()

        val nodeInputs = states.filterIsInstance<AgentOrchestratorState.NodeIO>().associate { it.nodeId to it.input }
        val warned = nodeInputs.getValue("llm_6")
        assertTrue("the ceiling's advice must survive; got: $warned", warned.contains("close to its resource limit"))
        assertTrue("the detector's advice must survive; got: $warned", warned.contains("repeating itself"))
    }

    @Test
    fun `given the nudge is raised before a sub-pipeline then the child still receives it`() = runTest {
        // The grace period is spent by steps at EVERY depth, so a nudge raised
        // just before a PIPELINE node has its clock run down by the child's
        // steps. With the note held in the parent's own invocation it could
        // never reach that child, and the run would be stopped for ignoring
        // advice no model in the tree was ever given.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("the same answer")

        val subGraph = PipelineGraph(
            id = "sub-stuck",
            name = "Sub",
            nodes = listOf(
                NodeModel("sub_in", NodeType.INPUT, 0f, 0f),
                NodeModel("sub_llm", NodeType.LITE_RT, 10f, 0f, systemPrompt = "work"),
                NodeModel("sub_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("sc1", "sub_in", "sub_llm"),
                ConnectionModel("sc2", "sub_llm", "sub_out"),
            ),
        )
        coEvery { pipelineRepository.getPipelineById("sub-stuck") } returns subGraph

        // The parent repeats itself enough to earn the nudge, and the only
        // prompt-composing node left after it lives inside the child.
        val mainGraph = PipelineGraph(
            id = "main-stuck",
            name = "Main",
            nodes = listOf(
                NodeModel("main_in", NodeType.INPUT, 0f, 0f),
                NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_2", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_3", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("llm_4", NodeType.LITE_RT, 0f, 0f, systemPrompt = "work"),
                NodeModel("pipe_node", NodeType.PIPELINE, 0f, 0f, targetPipelineId = "sub-stuck"),
                NodeModel("main_out", NodeType.OUTPUT, 0f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("mc1", "main_in", "llm_1"),
                ConnectionModel("mc2", "llm_1", "llm_2"),
                ConnectionModel("mc3", "llm_2", "llm_3"),
                ConnectionModel("mc4", "llm_3", "llm_4"),
                ConnectionModel("mc5", "llm_4", "pipe_node"),
                ConnectionModel("mc6", "pipe_node", "main_out"),
            ),
        )

        // Both child paths, because `PipelineNodeExecutor` has two nearly
        // identical hand-off blocks: an unpersisted child (the editor's test
        // run, `runId == null`) and `runPersistedChild` (every real run). A
        // test that exercised only one would let someone tidying the pair drop
        // `contextNotes` from the other and never hear about it.
        // A relaxed mock answers `getRun` with a relaxed PipelineRun rather than
        // null, which `prepareChildRun` reads as an unexpected existing run and
        // aborts on. Say "no such run" explicitly so the persisted path takes
        // its fresh-child branch.
        coEvery { pipelineRunRepository.getRun(any()) } returns null

        listOf(null, "run-parent").forEach { parentRunId ->
            val states = engine(
                sessionId,
                "prompt",
                mainGraph,
                parentRunId,
                stuckDetector = GraphStuckDetector(staleStreak = 2, graceSteps = 99),
            ).toList()

            val inputs = states.filterIsInstance<AgentOrchestratorState.NodeIO>()
            assertTrue(
                "the nudge must have been raised at all (runId=$parentRunId)",
                states.filterIsInstance<AgentOrchestratorState.RunNotice>().isNotEmpty(),
            )
            val childInput = inputs.firstOrNull { it.nodeId == "sub_llm" }?.input
            assertNotNull("the child's LITE_RT must have run (runId=$parentRunId)", childInput)
            assertTrue(
                "the advice must cross the sub-pipeline boundary (runId=$parentRunId); got: $childInput",
                childInput!!.contains("repeating itself"),
            )
        }
    }

    @Test
    fun `given a resumed run that inherits the escalation then it is warned before it is stopped`() = runTest {
        // Notes are live-only, so the attempt that raised one and then parked
        // destroyed it. Carrying only the CLOCK across the resume would stop
        // the run for ignoring advice that no longer exists — a stepped
        // recovery with the first step missing.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("the same answer")

        // A prefix long enough to have earned the nudge on the parked attempt.
        val resume = ResumeContext(
            records = (1..5).map { i ->
                nodeIoRecord("run-armed", i.toLong(), "llm_$i", NodeType.LITE_RT, "the same answer")
            },
            memorySnapshot = null,
            nextSeq = 6L,
        )

        val states = engine(
            sessionId,
            "prompt",
            // Long enough that the resumed run actually reaches its stop. With
            // a shorter graph it merely completed, and the "before it is
            // stopped" half of this test's name was asserting nothing.
            repeatingChainGraph(count = 9),
            "run-armed",
            resume,
            stuckDetector = GraphStuckDetector(staleStreak = 2, graceSteps = 1),
        ).toList()

        // Both halves, in order: the run IS stopped, and a live node was handed
        // the advice before that happened.
        val error = states.last() as AgentOrchestratorState.Error
        assertEquals(RunTerminationReason.NoProgress, error.reason)

        val liveIo = states.filterIsInstance<AgentOrchestratorState.NodeIO>()
            .filter { it.nodeId !in resume.records.map { r -> r.nodeId } }
        assertTrue(
            "the first live node must be handed the advice the parked attempt lost; got: " +
                liveIo.map { it.nodeId },
            liveIo.any { it.input.contains("repeating itself") },
        )
    }

    @Test
    fun `given a replayed prefix that repeats then the resumed run is not stopped on it`() = runTest {
        // A resume must rebuild the window rather than start blind — a run that
        // parks often is exactly the one that may be looping. But the attempt
        // that ran this prefix did not stop on it, and reaching a different
        // verdict now would rewrite what already happened.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("fresh answer")

        // The prefix is long enough that acting on it WOULD end the run: with
        // these thresholds the third record nudges and the fourth stops. A
        // shorter prefix could never have failed this test whatever the engine
        // did with it.
        val graph = repeatingChainGraph(count = 5)
        val resume = ResumeContext(
            records = (1..4).map { i ->
                nodeIoRecord("run-stuck", i.toLong(), "llm_$i", NodeType.LITE_RT, "the same answer")
            },
            memorySnapshot = null,
            nextSeq = 5L,
        )

        val states = engine(
            sessionId,
            "prompt",
            graph,
            "run-stuck",
            resume,
            stuckDetector = GraphStuckDetector(staleStreak = 2, graceSteps = 1),
        ).toList()

        assertTrue(
            "a replayed prefix must not end the resumed run: ${states.last()}",
            states.last() is AgentOrchestratorState.Completed,
        )
    }

    // endregion

    private companion object {
        const val LEAKED_KEY = "AIzaSyTESTKEY"
        const val LEAKING_PROVIDER_ERROR =
            "Socket timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini:streamGenerateContent?alt=sse&key=$LEAKED_KEY]"
    }
}
