package app.knotwork.android.domain.preset

import android.content.Context
import app.knotwork.android.data.engine.KoogClientFactory
import app.knotwork.android.data.engine.KoogCloudLlmModelResolver
import app.knotwork.android.data.local.AgentWorkspaceImpl
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
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.models.SkillImportOutcome
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
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
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.services.NativeMemorySampler
import app.knotwork.android.domain.skillio.SkillJsonSerializer
import app.knotwork.android.domain.usecases.EvaluateIfConditionUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.inject.Provider

/**
 * End-to-end integration test for a **`SKILL` node bound to the bundled Report
 * Writer skill** — the tie between skill execution and the agent's workspace
 * file tools.
 *
 * ```
 * INPUT → SKILL(report_writer) → OUTPUT
 *   skill instruction rendered, LITE_RT inference emits a write_file tool call,
 *   the SKILL executor enforces the allowlist, then dispatches through the real
 *   ToolInvocationGate → real WriteFileExecutor → file on disk.
 * ```
 *
 * The skill is loaded from the **real bundled `report_writer.json`** (allowlist
 * = `[write_file]`). The only stubbed seam is the LLM token stream, scripted to
 * emit the tool call. Two arcs:
 *
 * - an allowed `write_file` call lands a report on disk and the gate's
 *   observation flows out as the node's answer;
 * - an out-of-allowlist `delete_file` call is refused **before** the gate — the
 *   tool is never executed.
 *
 * Pure JVM. `:app:test` runs in the `app/` working directory; the workspace
 * [Context] is mocked to read only [Context.filesDir], the same way
 * [app.knotwork.android.data.local.AgentWorkspaceImplTest] does.
 */
class SkillReportWriterIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val skillDir: File = File("src/main/assets/presets/skills")
    private val sessionId = "skill-report-session"

    private val reportPath = "reports/agenda.md"
    private val reportContent = "# Agenda\n\nDiscuss Q3 roadmap.\n"
    private val writeCall =
        """{"tool":"write_file","arguments":{"path":"$reportPath","content":"# Agenda\n\nDiscuss Q3 roadmap.\n"}}"""
    private val deleteCall = """{"tool":"delete_file","arguments":{"path":"$reportPath"}}"""

    private lateinit var llmEngine: LlmInferenceEngine
    private lateinit var toolRepository: ToolRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var skillRepository: SkillRepository
    private lateinit var workspace: AgentWorkspaceImpl
    private lateinit var writeFileExecutor: WriteFileExecutor
    private lateinit var engine: GraphExecutionEngine

    /** The next scripted LLM answer (flipped per test). */
    private var llmReply: String = ""

    @Before
    fun setup() {
        llmEngine = mockk()
        every { llmEngine.currentModelPath } returns null
        toolRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.workspaceReadTokenBudget } returns flowOf(2_000)
        skillRepository = mockk()
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root

        every { settingsRepository.workspaceMaxFileSizeBytes } returns flowOf(1_000_000L)
        every { settingsRepository.workspaceMaxTotalBytes } returns flowOf(10_000_000L)
        every { settingsRepository.systemPromptPrefix } returns flowOf("")
        // NeverPrompt so the SENSITIVE write_file runs without a live HITL gate.
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.NeverPrompt)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(15)
        every { settingsRepository.runMaxTokens } returns flowOf(1_000_000)
        every { settingsRepository.runMaxTokensBackground } returns flowOf(100_000)
        every { settingsRepository.pipelineMaxNestingDepth } returns flowOf(3)
        every { settingsRepository.verboseMemoryLoggingEnabled } returns flowOf(false)
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)

        workspace = AgentWorkspaceImpl(context, settingsRepository)
        writeFileExecutor = WriteFileExecutor(workspace)

        coEvery { skillRepository.getSkillById("report_writer") } returns loadBundledSkill()

        coEvery { toolRepository.getAvailableTools() } returns listOf(
            AgentTool(WriteFileExecutor.TOOL_NAME, WriteFileExecutor.DESCRIPTION, WriteFileExecutor.PARAMETERS),
        )
        coEvery { toolRepository.getRisk(WriteFileExecutor.TOOL_NAME, any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.executeTool(WriteFileExecutor.TOOL_NAME, any(), any()) } coAnswers {
            writeFileExecutor.execute(secondArg(), thirdArg())
        }

        every { llmEngine.generateResponseStream(any()) } answers { flowOf(llmReply) }
        engine = buildEngine()
    }

    @Test
    fun `report writer skill writes a file through its allowlist`() = runTest {
        llmReply = writeCall

        val states = engine(sessionId, "Write an agenda", skillPipeline()).toList()

        val terminal = states.last()
        assertTrue("Expected Completed but got $terminal", terminal is AgentOrchestratorState.Completed)
        // The file actually lands on disk with the exact content the model emitted.
        val onDisk = File(tempFolder.root, "agent_workspace/$reportPath")
        assertTrue("Report file should exist at ${onDisk.path}", onDisk.isFile)
        assertEquals(reportContent, onDisk.readText())
        // And the tool was dispatched through the real gate exactly once.
        coVerify(exactly = 1) { toolRepository.executeTool(WriteFileExecutor.TOOL_NAME, any(), any()) }
    }

    @Test
    fun `report writer skill refuses a tool outside its allowlist before the gate`() = runTest {
        llmReply = deleteCall

        val states = engine(sessionId, "Delete the agenda", skillPipeline()).toList()

        // The run still completes, but the refusal is the node's output and the
        // out-of-allowlist tool is never executed.
        val terminal = states.last()
        assertTrue("Expected Completed but got $terminal", terminal is AgentOrchestratorState.Completed)
        assertTrue(
            "Final answer should report the refusal",
            (terminal as AgentOrchestratorState.Completed).finalResponse.contains("not in skill"),
        )
        coVerify(exactly = 0) { toolRepository.executeTool("delete_file", any(), any()) }
        // No file was written either.
        assertFalse(File(tempFolder.root, "agent_workspace/$reportPath").exists())
    }

    private fun skillPipeline(): PipelineGraph = PipelineGraph(
        id = "skill-pipe",
        name = "Skill pipe",
        nodes = listOf(
            NodeModel("s_in", NodeType.INPUT, 0f, 0f),
            NodeModel(
                "s_skill",
                NodeType.SKILL,
                10f,
                0f,
                skillId = "report_writer",
                contextConfig = NodeContextConfig(
                    chatHistory = false,
                    originalTask = true,
                    nodeInput = true,
                    longTermMemory = false,
                    toolResults = false,
                ),
            ),
            NodeModel("s_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
        ),
        connections = listOf(
            ConnectionModel("sc1", "s_in", "s_skill"),
            ConnectionModel("sc2", "s_skill", "s_out"),
        ),
    )

    private fun loadBundledSkill(): Skill {
        val outcome = SkillJsonSerializer.parse(File(skillDir, "report_writer.json").readText(), isBundled = true)
        assertTrue("report_writer.json must parse: $outcome", outcome is SkillImportOutcome.Success)
        return (outcome as SkillImportOutcome.Success).skill
    }

    private fun buildEngine(): GraphExecutionEngine {
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val metricsRepository = mockk<MetricsRepository>(relaxed = true)
        val loadModelUseCase = mockk<LoadModelUseCase>()
        val retrieveRelevantMemoryUseCase = mockk<RetrieveRelevantMemoryUseCase>()
        val pipelineRunRepository = mockk<PipelineRunRepository>(relaxed = true)
        val runTraceRepository = mockk<RunTraceRepository>(relaxed = true)
        val pendingInteractionRepository = mockk<PendingInteractionRepository>(relaxed = true)
        val ceilingNotifier = mockk<CeilingNotifier>(relaxed = true)

        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        coEvery { retrieveRelevantMemoryUseCase(any()) } returns emptyList()
        coEvery { retrieveRelevantMemoryUseCase.retrieveScored(any()) } returns emptyList()
        every { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())

        val toolInvocationGate = ToolInvocationGate(
            toolRepository,
            settingsRepository,
            mockk<ApprovalNotifier>(relaxed = true),
            chatRepository,
            mockk<PendingInteractionRepository>(relaxed = true),
            recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
        )
        val toolNodeExecutor = ToolNodeExecutor(
            llmEngine,
            loadModelUseCase,
            toolRepository,
            toolInvocationGate,
            StructuredOutputGate(),
            settingsRepository,
            CloudStructuredInferenceClientFactory { _, _ -> null },
        )
        val liteRtNodeExecutor =
            LiteRtNodeExecutor(
                llmEngine,
                settingsRepository,
                metricsRepository,
                mockk(relaxed = true),
                NativeMemorySampler { 0L },
                loadModelUseCase,
            )
        val cloudLlmNodeExecutor = CloudLlmNodeExecutor(
            settingsRepository,
            mockk<ApiKeyRepository>(relaxed = true),
            metricsRepository,
            mockk<KoogClientFactory>(relaxed = true),
            mockk<KoogCloudLlmModelResolver>(relaxed = true),
            mockk<NetworkActivityTracker>(relaxed = true),
        )
        val skillNodeExecutor = SkillNodeExecutor(
            skillRepository,
            PromptTemplateEngine(),
            emptySet(),
            toolRepository,
            liteRtNodeExecutor,
            cloudLlmNodeExecutor,
            toolInvocationGate,
        )

        val factory = NodeExecutorFactory(
            InputNodeExecutor(),
            OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true)),
            IfConditionNodeExecutor(mockk<EvaluateIfConditionUseCase>(relaxed = true)),
            toolNodeExecutor,
            liteRtNodeExecutor,
            cloudLlmNodeExecutor,
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
                mockk(relaxed = true),
                settingsRepository,
                pipelineRunRepository,
                runTraceRepository,
                Provider { engine },
            ),
            skillNodeExecutor,
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
            mockk<CrashReportingRepository>(relaxed = true),
            mockk<LocalModelRepository>(relaxed = true),
            mockk(relaxed = true),
            pipelineRunRepository,
            runTraceRepository,
            ResolveRunCeilingsUseCase(settingsRepository),
            pendingInteractionRepository,
            ceilingNotifier,
        )
    }
}
