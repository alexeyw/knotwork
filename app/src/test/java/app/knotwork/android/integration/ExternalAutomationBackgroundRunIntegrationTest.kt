package app.knotwork.android.integration

import androidx.room.Room
import app.knotwork.android.data.engine.KoogClientFactory
import app.knotwork.android.data.engine.KoogCloudLlmModelResolver
import app.knotwork.android.data.engine.TaskQueueManagerImpl
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.models.ChatSessionEntity
import app.knotwork.android.data.repositories.ExternalAutomationJournalRepositoryImpl
import app.knotwork.android.data.repositories.PendingInteractionRepositoryImpl
import app.knotwork.android.data.repositories.PipelineRunRepositoryImpl
import app.knotwork.android.data.repositories.RunTraceRepositoryImpl
import app.knotwork.android.data.repositories.TriggerJournalRepositoryImpl
import app.knotwork.android.domain.constants.ExternalAutomationContract
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
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.ExternalAutomationInvocation
import app.knotwork.android.domain.models.ExternalAutomationStatus
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.services.ExternalAutomationCallbackNotifier
import app.knotwork.android.domain.services.NativeMemorySampler
import app.knotwork.android.domain.services.ScheduledTaskConstraints
import app.knotwork.android.domain.services.TaskScheduler
import app.knotwork.android.domain.usecases.AgentOrchestratorUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.ParkedRunResumer
import app.knotwork.android.domain.usecases.PendingSubmissionOutcome
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.ResumePipelineRunUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import app.knotwork.android.domain.usecases.SubmitApprovalDecisionUseCase
import app.knotwork.android.domain.usecases.automation.AuthorizeExternalAutomationRequestUseCase
import app.knotwork.android.domain.usecases.automation.HandleExternalAutomationRequestUseCase
import app.knotwork.android.domain.usecases.automation.ParseExternalAutomationRequestUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider

