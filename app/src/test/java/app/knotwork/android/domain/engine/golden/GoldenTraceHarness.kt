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
import app.knotwork.android.domain.engine.RunHeaders
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
import app.knotwork.android.domain.engine.testGraphExecutionEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AgentTask
import app.knotwork.android.domain.models.ChatHistorySummary
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.Identity
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySummary
import app.knotwork.android.domain.models.ModelPerformanceSample
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteractionKind
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
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.services.NativeMemorySampler
import app.knotwork.android.domain.services.RunEnvironment
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
 * - long-term memory — a fixed snapshot of six chunks, of which a retrieval returns two, recency
 *   scoring not involved;
 * - chat history — a fixed conversation plus whatever the run writes;
 * - run records, trace and parked interactions — in-memory, with the production guards;
 * - metrics, model-performance samples, crash keys, the network indicator, the trigger
 *   journal and the notifiers — recorded without durations;
 * - the task queue — this class plays it, as `TaskQueueManagerImpl` does: it creates the run,
 *   mirrors terminal states into the record, and processes a resume task the answering path
 *   enqueues;
 * - the user — the scenario's approvals, answers and ceiling grants.
 *
 * Two use cases stay MockK mocks (classes, not interfaces): `LoadModelUseCase` and
 * `RetrieveRelevantMemoryUseCase`, each answering the one method a run calls.
 *
 * Every interface a run reaches is answered by a [goldenStrict] stand-in, so a member no run
 * path uses is a harness violation rather than a silent default. Settings are one of them; their
 * numeric values differ from `SettingsDefaults` and each reaches some scenario, so a read
 * replaced by the shipped default changes a trace.
 *
 * An attempt that is still running after [attemptLimitMs] of virtual time fails the scenario:
 * the driver loop has no suspension point, so `runTest`'s own timeout could not end it, and a
 * run waiting on something nothing will answer — the failure a refactoring of a suspension is
 * most likely to introduce — would hang the test task instead of failing it.
 *
 * @param scenario The scenario to run.
 * @param attemptLimitMs Virtual time one engine invocation may take; far above any scenario's
 *   real need (the longest waits out one 60-second clarification window).
 * @param samplingOverride The seed and sampler the run is started again with, as the queue
 *   passes a task's own; `null` — every golden scenario — for the harness's fixed seed and the
 *   settings' sampler.
 */
