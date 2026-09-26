package app.knotwork.android.domain.preset

import app.knotwork.android.data.engine.KoogClientFactory
import app.knotwork.android.data.engine.KoogCloudLlmModelResolver
import app.knotwork.android.domain.engine.ChatHistoryWindowPlanner
import app.knotwork.android.domain.engine.GraphExecutionEngine
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.NodeContextBuilder
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
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelinePresetImportOutcome
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.pipelineio.PipelinePresetJsonSerializer
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.ClarificationRepository
import app.knotwork.android.domain.repositories.CrashReportingRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelinePresetRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.services.NativeMemorySampler
import app.knotwork.android.domain.services.PipelineCompositionValidator
import app.knotwork.android.domain.usecases.EvaluateIfConditionUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.LoadPipelineFromPresetUseCase
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.inject.Provider

/**
 * End-to-end integration test for the **composed** bundled Showcase agent: the
 * task loop whose four subtask branches now run as sub-pipelines through
 * `PIPELINE` nodes.
 *
 * Two arcs are proven against the **real shipped assets**:
 *
 * 1. *Seeding.* Materialising `showcase_full_agent.json` through the real
 *    [LoadPipelineFromPresetUseCase] persists not only the parent but also the
 *    four `subtask_*` sub-pipelines under their **stable ids**, and the
 *    resulting composition passes the real [PipelineCompositionValidator]
 *    (every `PIPELINE` target resolves, no cycle, within the depth limit).
 *
 * 2. *Nested run.* A parent pipeline whose `PIPELINE` node targets the seeded
 *    `subtask_process` sub-pipeline runs end-to-end through the real
 *    [GraphExecutionEngine] (driving a real [PipelineNodeExecutor] recursively):
 *    a **child run row** is recorded under the parent (`parentRunId`,
 *    deterministic id), and the sub-pipeline's final answer becomes the run's
 *    final answer.
 *
 * Pure JVM; the only stubbed seam is the LLM token stream. `:app:test` runs in
 * the `app/` working directory, so the relative asset path resolves.
 */
class ShowcaseCompositionIntegrationTest {

    private val catalogDir: File = File("src/main/assets/presets/pipelines")
    private val sessionId = "composition-session"

    // In-memory pipeline store: LoadPipelineFromPresetUseCase persists into it,
    // and both the composition validator and the engine resolve targets from it.
    private val pipelines = mutableMapOf<String, PipelineGraph>()

    // In-memory run store: the PIPELINE executor records child runs here so the
    // run tree can be asserted.
    private val runs = mutableMapOf<String, PipelineRun>()

    private lateinit var llmEngine: LlmInferenceEngine
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var pipelineRunRepository: PipelineRunRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var presetRepository: PipelinePresetRepository
    private lateinit var loadFromPreset: LoadPipelineFromPresetUseCase
    private lateinit var compositionValidator: PipelineCompositionValidator
    private lateinit var engine: GraphExecutionEngine

    /** Deterministic Process-node answer. */
    private val processAnswer = "Drafted the meeting agenda."

    @Before
    fun setup() {
        llmEngine = mockk()
        every { llmEngine.currentModelPath } returns null
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.workspaceReadTokenBudget } returns flowOf(2_000)

        // --- in-memory pipeline repository ---
        val pipelinesFlow = MutableStateFlow<List<PipelineGraph>>(emptyList())
        pipelineRepository = mockk(relaxed = true)
        coEvery { pipelineRepository.getPipelineById(any()) } answers { pipelines[firstArg()] }
        coEvery { pipelineRepository.savePipeline(any()) } answers {
            val g = firstArg<PipelineGraph>()
            pipelines[g.id] = g
            pipelinesFlow.value = pipelines.values.toList()
        }
        every { pipelineRepository.getAllPipelines() } returns pipelinesFlow

