package app.knotwork.android.domain.engine.golden

import app.knotwork.android.data.prompt.DateVariableProvider
import app.knotwork.android.data.prompt.DeviceVariableProvider
import app.knotwork.android.data.prompt.LangVariableProvider
import app.knotwork.android.data.prompt.LocationVariableProvider
import app.knotwork.android.data.prompt.MemorySummaryVariableProvider
import app.knotwork.android.data.prompt.ModelVariableProvider
import app.knotwork.android.data.prompt.TimeVariableProvider
import app.knotwork.android.data.prompt.ToolsVariableProvider
import app.knotwork.android.data.prompt.UserVariableProvider
import app.knotwork.android.data.repositories.ClarificationRepositoryImpl
import app.knotwork.android.domain.engine.ChatHistoryWindowPlanner
import app.knotwork.android.domain.engine.GraphExecutionEngine
import app.knotwork.android.domain.engine.NodeContextBuilder
import app.knotwork.android.domain.engine.TaskQueueManager
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
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AgentTask
import app.knotwork.android.domain.models.ChatHistorySummary
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.Identity
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySummary
import app.knotwork.android.domain.models.ModelPerformanceSample
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.TriggerHitlEvent
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.CrashReportingRepository
import app.knotwork.android.domain.repositories.IdentityRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.ModelPerformanceRepository
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.services.NativeMemorySampler
import app.knotwork.android.domain.usecases.EvaluateIfConditionUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.ParkedRunResumer
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.ResumePipelineRunUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import app.knotwork.android.domain.usecases.SubmitApprovalDecisionUseCase
import app.knotwork.android.domain.usecases.SubmitCeilingDecisionUseCase
import app.knotwork.android.domain.usecases.SubmitClarificationAnswerUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Provider

/**
 * Runs one [GoldenScenario] through the production `GraphExecutionEngine` and returns its
 * normal-form trace.
 *
 * **What is real.** The engine and all twelve node executors, the HITL gate, the structured
 * output gate, the `IF` evaluation use case, the ceiling resolver, the context builder, the
 * chat-history planner, the prompt template engine, every prompt variable provider (on a
 * frozen clock, locale, device and identity), the clarification repository, the trigger-HITL
 * journal use case, and the whole answering path a user's reply takes: the `Submit…UseCase`s,
 * `ParkedRunResumer` and `ResumePipelineRunUseCase`.
 *
 * **What is a fake.** What reaches outside the process or the clock, each one recording:
 * - the model at all three seams ([GoldenModel]);
 * - tools ([GoldenToolRepository] — recorded outputs, no side effects);
 * - long-term memory — a fixed snapshot of two chunks, recency scoring not involved;
 * - chat history — a fixed conversation plus whatever the run writes;
 * - run records, trace and parked interactions — in-memory, with the production guards;
 * - metrics, model-performance samples, crash keys, the network indicator, the trigger
 *   journal and the notifiers — recorded without durations;
 * - the task queue — this class plays it, as `TaskQueueManagerImpl` does: it creates the run,
 *   mirrors terminal states into the record, and processes a resume task the answering path
 *   enqueues;
 * - the user — the scenario's approvals, answers and ceiling grants.
 *
 * Settings are a strict mock with values chosen to differ from `SettingsDefaults`: an
 * unstubbed read throws, and a read replaced by the shipped default changes the trace.
 *
 * An attempt that is still running after [attemptLimitMs] of virtual time fails the scenario:
 * the driver loop has no suspension point, so `runTest`'s own timeout could not end it, and a
 * run waiting on something nothing will answer — the failure a refactoring of a suspension is
 * most likely to introduce — would hang the test task instead of failing it.
 *
 * @param scenario The scenario to run.
 * @param attemptLimitMs Virtual time one engine invocation may take; far above any scenario's
 *   real need (the longest waits out one 60-second clarification window).
 */
