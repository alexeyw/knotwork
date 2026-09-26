package app.knotwork.android.domain.preset

import android.content.Context
import app.knotwork.android.data.engine.KoogClientFactory
import app.knotwork.android.data.engine.KoogCloudLlmModelResolver
import app.knotwork.android.data.local.AgentWorkspaceImpl
import app.knotwork.android.data.tools.local.SearchTool
import app.knotwork.android.data.tools.local.executors.WriteFileExecutor
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
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelinePresetImportOutcome
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.inject.Provider

/**
 * End-to-end integration test for the bundled `showcase_research_to_file`
 * preset: the "research → file in the user's hands" loop the phase exists to
 * prove.
 *
 * ```
 * showcase_research_to_file.json
 *   ──parse──▶ PipelinePreset
 *   ──LoadPipelineFromPresetUseCase──▶ fresh PipelineGraph (validate clean)
 *   ──GraphExecutionEngine──▶ INPUT → Query Builder → search_tool
 *        → Research Distiller → Synthesis → write_file → OUTPUT → Completed
 * ```
 *
 * Unlike [PipelinePresetIntegrationTest], which proves a *local-only* preset
 * runs to completion against a single canned LLM answer, this test wires a
 * **real [WriteFileExecutor]** over a temp-dir [AgentWorkspaceImpl] so the
 * `write_file` TOOL node actually lands a file on disk. The only stubbed seam
 * is the LLM token stream, scripted per node so the chain produces a
 * deterministic report. Assertions: the report file exists with the exact
 * content the model emitted, and the final OUTPUT references the saved path.
 *
 * Pure JVM. `:app:test` runs in the `app/` working directory, so the relative
 * asset path resolves correctly. The workspace [Context] is mocked the same
 * way [app.knotwork.android.data.local.AgentWorkspaceImplTest] does — only
 * [Context.filesDir] is ever read.
 */
class ShowcaseResearchToFilePresetIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val catalogDir: File = File("src/main/assets/presets/pipelines")
    private val sessionId = "research-to-file-session"

    private lateinit var llmEngine: LlmInferenceEngine
    private lateinit var toolRepository: ToolRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var context: Context
    private lateinit var workspace: AgentWorkspaceImpl
    private lateinit var writeFileExecutor: WriteFileExecutor

    private lateinit var engine: GraphExecutionEngine

    /** The exact report body the scripted Synthesis node emits and write_file persists. */
    private val reportPath = "reports/mount-everest.md"
    private val reportContent = "# Mount Everest\n\nMount Everest is 8849 m tall.\n"

    /** The JSON envelope Synthesis emits and the write_file arg-gen pass echoes back. */
    private val writeFileEnvelope =
        """{"path":"$reportPath","content":"# Mount Everest\n\nMount Everest is 8849 m tall.\n"}"""

    /** Canned Wikipedia extract returned by the (mocked) search_tool. */
    private val searchExtract = "Mount Everest is Earth's highest mountain above sea level, at 8849 metres."

    @Before
    fun setup() {
        llmEngine = mockk()
        every { llmEngine.currentModelPath } returns null
        toolRepository = mockk()
        chatRepository = mockk(relaxed = true)
        settingsRepository = mockk()
        every { settingsRepository.workspaceReadTokenBudget } returns flowOf(2_000)
        context = mockk()
        every { context.filesDir } returns tempFolder.root

        val getContextWindowUseCase = mockk<GetContextWindowUseCase>()
        val retrieveRelevantMemoryUseCase = mockk<RetrieveRelevantMemoryUseCase>()
        val apiKeyRepository = mockk<ApiKeyRepository>(relaxed = true)
        val metricsRepository = mockk<MetricsRepository>(relaxed = true)
        val approvalNotifier = mockk<ApprovalNotifier>(relaxed = true)
        val pendingInteractionRepository = mockk<PendingInteractionRepository>(relaxed = true)
        val ceilingNotifier = mockk<CeilingNotifier>(relaxed = true)
        val clarificationNotifier = mockk<ClarificationNotifier>(relaxed = true)
        val koogClientFactory = mockk<KoogClientFactory>(relaxed = true)
        val cloudLlmModelResolver = mockk<KoogCloudLlmModelResolver>(relaxed = true)
        val networkActivityTracker = mockk<NetworkActivityTracker>(relaxed = true)
        val evaluateIfConditionUseCase = mockk<EvaluateIfConditionUseCase>(relaxed = true)
        val loadModelUseCase = mockk<LoadModelUseCase>()
        val clarificationRepository = mockk<ClarificationRepository>(relaxed = true)
        val crashReportingRepository = mockk<CrashReportingRepository>(relaxed = true)
        val localModelRepository = mockk<LocalModelRepository>(relaxed = true)

        // Real workspace + write_file executor: the file must actually land on disk.
        every { settingsRepository.workspaceMaxFileSizeBytes } returns flowOf(1_000_000L)
        every { settingsRepository.workspaceMaxTotalBytes } returns flowOf(10_000_000L)
        workspace = AgentWorkspaceImpl(context, settingsRepository)
        writeFileExecutor = WriteFileExecutor(workspace)

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

        // Scripted LLM: one canned answer per node / arg-generation pass. The second
        // (temperature) argument is matched so structured-output repair re-inferences are
        // answered too.
        every { llmEngine.generateResponseStream(any(), any(), any()) } answers {
            flowOf(scriptFor(firstArg()))
        }
        coEvery { getContextWindowUseCase(any()) } returns ""
        coEvery { retrieveRelevantMemoryUseCase(any()) } returns emptyList()
        every { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
        every { settingsRepository.systemPromptPrefix } returns flowOf("")
        // NeverPrompt so the SENSITIVE write_file call runs without a HITL gate.
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.NeverPrompt)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(15)
        every { settingsRepository.runMaxTokens } returns flowOf(1_000_000)
        every { settingsRepository.runMaxTokensBackground } returns flowOf(100_000)
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        coEvery { localModelRepository.getActiveModel() } returns null

        // Tool catalogue: the two tools the preset references.
        coEvery { toolRepository.getAvailableTools() } returns listOf(
            AgentTool(SearchTool.TOOL_NAME, "Wikipedia lookup.", "{}", ToolRisk.READ_ONLY),
            AgentTool(WriteFileExecutor.TOOL_NAME, WriteFileExecutor.DESCRIPTION, WriteFileExecutor.PARAMETERS),
        )
        coEvery { toolRepository.getRisk(SearchTool.TOOL_NAME, any()) } returns ToolRisk.READ_ONLY
        coEvery { toolRepository.getRisk(WriteFileExecutor.TOOL_NAME, any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.executeTool(SearchTool.TOOL_NAME, any(), any()) } returns searchExtract
        coEvery { toolRepository.executeTool(WriteFileExecutor.TOOL_NAME, any(), any()) } coAnswers {
            writeFileExecutor.execute(secondArg(), thirdArg())
        }
    }

    @Test
    fun `research-to-file preset materialises and writes a report the OUTPUT references`() = runTest {
        // Given — the bundled preset is materialised into a runnable pipeline.
        val preset = parseBundled("showcase_research_to_file.json")
        val savedGraph = slot<PipelineGraph>()
        val presetRepository = mockk<PipelinePresetRepository>()
        val pipelineRepository = mockk<PipelineRepository>(relaxed = true)
        coEvery { presetRepository.getPresetById(preset.id) } returns preset
        coEvery { pipelineRepository.savePipeline(capture(savedGraph)) } returns Unit
        val loadUseCase = LoadPipelineFromPresetUseCase(presetRepository, pipelineRepository)

        val loadResult = loadUseCase(preset.id)
        assertTrue("Preset should materialise: ${loadResult.exceptionOrNull()}", loadResult.isSuccess)
        val runnableGraph = savedGraph.captured
        assertTrue("Materialised graph must validate cleanly", runnableGraph.validate().isEmpty())

        // When — the pipeline runs end-to-end.
        val states = engine(sessionId, "How tall is Mount Everest?", runnableGraph).toList()

        // Then — execution reaches Completed and the OUTPUT references the saved path.
        val terminal = states.last()
        assertTrue("Expected terminal Completed but got $terminal", terminal is AgentOrchestratorState.Completed)
        val finalResponse = (terminal as AgentOrchestratorState.Completed).finalResponse
        assertTrue("OUTPUT must reference the saved path, was: $finalResponse", finalResponse.contains(reportPath))

        // And — the report file exists on disk with exactly the content the model emitted.
        val onDisk = File(tempFolder.root, "agent_workspace/$reportPath")
        assertTrue("Report file should exist at ${onDisk.path}", onDisk.isFile)
        assertEquals(reportContent, onDisk.readText())
    }

    /**
     * Returns the canned LLM answer for a given prompt, matched on a unique
     * substring of each node's system prompt or the tool argument-generation
     * template. Most specific (tool arg-gen) matches first.
     */
    private fun scriptFor(prompt: String): String = when {
        prompt.contains("TOOL: ${WriteFileExecutor.TOOL_NAME}") -> writeFileEnvelope
        prompt.contains("TOOL: ${SearchTool.TOOL_NAME}") -> "Mount Everest"
        prompt.contains("compact fact digest") -> "Mount Everest - height: 8849 m"
        prompt.contains("Output one JSON object") -> writeFileEnvelope
        prompt.contains("Report saved to") -> "Report saved to $reportPath"
        prompt.contains("search term") -> "Mount Everest"
        else -> "Mount Everest"
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