internal class GoldenTraceHarness(
    private val scenario: GoldenScenario,
    private val attemptLimitMs: Long = ATTEMPT_LIMIT_MS,
    private val samplingOverride: LocalSampling? = null,
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

    /** The run records of the scenario, for a test that reads the finished run tree. */
    internal val runRecords: PipelineRunRepository get() = runs

    /** The durable run trace of the scenario, for a test that reads the finished run tree. */
    internal val runTrace: RunTraceRepository get() = trace

    /** From now on any tool execution is a violation (see [GoldenToolRepository.refuseCalls]). */
    internal fun refuseToolCalls() {
        tools.refuseCalls = true
    }

    /**
     * Runs the scenario to its end — a terminal state, or a park the scenario does not
     * resolve — on the virtual time of [scope], then checks it ended the way it declares.
     *
     * @param scope The `runTest` scope; its scheduler drives every delay and timeout.
     * @return The rendered golden trace.
     */
    suspend fun run(scope: TestScope): String {
        chatMessages += seededHistory()
        var task = startFreshTask()
        var graph = root
        var resume: ResumeContext? = null
        var ending: GoldenOutcome
        while (true) {
            ending = scope.attempt(task, graph, resume)
            trace.dropUnflushed()
            if (ending != GoldenOutcome.PARKED) break
            val resolution = parkResolutions.removeFirstOrNull() ?: break
            resolve(resolution)
            task = enqueued.removeFirstOrNull() ?: run {
                log.record("driver nothing to resume")
                break
            }
            val resumed = processResumeTask(task)
            graph = resumed.first
            resume = resumed.second
        }
        check(log.violations.isEmpty()) { "Scenario $scenario violated the harness: ${log.violations}" }
        check(approvals.isEmpty()) { "Scenario $scenario scripted approvals the run never raised: $approvals" }
        check(clarifications.isEmpty()) { "Scenario $scenario scripted answers nobody asked for: $clarifications" }
        check(parkResolutions.isEmpty()) { "Scenario $scenario scripted resolutions for parks that never happened" }
        check(ending == scenario.outcome) { "Scenario $scenario declares ${scenario.outcome} but ended $ending" }
        val status = runs.getRun(ROOT_RUN_ID)?.status
        check(statusMatches(ending, status)) { "Scenario $scenario ended $ending, but its run record says $status" }
        return GoldenTraceRenderer.render(scenario, log.events)
    }

    /** Whether the root run's record agrees with how the scenario ended. */
    private fun statusMatches(ending: GoldenOutcome, status: PipelineRunStatus?): Boolean = when (ending) {
        GoldenOutcome.COMPLETED -> status == PipelineRunStatus.COMPLETED
        GoldenOutcome.ERROR -> status == PipelineRunStatus.FAILED
        GoldenOutcome.PARKED -> status in WAITING_STATUSES
    }

    /**
     * The task queue accepting a fresh task: it creates the run record and marks it running
     * before the engine starts.
     *
     * @return The task, which every later attempt is driven from.
     */
    private suspend fun startFreshTask(): AgentTask {
        val task = AgentTask(
            id = ROOT_RUN_ID,
            sessionId = SESSION_ID,
            prompt = scenario.prompt,
            timestamp = 0L,
            pipelineId = root.id,
            origin = scenario.origin,
        )
        runs.createRun(
            PipelineRun(
                id = task.id,
                sessionId = task.sessionId,
                pipelineId = root.id,
                origin = task.origin,
                status = PipelineRunStatus.QUEUED,
                currentNodeId = null,
                startedAt = 0L,
                finishedAt = null,
                errorMessage = null,
                graphContentHash = root.contentHash(),
                userPrompt = task.prompt,
            ),
        )
        runs.markRunning(task.id, root.id, root.contentHash())
        return task
    }

    /**
     * The task queue picking up a resume task, as `TaskQueueManagerImpl.processResumeTask`
     * does: the graph is the one the run record names and must still hash as recorded, the
     * checkpoint comes from the durable trace, and the run is marked running before the
     * engine starts.
     *
     * @return The graph to run and the checkpoint to resume from.
     */
    private suspend fun processResumeTask(task: AgentTask): Pair<PipelineGraph, ResumeContext> {
        log.record("queue.resume ${task.id}")
        val run = runs.getRun(task.id) ?: log.violation("Resume task for an unknown run ${task.id}")
        val graph = run.pipelineId?.let { library[it] }
            ?: log.violation("Resume task for an unknown pipeline ${run.pipelineId}")
        val recordedHash = run.graphContentHash ?: log.violation("Run ${task.id} carries no graph hash")
        if (graph.contentHash() != recordedHash) log.violation("The graph of ${task.id} changed before its resume")
        val resume = trace.resumeContextFor(task.id)
        runs.markRunning(task.id, graph.id, recordedHash)
        return graph to resume
    }

    /**
     * Runs one engine invocation from [task], as the queue's `executeRun` does, and returns how
     * it ended.
     *
     * Terminal states are mirrored into the run record after the engine's flow completes. The
     * queue does it inside its collector, but its collector reads through a buffered
     * `channelFlow` (`failIfStalled`), so in the app the engine usually runs past the terminal
     * state — and makes its last writes — before the record is settled. Settling it here only
     * at the end follows that order instead of pinning the other side of the race.
     */
    private suspend fun TestScope.attempt(
        task: AgentTask,
        graph: PipelineGraph,
        resume: ResumeContext?,
    ): GoldenOutcome {
        tracker.startAttempt()
        model.startAttempt()
        log.record(if (resume == null) "driver start" else "driver attempt resumed")
        var ending: GoldenOutcome? = null
        var parkedKind: PendingInteractionKind? = null
        val terminalStates = mutableListOf<AgentOrchestratorState>()
        val job = launch {
            engine(
                sessionId = task.sessionId,
                userPrompt = task.prompt,
                graph = graph,
                runId = task.id,
                resume = resume,
                runHadImage = runs.getRun(task.id)?.hadImage == true,
                origin = task.origin,
                samplingOverride = samplingOverride,
            ).collect { state ->
                if (ending == GoldenOutcome.COMPLETED || ending == GoldenOutcome.ERROR) {
                    log.record("after the terminal state:")
                }
                recordState(state)
                when (state) {
                    is AgentOrchestratorState.Completed -> ending = GoldenOutcome.COMPLETED
                    is AgentOrchestratorState.Error -> ending = GoldenOutcome.ERROR
                    is AgentOrchestratorState.SuspendedInBackground -> {
                        ending = GoldenOutcome.PARKED
                        parkedKind = state.kind
                    }
                    else -> Unit
                }
                if (state is AgentOrchestratorState.Completed || state is AgentOrchestratorState.Error) {
                    terminalStates += state
                }
            }
        }
        var answeredApproval: String? = null
        var approvalRaisedAt: Long? = null
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
            val approval = engine.pendingApprovalFor(task.sessionId)
            if (approval != null && approval.requestId != answeredApproval) {
                answeredApproval = approval.requestId
                if (settleLiveApproval(approval.requestId) == ApprovalAction.LET_IT_PARK) approvalRaisedAt = currentTime
            }
            clarificationRepository.pendingRequests.first()
                .filter { answeredClarifications.add(it.id) }
                .forEach { settleLiveClarification(it.id) }
            if (job.isActive) advanceTimeBy(STEP_MS)
        }
        // The live window the gate waited before parking: virtual time, so deterministic, and
        // the one place the approval timeout setting is visible.
        if (parkedKind == PendingInteractionKind.APPROVAL && approvalRaisedAt != null) {
            log.record(
                "driver the approval waited ${(currentTime - approvalRaisedAt) / STEP_MS * STEP_MS} ms before parking",
            )
        }
        terminalStates.forEach { state ->
            when (state) {
                is AgentOrchestratorState.Completed -> runs.finishRun(task.id, PipelineRunStatus.COMPLETED)
                is AgentOrchestratorState.Error ->
                    runs.finishRun(task.id, PipelineRunStatus.FAILED, state.message, state.reason)
                else -> Unit
            }
        }
        return ending ?: log.violation("Scenario $scenario ended without a terminal state or a park")
    }

    private suspend fun settleLiveApproval(requestId: String): ApprovalAction {
        val action = approvals.removeFirstOrNull()
            ?: log.violation("Scenario $scenario raised an approval it does not settle; add an ApprovalAction")
        val outcome = when (action) {
            ApprovalAction.APPROVE -> submitApproval(SESSION_ID, requestId, isApproved = true)
            ApprovalAction.DENY -> submitApproval(SESSION_ID, requestId, isApproved = false)
            ApprovalAction.LET_IT_PARK -> null
        }
        log.record("driver approval ${action.name.lowercase()} request=${log.alias(requestId)} → ${outcome ?: "-"}")
        return action
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
            is AgentOrchestratorState.PipelineTrace -> recordPipelineTrace(state)
            is AgentOrchestratorState.ConsoleLog -> recordConsoleLog(state)
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

    /**
     * The trace panel's newest step normally repeats a trace record of the same node type, depth
     * and output — the one just written, or on a resume the one the replayed step came from;
     * only a step no record matches is spelled out.
     */
    private fun recordPipelineTrace(state: AgentOrchestratorState.PipelineTrace) {
        val last = state.steps.lastOrNull()
        val header = "state PipelineTrace steps=${state.steps.size} last=${last?.nodeName} depth=${last?.depth} " +
            "tokens=${last?.tokenCount}"
        if (last == null) {
            log.record(header)
            return
        }
        val record = trace.matchingNodeIo(last.nodeName, last.depth, last.outputText)
        if (record != null) {
            log.record("$header (= trace.node seq=${record.seq})")
        } else {
            log.record("$header (no trace record has this output)", "output" to last.outputText)
        }
    }

    /**
     * The live console's newest line normally repeats a persisted console record; only a
     * divergence is spelled out.
     */
    private fun recordConsoleLog(state: AgentOrchestratorState.ConsoleLog) {
        val last = state.events.lastOrNull()
        val header = "state ConsoleLog run=${state.runId} events=${state.events.size}"
        val record = last?.let { trace.consoleEntry(seq = it.seq, depth = it.depth) }
        if (last == null || (record != null && record.type == last.type && record.message == last.message)) {
            log.record(
                if (record ==
                    null
                ) {
                    header
                } else {
                    "$header (= trace.console run=${record.runId} seq=${record.seq})"
                },
            )
        } else {
            log.record("$header (differs from its trace record) ${last.type}", "message" to last.message)
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
        val network = object : NetworkActivityTracker by goldenStrict(log) {
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
        engine = testGraphExecutionEngine(
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
            RunHeaders(
                generationSettings = settings,
                seedSource = { RUN_SEED },
                environment = object : RunEnvironment {
                    override val appVersion = "golden (1)"
                    override val runtimeVersion = "LiteRT-LM golden"
                    override val device = "Golden Reference Phone · Android 16"
                },
            ),
        )
        return engine
    }

    /**
     * The settings every golden run reads. Numeric values differ from `SettingsDefaults`, so a
     * read replaced by the shipped default changes the trace wherever the value reaches; a
     * member no run path reads is a violation, not a silent default.
     */
    private fun strictSettings(): SettingsRepository {
        val chosen = scenario.settings
        val compressed = chosen.compressedHistory
        return object : SettingsRepository by goldenStrict(log) {
            override val systemPromptPrefix = flowOf("[golden prefix] Answer in plain words.")
            override val structuredOutputMaxRepairs = flowOf(3)
            override val temperature = flowOf(0.55f)
            override val topK = flowOf(17)
            override val topP = flowOf(0.85f)
            override val toolApprovalPolicy = flowOf(chosen.approvalPolicy)
            override val blockDestructiveTools = flowOf(chosen.blockDestructiveTools)
            override val toolCallTimeoutMs = flowOf(APPROVAL_WINDOW_MS)
            override val pipelineMaxNestingDepth = flowOf(4)
            override val pipelineMaxSteps = flowOf(chosen.maxSteps)
            override val pipelineMaxStepsBackground = flowOf(chosen.maxStepsBackground)
            override val runMaxTokens = flowOf(chosen.maxTokens)
            override val runMaxTokensBackground = flowOf(chosen.maxTokensBackground)
            override val verboseMemoryLoggingEnabled = flowOf(chosen.verboseMemoryLogging)
            override val chatHistoryCompressionEnabled = flowOf(compressed)
            override val chatHistoryCompressionThresholdTokens = flowOf(if (compressed) 500 else 4_000)
            override val chatHistoryLiveWindowSize = flowOf(if (compressed) 6 else 20)
            override val workspaceReadTokenBudget = flowOf(1_500)
            override val memorySummaryDefaultLimit = flowOf(3)
            override val backgroundApprovalWindowHours = flowOf(6)
            override val resumeMaxAgeHours = flowOf(12)
        }
    }

    private fun promptVariableProviders(
        localModels: LocalModelRepository,
        memoryRepository: MemoryRepository,
    ): Set<PromptVariableProvider> {
        val clock = Clock.fixed(Instant.parse(FROZEN_INSTANT), ZoneOffset.UTC)
        val identity = object : IdentityRepository by goldenStrict(log) {
            override suspend fun getIdentity(anonymousLabel: String) = Identity(anonymousLabel, "golden-device", true)
        }
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

    private fun playedTaskQueue(): TaskQueueManager = object : TaskQueueManager by goldenStrict(log) {
        override fun enqueueTask(task: AgentTask) {
            enqueued += task
            log.record(
                "queue.enqueue ${task.id} resume=${task.isResume} session=${task.sessionId} origin=${task.origin} " +
                    "pipeline=${task.pipelineId}",
                "prompt" to task.prompt,
            )
        }

        override fun resumeWithApproval(sessionId: String, requestId: String, isApproved: Boolean): Boolean =
            engine.resumeWithApproval(sessionId, requestId, isApproved)
    }

    private fun recordingChatRepository(): ChatRepository = object : ChatRepository by goldenStrict(log) {
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

    private fun recordingMemoryRepository(): MemoryRepository = object : MemoryRepository by goldenStrict(log) {
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
            MEMORY.take(RETRIEVED_CHUNKS).zip(listOf(0.91f, 0.74f))
        }
    }

    private fun recordingMetrics(): MetricsRepository = object : MetricsRepository by goldenStrict(log) {
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
        object : ModelPerformanceRepository by goldenStrict(log) {
            override suspend fun record(sample: ModelPerformanceSample) {
                log.record(
                    "modelPerformance.record path=${sample.modelPath} tokens=${sample.tokenCount} " +
                        "benchmark=${sample.isBenchmark}",
                )
            }
        }

    private fun recordingCrashReporting(): CrashReportingRepository =
        object : CrashReportingRepository by goldenStrict(log) {
            override suspend fun setCustomKey(key: String, value: String) {
                log.record("crash.key $key=$value")
            }

            override suspend fun recordException(throwable: Throwable, extras: Map<String, String>) {
                log.record("crash.exception ${throwable::class.simpleName} extras=$extras")
            }
        }

    private fun recordingJournal(): TriggerJournalRepository = object : TriggerJournalRepository by goldenStrict(log) {
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

    private fun localModelRepository(): LocalModelRepository {
        val active = LocalModel(
            id = 1,
            name = GoldenModel.MODEL_NAME,
            path = GoldenModel.MODEL_PATH,
            size = 0,
            isActive = true,
        )
        return object : LocalModelRepository by goldenStrict(log) {
            override suspend fun getActiveModel(): LocalModel = active

            override suspend fun currentFileHash(path: String): String? =
                MODEL_SHA256.takeIf { path == GoldenModel.MODEL_PATH }

            override fun getAllModels(): Flow<List<LocalModel>> = MutableStateFlow(listOf(active))
        }
    }

    /**
     * Only an OpenAI key is set, so an `auto` cloud node resolves to OpenAI. The keys `auto`
     * may ask about are answered; any other read stays a violation.
     */
    private fun apiKeys(): ApiKeyRepository {
        val strict = goldenStrict<ApiKeyRepository>(log)
        return object : ApiKeyRepository by strict {
            override fun getApiKey(provider: CloudProvider): Flow<String?> = when (provider) {
                CloudProvider.OPENAI -> flowOf("golden-openai-key")
                CloudProvider.GOOGLE, CloudProvider.ANTHROPIC, CloudProvider.DEEPSEEK -> flowOf(null)
                else -> strict.getApiKey(provider)
            }
        }
    }

    private fun libraryRepository(): PipelineRepository = object : PipelineRepository by goldenStrict(log) {
        override suspend fun getPipelineById(pipelineId: String): PipelineGraph? = library[pipelineId]
    }

    private fun skillRepository(): SkillRepository = object : SkillRepository by goldenStrict(log) {
        override suspend fun getSkillById(id: String): Skill? = skills[id]
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

        /** The statuses a parked run's record holds. */
        private val WAITING_STATUSES = setOf(
            PipelineRunStatus.WAITING_APPROVAL,
            PipelineRunStatus.WAITING_CLARIFICATION,
            PipelineRunStatus.WAITING_CEILING,
        )

        /** Default virtual-time limit of one engine invocation. */
        private const val ATTEMPT_LIMIT_MS = 10 * 60 * 1_000L

        /** The live approval window — half the shipped default. */
        private const val APPROVAL_WINDOW_MS = 30_000L

        /** The seed of every golden root run: fixed, so the derived call seeds are too. */
        private const val RUN_SEED = 20_261_005

        /** The registry's hash of the golden model file. */
        private const val MODEL_SHA256 = "90d3e1c1a5b4f6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5"

        /** Virtual-time step of the driver loop — below every delay the engine uses. */
        private const val STEP_MS = 100L

        /** The instant `$DATE` and `$TIME` render. */
        private const val FROZEN_INSTANT = "2026-09-15T09:30:00Z"

        /** Length of the conversation a compressed-history scenario starts from. */
        private const val LONG_CONVERSATION = 40

        /** How many of those messages the stored summary covers. */
        private const val SUMMARISED_MESSAGES = 30

        /** How many chunks a retrieval returns — the two most relevant of [MEMORY]. */
        private const val RETRIEVED_CHUNKS = 2

        /**
         * The long-term memory every golden run sees. Six chunks: more than the harness's
         * `$MEMORY_SUMMARY` limit (3) and than the shipped one (5), so the limit shows.
         */
        private val MEMORY = listOf(
            "The user prefers short answers with one example.",
            "The user is planning a trip to Lisbon in October.",
            "The user's sister is called Ana.",
            "The user works on a night shift on Thursdays.",
            "The user is learning Portuguese.",
            "The user does not drink coffee after noon.",
        ).mapIndexed { index, text ->
            MemoryChunk(id = 11L + index, text = text, embedding = FloatArray(0), timestamp = 1L + index)
        }
    }
}
