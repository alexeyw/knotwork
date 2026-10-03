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
import app.knotwork.android.domain.engine.ChatHistoryWindowPlanner
import app.knotwork.android.domain.engine.GraphExecutionEngine
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
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ClarificationOutcome
import app.knotwork.android.domain.models.ClarificationRequest
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.Identity
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySummary
import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.ClarificationRepository
import app.knotwork.android.domain.repositories.IdentityRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.services.NativeMemorySampler
import app.knotwork.android.domain.usecases.EvaluateIfConditionUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
 * chat-history planner, the prompt template engine and every prompt variable provider — the
 * providers run on frozen inputs (a fixed clock, locale, device and identity), not on stubs.
 *
 * **What is a fake, and why.** Only what reaches outside the process or the clock:
 * - the model at all three seams ([GoldenModel]);
 * - tools ([GoldenToolRepository] — recorded outputs, no side effects);
 * - long-term memory — a fixed snapshot of two chunks, recency scoring not involved;
 * - chat history — a fixed two-turn conversation plus whatever the run writes;
 * - run records, trace and parked interactions — in-memory, recording stores;
 * - the notifiers, and the user (the scenario's approvals, answers and grants).
 *
 * Settings are a strict mock: a setting the run path starts reading fails every scenario until
 * the harness gives it a value, so a new read cannot change behaviour unnoticed.
 *
 * **The driver** plays the task queue and the user. It runs an attempt on virtual time,
 * settles each live approval as the scenario says, and when an attempt parks it applies the
 * scenario's resolution to the parked record and resumes the root run from the checkpoint the
 * trace holds — as `TaskQueueManagerImpl` does.
 *
 * @param scenario The scenario to run.
 */
internal class GoldenTraceHarness(private val scenario: GoldenScenario) {

    private val log = GoldenEventLog()
    private val library: Map<String, PipelineGraph> = GoldenPipelineSources.library()
    private val root: PipelineGraph = GoldenPipelineSources.root(scenario.source)
    private val skills: Map<String, Skill> = GoldenPipelineSources.skills()
    private val tracker = GoldenNodeTracker(ROOT_RUN_ID, root, library)
    private val model = GoldenModel(tracker, scenario.script, root.id, log)
    private val tools = GoldenToolRepository(log)
    private val runs = GoldenPipelineRunRepository(log, tracker)
    private val trace = GoldenRunTraceRepository(log)
    private val pending = GoldenPendingInteractionRepository(log)
    private val approvals = ArrayDeque(scenario.approvals)
    private val clarifications = ArrayDeque(scenario.clarifications)
    private val parkResolutions = ArrayDeque(scenario.parkResolutions)
    private val chatMessages = mutableListOf<ChatMessage>()

    private val settings: SettingsRepository = strictSettings()
    private val engine: GraphExecutionEngine = buildEngine()

    /**
     * Runs the scenario to its end — a terminal state, or a park the scenario does not
     * resolve — on the virtual time of [scope].
     *
     * @param scope The `runTest` scope; its scheduler drives every delay and timeout.
     * @return The rendered golden trace.
     */
    suspend fun run(scope: TestScope): String {
        chatMessages += seededHistory()
        runs.seedRoot(
            PipelineRun(
                id = ROOT_RUN_ID,
                sessionId = SESSION_ID,
                pipelineId = root.id,
                origin = scenario.origin,
                status = PipelineRunStatus.RUNNING,
                currentNodeId = null,
                startedAt = 0L,
                finishedAt = null,
                errorMessage = null,
                graphContentHash = root.contentHash(),
                userPrompt = scenario.prompt,
            ),
        )
        var resume: ResumeContext? = null
        while (true) {
            val parked = scope.attempt(resume)
            if (!parked) break
            val resolution = parkResolutions.removeFirstOrNull() ?: break
            applyResolution(resolution)
            runs.prepareResume(ROOT_RUN_ID)
            resume = trace.resumeContextFor(ROOT_RUN_ID)
            log.record("driver resume $ROOT_RUN_ID from seq=${resume.nextSeq} replaying=${resume.records.size}")
        }
        check(approvals.isEmpty()) { "Scenario $scenario scripted approvals the run never raised: $approvals" }
        check(clarifications.isEmpty()) { "Scenario $scenario scripted answers nobody asked for: $clarifications" }
        check(parkResolutions.isEmpty()) { "Scenario $scenario scripted resolutions for parks that never happened" }
        return GoldenTraceRenderer.render(scenario, log.events)
    }

    /** Runs one engine invocation; returns `true` when it ended parked. */
    private suspend fun TestScope.attempt(resume: ResumeContext?): Boolean {
        tracker.startAttempt()
        log.record(if (resume == null) "driver start" else "driver attempt resumed")
        var parked = false
        var terminal = false
        val job = launch {
            engine(
                sessionId = SESSION_ID,
                userPrompt = scenario.prompt,
                graph = root,
                runId = ROOT_RUN_ID,
                resume = resume,
                origin = scenario.origin,
            ).collect { state ->
                if (terminal) log.record("after the terminal state:")
                recordState(state)
                when (state) {
                    is AgentOrchestratorState.SuspendedInBackground -> parked = true
                    is AgentOrchestratorState.Completed, is AgentOrchestratorState.Error -> terminal = true
                    else -> Unit
                }
            }
        }
        var answeredRequest: String? = null
        while (job.isActive) {
            runCurrent()
            val request = engine.pendingApprovalFor(SESSION_ID)
            if (request != null && request.requestId != answeredRequest) {
                answeredRequest = request.requestId
                settleLiveApproval(request.requestId)
            }
            if (job.isActive) advanceTimeBy(STEP_MS)
        }
        if (!parked && !terminal) log.record("end without a terminal state")
        return parked
    }

    private fun settleLiveApproval(requestId: String) {
        val action = approvals.removeFirstOrNull()
            ?: error("Scenario $scenario raised an approval it does not settle; add an ApprovalAction")
        log.record("driver approval ${action.name.lowercase()}")
        when (action) {
            ApprovalAction.APPROVE -> check(engine.resumeWithApproval(SESSION_ID, requestId, true))
            ApprovalAction.DENY -> check(engine.resumeWithApproval(SESSION_ID, requestId, false))
            ApprovalAction.LET_IT_PARK -> Unit
        }
    }

    private suspend fun applyResolution(resolution: ParkResolution) {
        val (runId, interaction) = pending.parked().entries.singleOrNull()?.toPair()
            ?: error("Scenario $scenario resolves a park, but ${pending.parked().size} interactions are parked")
        log.record("driver resolve $runId ${interaction.kind} with $resolution")
        when (resolution) {
            ParkResolution.Approve -> pending.decide(runId, PendingDecision.APPROVED)
            ParkResolution.Deny -> pending.decide(runId, PendingDecision.DENIED)
            is ParkResolution.Answer -> pending.answer(runId, resolution.text)
            ParkResolution.GrantSteps -> {
                check(interaction.kind == PendingInteractionKind.CEILING) { "GrantSteps on a ${interaction.kind} park" }
                runs.extendCeiling(requireNotNull(runs.getRootRunId(runId)), RunCeilingAxis.STEPS)
            }
        }
    }

    private fun recordState(state: AgentOrchestratorState) {
        when (state) {
            is AgentOrchestratorState.WaitingForApproval -> log.record(
                "state WaitingForApproval tool=${state.toolName} risk=${state.risk}",
                "arguments" to state.arguments,
            )
            is AgentOrchestratorState.AwaitingClarification -> log.record(
                "state AwaitingClarification timeoutMs=${state.request.timeoutMs}",
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
            is AgentOrchestratorState.NodeIO ->
                log.record("state NodeIO ${state.nodeId} ${state.nodeType} depth=${state.depth}")
            is AgentOrchestratorState.ExecutingTool -> log.record("state ExecutingTool ${state.toolName}")
            is AgentOrchestratorState.ObservationResult -> log.record("state ObservationResult ${state.toolName}")
            is AgentOrchestratorState.Completed -> log.record("end Completed", "finalResponse" to state.finalResponse)
            is AgentOrchestratorState.Error -> log.record(
                "end Error reason=${state.reason}",
                "message" to state.message,
            )
            else -> Unit
        }
    }

    private fun buildEngine(): GraphExecutionEngine {
        lateinit var engine: GraphExecutionEngine
        val chatRepository = recordingChatRepository()
        val memoryRepository = recordingMemoryRepository()
        val localModels = localModelRepository()
        val loadModel = recordingLoadModel()
        val structuredGate = StructuredOutputGate()
        val providers = promptVariableProviders(localModels, memoryRepository)
        val gate = ToolInvocationGate(
            tools,
            settings,
            recordingApprovalNotifier(),
            chatRepository,
            pending,
            recordTriggerHitlEvent = mockk(relaxed = true),
        )
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
            mockk(relaxed = true),
            mockk(relaxed = true),
            NativeMemorySampler { 0L },
            loadModel,
        )
        val cloud = CloudLlmNodeExecutor(
            settings,
            apiKeys(),
            mockk(relaxed = true),
            model.cloudClientFactory,
            model.cloudModelResolver,
            mockk(relaxed = true),
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
                scriptedClarifications(),
                pending,
                recordingClarificationNotifier(),
                recordTriggerHitlEvent = mockk(relaxed = true),
            ),
            PipelineNodeExecutor(libraryRepository(), settings, runs, trace, Provider { engine }),
            SkillNodeExecutor(skillRepository(), PromptTemplateEngine(), providers, tools, liteRt, cloud, gate),
        )
        engine = GraphExecutionEngine(
            factory,
            toolNode,
            chatRepository,
            settings,
            mockk(relaxed = true),
            PromptTemplateEngine(),
            providers,
            NodeContextBuilder(),
            ChatHistoryWindowPlanner(),
            fixedMemoryRetrieval(),
            mockk(relaxed = true),
            localModels,
            memoryRepository,
            runs,
            trace,
            ResolveRunCeilingsUseCase(settings),
            pending,
            recordingCeilingNotifier(),
        )
        return engine
    }

    private fun strictSettings(): SettingsRepository = mockk<SettingsRepository>().also { s ->
        every { s.systemPromptPrefix } returns flowOf("[golden prefix] Answer in plain words.")
        every { s.structuredOutputMaxRepairs } returns flowOf(2)
        every { s.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { s.blockDestructiveTools } returns flowOf(false)
        every { s.toolCallTimeoutMs } returns flowOf(APPROVAL_WINDOW_MS)
        every { s.pipelineMaxNestingDepth } returns flowOf(3)
        every { s.pipelineMaxSteps } returns flowOf(scenario.maxSteps ?: 60)
        every { s.pipelineMaxStepsBackground } returns flowOf(scenario.maxSteps ?: 40)
        every { s.runMaxTokens } returns flowOf(1_000_000)
        every { s.runMaxTokensBackground } returns flowOf(200_000)
        every { s.verboseMemoryLoggingEnabled } returns flowOf(false)
        every { s.chatHistoryCompressionEnabled } returns flowOf(false)
        every { s.chatHistoryCompressionThresholdTokens } returns flowOf(4_000)
        every { s.chatHistoryLiveWindowSize } returns flowOf(20)
        every { s.workspaceReadTokenBudget } returns flowOf(2_000)
        every { s.memorySummaryDefaultLimit } returns flowOf(3)
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

        override suspend fun getHistorySummary(sessionId: String) = null
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
            log.record("memory.retrieve", "query" to firstArg<String>())
            MEMORY.zip(listOf(0.91f, 0.74f))
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

    private fun scriptedClarifications(): ClarificationRepository = mockk<ClarificationRepository>().also {
        coEvery { it.requestAnswer(any()) } answers {
            val request = firstArg<ClarificationRequest>()
            val action = clarifications.removeFirstOrNull()
                ?: error("Scenario $scenario asked '${request.question}' and scripts no answer")
            log.record("driver clarification $action")
            when (action) {
                is ClarificationAction.Answer -> ClarificationOutcome.Answered(action.text)
                ClarificationAction.TimeOut -> ClarificationOutcome.TimedOut
            }
        }
    }

    private fun recordingApprovalNotifier(): ApprovalNotifier = object : ApprovalNotifier {
        override fun sendApprovalRequest(
            sessionId: String,
            requestId: String,
            toolName: String,
            arguments: String,
            risk: ToolRisk,
        ) = log.record("notify.approval tool=$toolName risk=$risk")

        override fun sendPersistentApprovalRequest(
            runId: String,
            sessionId: String,
            requestId: String,
            toolName: String,
            arguments: String,
            risk: ToolRisk,
        ) = log.record("notify.approval.persistent run=$runId tool=$toolName risk=$risk")

        override fun cancelApprovalNotification(requestId: String) = log.record("notify.approval.cancel")

        override fun cancelPreUpdateNotification(sessionId: String) = log.record("notify.approval.cancelPreUpdate")
    }

    private fun recordingClarificationNotifier(): ClarificationNotifier = object : ClarificationNotifier {
        override fun sendPersistentClarificationRequest(runId: String, sessionId: String, question: String) =
            log.record("notify.clarification.persistent run=$runId", "question" to question)

        override fun cancelClarificationNotification(sessionId: String) = log.record("notify.clarification.cancel")
    }

    private fun recordingCeilingNotifier(): CeilingNotifier = object : CeilingNotifier {
        override fun sendCeilingPauseRequest(runId: String, sessionId: String, breach: HardCeilingBreach) =
            log.record("notify.ceiling run=$runId breach=$breach")

        override fun cancelCeilingNotification(sessionId: String) = log.record("notify.ceiling.cancel")
    }

    private fun seededHistory(): List<ChatMessage> = listOf(
        ChatMessage(
            sessionId = SESSION_ID,
            role = Role.USER,
            content = "Earlier: what is a golden test?",
            timestamp = 1L,
        ),
        ChatMessage(
            sessionId = SESSION_ID,
            role = Role.AGENT,
            content = "A test that compares output with a recorded reference.",
            timestamp = 2L,
        ),
    )

    companion object {
        /** Session every golden run belongs to. */
        const val SESSION_ID: String = "golden-session"

        /** Run id of every scenario's root run. */
        const val ROOT_RUN_ID: String = "golden"

        /** The live approval window; a scenario that lets an approval park waits this long. */
        private const val APPROVAL_WINDOW_MS = 30_000L

        /** Virtual-time step of the driver loop — below every delay the engine uses. */
        private const val STEP_MS = 100L

        /** The instant `$DATE` and `$TIME` render. */
        private const val FROZEN_INSTANT = "2026-09-15T09:30:00Z"

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
