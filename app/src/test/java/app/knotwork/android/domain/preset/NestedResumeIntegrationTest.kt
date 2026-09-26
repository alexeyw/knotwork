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
import app.knotwork.android.domain.models.ClarificationOutcome
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.models.ToolApprovalPolicy
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * End-to-end integration test for **resume across a sub-pipeline boundary** —
 * the nested human-in-the-loop scenario the composition feature has to get
 * right (the scenario behind the nested-HITL fix).
 *
 * ```
 * parent: INPUT → PIPELINE(child) → OUTPUT
 * child:  INPUT → CLARIFICATION → OUTPUT
 * ```
 *
 * A clarifying question raised **inside** the child sub-pipeline parks the
 * whole stack: the child engine flips its run record to `WAITING_CLARIFICATION`,
 * the `PIPELINE` node forwards the suspension upward, and the run ends in
 * [AgentOrchestratorState.SuspendedInBackground] keyed to the **child** run id.
 * When the user answers, resuming the **root** run replays the parent prefix,
 * re-enters the `PIPELINE` node, finds the parked child resumable, and continues
 * it from its checkpoint — the recorded answer flows out without re-asking.
 *
 * The run and trace stores are real in-memory fakes so the resume actually
 * reconstructs its checkpoint from persisted records; only the LLM and the
 * clarification wait are stubbed.
 */
class NestedResumeIntegrationTest {

    private val sessionId = "nested-resume-session"
    private val childPipelineId = "child-pipe"
    private val childRunId = "root::r_pipe::0"
    private val recordedAnswer = "Blue"

    // In-memory persistence so resume reconstructs from real records.
    private val runs = mutableMapOf<String, PipelineRun>()
    private val traceStore = mutableListOf<RunTraceRecord>()

    private lateinit var llmEngine: LlmInferenceEngine
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var pipelineRunRepository: PipelineRunRepository
    private lateinit var runTraceRepository: RunTraceRepository
    private lateinit var pendingInteractionRepository: PendingInteractionRepository
    private lateinit var ceilingNotifier: CeilingNotifier
    private lateinit var clarificationRepository: ClarificationRepository
    private lateinit var engine: GraphExecutionEngine