internal class GoldenTraceHarness(
    private val scenario: GoldenScenario,
    private val attemptLimitMs: Long = ATTEMPT_LIMIT_MS,
) {

    private val log = GoldenEventLog()
    private val library: Map<String, PipelineGraph> = GoldenPipelineSources.library()
    private val root: PipelineGraph = GoldenPipelineSources.root(scenario.source)
    private val skills: Map<String, Skill> = GoldenPipelineSources.skills()
    private val tracker = GoldenNodeTracker(ROOT_RUN_ID, root, library, log)
    private val model = GoldenModel(tracker, scenario.script, root.id, log)
    private val tools = GoldenToolRepository(log)
    private val runs = GoldenPipelineRunRepository(log, tracker)
    private val trace = GoldenRunTraceRepository(log)
    private val pending = GoldenPendingInteractionRepository(log)
    private val clarificationRepository = ClarificationRepositoryImpl()
    private val approvals = ArrayDeque(scenario.approvals)
    private val clarifications = ArrayDeque(scenario.clarifications)
    private val parkResolutions = ArrayDeque(scenario.parkResolutions)
    private val chatMessages = mutableListOf<ChatMessage>()
    private val enqueued = ArrayDeque<AgentTask>()

    /** Request id of the last parked approval's notification — what the user answers. */
    private var lastParkedApprovalRequestId: String? = null

    /** Run id of the last ceiling notification — what the user answers. */
    private var lastCeilingRunId: String? = null

    private val settings: SettingsRepository = strictSettings()
    private val approvalNotifier = recordingApprovalNotifier()
    private val clarificationNotifier = recordingClarificationNotifier()
    private val ceilingNotifier = recordingCeilingNotifier()
    private val recordHitlEvent = RecordTriggerHitlEventUseCase(recordingJournal(), runs)
    private val engine: GraphExecutionEngine = buildEngine()
    private val queue: TaskQueueManager = playedTaskQueue()
    private val resumer = ParkedRunResumer(
        pending,
        runs,
        settings,
        approvalNotifier,
        clarificationNotifier,
        ceilingNotifier,
        ResumePipelineRunUseCase(runs, libraryRepository(), settings, pending, queue),
        recordHitlEvent,
    )
    private val submitApproval = SubmitApprovalDecisionUseCase(queue, pending, resumer)
    private val submitClarification = SubmitClarificationAnswerUseCase(clarificationRepository, pending, resumer)
    private val submitCeiling = SubmitCeilingDecisionUseCase(pending, runs, resumer)

    /**
     * Runs the scenario to its end — a terminal state, or a park the scenario does not
     * resolve — on the virtual time of [scope], then checks it ended the way it declares.
     *
     * @param scope The `runTest` scope; its scheduler drives every delay and timeout.
     * @return The rendered golden trace.
     */
    suspend fun run(scope: TestScope): String {
        chatMessages += seededHistory()
        startFreshTask()
        var resume: ResumeContext? = null
        var ending: GoldenOutcome
        while (true) {
            ending = scope.attempt(resume)
            trace.dropUnflushed()
            if (ending != GoldenOutcome.PARKED) break
            val resolution = parkResolutions.removeFirstOrNull() ?: break
            resolve(resolution)
            val task = enqueued.removeFirstOrNull()
            if (task == null) {
                log.record("driver nothing to resume")
                break
            }
            resume = processResumeTask(task)
        }
        check(log.violations.isEmpty()) { "Scenario $scenario violated the harness: ${log.violations}" }
        check(approvals.isEmpty()) { "Scenario $scenario scripted approvals the run never raised: $approvals" }
        check(clarifications.isEmpty()) { "Scenario $scenario scripted answers nobody asked for: $clarifications" }
        check(parkResolutions.isEmpty()) { "Scenario $scenario scripted resolutions for parks that never happened" }
        check(ending == scenario.outcome) { "Scenario $scenario declares ${scenario.outcome} but ended $ending" }
        return GoldenTraceRenderer.render(scenario, log.events)
    }

    /** The task queue creating the run record of a fresh task before the engine starts. */
    private suspend fun startFreshTask() {
        runs.createRun(
            PipelineRun(
                id = ROOT_RUN_ID,
                sessionId = SESSION_ID,
                pipelineId = root.id,
                origin = scenario.origin,
                status = PipelineRunStatus.QUEUED,
                currentNodeId = null,
                startedAt = 0L,
                finishedAt = null,
                errorMessage = null,
                graphContentHash = root.contentHash(),
                userPrompt = scenario.prompt,
            ),
        )
        runs.markRunning(ROOT_RUN_ID, root.id, root.contentHash())
    }

    /**
     * The task queue picking up a resume task, as `TaskQueueManagerImpl.processResumeTask`
     * does: the graph must still hash as recorded, the checkpoint comes from the durable trace,
     * and the run is marked running before the engine starts.
     */
    private suspend fun processResumeTask(task: AgentTask): ResumeContext {
        log.record("queue.resume ${task.id} resume=${task.isResume} origin=${task.origin}")
        val run = runs.getRun(task.id) ?: log.violation("Resume task for an unknown run ${task.id}")
        val graph = library[run.pipelineId] ?: log.violation("Resume task for an unknown pipeline ${run.pipelineId}")
        val recordedHash = run.graphContentHash ?: log.violation("Run ${task.id} carries no graph hash")
        if (graph.contentHash() != recordedHash) log.violation("The graph of ${task.id} changed before its resume")
        val resume = trace.resumeContextFor(task.id)
        runs.markRunning(task.id, graph.id, recordedHash)
        return resume
    }

    /** Runs one engine invocation; returns how it ended. */
    private suspend fun TestScope.attempt(resume: ResumeContext?): GoldenOutcome {
        tracker.startAttempt()
        model.startAttempt()
        log.record(if (resume == null) "driver start" else "driver attempt resumed")
        var ending: GoldenOutcome? = null
        val job = launch {
            engine(
                sessionId = SESSION_ID,
                userPrompt = scenario.prompt,
                graph = root,
                runId = ROOT_RUN_ID,
                resume = resume,
                origin = scenario.origin,
            ).collect { state ->
                if (ending == GoldenOutcome.COMPLETED || ending == GoldenOutcome.ERROR) {
                    log.record("after the terminal state:")
                }
                recordState(state)
                // The queue mirrors terminal states into the record inside its collector,
                // before the engine resumes past the emit — and so before its last writes.
                when (state) {
                    is AgentOrchestratorState.Completed -> {
                        ending = GoldenOutcome.COMPLETED
                        runs.finishRun(ROOT_RUN_ID, PipelineRunStatus.COMPLETED)
                    }
                    is AgentOrchestratorState.Error -> {
                        ending = GoldenOutcome.ERROR
                        runs.finishRun(ROOT_RUN_ID, PipelineRunStatus.FAILED, state.message, state.reason)
                    }
                    is AgentOrchestratorState.SuspendedInBackground -> ending = GoldenOutcome.PARKED
                    else -> Unit
                }
            }
        }
        var answeredApproval: String? = null
        val answeredClarifications = mutableSetOf<String>()
        val deadline = currentTime + attemptLimitMs
        while (job.isActive) {
            if (currentTime > deadline) {
                job.cancel()
                error(
                    "Scenario $scenario did not finish within $attemptLimitMs ms of virtual time: " +
                        "it waits on something nothing answers",
                )
            }
            runCurrent()
            val approval = engine.pendingApprovalFor(SESSION_ID)
            if (approval != null && approval.requestId != answeredApproval) {
                answeredApproval = approval.requestId
                settleLiveApproval(approval.requestId)
            }
            clarificationRepository.pendingRequests.first()
                .filter { answeredClarifications.add(it.id) }
                .forEach { settleLiveClarification(it.id) }
            if (job.isActive) advanceTimeBy(STEP_MS)
        }
        return ending ?: log.violation("Scenario $scenario ended without a terminal state or a park")
    }

    private suspend fun settleLiveApproval(requestId: String) {
        val action = approvals.removeFirstOrNull()
            ?: log.violation("Scenario $scenario raised an approval it does not settle; add an ApprovalAction")
        val outcome = when (action) {
            ApprovalAction.APPROVE -> submitApproval(SESSION_ID, requestId, isApproved = true)
            ApprovalAction.DENY -> submitApproval(SESSION_ID, requestId, isApproved = false)
            ApprovalAction.LET_IT_PARK -> null
        }
        log.record("driver approval ${action.name.lowercase()} request=${log.alias(requestId)} → ${outcome ?: "-"}")
    }

    private suspend fun settleLiveClarification(requestId: String) {
        val action = clarifications.removeFirstOrNull()
            ?: log.violation("Scenario $scenario asked a clarification it does not answer; add a ClarificationAction")
        val outcome = when (action) {
            is ClarificationAction.Answer -> submitClarification(SESSION_ID, requestId, action.text)
            ClarificationAction.TimeOut -> null
        }
        log.record("driver clarification $action request=${log.alias(requestId)} → ${outcome ?: "-"}")
    }

    /** The user answering a parked run through the app's own use case. */
    private suspend fun resolve(resolution: ParkResolution) {
        val outcome = when (resolution) {
            ParkResolution.Approve, ParkResolution.Deny -> {
                val requestId = lastParkedApprovalRequestId
                    ?: log.violation("Scenario $scenario approves a park no approval notification announced")
                submitApproval(SESSION_ID, requestId, isApproved = resolution == ParkResolution.Approve)
            }
            is ParkResolution.Answer -> submitClarification(SESSION_ID, null, resolution.text)
            ParkResolution.Continue -> submitCeiling(SESSION_ID, shouldContinue = true, runId = lastCeilingRunId)
        }
        log.record("driver resolve $resolution → $outcome")
    }

    private fun recordState(state: AgentOrchestratorState) {
        when (state) {
            is AgentOrchestratorState.Thinking -> log.stream("Thinking", state.partialText)
            is AgentOrchestratorState.Answering -> log.stream("Answering", state.partialText)
            is AgentOrchestratorState.WaitingForApproval -> log.record(
                "state WaitingForApproval tool=${state.toolName} risk=${state.risk} request=${log.alias(
                    state.requestId,
                )}",
                "arguments" to state.arguments,
            )
            is AgentOrchestratorState.AwaitingClarification -> log.record(
                "state AwaitingClarification request=${log.alias(
                    state.request.id,
                )} timeoutMs=${state.request.timeoutMs}",
                "question" to state.request.question,
                "options" to state.request.options.orEmpty().joinToString("\n"),
            )
            is AgentOrchestratorState.WaitingForCeilingRaise ->
                log.record("state WaitingForCeilingRaise axis=${state.axis} limit=${state.limit} spent=${state.spent}")
            is AgentOrchestratorState.SuspendedInBackground -> log.record("state SuspendedInBackground ${state.kind}")
            is AgentOrchestratorState.RunNotice -> log.record("state RunNotice ${state.cause}")
            is AgentOrchestratorState.PipelineStage -> log.record(
                "state Stage step=${state.stepInfo.stepIndex} of=${state.stepInfo.totalSteps} ${state.stepInfo.nodeName}",
            )
            is AgentOrchestratorState.PipelineTrace -> state.steps.lastOrNull().let { last ->
                log.record(
                    "state PipelineTrace steps=${state.steps.size} last=${last?.nodeName} depth=${last?.depth} " +
                        "tokens=${last?.tokenCount}",
                )
            }
            is AgentOrchestratorState.ConsoleLog ->
                log.record("state ConsoleLog run=${state.runId} events=${state.events.size}")
            is AgentOrchestratorState.NodeIO -> recordNodeIo(state)
            is AgentOrchestratorState.ExecutingTool ->
                log.record("state ExecutingTool ${state.toolName}", "arguments" to state.arguments)
            is AgentOrchestratorState.ObservationResult ->
                log.record("state ObservationResult ${state.toolName}", "result" to state.result)
            is AgentOrchestratorState.Completed -> log.record("end Completed", "finalResponse" to state.finalResponse)
            is AgentOrchestratorState.Error -> log.record(
                "end Error reason=${state.reason}",
                "message" to state.message,
            )
            AgentOrchestratorState.Idle, AgentOrchestratorState.Loading, AgentOrchestratorState.Queued ->
                log.record("state $state")
        }
    }

    /** A `NodeIO` state normally repeats the trace record; only a divergence is spelled out. */
    private fun recordNodeIo(state: AgentOrchestratorState.NodeIO) {
        val record = trace.lastNodeIo(state.nodeId, state.depth)
        if (record != null && record.inputText == state.input && record.outputText == state.output) {
            log.record(
                "state NodeIO ${state.nodeId} ${state.nodeType} depth=${state.depth} (= trace.node seq=${record.seq})",
            )
        } else {
            log.record(
                "state NodeIO ${state.nodeId} ${state.nodeType} depth=${state.depth} (differs from its trace record)",
                "input" to state.input,
                "output" to state.output,
            )
        }
    }

    private fun buildEngine(): GraphExecutionEngine {
        lateinit var engine: GraphExecutionEngine
        val chatRepository = recordingChatRepository()
        val memoryRepository = recordingMemoryRepository()
        val metrics = recordingMetrics()
        val network = object : NetworkActivityTracker by mockk<NetworkActivityTracker>() {
            override fun recordOutbound() = log.record("network.outbound")
        }
        val localModels = localModelRepository()
        val loadModel = recordingLoadModel()
        val structuredGate = StructuredOutputGate()
        val providers = promptVariableProviders(localModels, memoryRepository)
        val gate = ToolInvocationGate(tools, settings, approvalNotifier, chatRepository, pending, recordHitlEvent)
        val toolNode = ToolNodeExecutor(
            model.localEngine,
            loadModel,
            tools,
            gate,
            structuredGate,
            settings,
            model.structuredFactory,
        )
        val liteRt = LiteRtNodeExecutor(
            model.localEngine,
            settings,
            metrics,
            recordingModelPerformance(),
            NativeMemorySampler { 0L },
            loadModel,
        )
        val cloud = CloudLlmNodeExecutor(
            settings,
            apiKeys(),
            metrics,
            model.cloudClientFactory,
            model.cloudModelResolver,
            network,
        )
        val factory = NodeExecutorFactory(
            InputNodeExecutor(),
            OutputNodeExecutor(model.localEngine, loadModel, chatRepository, localModels),
            IfConditionNodeExecutor(
                EvaluateIfConditionUseCase(model.localEngine, structuredGate, settings, model.structuredFactory),
            ),
            toolNode,
            liteRt,
            cloud,
            SystemNodeExecutor(
                model.localEngine,
                loadModel,
                chatRepository,
                structuredGate,
                settings,
                model.structuredFactory,
            ),
            QueueProcessorNodeExecutor(),
            SummaryNodeExecutor(model.localEngine, loadModel),
            ClarificationNodeExecutor(
                model.localEngine,
                loadModel,
                clarificationRepository,
                pending,
                clarificationNotifier,
                recordHitlEvent,
            ),
            PipelineNodeExecutor(libraryRepository(), settings, runs, trace, Provider { engine }),
            SkillNodeExecutor(skillRepository(), PromptTemplateEngine(), providers, tools, liteRt, cloud, gate),
        )
        engine = GraphExecutionEngine(
            factory,
            toolNode,
            chatRepository,
            settings,
            metrics,
            PromptTemplateEngine(),
            providers,
            NodeContextBuilder(),
            ChatHistoryWindowPlanner(),
            fixedMemoryRetrieval(),
            recordingCrashReporting(),
            localModels,
            memoryRepository,
            runs,
            trace,
            ResolveRunCeilingsUseCase(settings),
            pending,
            ceilingNotifier,
        )
        return engine
    }

    /** Every value differs from `SettingsDefaults`, so a read replaced by the default shows. */
    private fun strictSettings(): SettingsRepository = mockk<SettingsRepository>().also { s ->
        val chosen = scenario.settings
        every { s.systemPromptPrefix } returns flowOf("[golden prefix] Answer in plain words.")
        every { s.structuredOutputMaxRepairs } returns flowOf(3)
        every { s.toolApprovalPolicy } returns flowOf(chosen.approvalPolicy)
        every { s.blockDestructiveTools } returns flowOf(chosen.blockDestructiveTools)
        every { s.toolCallTimeoutMs } returns flowOf(APPROVAL_WINDOW_MS)
        every { s.pipelineMaxNestingDepth } returns flowOf(4)
        every { s.pipelineMaxSteps } returns flowOf(chosen.maxSteps)
        every { s.pipelineMaxStepsBackground } returns flowOf(chosen.maxStepsBackground)
        every { s.runMaxTokens } returns flowOf(900_000)
        every { s.runMaxTokensBackground } returns flowOf(150_000)
        every { s.verboseMemoryLoggingEnabled } returns flowOf(chosen.verboseMemoryLogging)
        every { s.chatHistoryCompressionEnabled } returns flowOf(chosen.compressedHistory)
        every { s.chatHistoryCompressionThresholdTokens } returns flowOf(if (chosen.compressedHistory) 500 else 4_000)
        every { s.chatHistoryLiveWindowSize } returns flowOf(if (chosen.compressedHistory) 6 else 20)
        every { s.workspaceReadTokenBudget } returns flowOf(1_500)
        every { s.memorySummaryDefaultLimit } returns flowOf(3)
        every { s.backgroundApprovalWindowHours } returns flowOf(6)
        every { s.resumeMaxAgeHours } returns flowOf(12)
    }

    private fun promptVariableProviders(
        localModels: LocalModelRepository,
        memoryRepository: MemoryRepository,
    ): Set<PromptVariableProvider> {
        val clock = Clock.fixed(Instant.parse(FROZEN_INSTANT), ZoneOffset.UTC)
        val identity = mockk<IdentityRepository>()
        coEvery { identity.getIdentity(any()) } answers { Identity(firstArg(), "golden-device", true) }
        return linkedSetOf(
            DateVariableProvider({ clock }, { Locale.US }),
            TimeVariableProvider { clock },
            ToolsVariableProvider(tools),
            ModelVariableProvider(localModels),
            MemorySummaryVariableProvider(memoryRepository, settings),
            LangVariableProvider { Locale.US },
            LocationVariableProvider { Locale.US },
            UserVariableProvider(identity),
            DeviceVariableProvider({ "Golden" }, { "Reference Phone" }, { "16" }),
        )
    }

    private fun playedTaskQueue(): TaskQueueManager = object : TaskQueueManager by mockk<TaskQueueManager>() {
        override fun enqueueTask(task: AgentTask) {
            enqueued += task
            log.record(
                "queue.enqueue ${task.id} resume=${task.isResume} origin=${task.origin} pipeline=${task.pipelineId}",
            )
        }

        override fun resumeWithApproval(sessionId: String, requestId: String, isApproved: Boolean): Boolean =
            engine.resumeWithApproval(sessionId, requestId, isApproved)
    }

    private fun recordingChatRepository(): ChatRepository = object : ChatRepository by mockk<ChatRepository>() {
        override suspend fun saveMessage(message: ChatMessage) {
            chatMessages += message
            log.record(
                "chat.save ${message.role} final=${message.isFinal} model=${message.modelName}",
                "content" to message.content,
            )
        }

        override fun getMessagesForSession(sessionId: String): Flow<List<ChatMessage>> =
            flowOf(chatMessages.filter { it.sessionId == sessionId })

        override suspend fun getHistorySummary(sessionId: String): ChatHistorySummary? = ChatHistorySummary(
            sessionId = sessionId,
            summary = "Earlier, the user asked about golden tests and planned a trip.",
            coveredMessageCount = SUMMARISED_MESSAGES,
            updatedAt = 0L,
        ).takeIf { scenario.settings.compressedHistory }
    }

    private fun recordingMemoryRepository(): MemoryRepository = object : MemoryRepository by mockk<MemoryRepository>() {
        override suspend fun recordUsage(ids: List<Long>, atMillis: Long) {
            log.record("memory.used ids=$ids")
        }

        override suspend fun getRecentMemorySummaries(limit: Int): List<MemorySummary> =
            MEMORY.take(limit).map { MemorySummary(it.id, it.text, it.timestamp) }
    }

    private fun fixedMemoryRetrieval(): RetrieveRelevantMemoryUseCase = mockk<RetrieveRelevantMemoryUseCase>().also {
        coEvery { it.retrieveScored(any(), any(), any()) } answers {
            log.record(
                "memory.retrieve limit=${secondArg<Int?>()} threshold=${thirdArg<Float?>()}",
                "query" to firstArg<String>(),
            )
            MEMORY.zip(listOf(0.91f, 0.74f))
        }
    }

    private fun recordingMetrics(): MetricsRepository = object : MetricsRepository by mockk<MetricsRepository>() {
        override fun updateMetrics(timeMs: Long, tokensProcessed: Int) {
            log.record("metrics.update tokens=$tokensProcessed")
        }

        override fun recordNodeExecution(nodeType: NodeType, durationMs: Long, tokenCount: Int?) {
            log.record("metrics.node $nodeType tokens=$tokenCount")
        }

        override fun recordStructuredOutputRepair(nodeName: String) {
            log.record("metrics.repair $nodeName")
        }
    }

    private fun recordingModelPerformance(): ModelPerformanceRepository =
        object : ModelPerformanceRepository by mockk<ModelPerformanceRepository>() {
            override suspend fun record(sample: ModelPerformanceSample) {
                log.record(
                    "modelPerformance.record path=${sample.modelPath} tokens=${sample.tokenCount} " +
                        "benchmark=${sample.isBenchmark}",
                )
            }
        }

    private fun recordingCrashReporting(): CrashReportingRepository =
        object : CrashReportingRepository by mockk<CrashReportingRepository>() {
            override suspend fun setCustomKey(key: String, value: String) {
                log.record("crash.key $key=$value")
            }

            override suspend fun recordException(throwable: Throwable, extras: Map<String, String>) {
                log.record("crash.exception ${throwable::class.simpleName} extras=$extras")
            }
        }

    private fun recordingJournal(): TriggerJournalRepository =
        object : TriggerJournalRepository by mockk<TriggerJournalRepository>() {
            override suspend fun recordHitlEvent(runId: String, event: TriggerHitlEvent) {
                log.record("journal.hitl $runId $event")
            }
        }

    private fun recordingLoadModel(): LoadModelUseCase = mockk<LoadModelUseCase>().also {
        coEvery { it(any(), any(), any()) } answers {
            log.record(
                "model.load path=${firstArg<String?>()} vision=${secondArg<Boolean>()} audio=${thirdArg<Boolean>()}",
            )
            Result.Success(Unit)
        }
    }

    private fun localModelRepository(): LocalModelRepository = mockk<LocalModelRepository>().also {
        val active = LocalModel(
            id = 1,
            name = GoldenModel.MODEL_NAME,
            path = GoldenModel.MODEL_PATH,
            size = 0,
            isActive = true,
        )
        coEvery { it.getActiveModel() } returns active
        every { it.getAllModels() } returns MutableStateFlow(listOf(active))
    }

    private fun apiKeys(): ApiKeyRepository = mockk<ApiKeyRepository>().also {
        every { it.getGoogleKey() } returns flowOf(null)
        every { it.getAnthropicKey() } returns flowOf(null)
        every { it.getOpenAIKey() } returns flowOf("golden-openai-key")
        every { it.getDeepSeekKey() } returns flowOf(null)
    }

    private fun libraryRepository(): PipelineRepository = mockk<PipelineRepository>().also {
        coEvery { it.getPipelineById(any()) } answers { library[firstArg()] }
    }

    private fun skillRepository(): SkillRepository = mockk<SkillRepository>().also {
        coEvery { it.getSkillById(any()) } answers { skills[firstArg()] }
    }

    private fun recordingApprovalNotifier(): ApprovalNotifier = object : ApprovalNotifier {
        override fun sendApprovalRequest(
            sessionId: String,
            requestId: String,
            toolName: String,
            arguments: String,
            risk: ToolRisk,
        ) = log.record("notify.approval request=${log.alias(requestId)} tool=$toolName risk=$risk")

        override fun sendPersistentApprovalRequest(
            runId: String,
            sessionId: String,
            requestId: String,
            toolName: String,
            arguments: String,
            risk: ToolRisk,
        ) {
            lastParkedApprovalRequestId = requestId
            log.record(
                "notify.approval.persistent run=$runId request=${log.alias(requestId)} tool=$toolName risk=$risk",
            )
        }

        override fun cancelApprovalNotification(requestId: String) =
            log.record("notify.approval.cancel request=${log.alias(requestId)}")

        override fun cancelPreUpdateNotification(sessionId: String) = log.record("notify.approval.cancelPreUpdate")
    }

    private fun recordingClarificationNotifier(): ClarificationNotifier = object : ClarificationNotifier {
        override fun sendPersistentClarificationRequest(runId: String, sessionId: String, question: String) =
            log.record("notify.clarification.persistent run=$runId", "question" to question)

        override fun cancelClarificationNotification(sessionId: String) = log.record("notify.clarification.cancel")
    }

    private fun recordingCeilingNotifier(): CeilingNotifier = object : CeilingNotifier {
        override fun sendCeilingPauseRequest(runId: String, sessionId: String, breach: HardCeilingBreach) {
            lastCeilingRunId = runId
            log.record("notify.ceiling run=$runId breach=$breach")
        }

        override fun cancelCeilingNotification(sessionId: String) = log.record("notify.ceiling.cancel")
    }

    private fun seededHistory(): List<ChatMessage> {
        if (!scenario.settings.compressedHistory) {
            return listOf(
                message(Role.USER, "Earlier: what is a golden test?", 1L),
                message(Role.AGENT, "A test that compares output with a recorded reference.", 2L),
            )
        }
        return (1..LONG_CONVERSATION).map { turn ->
            val role = if (turn % 2 == 1) Role.USER else Role.AGENT
            message(
                role,
                "Turn $turn: a sentence long enough to cost a handful of tokens in the window.",
                turn.toLong(),
            )
        }
    }

    private fun message(role: Role, content: String, timestamp: Long) =
        ChatMessage(sessionId = SESSION_ID, role = role, content = content, timestamp = timestamp)

    companion object {
        /** Session every golden run belongs to. */
        const val SESSION_ID: String = "golden-session"

        /** Run id of every scenario's root run. */
        const val ROOT_RUN_ID: String = "golden"

        /** Default virtual-time limit of one engine invocation. */
        private const val ATTEMPT_LIMIT_MS = 10 * 60 * 1_000L

        /** The live approval window — half the shipped default. */
        private const val APPROVAL_WINDOW_MS = 30_000L

        /** Virtual-time step of the driver loop — below every delay the engine uses. */
        private const val STEP_MS = 100L

        /** The instant `$DATE` and `$TIME` render. */
        private const val FROZEN_INSTANT = "2026-09-15T09:30:00Z"

        /** Length of the conversation a compressed-history scenario starts from. */
        private const val LONG_CONVERSATION = 40

        /** How many of those messages the stored summary covers. */
        private const val SUMMARISED_MESSAGES = 30

        /** The long-term memory snapshot every retrieval returns. */
        private val MEMORY = listOf(
            MemoryChunk(
                id = 11,
                text = "The user prefers short answers with one example.",
                embedding = FloatArray(0),
                timestamp = 1L,
            ),
            MemoryChunk(
                id = 12,
                text = "The user is planning a trip to Lisbon in October.",
                embedding = FloatArray(0),
                timestamp = 2L,
            ),
        )
    }
}
