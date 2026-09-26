package app.knotwork.android.integration

import androidx.room.Room
import app.knotwork.android.data.engine.KoogClientFactory
import app.knotwork.android.data.engine.KoogCloudLlmModelResolver
import app.knotwork.android.data.engine.TaskQueueManagerImpl
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.models.ChatSessionEntity
import app.knotwork.android.data.repositories.PendingInteractionRepositoryImpl
import app.knotwork.android.data.repositories.PipelineRunRepositoryImpl
import app.knotwork.android.data.repositories.RunTraceRepositoryImpl
import app.knotwork.android.data.repositories.TriggerJournalRepositoryImpl
import app.knotwork.android.data.repositories.TriggerRepositoryImpl
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
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NetworkState
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.PowerState
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerCondition
import app.knotwork.android.domain.models.TriggerEvaluationSource
import app.knotwork.android.domain.models.TriggerEvaluationVerdict
import app.knotwork.android.domain.models.TriggerHitlActivity
import app.knotwork.android.domain.models.TriggerHitlResolution
import app.knotwork.android.domain.models.TriggerRunOutcome
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.NetworkStateRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PowerStateRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.services.NativeMemorySampler
import app.knotwork.android.domain.services.ScheduledTaskConstraints
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import app.knotwork.android.domain.services.TaskScheduler
import app.knotwork.android.domain.services.TriggerScheduler
import app.knotwork.android.domain.usecases.AgentOrchestratorUseCase
import app.knotwork.android.domain.usecases.EvaluateTriggerFiringUseCase
import app.knotwork.android.domain.usecases.FireTriggerUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.ParkedRunResumer
import app.knotwork.android.domain.usecases.PendingSubmissionOutcome
import app.knotwork.android.domain.usecases.RecordTriggerEvaluationUseCase
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.ResumePipelineRunUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import app.knotwork.android.domain.usecases.SubmitApprovalDecisionUseCase
import app.knotwork.android.domain.usecases.TriggerFireOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.inject.Provider