    @Before
    fun setup() {
        llmEngine = mockk()
        every { llmEngine.currentModelPath } returns null
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.workspaceReadTokenBudget } returns flowOf(2_000)
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"question":"What color?","options":["Blue","Red"]}""")
        every { settingsRepository.pipelineMaxNestingDepth } returns flowOf(3)
        every { settingsRepository.pipelineMaxSteps } returns flowOf(50)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(15)
        every { settingsRepository.runMaxTokens } returns flowOf(1_000_000)
        every { settingsRepository.runMaxTokensBackground } returns flowOf(100_000)
        every { settingsRepository.systemPromptPrefix } returns flowOf("")
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.verboseMemoryLoggingEnabled } returns flowOf(false)
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)

        // The child sub-pipeline is resolvable by its stable id.
        pipelineRepository = mockk(relaxed = true)
        coEvery { pipelineRepository.getPipelineById(childPipelineId) } returns childGraph()

        // In-memory run store (status transitions matter for resume).
        pipelineRunRepository = mockk(relaxed = true)
        coEvery { pipelineRunRepository.getSpend(any()) } returns RunSpend()
        coEvery { pipelineRunRepository.getRun(any()) } answers { runs[firstArg()] }
        coEvery { pipelineRunRepository.createRun(any()) } answers {
            val r = firstArg<PipelineRun>()
            runs[r.id] = r
        }
        coEvery { pipelineRunRepository.markRunning(any(), any(), any()) } answers {
            val id = firstArg<String>()
            runs[id]?.let {
                runs[id] =
                    it.copy(status = PipelineRunStatus.RUNNING, pipelineId = secondArg(), graphContentHash = thirdArg())
            }
        }
        coEvery { pipelineRunRepository.updateStatus(any(), any()) } answers {
            val id = firstArg<String>()
            runs[id]?.let { runs[id] = it.copy(status = secondArg()) }
        }
        coEvery { pipelineRunRepository.finishRun(any(), any(), any()) } answers {
            val id = firstArg<String>()
            runs[id]?.let { runs[id] = it.copy(status = secondArg()) }
        }
        coEvery { pipelineRunRepository.markResumed(any(), any()) } returns true

        // In-memory trace store: append on write, filter by runId on read.
        runTraceRepository = mockk()
        coEvery { runTraceRepository.append(any()) } answers { traceStore += firstArg<RunTraceRecord>() }
        coEvery { runTraceRepository.flush() } returns Unit
        coEvery { runTraceRepository.getTraceForRun(any()) } answers {
            val id = firstArg<String>()
            traceStore.filter { it.runId == id }
        }

        // First phase: no parked record yet, the live wait times out, the park persists.
        pendingInteractionRepository = mockk(relaxed = true)
        ceilingNotifier = mockk(relaxed = true)
        coEvery { pendingInteractionRepository.getForRun(any()) } returns null
        coEvery { pendingInteractionRepository.save(any()) } returns true

        clarificationRepository = mockk()
        coEvery { clarificationRepository.requestAnswer(any()) } returns ClarificationOutcome.TimedOut

        engine = buildEngine()
    }

    @Test
    fun `a clarification inside a sub-pipeline parks the whole stack and resume completes it from the checkpoint`() =
        runTest {
            // --- Step 1: run parks on the nested clarification. ---
            val first = engine(sessionId, "pick a colour", parentGraph(), runId = "root").toList()

            // The whole stack parks: the suspension is forwarded up (a trailing
            // console snapshot may follow it, so scan rather than take the last).
            assertTrue(
                "The whole stack must park, got: ${first.map { it::class.simpleName }}",
                first.any { it is AgentOrchestratorState.SuspendedInBackground },
            )
            assertTrue(
                "No terminal Completed may be emitted while parked",
                first.none { it is AgentOrchestratorState.Completed },
            )
            // The child run is parked (WAITING_CLARIFICATION), keyed under the parent.
            val child = runs[childRunId]
            assertNotNull("Child run must exist", child)
            assertEquals(PipelineRunStatus.WAITING_CLARIFICATION, child!!.status)
            assertEquals("root", child.parentRunId)
            // The pending interaction was saved against the CHILD run id — the crux
            // of nested HITL: the park belongs to the sub-pipeline, not the root.
            coVerify { pendingInteractionRepository.save(match { it.runId == childRunId }) }

            // --- The user answers: the parked record now carries the answer. ---
            coEvery { pendingInteractionRepository.getForRun(childRunId) } returns PendingInteraction(
                runId = childRunId,
                sessionId = sessionId,
                kind = PendingInteractionKind.CLARIFICATION,
                question = "What color?",
                options = listOf("Blue", "Red"),
                answer = recordedAnswer,
                requestedAt = 0L,
            )

            // --- Step 2: resume the ROOT run from its checkpoint. ---
            val parentResume = resumeContextFor("root")
            val second = engine(
                sessionId,
                "pick a colour",
                parentGraph(),
                runId = "root",
                resume = parentResume,
            ).toList()

            val completed = second.filterIsInstance<AgentOrchestratorState.Completed>().lastOrNull()
            assertNotNull("Resume must complete, got: ${second.map { it::class.simpleName }}", completed)
            // The clarification output pairs the recorded question with the recorded
            // answer, and both pass-through OUTPUT nodes echo it up to the final response.
            assertEquals("Q: What color?\nA: $recordedAnswer", completed!!.finalResponse)
        }

    @Test
    fun `a ceiling that binds inside a sub-pipeline parks the whole stack and the grant resumes it`() = runTest {
        // The nested shape has its own failure mode, and it is invisible to the
        // compiler: WAITING_CEILING has to be in the PIPELINE executor's
        // resumable-child set, or the resumed parent finds its child in an
        // "unexpected state" and fails the whole run — after the user answered.
        // Drop WAITING_CEILING from RESUMABLE_CHILD_STATUSES and this test goes
        // red on the resume, not on the park.
        //
        // Two steps: INPUT charges one in the parent, the child's own INPUT
        // charges the second, and the ceiling binds inside the child before it
        // reaches its clarification node.
        every { settingsRepository.pipelineMaxSteps } returns flowOf(2)

        val first = engine(sessionId, "pick a colour", parentGraph(), runId = "root").toList()

        assertTrue(
            "The whole stack must park, got: ${first.map { it::class.simpleName }}",
            first.any { it is AgentOrchestratorState.SuspendedInBackground },
        )
        assertTrue("A pause is not a failure", first.none { it is AgentOrchestratorState.Error })
        val child = runs[childRunId]
        assertEquals(PipelineRunStatus.WAITING_CEILING, child?.status)
        // The park belongs to the sub-pipeline that spent the budget, exactly as
        // a nested clarification's does; the grant it buys lands on the root.
        coVerify {
            pendingInteractionRepository.save(
                match { it.runId == childRunId && it.kind == PendingInteractionKind.CEILING },
            )
        }

        // --- The user continues: the root record now carries one granted portion. ---
        coEvery { pipelineRunRepository.getSpend(any()) } returns RunSpend(stepCeilingExtensions = 1)

        val second = engine(
            sessionId,
            "pick a colour",
            parentGraph(),
            runId = "root",
            resume = resumeContextFor("root"),
        ).toList()

        assertTrue(
            "Resume must not report the child as unexpected, got: " +
                second.filterIsInstance<AgentOrchestratorState.Error>().map { it.message },
            second.none { it is AgentOrchestratorState.Error },
        )
    }

    /** Reconstructs the checkpoint of [runId] from its persisted trace, as the task queue does. */
    private fun resumeContextFor(runId: String): ResumeContext {
        val trace = traceStore.filter { it.runId == runId }
        return ResumeContext(
            records = trace.filterIsInstance<RunTraceRecord.NodeIo>().sortedBy { it.seq },
            memorySnapshot = trace.filterIsInstance<RunTraceRecord.MemorySnapshot>().maxByOrNull { it.seq }?.entries,
            nextSeq = (trace.maxOfOrNull { it.seq } ?: -1L) + 1,
        )
    }

    private fun parentGraph(): PipelineGraph = PipelineGraph(
        id = "root-pipe",
        name = "Root",
        nodes = listOf(
            NodeModel("r_in", NodeType.INPUT, 0f, 0f),
            NodeModel(
                "r_pipe",
                NodeType.PIPELINE,
                10f,
                0f,
                targetPipelineId = childPipelineId,
                contextConfig = NodeContextConfig(false, true, true, false, false),
            ),
            NodeModel("r_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
        ),
        connections = listOf(
            ConnectionModel("rc1", "r_in", "r_pipe"),
            ConnectionModel("rc2", "r_pipe", "r_out"),
        ),
    )

    private fun childGraph(): PipelineGraph = PipelineGraph(
        id = childPipelineId,
        name = "Child",
        nodes = listOf(
            NodeModel("c_in", NodeType.INPUT, 0f, 0f),
            NodeModel(
                "c_clar",
                NodeType.CLARIFICATION,
                10f,
                0f,
                clarificationTimeoutMs = 1L,
                contextConfig = NodeContextConfig(false, true, true, false, false),
            ),
            NodeModel("c_out", NodeType.OUTPUT, 20f, 0f, systemPrompt = null),
        ),
        connections = listOf(
            ConnectionModel("cc1", "c_in", "c_clar"),
            ConnectionModel("cc2", "c_clar", "c_out"),
        ),
    )

    private fun buildEngine(): GraphExecutionEngine {
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val metricsRepository = mockk<MetricsRepository>(relaxed = true)
        val loadModelUseCase = mockk<LoadModelUseCase>()
        val toolRepository = mockk<ToolRepository>(relaxed = true)
        val retrieveRelevantMemoryUseCase = mockk<RetrieveRelevantMemoryUseCase>()

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
                pendingInteractionRepository,
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
                clarificationRepository,
                pendingInteractionRepository,
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