        // --- in-memory run repository ---
        pipelineRunRepository = mockk(relaxed = true)
        coEvery { pipelineRunRepository.getRun(any()) } answers { runs[firstArg()] }
        coEvery { pipelineRunRepository.createRun(any()) } answers {
            val r = firstArg<PipelineRun>()
            runs[r.id] = r
        }
        coEvery { pipelineRunRepository.markRunning(any(), any(), any()) } answers {
            val id = firstArg<String>()
            runs[id]?.let { runs[id] = it.copy(status = PipelineRunStatus.RUNNING) }
        }
        coEvery { pipelineRunRepository.finishRun(any(), any(), any()) } answers {
            val id = firstArg<String>()
            runs[id]?.let { runs[id] = it.copy(status = secondArg()) }
        }
        coEvery { pipelineRunRepository.getDescendantRuns(any()) } answers {
            val root = firstArg<String>()
            runs.values.filter { it.parentRunId == root }
        }

        presetRepository = mockk()
        listOf("showcase_full_agent", "subtask_clarify", "subtask_lookup", "subtask_act", "subtask_process")
            .forEach { id -> coEvery { presetRepository.getPresetById(id) } returns parseBundled("$id.json") }

        loadFromPreset = LoadPipelineFromPresetUseCase(presetRepository, pipelineRepository)
        compositionValidator = PipelineCompositionValidator(pipelineRepository, settingsRepository)

        engine = buildEngine()