/**
 * End-to-end JVM integration test of the **external request → background run →
 * callback** arc: the entry point another app on the device broadcasts to.
 *
 * It exists to prove the guarantee the published contract makes in one sentence —
 * *"an external call is not a form of approval"* — against the real engine rather
 * than against a mock of it. A unit test can show that the use case calls
 * `scheduleOneTime`; only this can show that what comes out the other end is a run
 * that still stops and asks, still refuses a blocked tool, and still tells the
 * caller how it ended **after** a human answered hours later.
 *
 * The process wiring is the same as [TriggerBackgroundRunIntegrationTest]'s, with
 * the trigger-side collaborators swapped for the external ones: a real Room-backed
 * request journal (so the admission row and its terminal settlement are the real
 * two-phase write) and a spy callback notifier standing in for the outbound
 * broadcast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExternalAutomationBackgroundRunIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var database: AppDatabase

    private lateinit var chatRepository: ChatRepository
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var toolRepository: ToolRepository

    private val blockDestructiveTools = MutableStateFlow(false)

    private val toolGraph = PipelineGraph(
        id = GRAPH_ID,
        name = BOUND_NAME,
        nodes = listOf(
            NodeModel("input_1", NodeType.INPUT, 0f, 0f),
            NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f, systemPrompt = STEP_MARKER),
            NodeModel("tool_1", NodeType.TOOL, 0f, 0f, toolName = TOOL_NAME),
            NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = FINAL_MARKER),
        ),
        connections = listOf(
            ConnectionModel("c1", "input_1", "llm_1"),
            ConnectionModel("c2", "llm_1", "tool_1"),
            ConnectionModel("c3", "tool_1", "output_1"),
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // The persisted trace carries a foreign key into `chat_sessions`; chat
        // persistence is mocked, so seed the surface's accumulating session row the
        // way the worker would have on the first external run.
        runBlocking {
            database.chatDao().insertSession(
                ChatSessionEntity(id = SESSION_ID, name = "External automation", updatedAt = 0L),
            )
        }

        chatRepository = mockk(relaxed = true)
        every { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
        coEvery { chatRepository.sessionExists(SESSION_ID) } returns true

        settingsRepository = mockk()
        every { settingsRepository.verboseMemoryLoggingEnabled } returns flowOf(false)
        every { settingsRepository.chatHistoryCompressionEnabled } returns flowOf(false)
        every { settingsRepository.chatHistoryCompressionThresholdTokens } returns flowOf(3_500)
        every { settingsRepository.chatHistoryLiveWindowSize } returns flowOf(10)
        every { settingsRepository.systemPromptPrefix } returns flowOf("")
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns blockDestructiveTools
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(15)
        every { settingsRepository.runMaxTokens } returns flowOf(1_000_000)
        every { settingsRepository.runMaxTokensBackground } returns flowOf(100_000)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(LIVE_WAIT_TIMEOUT_MS)
        every { settingsRepository.resumeMaxAgeHours } returns flowOf(48)
        every { settingsRepository.backgroundApprovalWindowHours } returns flowOf(24)
        every { settingsRepository.defaultPipelineId } returns flowOf(GRAPH_ID)
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)
        // The contract is on and bound to the pipeline under test.
        every { settingsRepository.externalAutomationEnabled } returns flowOf(true)
        every { settingsRepository.externalAutomationPipelineId } returns flowOf(GRAPH_ID)

        toolRepository = mockk(relaxed = true)
        coEvery { toolRepository.getAvailableTools() } returns
            listOf(AgentTool(TOOL_NAME, "Searches the web", "{}"))
        coEvery { toolRepository.getRisk(TOOL_NAME, any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.executeTool(TOOL_NAME, any(), any()) } returns TOOL_RESULT

        pipelineRepository = mockk()
        every { pipelineRepository.getAllPipelines() } returns flowOf(listOf(toolGraph))
        coEvery { pipelineRepository.getPipelineById(GRAPH_ID) } returns toolGraph
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    /** The real use case wired over this process's bridging scheduler. */
    private fun buildHandler(process: ProcessHarness) = HandleExternalAutomationRequestUseCase(
        parseRequest = ParseExternalAutomationRequestUseCase(),
        authorizeRequest = AuthorizeExternalAutomationRequestUseCase(),
        settingsRepository = settingsRepository,
        pipelineRepository = pipelineRepository,
        journal = process.externalJournal,
        taskScheduler = process.scheduler,
    )

    /** A well-formed request naming the bound pipeline. */
    private fun request() = ExternalAutomationInvocation(
        action = ExternalAutomationContract.ACTION_RUN_PIPELINE,
        extras = mapOf(
            ExternalAutomationContract.EXTRA_PIPELINE_ID to GRAPH_ID,
            ExternalAutomationContract.EXTRA_PROMPT to USER_PROMPT,
            ExternalAutomationContract.EXTRA_REQUEST_ID to REQUEST_ID,
            ExternalAutomationContract.EXTRA_RETURN_PACKAGE to CALLER_PACKAGE,
        ),
    )

    @Test
    fun `a sensitive tool in an external run still parks on the HITL gate, and the caller hears only after approval`() =
        testScope.runTest {
            val process = buildProcess(scriptedLlmEngine())
            val handler = buildHandler(process)

            val status = handler(request(), attestedSenderPackage = null, nowMillis = NOW)

            assertEquals(ExternalAutomationStatus.Accepted, status)
            assertEquals(RunOrigin.EXTERNAL, process.scheduler.lastOrigin)
            val runId = requireNotNull(process.scheduler.lastRunId)

            // ── Nobody is watching: the live wait expires and the run parks ──
            // Waiting on all three is waiting for one consistent state: the gate
            // retires the live holder before writing the durable record, so
            // asserting only the status would race that ordering.
            awaitUntil("run parked WAITING_APPROVAL with the live gate retired") {
                process.runRepository.getRun(runId)?.status == PipelineRunStatus.WAITING_APPROVAL &&
                    process.pendingRepository.getForRun(runId) != null &&
                    process.taskQueueManager.pendingApproval(SESSION_ID) == null
            }
            val parked = process.pendingRepository.getForRun(runId)
            assertNotNull("the external run parked a durable approval record", parked)
            assertEquals(PendingInteractionKind.APPROVAL, parked!!.kind)
            assertEquals(TOOL_NAME, parked.toolName)
            // The external call admitted the run; it did not approve the tool.
            coVerify(exactly = 0) { toolRepository.executeTool(TOOL_NAME, any(), any()) }
            // And nothing terminal has been reported to the caller yet.
            assertTrue(
                "the caller must hear nothing while the run is parked",
                process.externalCallback.calls.isEmpty(),
            )

            // ── A human approves from the notification, hours later ──
            val outcome = SubmitApprovalDecisionUseCase(
                process.taskQueueManager,
                process.pendingRepository,
                process.parkedRunResumer,
            )(SESSION_ID, process.parkedRequestId(runId), isApproved = true)
            assertEquals(PendingSubmissionOutcome.Resumed, outcome)
            awaitUntil("run COMPLETED after the approval") {
                process.runRepository.getRun(runId)?.status == PipelineRunStatus.COMPLETED
            }
            // Settle-then-notify: `finishRun` writes the terminal status first and
            // runs its observers after, so waiting on the status alone would race
            // the very hook under test.
            awaitUntil("the request journal settled the terminal status") {
                process.externalJournal.findByRunId(runId)?.status == ExternalAutomationStatus.Completed
            }

            // ── The caller is told, from the run-termination seam ──
            // This is the case `AgentWorker.announceOutcome` structurally cannot
            // cover: parking stopped the worker, and the resume came back through
            // the in-process queue with no worker left to announce anything.
            val notification = awaitSingleNotification(process.externalCallback)
            assertEquals(CALLER_PACKAGE, notification.returnPackage)
            assertEquals(ExternalAutomationContract.ACTION_RUN_RESULT, notification.returnAction)
            assertEquals(REQUEST_ID, notification.requestId)
            assertEquals(ExternalAutomationStatus.Completed, notification.status)
            assertEquals(
                ExternalAutomationStatus.Completed,
                process.externalJournal.findByRunId(runId)?.status,
            )

            process.taskQueueManager.scope.cancel()
        }

    @Test
    fun `a destructive tool in an external run is blocked when the user blocked destructive tools`() =
        testScope.runTest {
            // The block is a user stance about the device, not about a surface. An
            // external caller must not be able to walk past it.
            blockDestructiveTools.value = true
            coEvery { toolRepository.getRisk(TOOL_NAME, any()) } returns ToolRisk.DESTRUCTIVE
            val process = buildProcess(scriptedLlmEngine())
            val handler = buildHandler(process)

            handler(request(), attestedSenderPackage = null, nowMillis = NOW)
            val runId = requireNotNull(process.scheduler.lastRunId)

            awaitUntil("run reached a terminal status") {
                process.runRepository.getRun(runId)?.status?.isTerminal == true
            }

            // The tool never ran, and the run never parked to ask — a blocked
            // destructive tool is refused outright rather than offered for approval.
            coVerify(exactly = 0) { toolRepository.executeTool(TOOL_NAME, any(), any()) }
            assertNull(process.pendingRepository.getForRun(runId))

            process.taskQueueManager.scope.cancel()
        }

    @Test
    fun `a per-tool risk override raises the gate for a tool that would otherwise be read-only`() = testScope.runTest {
        // `getRisk` is the single source of truth for the gate, overrides
        // included. An external run consults exactly the same source.
        coEvery { toolRepository.getRisk(TOOL_NAME, any()) } returns ToolRisk.DESTRUCTIVE
        val process = buildProcess(scriptedLlmEngine())
        val handler = buildHandler(process)

        handler(request(), attestedSenderPackage = null, nowMillis = NOW)
        val runId = requireNotNull(process.scheduler.lastRunId)

        // Waiting on all three is waiting for one consistent state: the gate
        // retires the live holder before writing the durable record, so
        // asserting only the status would race that ordering.
        awaitUntil("run parked WAITING_APPROVAL with the live gate retired") {
            process.runRepository.getRun(runId)?.status == PipelineRunStatus.WAITING_APPROVAL &&
                process.pendingRepository.getForRun(runId) != null &&
                process.taskQueueManager.pendingApproval(SESSION_ID) == null
        }
        assertEquals(ToolRisk.DESTRUCTIVE, process.pendingRepository.getForRun(runId)?.risk)
        coVerify(exactly = 0) { toolRepository.executeTool(TOOL_NAME, any(), any()) }

        process.taskQueueManager.scope.cancel()
    }

    @Test
    fun `a denied approval on an external run still reports exactly one terminal status`() = testScope.runTest {
        val process = buildProcess(scriptedLlmEngine())
        val handler = buildHandler(process)

        handler(request(), attestedSenderPackage = null, nowMillis = NOW)
        val runId = requireNotNull(process.scheduler.lastRunId)
        // Waiting on all three is waiting for one consistent state: the gate
        // retires the live holder before writing the durable record, so
        // asserting only the status would race that ordering.
        awaitUntil("run parked WAITING_APPROVAL with the live gate retired") {
            process.runRepository.getRun(runId)?.status == PipelineRunStatus.WAITING_APPROVAL &&
                process.pendingRepository.getForRun(runId) != null &&
                process.taskQueueManager.pendingApproval(SESSION_ID) == null
        }

        SubmitApprovalDecisionUseCase(
            process.taskQueueManager,
            process.pendingRepository,
            process.parkedRunResumer,
        )(SESSION_ID, process.parkedRequestId(runId), isApproved = false)
        awaitUntil("the request journal settled the terminal status") {
            process.externalJournal.findByRunId(runId)?.status.let {
                it == ExternalAutomationStatus.Completed || it == ExternalAutomationStatus.Failed
            }
        }

        // A denial continues the run with a refusal recorded, so the run itself
        // completes; either way the caller is told exactly once and never left
        // waiting on a callback that does not come.
        val notification = awaitSingleNotification(process.externalCallback)
        assertEquals(CALLER_PACKAGE, notification.returnPackage)
        assertEquals(REQUEST_ID, notification.requestId)
        // The row and the callback have to agree. The journal entry is what makes
        // the callback once-only, so a callback carrying a different status than
        // the one recorded would leave the caller and the device with different
        // accounts of the same request — and this test is named for the status,
        // not just for the count.
        assertEquals(process.externalJournal.findByRunId(runId)?.status, notification.status)
        coVerify(exactly = 0) { toolRepository.executeTool(TOOL_NAME, any(), any()) }

        process.taskQueueManager.scope.cancel()
    }

    private fun buildProcess(llmEngine: LlmInferenceEngine): ProcessHarness {
        val telemetry = mockk<UsageTelemetryRepository>(relaxed = true)
        coEvery { telemetry.isEnabled() } returns false
        // Real, Room-backed trigger journal shared by the fire path (which opens
        // the Fired row) and the run store (which attributes the terminal outcome
        // back onto it) — so the two-phase correlation is exercised end-to-end.
        val triggerJournal = TriggerJournalRepositoryImpl(database.triggerJournalDao()).apply {
            dispatcher = testDispatcher
        }
        // Real, Room-backed external journal shared by the admission path (which
        // opens the Accepted row) and the run store (which settles the terminal
        // status onto it) — the two-phase correlation end to end. The callback
        // notifier is a spy: what a third-party caller is actually told is the
        // observable this test exists to pin.
        val externalJournal = ExternalAutomationJournalRepositoryImpl(
            database.externalAutomationJournalDao(),
        ).apply { dispatcher = testDispatcher }
        val externalCallback = RecordingCallbackNotifier()
        val runRepository = PipelineRunRepositoryImpl(
            database.pipelineRunDao(),
            telemetry,
            triggerJournal,
            externalJournal,
            externalCallback,
            mockk(relaxed = true),
        )
        val traceRepository = RunTraceRepositoryImpl(database.traceStepDao())
            .apply { dispatcher = testDispatcher }
        val pendingRepository = PendingInteractionRepositoryImpl(database.pendingInteractionDao())
        val approvalNotifier = mockk<ApprovalNotifier>(relaxed = true)
        val clarificationNotifier = mockk<ClarificationNotifier>(relaxed = true)
        val loadModelUseCase = mockk<LoadModelUseCase>()
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        val retrieveRelevantMemoryUseCase = mockk<RetrieveRelevantMemoryUseCase>()
        coEvery { retrieveRelevantMemoryUseCase(any()) } returns emptyList()
        coEvery { retrieveRelevantMemoryUseCase.retrieveScored(any()) } returns emptyList()

        // Real HITL reporter over the same journal and run store: the park →
        // approve → resume path must leave the gate visible on the fired row,
        // which is the whole point of the columns.
        val recordTriggerHitlEvent = RecordTriggerHitlEventUseCase(triggerJournal, runRepository)
        val toolInvocationGate = ToolInvocationGate(
            toolRepository,
            settingsRepository,
            approvalNotifier,
            chatRepository,
            pendingRepository,
            recordTriggerHitlEvent = recordTriggerHitlEvent,
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
        val nodeExecutorFactory = NodeExecutorFactory(
            InputNodeExecutor(),
            OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true)),
            IfConditionNodeExecutor(mockk()),
            toolNodeExecutor,
            LiteRtNodeExecutor(
                llmEngine,
                settingsRepository,
                mockk(relaxed = true),
                mockk(relaxed = true),
                NativeMemorySampler { 0L },
                loadModelUseCase,
            ),
            CloudLlmNodeExecutor(
                settingsRepository,
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk<KoogClientFactory>(),
                mockk<KoogCloudLlmModelResolver>(),
                mockk(relaxed = true),
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
                mockk(),
                pendingRepository,
                clarificationNotifier,
                recordTriggerHitlEvent = recordTriggerHitlEvent,
            ),
            PipelineNodeExecutor(
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                Provider { mockk(relaxed = true) },
            ),
            mockk<SkillNodeExecutor>(relaxed = true),
        )
        val engine = GraphExecutionEngine(
            nodeExecutorFactory,
            toolNodeExecutor,
            chatRepository,
            settingsRepository,
            mockk(relaxed = true),
            PromptTemplateEngine(),
            emptySet(),
            NodeContextBuilder(),
            ChatHistoryWindowPlanner(),
            retrieveRelevantMemoryUseCase,
            mockk(relaxed = true),
            mockk(relaxed = true) {
                coEvery { getActiveModel() } returns null
            },
            mockk(relaxed = true),
            runRepository,
            traceRepository,
            ResolveRunCeilingsUseCase(settingsRepository),
            pendingRepository,
            mockk<CeilingNotifier>(relaxed = true),
        )
        val taskQueueManager = TaskQueueManagerImpl(
            chatRepository = chatRepository,
            pipelineRepository = pipelineRepository,
            settingsRepository = settingsRepository,
            graphExecutionEngine = engine,
            pipelineRunRepository = runRepository,
            runTraceRepository = traceRepository,
            attachmentStore = mockk(relaxed = true),
        ).apply {
            dispatcher = testDispatcher
            // The no-progress valve is disabled here: this harness advances a
            // virtual clock while the run really progresses on `Dispatchers.IO`
            // threads the scheduler cannot see, so the window would elapse on a
            // healthy run. The valve itself is covered in
            // `TaskQueueManagerImplTest`, where nothing races the clock.
            silenceTimeoutMs = 0
        }

        val resumeRun = ResumePipelineRunUseCase(
            runRepository,
            pipelineRepository,
            settingsRepository,
            pendingRepository,
            taskQueueManager,
        )
        val parkedRunResumer = ParkedRunResumer(
            pendingRepository,
            runRepository,
            settingsRepository,
            approvalNotifier,
            clarificationNotifier,
            mockk<CeilingNotifier>(relaxed = true),
            resumeRun,
            recordTriggerHitlEvent,
        )
        return ProcessHarness(
            scheduler = QueueBridgeScheduler(AgentOrchestratorUseCase(taskQueueManager)),
            taskQueueManager = taskQueueManager,
            runRepository = runRepository,
            pendingRepository = pendingRepository,
            approvalNotifier = approvalNotifier,
            parkedRunResumer = parkedRunResumer,
            externalJournal = externalJournal,
            externalCallback = externalCallback,
        )
    }

    /**
     * Drives the virtual-time scheduler until [predicate] observes the expected
     * database state, yielding to real worker threads between passes — the
     * repositories hop through real `Dispatchers.IO`, whose completions the test
     * scheduler cannot see.
     */
    private suspend fun TestScope.awaitUntil(what: String, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for: $what" }
            advanceUntilIdle()
            withContext(Dispatchers.Default) { delay(REAL_POLL_DELAY_MS) }
        }
    }

    /**
     * Awaits the caller-facing notification **itself**, then asserts it happened
     * exactly once, and returns it.
     *
     * Why the notification rather than the journal row it follows: the production
     * order in `PipelineRunRepositoryImpl.recordExternalAutomationOutcome` is
     * `recordOutcome` (settle the journal) *then* `notifyOutcome` (tell the
     * caller), because the settlement is the once-only guard — a run can reach a
     * terminal status twice, `INTERRUPTED` being terminal *and* resumable, and the
     * second settlement is refused. `recordOutcome` suspends and [awaitUntil] hops
     * to `Dispatchers.Default` between passes, so a poller watching the journal can
     * legitimately observe the settled row while the observer has not yet reached
     * the notify. Asserting the callback at that moment is a race, and it is the
     * one that went red on CI.
     *
     * Waiting on the journal is not wrong, it is one step short: it settles the
     * *precondition* of the thing under test. So the journal wait stays where it
     * asserts a state worth asserting, and this waits for the observable.
     *
     * The "exactly once" half cannot be awaited — it is a claim about a call that
     * must never arrive. It is checked after a bounded settle, which proves "no
     * second notification within the window" rather than "never"; that is the same
     * guarantee the `verify(exactly = 1)` it replaces provided, now without the
     * race on the first one.
     */
    private suspend fun TestScope.awaitSingleNotification(
        callback: RecordingCallbackNotifier,
    ): RecordingCallbackNotifier.Call {
        awaitUntil("the caller was told the terminal status") { callback.calls.isNotEmpty() }
        repeat(SETTLE_PASSES) {
            advanceUntilIdle()
            withContext(Dispatchers.Default) { delay(REAL_POLL_DELAY_MS) }
        }
        assertEquals(
            "the caller must be told exactly once: ${callback.calls}",
            1,
            callback.calls.size,
        )
        return callback.calls.first()
    }

    /**
     * Recording stand-in for the outbound broadcast — the observable this test
     * exists to pin, since what a third-party caller is actually told is the whole
     * contract.
     *
     * A MockK spy cannot serve here. `verify` can only ask whether a call has
     * *already* happened, which forces the test to guess that the moment has
     * arrived; recording makes the notification something the test can **await**.
     * See [awaitSingleNotification] for the race that distinction removes.
     *
     * Backed by a [CopyOnWriteArrayList] because the notification arrives on a real
     * worker thread — the repositories hop through `Dispatchers.IO` — while the
     * polling coroutine reads the list from `Dispatchers.Default`.
     */
    private class RecordingCallbackNotifier : ExternalAutomationCallbackNotifier {

        private val recorded = CopyOnWriteArrayList<Call>()

        /** Every notification sent so far, in order. */
        val calls: List<Call> get() = recorded

        override suspend fun notifyOutcome(
            returnPackage: String,
            returnAction: String,
            requestId: String,
            status: ExternalAutomationStatus,
        ) {
            recorded += Call(returnPackage, returnAction, requestId, status)
        }

        /**
         * One recorded callback.
         *
         * @property returnPackage Package the broadcast was addressed to.
         * @property returnAction Broadcast action it was sent with.
         * @property requestId The caller's correlation id, echoed back.
         * @property status The terminal status reported.
         */
        data class Call(
            val returnPackage: String,
            val returnAction: String,
            val requestId: String,
            val status: ExternalAutomationStatus,
        )
    }

    /**
     * LLM double dispatching on the node's system-prompt marker embedded in the
     * rendered prompt, so the script stays correct no matter how many times a node
     * re-runs (checkpoint replay vs. live re-execution).
     */
    private fun scriptedLlmEngine(): LlmInferenceEngine = mockk {
        every { currentModelPath } returns null
        every { generateResponseStream(any()) } answers {
            val prompt = firstArg<String>()
            flowOf(
                when {
                    prompt.contains(STEP_MARKER) -> STEP_ANSWER
                    prompt.contains(FINAL_MARKER) -> FINAL_ANSWER
                    else -> TOOL_CALL_JSON
                },
            )
        }
    }

    /**
     * A [TaskScheduler] that forwards a one-time schedule straight into the live
     * task queue (the JVM stand-in for `WorkManagerTaskScheduler` -> `AgentWorker`),
     * capturing the routed arguments so the test can assert how the external entry
     * point enqueued its run.
     *
     * @property orchestrator The queue entry point a real `AgentWorker` would call.
     */
    private class QueueBridgeScheduler(private val orchestrator: AgentOrchestratorUseCase) : TaskScheduler {
        var lastRunId: String? = null
        var lastOrigin: RunOrigin? = null

        override fun scheduleOneTime(
            prompt: String,
            delayMinutes: Long,
            sessionId: String?,
            constraints: ScheduledTaskConstraints,
            pipelineId: String?,
            origin: RunOrigin,
            runId: String?,
        ) {
            lastOrigin = origin
            // Honour the pre-minted id exactly as `AgentWorker` does, so the run the
            // queue creates shares identity with the request's journal row.
            lastRunId = orchestrator.enqueueScheduled(
                sessionId = requireNotNull(sessionId) { "the external surface threads its accumulating session" },
                userPrompt = prompt,
                pipelineId = pipelineId,
                origin = origin,
                runId = runId,
            )
        }

        override fun cancelAllScheduled() = Unit

        override fun schedulePeriodic(
            prompt: String,
            intervalHours: Long,
            sessionId: String?,
            constraints: ScheduledTaskConstraints,
        ): Unit = error("the external surface only ever schedules one-time work")
    }

    /**
     * The per-process singleton set the production DI graph would provide.
     *
     * @property scheduler Bridging scheduler routing an admission into the queue.
     * @property taskQueueManager The process's queue worker.
     * @property runRepository Run store with the process-local ownership registry.
     * @property pendingRepository Persistent HITL park store.
     * @property approvalNotifier Captures the park notification.
     * @property parkedRunResumer Background-decision submission tail.
     * @property externalJournal Real request journal, shared by the admission path
     *   and the run store, so a test can read back the two-phase row.
     * @property externalCallback Recording stand-in for the outbound broadcast.
     */
    private data class ProcessHarness(
        val scheduler: QueueBridgeScheduler,
        val taskQueueManager: TaskQueueManagerImpl,
        val runRepository: PipelineRunRepositoryImpl,
        val pendingRepository: PendingInteractionRepositoryImpl,
        val approvalNotifier: ApprovalNotifier,
        val parkedRunResumer: ParkedRunResumer,
        val externalJournal: ExternalAutomationJournalRepositoryImpl,
        val externalCallback: RecordingCallbackNotifier,
    ) {
        /**
         * Identity of the request run [runId] is parked on — what the buttons
         * of its notification answer with.
         *
         * @param runId The parked run.
         * @return The request id its record carries.
         */
        suspend fun parkedRequestId(runId: String): String =
            requireNotNull(pendingRepository.getForRun(runId)?.requestId) { "run $runId parks no approval request" }
    }

    private companion object {
        const val GRAPH_ID = "external-graph"
        const val BOUND_NAME = "Nightly digest"
        const val SESSION_ID = HandleExternalAutomationRequestUseCase.EXTERNAL_AUTOMATION_SESSION_ID
        const val REQUEST_ID = "req-1"
        const val CALLER_PACKAGE = "com.example.caller"
        const val USER_PROMPT = "Summarise today"
        const val TOOL_NAME = "web.search"
        const val TOOL_RESULT = "Aurora visible after 23:00"
        const val TOOL_CALL_JSON = """{"tool":"web.search","arguments":"q=aurora forecast"}"""
        const val STEP_MARKER = "STEP-MARKER"
        const val STEP_ANSWER = "Need the live forecast - calling the search tool."
        const val FINAL_MARKER = "FINAL-MARKER"
        const val FINAL_ANSWER = "Here is your nightly digest."
        const val NOW = 2_000_000L
        const val LIVE_WAIT_TIMEOUT_MS = 1_000L
        const val AWAIT_TIMEOUT_MS = 30_000L
        const val REAL_POLL_DELAY_MS = 5L

        /**
         * Passes of "drain the scheduler, then let real workers run" used to check
         * that a second notification does not follow the first. Bounded on purpose:
         * absence cannot be awaited, only given a window to appear in.
         */
        const val SETTLE_PASSES = 20
    }
}
