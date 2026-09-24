package app.knotwork.android.domain.engine

import android.content.Context
import app.knotwork.android.data.engine.KoogClientFactory
import app.knotwork.android.data.engine.KoogCloudLlmModelResolver
import app.knotwork.android.data.local.AgentWorkspaceImpl
import app.knotwork.android.data.tools.local.executors.DeleteFileExecutor
import app.knotwork.android.data.tools.local.executors.HttpRequestExecutor
import app.knotwork.android.data.tools.local.executors.WriteFileExecutor
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.inject.Provider

/**
 * Cross-cutting security-contour test: drives the security guards of the
 * file-workspace and outbound-HTTP tool surfaces through a **real**
 * [GraphExecutionEngine] (only the LLM token stream is stubbed), proving they
 * hold when wired into an executing pipeline rather than only in their isolated
 * executor unit tests.
 *
 * Three independent guarantees, each via an `INPUT → TOOL → OUTPUT` pipeline:
 *
 *  1. A path-traversal `write_file` argument is rebuffed with a typed-error
 *     observation and the run still completes (the malicious file never lands).
 *  2. An `http_request` to a non-allowlisted host is refused in the observation
 *     without a byte leaving the device, and the run continues.
 *  3. A `delete_file` call (DESTRUCTIVE) raises the HITL gate; with no
 *     confirmation it never executes and the target file survives.
 *
 * Pure JVM: the workspace [Context] is mocked the same way
 * [app.knotwork.android.data.local.AgentWorkspaceImplTest] does, and the
 * `http_request` executor refuses before connecting, so no server is needed.
 */
class PipelineSecurityContourTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sessionId = "security-contour-session"

    private lateinit var llmEngine: LlmInferenceEngine
    private lateinit var toolRepository: ToolRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var context: Context

    private lateinit var workspace: AgentWorkspaceImpl
    private lateinit var writeFileExecutor: WriteFileExecutor
    private lateinit var deleteFileExecutor: DeleteFileExecutor
    private lateinit var httpRequestExecutor: HttpRequestExecutor

    private lateinit var engine: GraphExecutionEngine

    /** The JSON args the scripted arg-generation pass emits per tool. */
    private var writeFileArgs = """{"path":"reports/ok.md","content":"hi"}"""
    private var httpArgs = """{"method":"GET","url":"https://example.org/"}"""
    private var deleteArgs = """{"path":"keep.txt"}"""

    @Before
    fun setup() {
        llmEngine = mockk()
        every { llmEngine.currentModelPath } returns null
        toolRepository = mockk()
        chatRepository = mockk(relaxed = true)
        settingsRepository = mockk()
        context = mockk()
        every { context.filesDir } returns tempFolder.root

        val getContextWindowUseCase = mockk<GetContextWindowUseCase>()
        val retrieveRelevantMemoryUseCase = mockk<RetrieveRelevantMemoryUseCase>()
        val apiKeyRepository = mockk<ApiKeyRepository>()
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

        every { settingsRepository.workspaceMaxFileSizeBytes } returns flowOf(1_000_000L)
        every { settingsRepository.workspaceMaxTotalBytes } returns flowOf(10_000_000L)
        workspace = AgentWorkspaceImpl(context, settingsRepository)
        writeFileExecutor = WriteFileExecutor(workspace)
        deleteFileExecutor = spyk(DeleteFileExecutor(workspace))

        every { apiKeyRepository.getOpenAIKey() } returns flowOf(null)
        every { apiKeyRepository.getAnthropicKey() } returns flowOf(null)
        every { apiKeyRepository.getGoogleKey() } returns flowOf(null)
        every { apiKeyRepository.getDeepSeekKey() } returns flowOf(null)
        every { settingsRepository.httpToolMaxResponseBytes } returns flowOf(100_000L)
        // Allowlist contains a different domain so the test target is rejected as off-list.
        every { settingsRepository.allowedHttpDomains } returns flowOf(listOf("example.org"))
        httpRequestExecutor =
            HttpRequestExecutor(OkHttpClient(), settingsRepository, apiKeyRepository, networkActivityTracker)

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

        // The arg-generation pass returns the per-tool JSON; everything else is irrelevant.
        every { llmEngine.generateResponseStream(any()) } answers {
            val prompt = firstArg<String>()
            flowOf(
                when {
                    prompt.contains("TOOL: ${WriteFileExecutor.TOOL_NAME}") -> writeFileArgs
                    prompt.contains("TOOL: ${HttpRequestExecutor.TOOL_NAME}") -> httpArgs
                    prompt.contains("TOOL: ${DeleteFileExecutor.TOOL_NAME}") -> deleteArgs
                    else -> ""
                },
            )
        }
        coEvery { getContextWindowUseCase(any()) } returns ""
        coEvery { retrieveRelevantMemoryUseCase(any()) } returns emptyList()
        every { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
        every { settingsRepository.systemPromptPrefix } returns flowOf("")
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(15)
        every { settingsRepository.runMaxTokens } returns flowOf(1_000_000)
        every { settingsRepository.runMaxTokensBackground } returns flowOf(100_000)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(1_000L)
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        coEvery { localModelRepository.getActiveModel() } returns null

        coEvery { toolRepository.getAvailableTools() } returns listOf(
            AgentTool(WriteFileExecutor.TOOL_NAME, WriteFileExecutor.DESCRIPTION, WriteFileExecutor.PARAMETERS),
            AgentTool(HttpRequestExecutor.TOOL_NAME, "Outbound HTTP.", "{}"),
            AgentTool(DeleteFileExecutor.TOOL_NAME, DeleteFileExecutor.DESCRIPTION, DeleteFileExecutor.PARAMETERS),
        )
        coEvery { toolRepository.getRisk(WriteFileExecutor.TOOL_NAME, any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.getRisk(HttpRequestExecutor.TOOL_NAME, any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.getRisk(DeleteFileExecutor.TOOL_NAME, any()) } returns ToolRisk.DESTRUCTIVE
        coEvery { toolRepository.executeTool(WriteFileExecutor.TOOL_NAME, any(), any()) } coAnswers {
            writeFileExecutor.execute(secondArg(), thirdArg())
        }
        coEvery { toolRepository.executeTool(HttpRequestExecutor.TOOL_NAME, any(), any()) } coAnswers {
            httpRequestExecutor.execute(secondArg(), thirdArg())
        }
        coEvery { toolRepository.executeTool(DeleteFileExecutor.TOOL_NAME, any(), any()) } coAnswers {
            deleteFileExecutor.execute(secondArg(), thirdArg())
        }
    }

    @Test
    fun `path-traversal write argument is rebuffed with a typed error and the run completes`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.NeverPrompt)
        writeFileArgs = """{"path":"../escape.txt","content":"pwned"}"""

        val states = engine(sessionId, "write a file", toolGraph(WriteFileExecutor.TOOL_NAME)).toList()

        val terminal = states.last()
        assertTrue(
            "Run must complete despite the rejected write, was $terminal",
            terminal is AgentOrchestratorState.Completed,
        )
        assertTrue(
            "Observation must carry the typed path-traversal refusal, was: ${(terminal as AgentOrchestratorState.Completed).finalResponse}",
            terminal.finalResponse.contains("is outside the workspace"),
        )
        assertFalse(
            "The traversal target must never be written outside the workspace",
            File(tempFolder.root, "escape.txt").exists(),
        )
    }

    @Test
    fun `http request to a non-allowlisted host is refused in the observation and the run continues`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.NeverPrompt)
        httpArgs = """{"method":"GET","url":"https://blocked.example.com/data"}"""

        val states = engine(sessionId, "fetch a url", toolGraph(HttpRequestExecutor.TOOL_NAME)).toList()

        val terminal = states.last()
        assertTrue("Run must continue past the refusal, was $terminal", terminal is AgentOrchestratorState.Completed)
        assertTrue(
            "Observation must carry the allowlist refusal, was: ${(terminal as AgentOrchestratorState.Completed).finalResponse}",
            terminal.finalResponse.contains("not in the allowlist"),
        )
    }

    @Test
    fun `destructive delete without confirmation raises the HITL gate and never executes`() = runTest {
        // Default policy gates SENSITIVE/DESTRUCTIVE; no confirmation is ever delivered.
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        workspace.writeText("keep.txt", "precious")
        deleteArgs = """{"path":"keep.txt"}"""

        val states = engine(sessionId, "delete the file", toolGraph(DeleteFileExecutor.TOOL_NAME)).toList()

        val gate = states.filterIsInstance<AgentOrchestratorState.WaitingForApproval>().firstOrNull()
        assertNotNull("A DESTRUCTIVE delete must raise the HITL approval gate", gate)
        assertTrue("The gate must carry DESTRUCTIVE risk", gate!!.risk == ToolRisk.DESTRUCTIVE)
        coVerify(exactly = 0) { deleteFileExecutor.execute(any(), any()) }
        assertTrue(
            "The target file must survive an unconfirmed delete",
            File(tempFolder.root, "agent_workspace/keep.txt").isFile,
        )
    }

    /** Minimal `INPUT → TOOL(toolName) → OUTPUT` graph; OUTPUT echoes the tool observation. */
    private fun toolGraph(toolName: String): PipelineGraph = PipelineGraph(
        id = "security-graph",
        name = "Security graph",
        nodes = listOf(
            NodeModel(
                id = "in",
                type = NodeType.INPUT,
                x = 0f,
                y = 0f,
                systemPrompt = null,
                contextConfig = NodeContextConfig.defaultForType(NodeType.INPUT),
            ),
            NodeModel(
                id = "tool",
                type = NodeType.TOOL,
                x = 100f,
                y = 0f,
                toolName = toolName,
                systemPrompt = null,
                contextConfig = NodeContextConfig.defaultForType(NodeType.TOOL),
            ),
            NodeModel(
                id = "out",
                type = NodeType.OUTPUT,
                x = 200f,
                y = 0f,
                systemPrompt = null,
                contextConfig = NodeContextConfig.defaultForType(NodeType.OUTPUT),
            ),
        ),
        connections = listOf(
            ConnectionModel(id = "c1", sourceNodeId = "in", targetNodeId = "tool"),
            ConnectionModel(id = "c2", sourceNodeId = "tool", targetNodeId = "out"),
        ),
    )
}