        every { llmEngine.generateResponseStream(any()) } answers { flowOf(scriptFor(firstArg())) }
        every { settingsRepository.pipelineMaxNestingDepth } returns flowOf(3)
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(15)
        every { settingsRepository.runMaxTokens } returns flowOf(1_000_000)
        every { settingsRepository.runMaxTokensBackground } returns flowOf(100_000)
        coEvery { pipelineRunRepository.getSpend(any()) } returns RunSpend()
        every { settingsRepository.systemPromptPrefix } returns flowOf("")
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.verboseMemoryLoggingEnabled } returns flowOf(false)
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)
    }

    @Test
    fun `showcase materialises and seeds its four sub-pipelines under stable ids and validates as a composition`() =
        runTest {
            val result = loadFromPreset("showcase_full_agent")

            assertTrue("Showcase should materialise: ${result.exceptionOrNull()}", result.isSuccess)
            // The four sub-pipelines are persisted under exactly the stable ids the
            // parent's PIPELINE nodes reference.
            listOf("subtask_clarify", "subtask_lookup", "subtask_act", "subtask_process").forEach { id ->
                assertNotNull("Sub-pipeline '$id' must be seeded under its stable id", pipelines[id])
                assertEquals(id, pipelines.getValue(id).id)
            }
            // The materialised parent references those ids and validates as a
            // sound composition (targets resolve, no cycle, within depth).
            val parent = pipelines.getValue(result.getOrNull()!!)
            val targets = parent.nodes.filter {
                it.type == NodeType.PIPELINE
            }.mapNotNull { it.targetPipelineId }.toSet()
            assertEquals(
                setOf("subtask_clarify", "subtask_lookup", "subtask_act", "subtask_process"),
                targets,
            )
            assertTrue(
                "Composition must validate cleanly but had: ${compositionValidator.validate(parent)}",
                compositionValidator.validate(parent).isEmpty(),
            )
        }

    @Test
    fun `nested run records a child run under the parent and forwards the sub-pipeline final answer`() = runTest {
        // Seed the showcase (and its sub-pipelines) so subtask_process resolves.
        assertTrue(loadFromPreset("showcase_full_agent").isSuccess)

        // A minimal parent that calls the real subtask_process sub-pipeline.
        val parent = PipelineGraph(
            id = "caller",
            name = "Caller",
            nodes = listOf(
                NodeModel("p_in", NodeType.INPUT, 0f, 0f),
                NodeModel(
                    "p_pipe",
                    NodeType.PIPELINE,
                    10f,
                    0f,
                    targetPipelineId = "subtask_process",
                    contextConfig = NodeContextConfig(
                        chatHistory = false,
                        originalTask = true,
                        nodeInput = true,
                        longTermMemory = false,
                        toolResults = false,
                    ),
                ),
                NodeModel("p_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
            ),
            connections = listOf(
                ConnectionModel("pc1", "p_in", "p_pipe"),
                ConnectionModel("pc2", "p_pipe", "p_out"),
            ),
        )

        val states = engine(sessionId, "Draft a meeting agenda", parent, runId = "root").toList()

        // The composition completes with the sub-pipeline's answer flowing up.
        val terminal = states.last()
        assertTrue("Expected Completed but got $terminal", terminal is AgentOrchestratorState.Completed)
        assertEquals(processAnswer, (terminal as AgentOrchestratorState.Completed).finalResponse)

        // A child run was recorded under the parent with the deterministic id and
        // settled COMPLETED — the run tree the nested console / resume rely on.
        val childId = "root::p_pipe::0"
        val child = runs[childId]
        assertNotNull("Child run must be recorded", child)
        assertEquals("root", child!!.parentRunId)
        assertEquals("subtask_process", child.pipelineId)
        assertEquals(PipelineRunStatus.COMPLETED, child.status)
        assertEquals(listOf(childId), pipelineRunRepository.getDescendantRuns("root").map { it.id })
    }

    /** Canned LLM answer: the Process sub-pipeline's LITE_RT node returns the agenda. */
    private fun scriptFor(prompt: String): String = when {
        prompt.contains("completing ONE subtask") -> processAnswer
        else -> processAnswer
    }

    private fun buildEngine(): GraphExecutionEngine {
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val metricsRepository = mockk<MetricsRepository>(relaxed = true)
        val loadModelUseCase = mockk<LoadModelUseCase>()
        val toolRepository = mockk<ToolRepository>(relaxed = true)
        val retrieveRelevantMemoryUseCase = mockk<RetrieveRelevantMemoryUseCase>()
        val runTraceRepository = mockk<RunTraceRepository>(relaxed = true)
        val pendingInteractionRepository = mockk<PendingInteractionRepository>(relaxed = true)
        val ceilingNotifier = mockk<CeilingNotifier>(relaxed = true)
        val localModelRepository = mockk<LocalModelRepository>(relaxed = true)
        val crashReportingRepository = mockk<CrashReportingRepository>(relaxed = true)

        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        coEvery { retrieveRelevantMemoryUseCase(any()) } returns emptyList()
        coEvery { retrieveRelevantMemoryUseCase.retrieveScored(any()) } returns emptyList()
        every { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
        coEvery { toolRepository.getAvailableTools() } returns emptyList()

        val toolNodeExecutor = ToolNodeExecutor(
            llmEngine,
            loadModelUseCase,
            toolRepository,
            ToolInvocationGate(
                toolRepository,
                settingsRepository,
                mockk<ApprovalNotifier>(relaxed = true),
                chatRepository,
                mockk<PendingInteractionRepository>(relaxed = true),
                recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
            ),
            StructuredOutputGate(),
            settingsRepository,
            CloudStructuredInferenceClientFactory { _, _ -> null },
        )
        val factory = NodeExecutorFactory(
            InputNodeExecutor(),
            OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true)),
            IfConditionNodeExecutor(mockk<EvaluateIfConditionUseCase>(relaxed = true)),
            toolNodeExecutor,
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
                mockk<ApiKeyRepository>(relaxed = true),
                metricsRepository,
                mockk<KoogClientFactory>(relaxed = true),
                mockk<KoogCloudLlmModelResolver>(relaxed = true),
                mockk<NetworkActivityTracker>(relaxed = true),
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
                mockk<ClarificationRepository>(relaxed = true),
                mockk<PendingInteractionRepository>(relaxed = true),
                mockk<ClarificationNotifier>(relaxed = true),
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

        return GraphExecutionEngine(
            factory,
            toolNodeExecutor,
            chatRepository,
            settingsRepository,
            metricsRepository,
            PromptTemplateEngine(),
            emptySet<PromptVariableProvider>(),
            NodeContextBuilder(),
            ChatHistoryWindowPlanner(),
            retrieveRelevantMemoryUseCase,
            crashReportingRepository,
            localModelRepository,
            mockk(relaxed = true),
            pipelineRunRepository,
            runTraceRepository,
            ResolveRunCeilingsUseCase(settingsRepository),
            pendingInteractionRepository,
            ceilingNotifier,
        )
    }

    private fun parseBundled(fileName: String): PipelinePreset {
        val outcome = PipelinePresetJsonSerializer.parse(File(catalogDir, fileName).readText(), isBundled = true)
        assertTrue(
            "Bundled preset $fileName did not parse cleanly: $outcome",
            outcome is PipelinePresetImportOutcome.Success,
        )
        return (outcome as PipelinePresetImportOutcome.Success).preset
    }
}