/**
 * End-to-end JVM integration test of the **automation-trigger → background run →
 * notification → result-in-chat** arc — the privacy-sensitive surface automation
 * triggers add on top of the persisted background-run infrastructure.
 *
 * Both tests start from a charging trigger and drive a **real**
 * [FireTriggerUseCase] (loading the trigger from a real Room-backed
 * [TriggerRepositoryImpl], deciding via the real [EvaluateTriggerFiringUseCase],
 * resolving the bound session, and enqueuing) over a **real** engine + task
 * queue. The only seam between the trigger and the queue is a bridging
 * [TaskScheduler] that forwards `scheduleOneTime(...)` into the same
 * `enqueueScheduled` path `AgentWorker` drives — so WorkManager (untestable on
 * the JVM) is the single stubbed boundary, not the run itself.
 *
 * - [trigger fires, runs the bound pipeline in the background, and lands the result in the bound chat]
 *   proves the happy arc: the charging edge fires, the run is enqueued as
 *   [RunOrigin.TRIGGER] into the trigger's bound session, the "Trigger fired"
 *   notification is posted, the firing is tallied into the on-device usage
 *   statistics by kind, the pipeline runs to completion, and the final answer
 *   lands in that session as a regular assistant message.
 * - [a sensitive tool inside a trigger run still parks on the background HITL gate and resumes after approval]
 *   proves the safety invariant the security model leans on: a `SENSITIVE` tool
 *   inside an **unattended** trigger run does not execute silently. With no UI to
 *   answer, the live wait times out and the run **parks** (persistent
 *   WAITING_APPROVAL + approval notification); approving from the notification
 *   (the [SubmitApprovalDecisionUseCase] entry point `AgentApprovalReceiver`
 *   dispatches to) resumes the run from its checkpoint and it completes.
 *
 * Real `Dispatchers.IO` hops inside the repositories do not run on the
 * virtual-time scheduler, so the test synchronises on **observed database
 * state** via [awaitUntil] rather than bare `advanceUntilIdle()` — the same
 * convention [BackgroundAutonomyCycleIntegrationTest] uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TriggerBackgroundRunIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var database: AppDatabase

    // Device-edge doubles shared across the process wiring.
    private lateinit var chatRepository: ChatRepository
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var toolRepository: ToolRepository

    // Real, Room-backed trigger store + the pure firing evaluator.
    private lateinit var triggerRepository: TriggerRepository
    private val evaluateTriggerFiring = EvaluateTriggerFiringUseCase()

    // Trigger-side doubles whose interactions the tests assert.
    private lateinit var powerStateRepository: PowerStateRepository
    private lateinit var networkStateRepository: NetworkStateRepository
    private lateinit var scheduledTaskNotifier: ScheduledTaskNotifier
    private lateinit var triggerScheduler: TriggerScheduler
    private lateinit var usageTelemetry: UsageTelemetryRepository

    /** A charging trigger already bound to the seeded session, armed and ready to fire. */
    private val chargingTrigger = Trigger(
        id = TRIGGER_ID,
        name = TRIGGER_NAME,
        condition = TriggerCondition.Charging,
        pipelineId = GRAPH_ID,
        prompt = USER_PROMPT,
        enabled = true,
        armed = true,
        createdAt = 0L,
        sessionId = SESSION_ID,
    )

    private val happyGraph = PipelineGraph(
        id = GRAPH_ID,
        name = "Trigger run",
        nodes = listOf(
            NodeModel("input_1", NodeType.INPUT, 0f, 0f),
            NodeModel("llm_1", NodeType.LITE_RT, 0f, 0f, systemPrompt = STEP_MARKER),
            NodeModel("output_1", NodeType.OUTPUT, 0f, 0f, systemPrompt = FINAL_MARKER),
        ),
        connections = listOf(
            ConnectionModel("c1", "input_1", "llm_1"),
            ConnectionModel("c2", "llm_1", "output_1"),
        ),
    )

    private val toolGraph = PipelineGraph(
        id = GRAPH_ID,
        name = "Trigger run with tool",
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
        // persistence is mocked, so seed the bound session row the way the real
        // chat repository would have when the trigger first minted it.
        runBlocking {
            database.chatDao().insertSession(ChatSessionEntity(id = SESSION_ID, name = TRIGGER_NAME, updatedAt = 0L))
        }
        triggerRepository = TriggerRepositoryImpl(database.triggerDao())

        chatRepository = mockk(relaxed = true)
        every { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
        // The trigger's bound session still exists, so the fire reuses it
        // verbatim (no fresh mint, deterministic foreign key).
        coEvery { chatRepository.sessionExists(SESSION_ID) } returns true

        settingsRepository = mockk()
        every { settingsRepository.verboseMemoryLoggingEnabled } returns flowOf(false)
        every { settingsRepository.chatHistoryCompressionEnabled } returns flowOf(false)
        every { settingsRepository.chatHistoryCompressionThresholdTokens } returns flowOf(3_500)
        every { settingsRepository.chatHistoryLiveWindowSize } returns flowOf(10)
        every { settingsRepository.systemPromptPrefix } returns flowOf("")
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.pipelineMaxSteps } returns flowOf(15)
        every { settingsRepository.pipelineMaxStepsBackground } returns flowOf(15)
        every { settingsRepository.runMaxTokens } returns flowOf(1_000_000)
        every { settingsRepository.runMaxTokensBackground } returns flowOf(100_000)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(LIVE_WAIT_TIMEOUT_MS)
        every { settingsRepository.resumeMaxAgeHours } returns flowOf(48)
        every { settingsRepository.backgroundApprovalWindowHours } returns flowOf(24)
        every { settingsRepository.defaultPipelineId } returns flowOf(GRAPH_ID)
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)

        toolRepository = mockk(relaxed = true)
        coEvery { toolRepository.getAvailableTools() } returns
            listOf(AgentTool(TOOL_NAME, "Searches the web", "{}"))
        coEvery { toolRepository.getRisk(TOOL_NAME, any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.executeTool(TOOL_NAME, any(), any()) } returns TOOL_RESULT

        // The device is charging, so the charging trigger's condition is met.
        powerStateRepository = mockk { every { powerState } returns MutableStateFlow(PowerState(isCharging = true)) }
        networkStateRepository = mockk { every { networkState } returns MutableStateFlow(NetworkState()) }
        scheduledTaskNotifier = mockk(relaxed = true)
        triggerScheduler = mockk(relaxed = true)
        usageTelemetry = mockk(relaxed = true)
        coEvery { usageTelemetry.isEnabled() } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `trigger fires, runs the bound pipeline in the background, and lands the result in the bound chat`() =
        testScope.runTest {
            stubPipeline(happyGraph)
            runBlocking { triggerRepository.saveTrigger(chargingTrigger) }
            val process = buildProcess(scriptedLlmEngine())
            val fireTrigger = buildFireTrigger(process)

            // ── Fire the trigger (the charging edge is satisfied + armed) ──
            val outcome = fireTrigger(TRIGGER_ID, TriggerEvaluationSource.POLL, NOW)

            assertEquals(TriggerFireOutcome.Fired(GRAPH_ID), outcome)
            // The run was enqueued through the scheduler path attributed to the trigger.
            assertEquals(RunOrigin.TRIGGER, process.scheduler.lastOrigin)
            assertEquals(GRAPH_ID, process.scheduler.lastPipelineId)
            // Into the trigger's own bound session (not a fresh one).
            assertEquals(SESSION_ID, process.scheduler.lastSessionId)
            assertNotNull("the bridge captured the enqueued run id", process.scheduler.lastRunId)
            val runId = process.scheduler.lastRunId!!

            // ── The background run executes to completion ──
            awaitUntil("run COMPLETED") {
                process.runRepository.getRun(runId)?.status == PipelineRunStatus.COMPLETED
            }
            val run = process.runRepository.getRun(runId)
            assertEquals(RunOrigin.TRIGGER, run?.origin)
            assertEquals(SESSION_ID, run?.sessionId)

            // ── The result landed in the bound session as a final assistant message ──
            val saved = mutableListOf<ChatMessage>()
            coVerify(atLeast = 1) { chatRepository.saveMessage(capture(saved)) }
            assertTrue(
                "Expected a final assistant message carrying the OUTPUT answer in the bound session",
                saved.any {
                    it.role == Role.AGENT &&
                        it.isFinal &&
                        it.sessionId == SESSION_ID &&
                        it.content.contains(FINAL_ANSWER)
                },
            )

            // ── A "Trigger fired" notification announced the background run ──
            coVerify(exactly = 1) { scheduledTaskNotifier.notifyTriggerFired(SESSION_ID, TRIGGER_NAME) }
            // ── The firing was tallied into the privacy-preserving local statistics by kind ──
            coVerify(exactly = 1) { usageTelemetry.recordTriggerFired("CHARGING", NOW) }

            // ── The trigger latched: fired-time recorded and the event trigger disarmed ──
            val firedTrigger = triggerRepository.getTriggerById(TRIGGER_ID)
            assertEquals(NOW, firedTrigger?.lastFiredAt)
            assertFalse("a fired event trigger is disarmed until its condition drops", firedTrigger?.armed ?: true)

            // ── The journal completed the two-phase row: one Fired evaluation for
            //    this run id, its outcome now attributed as Success end-to-end ──
            val journal = process.triggerJournal.observeByTrigger(TRIGGER_ID).first()
            assertEquals(1, journal.size)
            val entry = journal.single()
            assertEquals(TriggerEvaluationVerdict.Fired, entry.verdict)
            assertEquals(TriggerEvaluationSource.POLL, entry.source)
            assertEquals(runId, entry.runId)
            assertEquals(TriggerRunOutcome.Success, entry.outcome)
            // This pipeline never stops to ask, so the row carries no HITL
            // activity at all — "no gate" is an absence, not a neutral value.
            assertNull(entry.hitl)

            process.taskQueueManager.scope.cancel()
        }

    @Test
    fun `a sensitive tool inside a trigger run still parks on the background HITL gate and resumes after approval`() =
        testScope.runTest {
            stubPipeline(toolGraph)
            runBlocking { triggerRepository.saveTrigger(chargingTrigger) }
            val process = buildProcess(scriptedLlmEngine())
            val fireTrigger = buildFireTrigger(process)

            // ── Fire the trigger; the run reaches the SENSITIVE tool node ──
            val outcome = fireTrigger(TRIGGER_ID, TriggerEvaluationSource.POLL, NOW)
            assertEquals(TriggerFireOutcome.Fired(GRAPH_ID), outcome)
            assertNotNull("the bridge captured the enqueued run id", process.scheduler.lastRunId)
            val runId = process.scheduler.lastRunId!!

            // ── No UI answers: the live wait times out and the run parks ──
            // The live gate is part of the condition, not an afterthought: the
            // approval below must take the durable path, and it only does so
            // once `pendingApproval` is gone. `ToolInvocationGate` retires the
            // holder before writing the durable record, so waiting for all
            // three is waiting for one consistent state — asserting only the
            // record would race that ordering.
            awaitUntil("run parked WAITING_APPROVAL with the live gate retired") {
                process.runRepository.getRun(runId)?.status == PipelineRunStatus.WAITING_APPROVAL &&
                    process.pendingRepository.getForRun(runId) != null &&
                    process.taskQueueManager.pendingApproval(SESSION_ID) == null
            }
            verify(atLeast = 1) {
                process.approvalNotifier.sendApprovalRequest(any(), any(), any(), any(), any())
            }

            // ── Approve from the notification — the run resumes and completes ──
            val submission = SubmitApprovalDecisionUseCase(
                process.taskQueueManager,
                process.pendingRepository,
                process.parkedRunResumer,
            )(SESSION_ID, process.parkedRequestId(runId), isApproved = true)
            assertEquals(PendingSubmissionOutcome.Resumed, submission)

            awaitUntil("run COMPLETED after approval") {
                process.runRepository.getRun(runId)?.status == PipelineRunStatus.COMPLETED
            }
            // The staged tool really executed only after the approval.
            coVerify(exactly = 1) { toolRepository.executeTool(TOOL_NAME, any(), any()) }
            // The pending record was consumed.
            assertNull(process.pendingRepository.getForRun(runId))
            // The final answer landed in the bound session.
            val saved = mutableListOf<ChatMessage>()
            coVerify(atLeast = 1) { chatRepository.saveMessage(capture(saved)) }
            assertTrue(
                "Expected a final assistant message in the bound session after approval",
                saved.any {
                    it.role == Role.AGENT &&
                        it.isFinal &&
                        it.sessionId == SESSION_ID &&
                        it.content.contains(FINAL_ANSWER)
                },
            )

            // ── The two-phase journal row survived the park and resume: still a
            //    single Fired entry, its outcome now Success after the resumed run
            //    completed (attributed on the resume path, not by the dead worker) ──
            val entry = process.triggerJournal.observeByTrigger(TRIGGER_ID).first().single()
            assertEquals(TriggerEvaluationVerdict.Fired, entry.verdict)
            assertEquals(runId, entry.runId)
            assertEquals(TriggerRunOutcome.Success, entry.outcome)

            // ── …and the row says the run asked for approval, had to wait in the
            //    shade for it, and got it. Without this the run is a plain
            //    Success, indistinguishable from one that never asked — which is
            //    exactly what made the background-approval criterion of the soak
            //    protocol unprovable from a journal dump ──
            assertEquals(
                TriggerHitlActivity(
                    gateCount = 1,
                    lastKind = PendingInteractionKind.APPROVAL,
                    lastResolution = TriggerHitlResolution.APPROVED,
                    parked = true,
                ),
                entry.hitl,
            )

            process.taskQueueManager.scope.cancel()
        }

    /** Publishes [graph] as the single saved pipeline the fire path resolves. */
    private fun stubPipeline(graph: PipelineGraph) {
        pipelineRepository = mockk()
        every { pipelineRepository.getAllPipelines() } returns flowOf(listOf(graph))
        coEvery { pipelineRepository.getPipelineById(GRAPH_ID) } returns graph
    }

    /**
     * Builds the real [FireTriggerUseCase] over this process's bridging
     * scheduler, so a fire flows straight into the live task queue.
     */
    private fun buildFireTrigger(process: ProcessHarness): FireTriggerUseCase = FireTriggerUseCase(
        triggerRepository = triggerRepository,
        pipelineRepository = pipelineRepository,
        powerStateRepository = powerStateRepository,
        networkStateRepository = networkStateRepository,
        evaluateTriggerFiring = evaluateTriggerFiring,
        taskScheduler = process.scheduler,
        chatRepository = chatRepository,
        scheduledTaskNotifier = scheduledTaskNotifier,
        triggerScheduler = triggerScheduler,
        usageTelemetry = usageTelemetry,
        recordTriggerEvaluation = RecordTriggerEvaluationUseCase(process.triggerJournal),
    )

    /**
     * One "process" worth of singletons: fresh Room-backed run/trace/pending
     * repositories, a real engine wired exactly like production DI, a real task
     * queue on the shared test dispatcher, and the bridging scheduler that turns
     * a `scheduleOneTime` into an `enqueueScheduled` on that queue.
     */
    private fun buildProcess(llmEngine: LlmInferenceEngine): ProcessHarness {
        val telemetry = mockk<UsageTelemetryRepository>(relaxed = true)
        coEvery { telemetry.isEnabled() } returns false
        // Real, Room-backed trigger journal shared by the fire path (which opens
        // the Fired row) and the run store (which attributes the terminal outcome
        // back onto it) — so the two-phase correlation is exercised end-to-end.
        val triggerJournal = TriggerJournalRepositoryImpl(database.triggerJournalDao()).apply {
            dispatcher = testDispatcher
        }
        val runRepository = PipelineRunRepositoryImpl(
            database.pipelineRunDao(),
            telemetry,
            triggerJournal,
            mockk(relaxed = true),
            mockk(relaxed = true),
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
            dispatcher = testDispatcher,
        ).apply {
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
            triggerJournal = triggerJournal,
        )
    }

    /**
     * LLM double dispatching on the node's system-prompt marker embedded in the
     * rendered prompt, so the script stays correct no matter how many times a
     * node re-runs (checkpoint replay vs. live re-execution).
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
     * A [TaskScheduler] that forwards a one-time schedule straight into the live
     * task queue (the JVM stand-in for `WorkManagerTaskScheduler` → `AgentWorker`),
     * capturing the routed arguments so the test can assert how the trigger
     * enqueued its run.
     *
     * @property orchestrator The queue entry point a real `AgentWorker` would call.
     */
    private class QueueBridgeScheduler(private val orchestrator: AgentOrchestratorUseCase) : TaskScheduler {
        var lastRunId: String? = null
        var lastSessionId: String? = null
        var lastPipelineId: String? = null
        var lastOrigin: RunOrigin? = null

        override suspend fun scheduleOneTime(
            prompt: String,
            delayMinutes: Long,
            sessionId: String?,
            constraints: ScheduledTaskConstraints,
            pipelineId: String?,
            origin: RunOrigin,
            runId: String?,
        ) {
            lastSessionId = sessionId
            lastPipelineId = pipelineId
            lastOrigin = origin
            // Honour the pre-minted id exactly as `AgentWorker` does, so the run
            // the queue creates shares identity with the trigger's journal row.
            lastRunId = orchestrator.enqueueScheduled(
                sessionId = requireNotNull(sessionId) { "a trigger run always threads a bound session" },
                userPrompt = prompt,
                pipelineId = pipelineId,
                origin = origin,
                runId = runId,
            )
        }

        /** Unused by the trigger path under test. */
        override fun cancelAllScheduled() = Unit

        override suspend fun cancelAllBackgroundRuns() = Unit

        override suspend fun pruneOrphanPrompts(): Int = 0

        override suspend fun schedulePeriodic(
            prompt: String,
            intervalHours: Long,
            sessionId: String?,
            constraints: ScheduledTaskConstraints,
        ): Unit = error("a trigger only ever schedules one-time work")
    }

    /**
     * The per-process singleton set the production DI graph would provide.
     *
     * @property scheduler Bridging scheduler routing a fire into the live queue.
     * @property taskQueueManager The process's queue worker.
     * @property runRepository Run store with the process-local ownership registry.
     * @property pendingRepository Persistent HITL park store.
     * @property approvalNotifier Captures the park notification.
     * @property parkedRunResumer Background-decision submission tail.
     * @property triggerJournal Real trigger-evaluation journal, shared by the
     *   fire path and the run store, so a test can read back the two-phase row.
     */
    private data class ProcessHarness(
        val scheduler: QueueBridgeScheduler,
        val taskQueueManager: TaskQueueManagerImpl,
        val runRepository: PipelineRunRepositoryImpl,
        val pendingRepository: PendingInteractionRepositoryImpl,
        val approvalNotifier: ApprovalNotifier,
        val parkedRunResumer: ParkedRunResumer,
        val triggerJournal: TriggerJournalRepositoryImpl,
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
        const val GRAPH_ID = "trigger-graph"
        const val SESSION_ID = "trigger-session"
        const val TRIGGER_ID = "trigger-1"
        const val TRIGGER_NAME = "Nightly digest"
        const val USER_PROMPT = "Summarise today"
        const val TOOL_NAME = "web.search"
        const val TOOL_RESULT = "Aurora visible after 23:00"
        const val TOOL_CALL_JSON = """{"tool":"web.search","arguments":"q=aurora forecast"}"""
        const val STEP_MARKER = "STEP-MARKER"
        const val STEP_ANSWER = "Need the live forecast — calling the search tool."
        const val FINAL_MARKER = "FINAL-MARKER"
        const val FINAL_ANSWER = "Here is your nightly digest."
        const val NOW = 2_000_000L
        const val LIVE_WAIT_TIMEOUT_MS = 1_000L
        const val AWAIT_TIMEOUT_MS = 30_000L
        const val REAL_POLL_DELAY_MS = 5L
    }
}
