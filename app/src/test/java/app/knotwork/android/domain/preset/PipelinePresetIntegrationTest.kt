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
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelinePresetImportOutcome
import app.knotwork.android.domain.models.Result
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
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.services.NativeMemorySampler
import app.knotwork.android.domain.usecases.EvaluateIfConditionUseCase
import app.knotwork.android.domain.usecases.GetContextWindowUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.LoadPipelineFromPresetUseCase
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.inject.Provider

/**
 * End-to-end integration test for the **pipeline-preset** path
 *
 *
 * ```
 * bundled JSON ──parse──▶ PipelinePreset
 *              ──LoadPipelineFromPresetUseCase──▶ fresh PipelineGraph
 *              ──validate──▶ (zero errors)
 *              ──GraphExecutionEngine──▶ Completed
 * ```
 *
 * Where `PipelinePresetCatalogValidationTest` pins the *shipped artefacts*
 * (filenames, parse success, embedded-graph validity), this test wires the
 * real [PipelinePresetJsonSerializer] → real [LoadPipelineFromPresetUseCase]
 * → real [GraphExecutionEngine] (with a mocked [LlmInferenceEngine]) and
 * proves the whole feature composes: a bundled preset can be **materialised
 * into a runnable pipeline that actually executes to completion**.
 *
 * Pure JVM. The only stubbed seam is the LLM token stream — there is no
 * on-device model in a unit test, so an instrumented variant would buy
 * nothing here; every other collaborator is the production class. Gradle's
 * `:app:test` task runs in the `app/` working directory, so the relative
 * asset path resolves correctly.
 */
class PipelinePresetIntegrationTest {

    private val catalogDir: File = File("src/main/assets/presets/pipelines")

    private val sessionId = "preset-integration-session"

    // Collaborators of GraphExecutionEngine. Mirrors the wiring in
    // GraphExecutionEngineTest; only the LLM stream is meaningfully stubbed.
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

    private lateinit var engine: GraphExecutionEngine

    /** Canned local-LLM output echoed downstream by the OUTPUT node. */
    private val cannedAnswer = "Mount Everest is the tallest mountain on Earth."

    @Before
    fun setup() {
        llmEngine = mockk()
        every { llmEngine.currentModelPath } returns null
        toolRepository = mockk(relaxed = true)
        chatRepository = mockk(relaxed = true)
        getContextWindowUseCase = mockk()
        retrieveRelevantMemoryUseCase = mockk()
        settingsRepository = mockk()
        every { settingsRepository.workspaceReadTokenBudget } returns flowOf(2_000)
        apiKeyRepository = mockk(relaxed = true)
        metricsRepository = mockk(relaxed = true)
        approvalNotifier = mockk(relaxed = true)
        pendingInteractionRepository = mockk(relaxed = true)
        ceilingNotifier = mockk(relaxed = true)
        coEvery { pendingInteractionRepository.getForRun(any()) } returns null
        coEvery { pendingInteractionRepository.save(any()) } returns true
        clarificationNotifier = mockk(relaxed = true)
        koogClientFactory = mockk(relaxed = true)
        cloudLlmModelResolver = mockk(relaxed = true)
        networkActivityTracker = mockk(relaxed = true)
        evaluateIfConditionUseCase = mockk(relaxed = true)
        loadModelUseCase = mockk()
        clarificationRepository = mockk(relaxed = true)
        crashReportingRepository = mockk(relaxed = true)
        localModelRepository = mockk(relaxed = true)

        val toolNodeExecutor = ToolNodeExecutor(
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
        val nodeExecutorFactory = NodeExecutorFactory(
            InputNodeExecutor(),
            OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true)),
            IfConditionNodeExecutor(evaluateIfConditionUseCase),
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
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                Provider {
                    mockk(relaxed = true)
                },
            ),
            mockk<SkillNodeExecutor>(relaxed = true),
        )

        engine = GraphExecutionEngine(
            nodeExecutorFactory,
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
            mockk(relaxed = true),
            mockk(relaxed = true),
            ResolveRunCeilingsUseCase(settingsRepository),
            pendingInteractionRepository,
            ceilingNotifier,
        )

        every { llmEngine.generateResponseStream(any()) } returns flowOf(cannedAnswer)
        coEvery { getContextWindowUseCase(any()) } returns ""
        coEvery { retrieveRelevantMemoryUseCase(any()) } returns emptyList()
        every { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
        every { settingsRepository.systemPromptPrefix } returns flowOf("")
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(15)
        every { settingsRepository.runMaxTokens } returns flowOf(1_000_000)
        every { settingsRepository.runMaxTokensBackground } returns flowOf(100_000)
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)
        coEvery { toolRepository.getAvailableTools() } returns emptyList()
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        coEvery { localModelRepository.getActiveModel() } returns null
    }

    @Test
    fun `every bundled preset materialises into a valid pipeline with fresh ids`() = runTest {
        forEachBundledFile { file ->
            val preset = parseBundled(file.name)

            val savedGraph = slot<PipelineGraph>()
            val presetRepository = mockk<PipelinePresetRepository>()
            val pipelineRepository = mockk<PipelineRepository>(relaxed = true)
            coEvery { presetRepository.getPresetById(preset.id) } returns preset
            coEvery { pipelineRepository.savePipeline(capture(savedGraph)) } returns Unit
            val useCase = LoadPipelineFromPresetUseCase(presetRepository, pipelineRepository)

            val result = useCase(preset.id)

            assertTrue(
                "Materialising ${file.name} should succeed but was: ${result.exceptionOrNull()}",
                result.isSuccess,
            )
            val materialised = savedGraph.captured

            // Fresh ids: the pipeline and every node id differ from the template.
            assertNotEquals(
                "Pipeline id must be regenerated for ${file.name}",
                preset.graph.id,
                materialised.id,
            )
            val templateNodeIds = preset.graph.nodes.map { it.id }.toSet()
            assertTrue(
                "All node ids must be regenerated for ${file.name}",
                materialised.nodes.none { it.id in templateNodeIds },
            )
            // Connection count is preserved (no dangling drops in a well-formed preset).
            assertEquals(
                "Connection count should match the template for ${file.name}",
                preset.graph.connections.size,
                materialised.connections.size,
            )
            // The materialised graph passes validation with zero errors.
            assertTrue(
                "Materialised ${file.name} must validate cleanly but had: ${materialised.validate()}",
                materialised.validate().isEmpty(),
            )
        }
    }

    @Test
    fun `local-only preset materialises and executes through the engine to Completed`() = runTest {
        // Given — the local_only_qa preset (INPUT → LITE_RT → OUTPUT) is materialised.
        val preset = parseBundled("local_only_qa.json")
        val savedGraph = slot<PipelineGraph>()
        val presetRepository = mockk<PipelinePresetRepository>()
        val pipelineRepository = mockk<PipelineRepository>(relaxed = true)
        coEvery { presetRepository.getPresetById(preset.id) } returns preset
        coEvery { pipelineRepository.savePipeline(capture(savedGraph)) } returns Unit
        val loadUseCase = LoadPipelineFromPresetUseCase(presetRepository, pipelineRepository)

        val loadResult = loadUseCase(preset.id)
        assertTrue("Preset should materialise", loadResult.isSuccess)
        val runnableGraph = savedGraph.captured

        // When — the materialised graph is executed end-to-end.
        val states = engine(sessionId, "What is the tallest mountain?", runnableGraph).toList()

        // Then — execution reaches the terminal Completed state carrying the LLM output.
        val terminal = states.last()
        assertTrue(
            "Expected terminal Completed but got $terminal",
            terminal is AgentOrchestratorState.Completed,
        )
        assertEquals(
            cannedAnswer,
            (terminal as AgentOrchestratorState.Completed).finalResponse,
        )
    }

    private fun parseBundled(fileName: String): PipelinePreset {
        val outcome = PipelinePresetJsonSerializer.parse(
            File(catalogDir, fileName).readText(),
            isBundled = true,
        )
        assertTrue(
            "Bundled preset $fileName did not parse cleanly: $outcome",
            outcome is PipelinePresetImportOutcome.Success,
        )
        return (outcome as PipelinePresetImportOutcome.Success).preset
    }

    private inline fun forEachBundledFile(block: (File) -> Unit) {
        val files = catalogDir.listFiles { _, name -> name.endsWith(".json", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue(
            "No bundled pipeline presets found in ${catalogDir.absolutePath}",
            files.isNotEmpty(),
        )
        files.forEach(block)
    }
}
