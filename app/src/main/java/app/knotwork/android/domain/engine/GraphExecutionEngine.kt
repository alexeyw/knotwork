package app.knotwork.android.domain.engine

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.constants.PipelineExecutionDefaults
import app.knotwork.android.domain.engine.executors.NodeExecutorFactory
import app.knotwork.android.domain.engine.executors.ToolNodeExecutor
import app.knotwork.android.domain.engine.structured.JsonPayloadExtractor
import app.knotwork.android.domain.engine.stuck.GraphStuckDetector
import app.knotwork.android.domain.engine.stuck.RunStepObservation
import app.knotwork.android.domain.engine.stuck.StuckVerdict
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatHistorySummary
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.EngineImageInput
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RouteLabels
import app.knotwork.android.domain.models.RunBudgetLedger
import app.knotwork.android.domain.models.RunContextNotes
import app.knotwork.android.domain.models.RunGeneratingModel
import app.knotwork.android.domain.models.RunImageDelivery
import app.knotwork.android.domain.models.RunNoticeCause
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.RunTreeContext
import app.knotwork.android.domain.models.ToolInvocationResult
import app.knotwork.android.domain.models.asCeilingBreach
import app.knotwork.android.domain.models.diagnostic
import app.knotwork.android.domain.models.usesContextConfig
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.CrashReportingRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine responsible for executing a given [PipelineGraph].
 * It traverses nodes starting from [NodeType.INPUT], evaluates conditions,
 * executes LLM inference, triggers tools, and reaches [NodeType.OUTPUT].
 */
// LargeClass is suppressed deliberately: this is the central pipeline
// orchestrator, and the run-walk logic (node traversal, lazy memory and
// chat-history resolution, HITL suspension, checkpoint/resume, sub-pipeline
// fan-out) is most readable as one cohesive state machine. Decomposing it into
// collaborators is tracked as future work rather than forced here.
@Suppress("LargeClass")
@Singleton
class GraphExecutionEngine
@Inject
// LongParameterList is suppressed on the constructor rather than on the class:
// every parameter here is a collaborator Hilt injects, so the list is assembled
// by the container and never written out by hand, and it shrinks only when the
// class is decomposed — the future work named above. Suppressing it class-wide
// would also silence the acknowledged finding on `invoke` below, and every
// future one in a 3k-line file.
@Suppress("LongParameterList")
constructor(
    private val nodeExecutorFactory: NodeExecutorFactory,
    private val toolNodeExecutor: ToolNodeExecutor,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val metricsRepository: MetricsRepository,
    private val promptTemplateEngine: PromptTemplateEngine,
    private val promptVariableProviders: Set<@JvmSuppressWildcards PromptVariableProvider>,
    private val nodeContextBuilder: NodeContextBuilder,
    private val chatHistoryWindowPlanner: ChatHistoryWindowPlanner,
    private val retrieveRelevantMemoryUseCase: RetrieveRelevantMemoryUseCase,
    private val crashReportingRepository: CrashReportingRepository,
    private val localModelRepository: LocalModelRepository,
    private val memoryRepository: MemoryRepository,
    private val pipelineRunRepository: PipelineRunRepository,
    private val runTraceRepository: RunTraceRepository,
    private val resolveRunCeilingsUseCase: ResolveRunCeilingsUseCase,
    private val pendingInteractionRepository: PendingInteractionRepository,
    private val ceilingNotifier: CeilingNotifier,
) {

    /**
     * Completes the live approval request [requestId] of [sessionId] with the
     * user's decision; delegates to the [ToolNodeExecutor] singleton that owns
     * the per-session suspension primitives.
     *
     * @param sessionId chat session id whose pending approval is being resolved.
     * @param requestId identity of the request the decision was given for.
     * @param isApproved `true` if the user approved tool execution, `false` to deny it.
     * @return `true` when the decision settled the live request it names.
     */
    fun resumeWithApproval(sessionId: String, requestId: String, isApproved: Boolean): Boolean =
        toolNodeExecutor.resumeWithApproval(sessionId, requestId, isApproved)

    /**
     * Returns the approval request the run of [sessionId] is currently
     * suspended on, or `null` when no approval gate is active. Mirrors
     * [resumeWithApproval] — both delegate to the [ToolNodeExecutor]
     * singleton that owns the per-session suspension primitives. Used by the
     * chat reattach protocol to restore the HITL confirmation card from the
     * authoritative pending snapshot.
     *
     * @param sessionId chat session id whose pending approval is queried.
     * @return the pending [AgentOrchestratorState.WaitingForApproval], or `null`.
     */
    fun pendingApprovalFor(sessionId: String): AgentOrchestratorState.WaitingForApproval? =
        toolNodeExecutor.pendingApprovalFor(sessionId)

    /**
     * Runs [graph] as the root of a new run tree.
     *
     * @param sessionId Id of the chat session the run belongs to.
     * @param userPrompt The user message that started the run.
     * @param graph The pipeline graph to execute.
     * @param runId Id of the persistent pipeline-run record this execution
     *   writes its progress into (the node currently executing and the
     *   WAITING_APPROVAL / WAITING_CLARIFICATION suspension statuses) and
     *   whose persistent trace receives every console event and per-node
     *   I/O snapshot (buffered, force-flushed at suspension and terminal
     *   points). `null` disables run persistence entirely — terminal statuses
     *   and the RUNNING transition are owned by the task queue, never by the
     *   engine.
     * @param resume Checkpoint payload that switches the walk into resume
     *   mode (see [ResumeContext]): while its seq-ordered record cursor is
     *   not exhausted, each visited non-INPUT/OUTPUT node consumes the next
     *   recorded snapshot instead of executing — the recorded output and
     *   routing verdicts drive the very same control-flow code live results
     *   would, so branches, queue iterations and inter-node inputs are
     *   re-derived deterministically without re-running executors. The first
     *   node without a record executes live; in particular a TOOL node the
     *   run died on raises a fresh HITL approval (see the TOOL asymmetry
     *   contract on [ResumeContext]). Requires a non-null [runId] — resuming
     *   without the persistent record/trace to continue makes no sense.
     *   `null` (the default) is a normal fresh run.
     * @param imageInput The run's single image attachment, already resolved to
     *   an absolute path + dimensions + byte size, or `null` for a text-only run
     *   and on resume (a replayed run never re-delivers). The engine emits an
     *   `Image input: W×H, N KB` console line at run start and seeds the tree's
     *   [RunTreeContext.imageDelivery] from it: the image reaches the **first**
     *   `LITE_RT` node whose context includes the original task in execution
     *   order *anywhere in the tree* (via [ExecutionScope.imagePath]) and exactly
     *   that node; every other node — and every `CLOUD` node — sees only text,
     *   realising the "attachment belongs to `userPrompt`, the graph carries
     *   text" contract. The send-time pre-flight verifies such a sink is
     *   *reachable* (recursing into sub-pipelines) before enqueuing;
     *   branch-dependent routing can still skip it, in which case the run emits
     *   an "Image not used" console note.
     * @param runHadImage Presence-only signal for a resumed run: `true` when the
     *   interrupted run's originating message carried an image (from the persisted
     *   `PipelineRun.hadImage`). A fresh run leaves this `false` and derives presence
     *   from [imageInput] instead. Becomes [RunTreeContext.imagePresent], so a
     *   live-executed IF/router node past the resume point can still branch on "the
     *   user sent a picture" even though the image itself is never re-delivered.
     * @param origin What started this run. Interactive origins ([RunOrigin.CHAT],
     *   [RunOrigin.SHARE]) key long-term-memory retrieval off [userPrompt] as before;
     *   background ones (trigger / scheduler / tile) prefer the pipeline's declared
     *   `memoryRetrievalQuery`, then the first memory-aware node's input — see
     *   [MemoryRetrievalQueryResolver] and `DESCRIPTION.md` §6.10.1. Defaults to
     *   [RunOrigin.CHAT] (the interactive, unchanged behaviour) so editor test runs and
     *   any caller that does not care keep the old semantics. Every sub-pipeline of
     *   the run inherits it through [RunTreeContext.origin].
     * @return A cold flow of orchestrator states describing the run.
     */
    operator fun invoke(
        sessionId: String,
        userPrompt: String,
        graph: PipelineGraph,
        runId: String? = null,
        resume: ResumeContext? = null,
        imageInput: EngineImageInput? = null,
        runHadImage: Boolean = false,
        origin: RunOrigin = RunOrigin.CHAT,
    ): Flow<AgentOrchestratorState> =
        invoke(sessionId, userPrompt, graph, runId, resume, RunEntry.Root(imageInput, runHadImage, origin))

    /**
     * Runs [graph] inside an existing run tree — the sub-pipeline a `PIPELINE`
     * node starts.
     *
     * Nothing about the tree is rebuilt: the ledger, the detector, the pending
     * notes, the image, the generating model and the origin are [tree]'s, so the
     * sub-pipeline charges the parent's ceilings and is observed as part of the
     * parent's run. No "Image input" line is emitted — the root announced it.
     *
     * @param sessionId Id of the chat session the run belongs to.
     * @param userPrompt The text the sub-pipeline runs on — the `PIPELINE` node's
     *   input, which becomes this graph's prompt.
     * @param graph The sub-pipeline's graph.
     * @param runId Id of the child run record, or `null` when the parent run is
     *   not persisted. See the root overload.
     * @param resume The child's checkpoint, when the parent died inside it.
     * @param tree The parent's run tree one level deeper
     *   ([RunTreeContext.nested]); its [RunTreeContext.depth] stamps this run's
     *   console and trace records.
     * @return A cold flow of orchestrator states describing the sub-pipeline run.
     */
    operator fun invoke(
        sessionId: String,
        userPrompt: String,
        graph: PipelineGraph,
        runId: String?,
        resume: ResumeContext?,
        tree: RunTreeContext,
    ): Flow<AgentOrchestratorState> = invoke(sessionId, userPrompt, graph, runId, resume, RunEntry.Nested(tree))

    /**
     * How an invocation obtains its run tree: built at the root, inherited below.
     *
     * @property depth Nesting depth of the invocation, known before the tree is:
     *   the root's tree is built only after the graph has been validated.
     */
    private sealed interface RunEntry {
        val depth: Int

        /**
         * A root run; the engine builds its tree from these.
         *
         * @property imageInput See the root overload's `imageInput`.
         * @property runHadImage See the root overload's `runHadImage`.
         * @property origin See the root overload's `origin`.
         */
        class Root(val imageInput: EngineImageInput?, val runHadImage: Boolean, val origin: RunOrigin) : RunEntry {
            override val depth: Int get() = 0
        }

        /**
         * A sub-pipeline running inside [tree].
         *
         * @property tree The run tree it belongs to, already one level deeper.
         */
        class Nested(val tree: RunTreeContext) : RunEntry {
            override val depth: Int get() = tree.depth
        }
    }

    /**
     * The walk both overloads share: validates [graph], obtains the run tree from
     * [entry], then executes nodes from INPUT until OUTPUT, a suspension, an error
     * or a protective stop.
     *
     * @param sessionId See the root overload.
     * @param userPrompt See the root overload.
     * @param graph See the root overload.
     * @param runId See the root overload.
     * @param resume See the root overload.
     * @param entry Where the run tree comes from.
     * @return A cold flow of orchestrator states describing the run.
     */
    // Reason: this is the agent's core orchestrator. It is a long single
    // state machine that walks the DAG, dispatches per node type, manages
    // queue/clarification/approval suspensions, emits typed orchestrator
    // states, and surfaces console events. Its decomposition into collaborators
    // is under way; each one that lands shortens this body, and the suppression
    // goes when it fits the thresholds.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun invoke(
        sessionId: String,
        userPrompt: String,
        graph: PipelineGraph,
        runId: String?,
        resume: ResumeContext?,
        entry: RunEntry,
    ): Flow<AgentOrchestratorState> = flow {
        val depth = entry.depth
        val imageInput = (entry as? RunEntry.Root)?.imageInput

        // Console lines and trace records of this invocation, in one numbering. A
        // resumed run continues the interrupted run's numbering instead of
        // colliding with its persisted records.
        val console = RunConsole(
            collector = this,
            runTraceRepository = runTraceRepository,
            sessionId = sessionId,
            runId = runId,
            depth = depth,
            pipelineName = graph.name,
            firstSeq = resume?.nextSeq ?: 0L,
        )

        // Position of the next checkpoint record to replay; meaningful only
        // in resume mode. Once it reaches the end of the recorded prefix the
        // walk is live for the rest of the run.
        var replayCursor = 0

        if (!graph.isValidDAG()) {
            // Push the console event BEFORE the terminal Error so the Error
            // remains the last value of the orchestrator state flow.
            // `TaskQueueManagerImpl.processTask` resets the flow to `Idle` in
            // its `finally` if the last value is anything other than
            // `Completed` / `Error`, so a trailing `ConsoleLog` would mask the
            // real failure for observers reading `stateFlow.value`.
            console.push(ConsoleEventType.Error, "Pipeline graph contains cycles")
            emit(AgentOrchestratorState.Error("Pipeline graph contains cycles and is invalid."))
            return@flow
        }

        val inputNode = graph.nodes.find { it.type == NodeType.INPUT }
        if (inputNode == null) {
            console.push(ConsoleEventType.Error, "Pipeline has no INPUT node")
            emit(AgentOrchestratorState.Error("Pipeline has no INPUT node"))
            return@flow
        }

        // Attach Crashlytics custom keys so any non-fatal recorded from
        // node executors downstream carries the pipeline/model context.
        // No-op when the user has not opted in to crash reporting.
        crashReportingRepository.setCustomKey(CRASH_KEY_PIPELINE_ID, graph.id)
        crashReportingRepository.setCustomKey(
            CRASH_KEY_ACTIVE_MODEL,
            localModelRepository.getActiveModel()?.name ?: ACTIVE_MODEL_NONE,
        )

        // Announce the image attachment once, at the root run's start (a
        // sub-pipeline run has no [imageInput]), so the console shows
        // the multimodal input before any node executes. The image itself is
        // delivered to a single LITE_RT node below; this line is informational.
        if (imageInput != null) {
            // Round to the nearest KB with a 1 KB floor: a valid sub-1 KB image
            // must never read "0 KB" (which looks like a broken attachment).
            val sizeKb = maxOf(1L, (imageInput.sizeBytes + BYTES_PER_KB / 2) / BYTES_PER_KB)
            console.push(
                ConsoleEventType.SystemMessage,
                "Image input: ${imageInput.width}×${imageInput.height}, $sizeKb KB",
            )
        }

        // A run resumed from a ceiling pause still holds the record of the
        // question it parked on. Nothing else will consume it — the other two
        // kinds are consumed by the node executor that raised them, and a
        // ceiling belongs to no node — so it is consumed here, at the one point
        // every attempt of every run in the tree passes through. Leaving it
        // would hand the maintenance sweep a park whose run is happily running
        // again, and the sweep's job is to fail exactly those.
        if (runId != null) {
            val parkedCeiling = pendingInteractionRepository.getForRun(runId)
            if (parkedCeiling?.kind == PendingInteractionKind.CEILING) {
                pendingInteractionRepository.delete(runId)
                ceilingNotifier.cancelCeilingNotification(sessionId)
            }
        }

        // The state the whole run tree shares. A sub-pipeline inherits its
        // parent's; the root builds one here, after the checks above, because
        // building it reads the run record (see [rootTree]).
        val tree = when (entry) {
            is RunEntry.Nested -> entry.tree
            is RunEntry.Root -> rootTree(runId, entry)
        }

        // Honesty note for a run that carried an image but routed down a path with
        // no vision sink: the send-time pre-flight guarantees such a node *exists*,
        // but branch-dependent routing can still skip it. Emitted only at the
        // root run (which owns the "Image input" announcement) and only when
        // the tree-wide delivery was never consumed at any depth.
        suspend fun noteUndeliveredImage() {
            if (imageInput != null && tree.imageDelivery?.consumed == false) {
                console.push(
                    ConsoleEventType.SystemMessage,
                    "Image not used: this run took a path with no on-device step that reads images.",
                )
            }
        }

        // Per-`PIPELINE`-node visit counter. A PIPELINE node inside a loop
        // (QUEUE_PROCESSOR) executes once per item; the index disambiguates the
        // child run id of each visit and is re-derived deterministically on
        // resume (it increments on replayed visits too), so the in-flight visit
        // lands on the same index as on the interrupted run.
        val pipelineVisitCounts = mutableMapOf<String, Int>()
        // For deterministic graphs (no routing/queue nodes) the total is fixed from the start.
        // For branching graphs it stays null until the active branch is resolved.
        val hasBranching = graph.nodes.any {
            it.type == NodeType.INTENT_ROUTER ||
                it.type == NodeType.IF_CONDITION ||
                it.type == NodeType.QUEUE_PROCESSOR
        }
        var estimatedTotalSteps: Int? = if (hasBranching) null else graph.nodes.size
        var currentNode: NodeModel? = inputNode
        var stepCount = 0
        var currentInputText = userPrompt
        // Whether a model wrote `currentInputText` (ModelAuthorship): the run's
        // prompt is the user's, a tool's result is not a model's, and a
        // pass-through node keeps whatever it received. The root OUTPUT records
        // it on the chat row so memory extraction skips relayed text.
        var currentInputByModel = false

        val activeQueue = mutableListOf<String>()
        var activeQueueProcessorId: String? = null
        val queueResults = mutableListOf<String>()
        val traceSteps = mutableListOf<AgentOrchestratorState.TraceStep>()

        // Long-term memory is retrieved lazily and at most once per run. Only
        // the first *executed* node that actually opts into the
        // `--- Long-Term Memory ---` block (`contextConfig.longTermMemory`)
        // triggers the query embedding — and that same node decides the
        // retrieval key (see [MemoryRetrievalQueryResolver]): an interactive run
        // keys off the immutable userPrompt as it always has, a background run
        // prefers the pipeline's declared query, then the node's own input,
        // because a trigger's prompt is authored once and describes no
        // particular firing. A graph where no executed node requests memory
        // never embeds anything at all — sparing avoidable embedding-provider
        // latency/cost and not shipping the prompt to a cloud embedding backend
        // the user did not ask memory for. A resumed run is seeded from the
        // interrupted run's persisted snapshot, so it neither re-runs retrieval
        // (the context must be identical to the interrupted one) nor re-counts
        // usage.
        var memoizedMemories: List<MemoryChunk>? = resume?.memorySnapshot
        suspend fun resolveMemoriesOnce(nodeInput: String): List<MemoryChunk> {
            memoizedMemories?.let { return it }
            // The declared query is a prompt template like any other, so `$DATE`
            // and friends resolve per run instead of being frozen at authoring
            // time. Rendering happens only when a declared query exists and only
            // on the one node that triggers retrieval.
            val declaredQuery = graph.memoryRetrievalQuery
                ?.takeIf { it.isNotBlank() }
                ?.let { promptTemplateEngine.render(it, promptVariableProviders.toList()) }
            val query = MemoryRetrievalQueryResolver.resolve(
                origin = tree.origin,
                declaredQuery = declaredQuery,
                nodeInput = nodeInput,
                userPrompt = userPrompt,
            )
            val scored: List<Pair<MemoryChunk, Float>> = try {
                retrieveRelevantMemoryUseCase.retrieveScored(query.text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Memory retrieval suspends (embedding + DB lookup). Swallowing
                // cancellation here would let the parent flow keep running after
                // the caller cancelled, breaking structured concurrency.
                throw e
            } catch (e: Exception) {
                Timber.tag("PipelineDebug").w(e, "Failed to retrieve long-term memories; continuing without them")
                emptyList()
            }
            val verbose = settingsRepository.verboseMemoryLoggingEnabled.first()
            console.push(
                ConsoleEventType.MemoryAccess,
                MemoryAccessLogFormatter.format(
                    query = query.text,
                    source = query.source,
                    hits = scored,
                    verbose = verbose,
                ),
            )
            val hits = scored.map { it.first }
            // Record that these chunks were injected into this run so the
            // Memory detail sheet can show "Used in N replies". Best-effort:
            // a failure here must never break the pipeline run.
            try {
                memoryRepository.recordUsage(hits.map { it.id }, System.currentTimeMillis())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("PipelineDebug").w(e, "Failed to record memory usage; continuing")
            }
            // Persist the resolved chunks so a checkpoint resume of this run
            // can seed its memory from the snapshot instead of re-running
            // retrieval — the resumed context must be identical to this one.
            console.recordMemorySnapshot(hits)
            return hits.also { memoizedMemories = it }
        }

        // Chat-history compression splits into a run-stable part and a per-node
        // part:
        //  - The cached summary and the compression settings are resolved at most
        //    once per run. They are safe to memoize because the background
        //    compressor is gated off while a pipeline is active (see
        //    ChatHistoryCompressionCoordinator), so the `chat_history_summaries`
        //    row and the settings cannot change mid-run.
        //  - The live message list is re-read on EVERY call and is NOT memoized.
        //    Message-writing nodes mutate it mid-run — in particular a TOOL node's
        //    observation is persisted as an `isFinal = false` SYSTEM chat message
        //    (ToolInvocationGate) — and a later history-enabled node must see it,
        //    so freezing the list here would hide in-run messages until the next
        //    user turn.
        // Not snapshotted for resume — chat history was never resume-stable.
        var chatCompressionResolved = false
        var chatCompressionEnabled = false
        var chatHistorySummary: ChatHistorySummary? = null
        var chatHistoryThresholdTokens = 0
        var chatHistoryLiveWindow = 0
        // The console note fires once per run, the first time compression actually
        // changes what a node sees.
        var historyCompressionLogged = false
        suspend fun resolveChatHistoryView(): ChatHistoryView {
            if (!chatCompressionResolved) {
                chatCompressionEnabled = settingsRepository.chatHistoryCompressionEnabled.first()
                chatHistorySummary = if (chatCompressionEnabled) {
                    try {
                        chatRepository.getHistorySummary(sessionId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag("PipelineDebug").w(e, "Failed to load chat-history summary; continuing without it")
                        null
                    }
                } else {
                    null
                }
                chatHistoryThresholdTokens = settingsRepository.chatHistoryCompressionThresholdTokens.first()
                chatHistoryLiveWindow = settingsRepository.chatHistoryLiveWindowSize.first()
                chatCompressionResolved = true
            }
            // Re-read fresh: the list grows as message-writing nodes append rows.
            val messages = chatRepository.getMessagesForSession(sessionId).first()
            val view = chatHistoryWindowPlanner.plan(
                messages = messages,
                summary = chatHistorySummary,
                compressionEnabled = chatCompressionEnabled,
                thresholdTokens = chatHistoryThresholdTokens,
                liveWindowSize = chatHistoryLiveWindow,
            )
            // Surface the compression only when it actually changed what the node
            // sees — a within-budget run stays silent — and only once per run.
            if (!historyCompressionLogged && (view.truncatedWithoutSummary || view.earlierSummary != null)) {
                historyCompressionLogged = true
                if (view.truncatedWithoutSummary) {
                    console.push(
                        ConsoleEventType.HistoryCompression,
                        "Chat history over budget; summary not ready, kept the last " +
                            "${view.liveWindow.size} messages",
                    )
                } else {
                    val gap = if (view.droppedUncoveredCount > 0) {
                        " (${view.droppedUncoveredCount} recent messages not yet summarized)"
                    } else {
                        ""
                    }
                    console.push(
                        ConsoleEventType.HistoryCompression,
                        "Chat history compressed: summarized older turns, kept the last " +
                            "${view.liveWindow.size} messages$gap",
                    )
                }
            }
            return view
        }
        // Tool invocations are accumulated as TOOL nodes complete and surfaced
        // via the `--- Tool Results ---` block on later nodes that opt in.
        val toolInvocationResults = mutableListOf<ToolInvocationResult>()

        // Tracks whether the persistent run record currently sits in a
        // WAITING_* suspension status, so the first state forwarded after
        // the suspension resolves flips the record back to RUNNING.
        var runSuspended = false

        // Set the moment a ceiling refuses to let the walk continue, so the
        // post-loop branch can say which one bound instead of re-deriving it.
        var terminationReason: RunTerminationReason? = null

        while (currentNode != null) {
            // Ask the ledger before charging, so a node that is refused is never
            // counted: a run stopped at the ceiling has spent exactly the
            // ceiling, not one more than it.
            val breach = tree.budget.hardBreach()
            if (breach != null) {
                // A ceiling is a number the user chose, and reaching it says
                // nothing went wrong — so the run asks whether it may carry on
                // instead of ending on the spot, which is what it used to do.
                //
                // The park is durable from the first moment, with no live
                // in-process phase before it. The two HITL gates have one
                // because something is in flight — a resolved tool call, a
                // generated question — that a park would have to persist and a
                // resume re-consume. Here the walk is *between* nodes: the
                // checkpoint already holds everything, so a live wait would
                // hold the foreground service open buying nothing.
                val ceiling = breach.asCeilingBreach()
                if (runId != null && ceiling != null && parkOnCeiling(runId, sessionId, ceiling)) {
                    // The diagnostic plus one stable word, not a sentence. The
                    // suffix is load-bearing: without it a pause and a stop
                    // produce byte-identical console lines, and whoever greps
                    // this later cannot tell a run that ended from one that is
                    // still waiting.
                    console.push(ConsoleEventType.RunCeiling, "${breach.diagnostic()} — paused")
                    val pause = AgentOrchestratorState.WaitingForCeilingRaise(
                        axis = ceiling.axis,
                        limit = ceiling.limit,
                        spent = ceiling.spent,
                    )
                    persistSuspensionTransition(runId, pause, runSuspended)
                    emit(pause)
                    // Ends the walk without a terminal state, exactly as an
                    // approval park does — and, from a sub-pipeline, tells the
                    // parent PIPELINE node to park the whole stack rather than
                    // settle this run.
                    emit(AgentOrchestratorState.SuspendedInBackground(PendingInteractionKind.CEILING))
                    console.flush()
                    return@flow
                }
                // Non-persisted runs (editor test runs) and storage failures
                // keep the old behaviour. A pause the user cannot be asked
                // about, or one recorded nowhere and so unanswerable after this
                // coroutine ends, is worse than a stop that says why it stopped.
                terminationReason = breach
                break
            }

            stepCount++

            // Visit index of this node when it is a PIPELINE node — incremented
            // on every entry (replayed or live) so the counter stays aligned
            // through a resume replay and the live visit gets the right index.
            val pipelineVisitIndex = if (currentNode.type == NodeType.PIPELINE) {
                val idx = pipelineVisitCounts.getOrDefault(currentNode.id, 0)
                pipelineVisitCounts[currentNode.id] = idx + 1
                idx
            } else {
                0
            }

            // Record the node about to execute so an interrupted run can
            // report where it stopped. The repository is best-effort by
            // contract — a storage failure never aborts the run itself.
            if (runId != null) {
                pipelineRunRepository.updateCurrentNode(runId, currentNode.id)
            }

            // Emit current step with dynamically estimated total (null = still unknown).
            emit(
                AgentOrchestratorState.PipelineStage(
                    AgentOrchestratorState.PipelineStepInfo(
                        stepIndex = stepCount,
                        totalSteps = estimatedTotalSteps,
                        nodeName = currentNode.type.name,
                    ),
                ),
            )
            // Checkpoint replay decision: while the resume cursor still holds
            // records, the recorded snapshot of this node substitutes for
            // execution. INPUT and OUTPUT nodes are never recorded (the trace
            // skips them by design), so they always run their executors even
            // mid-replay — INPUT is a pure passthrough, and a recorded OUTPUT
            // cannot exist for an interrupted run.
            val replayRecord = if (resume != null &&
                replayCursor < resume.records.size &&
                currentNode.type != NodeType.INPUT &&
                currentNode.type != NodeType.OUTPUT
            ) {
                resume.records[replayCursor]
            } else {
                null
            }
            if (replayRecord != null && replayRecord.nodeId != currentNode.id) {
                // The persisted prefix diverged from the graph walk: the trace
                // cannot serve as a checkpoint (corruption, or an edit that
                // slipped past hash validation). Failing loudly beats silently
                // executing a half-replayed run on inconsistent inputs.
                console.push(
                    ConsoleEventType.Error,
                    "Checkpoint trace diverged at ${currentNode.type.name}; resume aborted",
                )
                emit(
                    AgentOrchestratorState.Error(
                        "Recorded checkpoint no longer matches the pipeline graph. Restart the task instead.",
                        reason = RunTerminationReason.GraphChanged,
                    ),
                )
                return@flow
            }

            var nodeResult: NodeExecutionResult? = null
            val executorInput: String
            val nodeDurationMs: Long

            if (replayRecord != null) {
                // Replay branch: the node completed before the interruption —
                // its recorded output (and routing verdicts) feed the same
                // control flow a live result would. No executor call, no
                // metrics, no new NodeIo trace record; the compact console
                // event below is the only addition to the persisted trace.
                replayCursor++
                nodeResult = NodeExecutionResult(
                    outputText = replayRecord.outputText,
                    conditionResult = replayRecord.conditionResult,
                    routingKey = replayRecord.routingKey,
                    tokenCount = replayRecord.tokenCount,
                    resolvedToolName = replayRecord.resolvedToolName,
                )
                executorInput = replayRecord.inputText
                nodeDurationMs = replayRecord.durationMs
                console.push(
                    ConsoleEventType.NodeExecution,
                    "↻ ${currentNode.type.name} replayed from checkpoint",
                )
            } else {
                console.push(ConsoleEventType.NodeExecution, "▶ ${currentNode.type.name}")

                // Give UI time to render the stage before CPU-heavy inference starts
                kotlinx.coroutines.delay(PipelineExecutionDefaults.LITE_RT_PREWARM_DELAY_MS)

                val executor = nodeExecutorFactory.getExecutor(currentNode.type)
                Timber.tag(
                    "PipelineDebug",
                ).d(
                    "[NODE_IN] type=${currentNode.type.name} id=${currentNode.id} " +
                        "input=${currentInputText.take(PipelineExecutionDefaults.NODE_IO_LOG_CHAR_LIMIT)}",
                )

                // Render `$VARIABLE` placeholders in the node's system prompt before the LLM
                // sees it. We only touch nodes whose system prompt is actually fed into an LLM
                // engine — the others (TOOL, IF_CONDITION, INPUT, QUEUE_PROCESSOR) either ignore
                // `systemPrompt` or use it for non-LLM logic where placeholders are not expected.
                val nodeForExecution = renderNodeSystemPrompt(currentNode)

                // Compose the executor input by selecting only the context blocks the node
                // opted into via its [NodeContextConfig]. Control-flow nodes (INPUT,
                // IF_CONDITION, QUEUE_PROCESSOR) keep their raw passthrough semantics —
                // wrapping them would corrupt routing/queue state. OUTPUT in echo mode
                // (no systemPrompt) is also passed through so it forwards the upstream
                // result verbatim instead of leaking context headers to the user.
                executorInput = if (shouldComposeContext(currentNode)) {
                    // Embed + search only when this node actually renders the
                    // memory block; otherwise pass an empty list so retrieval is
                    // never triggered on its behalf.
                    val memoryEntries = if (currentNode.contextConfig.longTermMemory) {
                        // `currentInputText` is what this node actually executes
                        // on (the upstream node's output, or the run prompt for a
                        // node right behind INPUT) — the background run's
                        // second-choice retrieval key.
                        resolveMemoriesOnce(currentInputText)
                    } else {
                        emptyList()
                    }
                    // Only nodes that render chat history pay for loading +
                    // planning it; others get the empty view (no DB read). The
                    // view is recomputed per node (fresh message read) so in-run
                    // message writes — e.g. TOOL observations — are visible to
                    // later history-enabled nodes.
                    val chatHistoryView = if (currentNode.contextConfig.chatHistory) {
                        resolveChatHistoryView()
                    } else {
                        ChatHistoryView.EMPTY
                    }
                    // Read only when the node feeds the on-device model and
                    // there is tool text to cut: a result of this run, or an
                    // observation row (SYSTEM) in the history it replays.
                    val hasToolText = toolInvocationResults.isNotEmpty() ||
                        chatHistoryView.liveWindow.any { it.role == Role.SYSTEM }
                    val toolResultCharBudget = if (hasToolText && feedsOnDeviceModel(currentNode)) {
                        settingsRepository.workspaceReadTokenBudget.first() * ChatHistoryWindowPlanner.CHARS_PER_TOKEN
                    } else {
                        null
                    }
                    val executionContext = PipelineExecutionContext(
                        originalUserMessage = userPrompt,
                        chatHistory = chatHistoryView.liveWindow,
                        previousNodeOutput = currentInputText,
                        toolResults = toolInvocationResults.toList(),
                        memoryEntries = memoryEntries,
                        earlierSummary = chatHistoryView.earlierSummary,
                        toolResultCharBudget = toolResultCharBudget,
                    )
                    // No fallback to currentInputText: an empty result is the
                    // intended outcome of a sparse config (e.g. only toolResults=true
                    // before any tool has run). Step 3/6 forbids the all-flags-false
                    // case at the validation layer, so we will not silently leak
                    // previous-node output back into a node that opted out.
                    val composed = nodeContextBuilder.build(currentNode.contextConfig, executionContext)
                    // Deliver a soft warning raised by an earlier node into this
                    // node's *composed prompt*, so the model can wind the task up
                    // rather than discovering the hard stop by walking into it.
                    //
                    // Into `executorInput`, never into `currentInputText`. The
                    // latter is the text that travels between nodes, and for a
                    // node that does not compose a prompt it is *data*: a
                    // pass-through OUTPUT persists it verbatim as the agent's
                    // chat message, `QUEUE_PROCESSOR` parses it as a list,
                    // `IF_CONDITION` branches on it. Writing the notice there
                    // printed the engine's internals to the user as their
                    // answer — and guarding only the node it was handed to was
                    // not enough, because `INTENT_ROUTER` composes a prompt but
                    // deliberately forwards `currentInputText` unchanged, so the
                    // pollution outlived the router and reached OUTPUT anyway.
                    // Scoping it to one node's input makes that structurally
                    // impossible rather than conditionally avoided.
                    // A crossing or a stuck verdict announces itself on the
                    // console where it happens, but reaches the model only
                    // here, on the next node that composes a prompt.
                    //
                    // Except OUTPUT, which composes one and must still never be
                    // handed a note. Two reasons, and the second is the one
                    // that bites: advice to wrap up has no reader at the node
                    // that *is* the wrapping up; and `OutputNodeExecutor` falls
                    // back to persisting its own input verbatim when the model
                    // returns nothing, so a note delivered here becomes the
                    // agent's chat message on any empty generation. That is the
                    // third shape of the same defect — the first two were
                    // writing the note to `currentInputText`, and letting it
                    // survive an `INTENT_ROUTER` — and it is why the exclusion
                    // is on the node type rather than on the executor.
                    val notes = if (currentNode.type == NodeType.OUTPUT) null else tree.contextNotes.drain()
                    if (notes != null) "$notes\n\n$composed" else composed
                } else {
                    currentInputText
                }

                val nodeStartMs = System.currentTimeMillis()
                var runParked = false
                // A routing node validates its key against the labels of its own
                // outgoing edges, which only the graph knows — surface them through
                // the scope so the executor can constrain (and repair towards) a key
                // that actually matches a branch. Empty for every other node type.
                val routingChoices = if (currentNode.type == NodeType.INTENT_ROUTER) {
                    graph.connections
                        .filter { it.sourceNodeId == currentNode.id && !it.label.isNullOrBlank() }
                        .map { it.label!! }
                        .distinct()
                } else {
                    emptyList()
                }
                // Deliver the run's image to the FIRST vision-eligible node only:
                // a LITE_RT node whose context includes the original task (so the
                // image accompanies the user's prompt). Consumption is tracked on
                // the tree-shared delivery, so once any node at any depth takes
                // the image, every later node — and every CLOUD node — sees text only.
                val imagePathForNode = tree.imageDelivery
                    ?.takeIf {
                        !it.consumed &&
                            currentNode.type == NodeType.LITE_RT &&
                            currentNode.contextConfig.originalTask
                    }
                    ?.image
                    ?.absolutePath
                if (imagePathForNode != null) {
                    tree.imageDelivery?.consumed = true
                }
                // Note an undelivered image *before* the terminal OUTPUT node runs:
                // OUTPUT's executor emits the terminal `Completed`, after which the
                // engine must not push any further console line (it would shift the
                // last orchestrator state away from `Completed`). By the OUTPUT
                // iteration every upstream node — including any vision sink — has
                // already run, so the delivery state is final here.
                if (currentNode.type == NodeType.OUTPUT) {
                    noteUndeliveredImage()
                }
                try {
                    executor.execute(
                        nodeForExecution,
                        executorInput,
                        sessionId,
                        userPrompt,
                        runId,
                        ExecutionScope(
                            run = tree,
                            pipelineVisitIndex = pipelineVisitIndex,
                            routingChoices = routingChoices,
                            imagePath = imagePathForNode,
                            inputWrittenByModel = currentInputByModel,
                        ),
                    )
                        .collect { output ->
                            when (output) {
                                is NodeOutput.State -> {
                                    if (output.state is AgentOrchestratorState.SuspendedInBackground) {
                                        // Bypass persistSuspensionTransition: its
                                        // wasSuspended branch would flip the record
                                        // back to RUNNING, but a parked run must
                                        // keep its WAITING_* status.
                                        runParked = true
                                    } else if (runId != null) {
                                        runSuspended =
                                            persistSuspensionTransition(runId, output.state, runSuspended)
                                    }
                                    emit(output.state.withRedactedError())
                                }
                                is NodeOutput.Result -> nodeResult = output.result
                                is NodeOutput.Console -> {
                                    // The node has no console sink of its own; the
                                    // engine owns `seq`/`depth` stamping. A repair
                                    // attempt additionally bumps the per-node repair
                                    // counter so the statistics surface reflects how
                                    // often this node's structured output stumbled.
                                    console.push(output.type, output.message)
                                    if (output.type == ConsoleEventType.StructuredOutputRepair) {
                                        metricsRepository.recordStructuredOutputRepair(nodeForExecution.label)
                                    }
                                }
                            }
                        }

                    // A parked run ends the walk without a terminal state: the
                    // executor made the pending request durable and the run
                    // record keeps its WAITING_* status — the user's response
                    // resumes the run from its checkpoint later, possibly in
                    // another process. Flush the buffered trace first so the
                    // checkpoint is complete up to this exact node.
                    if (runParked) {
                        console.push(
                            ConsoleEventType.NodeExecution,
                            "⏸ ${currentNode.type.name} parked awaiting user response",
                        )
                        console.flush()
                        return@flow
                    }

                    // The executor flow completing means any HITL suspension of
                    // this node is definitively resolved — flip the record back
                    // to RUNNING here instead of waiting for the next forwarded
                    // state (a clarification node, for instance, emits no state
                    // after its answer arrives, which would otherwise leave the
                    // record stale-WAITING through the next node's model load).
                    if (runId != null && runSuspended) {
                        pipelineRunRepository.updateStatus(runId, PipelineRunStatus.RUNNING)
                        runSuspended = false
                    }

                    Timber.tag(
                        "PipelineDebug",
                    ).d(
                        "[NODE_OUT] type=${currentNode.type.name} id=${currentNode.id} " +
                            "output=${nodeResult?.outputText?.take(PipelineExecutionDefaults.NODE_IO_LOG_CHAR_LIMIT)}",
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Node executors suspend; collapsing a cancelled run into
                    // an `Error` emission would both surface a false error and
                    // keep the flow alive past its collector's cancellation.
                    throw e
                } catch (e: Exception) {
                    // The exception may be a provider's, quoting its key. The throwable
                    // still goes to Timber for its stack trace — the crash-reporting tree
                    // redacts every record it forwards — but the text surfaced and
                    // persisted from here is redacted at this line.
                    val safeMessage = CloudErrorSanitizer.redactSecrets(e.message ?: "Unknown error")
                    Timber.tag(
                        "PipelineDebug",
                    ).e(e, "[NODE_ERR] type=%s id=%s error=%s", currentNode.type.name, currentNode.id, safeMessage)
                    console.push(
                        ConsoleEventType.Error,
                        "${currentNode.type.name}: $safeMessage",
                    )
                    emit(AgentOrchestratorState.Error(safeMessage))
                    return@flow
                }
                nodeDurationMs = System.currentTimeMillis() - nodeStartMs
                metricsRepository.recordNodeExecution(currentNode.type, nodeDurationMs, nodeResult?.tokenCount)
                // Charge the tree only for work actually done. A replayed node
                // was charged when it really ran; charging it again would make a
                // run that parks often die earlier than one that never parks —
                // the inverse of what the persisted ledger is for. That is why
                // this sits in the live branch, beside the metrics write and the
                // trace append, which are guarded the same way.
                //
                // Two further exclusions keep the *step* counter stable across
                // attempts, which is the property a persisted counter has to have
                // and the one a per-attempt budget never needed:
                //
                //  - INPUT and OUTPUT are never written to the trace, so they are
                //    never replayed and run their executors again on every
                //    resumed attempt. On a fresh attempt they are ordinary steps
                //    and are charged; on a resume they were already charged the
                //    first time, and charging them again would let a run that
                //    parks fifteen times exhaust a fifteen-step ceiling on
                //    pass-through nodes alone — which is the very scenario the
                //    persisted counter exists to bound.
                //  - A node whose result carries a termination reason is a
                //    `PIPELINE` node whose child already charged this same tree
                //    and stopped it; charging the parent node on top would
                //    persist one more than the ceiling it just reported.
                //
                // Tokens are charged unconditionally: INPUT produces none, and
                // OUTPUT is terminal, so neither can be double-counted later.
                val replayedPassThrough = resume != null &&
                    (currentNode.type == NodeType.INPUT || currentNode.type == NodeType.OUTPUT)
                val chargeableStep = !replayedPassThrough && nodeResult?.terminationReason == null
                if (chargeableStep) {
                    tree.budget.chargeStep()
                }
                tree.budget.chargeTokens(nodeResult?.tokenCount, approximate = nodeResult?.tokensEstimated != false)
                if (runId != null) {
                    persistSpend(tree.budget)
                }
                // Announce a soft crossing where it happens, not at the top of
                // the next iteration: a run whose last node crosses the
                // threshold would otherwise never say so, because there is no
                // next iteration to say it in.
                //
                // OUTPUT is excluded for the reason the `✓` event and the
                // `NodeIO` emission below are: its executor has already emitted
                // `Completed`, and a console line pushed after that would shift
                // the terminal state away from the tail of the flow. Nothing is
                // lost — a warning that the run is approaching its limit has no
                // reader once the answer has been delivered.
                if (currentNode.type != NodeType.OUTPUT) {
                    tree.budget.claimSoftBreach()?.let { soft ->
                        // The cause is typed at the source. The console gets the
                        // terse diagnostic; the sentence the user reads is
                        // resolved from this same value in the presentation
                        // layer, so the crossing is worded once rather than once
                        // per surface.
                        val cause = RunNoticeCause.ApproachingCeiling(
                            axis = soft.axis,
                            spent = soft.spent,
                            hardLimit = soft.hardLimit,
                        )
                        console.push(ConsoleEventType.RunCeiling, cause.diagnostic())
                        emit(AgentOrchestratorState.RunNotice(cause))
                        tree.contextNotes.add(SOFT_CEILING_CONTEXT_NOTE)
                    }
                }
            }
            val nodeTokenCount = nodeResult?.tokenCount

            val nodeError = nodeResult?.error?.let(CloudErrorSanitizer::redactSecrets)
            if (nodeError != null) {
                // Every node's failure passes this line on its way to the run record,
                // the console and the surface, so this is where a credential quoted
                // in a provider error is stopped regardless of which executor
                // produced it — or forgot to scrub it.
                Timber.tag(
                    "PipelineDebug",
                ).e("[NODE_ERR] type=%s id=%s error=%s", currentNode.type.name, currentNode.id, nodeError)
                console.push(
                    ConsoleEventType.Error,
                    "${currentNode.type.name}: $nodeError",
                )
                // A queue whose author turned `stopOnError` off keeps going: the
                // failure becomes this item's result and the next item starts.
                // Opt-in on purpose — `null` and `true` both fail the run, which
                // is what every pipeline saved before this field did.
                //
                // A typed cause is never survivable, whatever the switch says. A
                // `PIPELINE` node forwards a sub-pipeline's ceiling breach or
                // stuck-detector verdict through this same field, and those are
                // not "this subtask failed" — they are the run being out of
                // budget or going in circles. Carrying on would spend the very
                // budget the breach reported as gone.
                val failedQueueId = activeQueueProcessorId
                val queueNode = failedQueueId?.let { id -> graph.nodes.find { it.id == id } }
                val survivable = nodeResult?.terminationReason == null
                if (survivable && failedQueueId != null && queueNode?.stopOnError == false) {
                    queueResults.add("Subtask failed: $nodeError")
                    val step = stepQueue(graph, failedQueueId, activeQueue, queueResults)
                    if (step.queueFinished) activeQueueProcessorId = null
                    currentInputByModel = false
                    currentInputText = step.inputText
                    currentNode = step.node
                    continue
                }
                // A `PIPELINE` node forwards its sub-pipeline's typed cause here.
                // Re-emitting it is what keeps a ceiling breach one nesting
                // level down from settling the root run as an ordinary failure.
                emit(AgentOrchestratorState.Error(nodeError, reason = nodeResult?.terminationReason))
                return@flow
            }

            // Skip the "✓" event for OUTPUT — its own emitted Completed state
            // already marks the end of the pipeline, and pushing a ConsoleLog
            // after Completed would shift the terminal state away from the
            // tail of the flow. Replayed nodes already pushed their compact
            // "↻ replayed" event instead.
            if (currentNode.type != NodeType.OUTPUT && replayRecord == null) {
                console.push(
                    ConsoleEventType.NodeExecution,
                    "✓ ${currentNode.type.name} in ${nodeDurationMs}ms",
                )
            }

            if (currentNode.type == NodeType.TOOL) {
                val toolOutput = nodeResult?.outputText ?: ""
                // Prefer the executor-resolved tool name so "auto"-configured TOOL
                // nodes attribute the observation to the tool that actually ran,
                // not the literal "auto" placeholder. Fall back to the node's
                // configured toolName, then the node label as a last resort.
                val toolName = nodeResult?.resolvedToolName
                    ?: currentNode.toolName?.takeUnless { it.equals("auto", ignoreCase = true) }
                    ?: currentNode.label
                toolInvocationResults += ToolInvocationResult(toolName = toolName, output = toolOutput)
                console.push(ConsoleEventType.ToolCall, toolName)
            }

            if (currentNode.type != NodeType.INPUT && currentNode.type != NodeType.OUTPUT) {
                val outputText = nodeResult?.outputText ?: currentInputText
                traceSteps.add(
                    AgentOrchestratorState.TraceStep(
                        nodeName = currentNode.type.name,
                        outputText = outputText,
                        durationMs = nodeDurationMs,
                        tokenCount = nodeTokenCount,
                        depth = depth,
                    ),
                )
                // Write-through into the persistent run trace, which a checkpoint
                // resume replays instead of re-running the node. A replayed node
                // appends nothing — its record is already in the trace.
                if (replayRecord == null) {
                    console.recordNodeIo(currentNode, executorInput, outputText, nodeDurationMs, nodeResult)
                }
                emit(AgentOrchestratorState.PipelineTrace(traceSteps.toList()))
                // Surface the per-node I/O pair for the Vars tab of the
                // chat-home console pane. INPUT is skipped (its input is
                // the raw user prompt already surfaced as the latest chat
                // row) and OUTPUT is skipped
                // (already terminal; emitting after the `Completed` of
                // OUTPUT would shift the terminal state away from the
                // tail of the flow — same rule applied to the `✓`
                // console event upstream).
                emit(
                    AgentOrchestratorState.NodeIO(
                        nodeId = currentNode.id,
                        nodeType = currentNode.type.name,
                        input = executorInput,
                        output = outputText,
                        depth = depth,
                    ),
                )
            }

            // ── Stuck-detector observation point ──────────────────────────
            //
            // Placed **after** the trace append above, not before it. The
            // stop's whole user-facing promise is *Open console*, where the
            // repetition is visible — and a `break` taken before the append
            // would drop the one step the verdict was actually reached on, so
            // the console would be missing precisely the evidence the reader
            // was sent to find. Judging the same input/output pair the trace
            // has just recorded is the point: for every node the trace carries,
            // what the detector saw and what a person can read back are the
            // same thing. (INPUT is the one exception, and it is the trace's
            // rule, not this one: INPUT and OUTPUT are never recorded. INPUT is
            // still observed, because the pass-through accounting needs it, but
            // it can only ever contribute a pass-through — never the repetition
            // a verdict is reached on.)
            //
            // OUTPUT is excluded on the same grounds as the ✓ event and the
            // soft-ceiling notice above: its executor has already emitted
            // `Completed`, and a console line pushed after that would shift the
            // terminal state away from the tail of the flow. Nothing is lost —
            // a run that has just delivered its answer is not going in circles.
            if (currentNode.type != NodeType.OUTPUT) {
                val observation = RunStepObservation(
                    nodeId = currentNode.id,
                    inputFingerprint = GraphStuckDetector.fingerprint(executorInput),
                    // The engine's own fallback for a node that emitted no
                    // result is to forward its input, so the fingerprints match
                    // and the step reads as the pass-through it is.
                    outputFingerprint = GraphStuckDetector.fingerprint(nodeResult?.outputText ?: executorInput),
                )
                if (replayRecord != null) {
                    // Rebuild the window from history without re-deciding it:
                    // the attempt that actually ran this prefix did not stop on
                    // it, and reaching a different verdict now would be the app
                    // rewriting what already happened.
                    //
                    // The escalation it earned does carry forward, though, and
                    // that obliges us to carry the advice with it: notes are
                    // live-only, so the attempt that raised one and then parked
                    // destroyed it. Re-queue it for the first live node that
                    // composes a prompt — otherwise the resumed run inherits
                    // only the clock and is stopped for ignoring something it
                    // was never told. The console line and the on-screen notice
                    // are not repeated: they were emitted when the verdict was
                    // actually reached, and a warning re-shown on every resume
                    // is one the reader learns to skip.
                    if (tree.stuckDetector.replay(observation)) {
                        tree.contextNotes.add(STUCK_CONTEXT_NOTE)
                    }
                } else {
                    when (val verdict = tree.stuckDetector.observe(observation)) {
                        StuckVerdict.Healthy -> Unit
                        is StuckVerdict.Nudge -> {
                            val cause = RunNoticeCause.LooksStuck(verdict.signal)
                            console.push(ConsoleEventType.StuckDetector, cause.diagnostic())
                            emit(AgentOrchestratorState.RunNotice(cause))
                            tree.contextNotes.add(STUCK_CONTEXT_NOTE)
                        }
                        is StuckVerdict.Stop -> {
                            // Same seam the ceilings use: set the reason and
                            // leave the walk. The post-loop branch owns the
                            // console line and the typed terminal state, so a
                            // protective stop is worded in exactly one place
                            // however it was decided.
                            console.push(ConsoleEventType.StuckDetector, verdict.signal.diagnostic)
                            terminationReason = RunTerminationReason.NoProgress
                            break
                        }
                    }
                }
            }

            if (currentNode.type == NodeType.OUTPUT) {
                return@flow
            }

            if (currentNode.type == NodeType.QUEUE_PROCESSOR) {
                val list = parseListFromText(nodeResult?.outputText ?: currentInputText)
                activeQueue.clear()
                activeQueue.addAll(list)
                queueResults.clear()
                activeQueueProcessorId = currentNode.id

                val edges = graph.connections.filter { it.sourceNodeId == currentNode.id }
                val itemNodeId = edges.find { RouteLabels.matches(it.label, RouteLabels.ITEM) }?.targetNodeId
                    ?: edges.firstOrNull()?.targetNodeId
                val doneNodeId = edges.find { RouteLabels.matches(it.label, RouteLabels.DONE) }?.targetNodeId

                if (activeQueue.isNotEmpty() && itemNodeId != null) {
                    // Compute dynamic total: current steps already done + all queue iterations + tail after queue.
                    val itemNode = graph.nodes.find { it.id == itemNodeId }
                    val doneNode = graph.nodes.find { it.id == doneNodeId }
                    val nodesPerItem = countNodesOnPath(itemNode, graph, stopNodeIds = setOf(currentNode.id))
                    val nodesAfterQueue = countNodesOnPath(doneNode, graph)
                    val totalItems = activeQueue.size // before removeAt — full queue size
                    estimatedTotalSteps = stepCount + totalItems * nodesPerItem + nodesAfterQueue

                    val nextItem = activeQueue.removeAt(0)
                    val contextStr = queueResults.mapIndexed { i, res ->
                        "Result of Subtask ${i + 1}:\n$res"
                    }.joinToString("\n\n")
                    val subtaskInstruction = DefaultPrompts.QueueProcessor.SUBTASK_INSTRUCTION
                    // Assembled from earlier results and a planned subtask: the
                    // app's text around other nodes' output, so not a model's.
                    currentInputByModel = false
                    currentInputText = if (contextStr.isNotEmpty()) {
                        "PREVIOUS RESULTS CONTEXT:\n$contextStr\n\n---\n\n$subtaskInstruction\n\nCURRENT SUBTASK TO EXECUTE:\n$nextItem"
                    } else {
                        "$subtaskInstruction\n\nCURRENT SUBTASK TO EXECUTE:\n$nextItem"
                    }
                    currentNode = graph.nodes.find { it.id == itemNodeId }
                    continue
                } else {
                    activeQueueProcessorId = null
                    currentNode = graph.nodes.find { it.id == doneNodeId }
                    continue
                }
            }

            // INTENT_ROUTER's outputText is the routing key — a control signal, not a content payload.
            // Preserve currentInputText so downstream nodes receive the original data, not the routing label.
            currentInputByModel =
                ModelAuthorship.after(currentNode.type, nodeResult, currentInputText, currentInputByModel)
            currentInputText = if (currentNode.type == NodeType.INTENT_ROUTER) {
                currentInputText
            } else {
                nodeResult?.outputText ?: currentInputText
            }

            val nextNodeId = findNextNodeId(currentNode, graph, nodeResult?.conditionResult, nodeResult?.routingKey)
            val nextNode = graph.nodes.find { it.id == nextNodeId }

            // After a branching node resolves its path, compute the estimated total for that branch.
            if (currentNode.type == NodeType.INTENT_ROUTER || currentNode.type == NodeType.IF_CONDITION) {
                estimatedTotalSteps = stepCount + countNodesOnPath(nextNode, graph)
            }

            val activeQueueId = activeQueueProcessorId
            if (activeQueueId != null && (nextNode == null || nextNode.type == NodeType.QUEUE_PROCESSOR)) {
                queueResults.add(currentInputText)
                val step = stepQueue(graph, activeQueueId, activeQueue, queueResults)
                if (step.queueFinished) activeQueueProcessorId = null
                currentInputByModel = false
                currentInputText = step.inputText
                currentNode = step.node
                continue
            }

            currentNode = nextNode
        }

        // Covers loop exits that don't go through an OUTPUT node (a dangling graph
        // whose walk ends with no terminal node); the OUTPUT path notes it inline.
        noteUndeliveredImage()

        val breach = terminationReason
        if (breach != null) {
            // A ceiling refused to let the walk continue. When this run is a
            // sub-pipeline the Error becomes the parent PIPELINE node's error and
            // terminates the whole stack; the typed reason travels with it, so a
            // breach at any depth stays classified all the way up.
            //
            // Both carriers get the *diagnostic* form, not prose. This string is
            // persisted to `pipeline_runs.errorMessage` and written to the run
            // trace, where it is read by engineers; the sentence a person reads
            // is resolved from `breach` in the presentation layer. Keeping a
            // hand-written English sentence here is what previously let one
            // event acquire four different wordings, two of which described
            // behaviour the engine never had.
            console.push(consoleTypeFor(breach), breach.diagnostic())
            emit(AgentOrchestratorState.Error(breach.diagnostic(), reason = breach))
        } else {
            // Loop exited because currentNode became null before reaching OUTPUT
            console.push(ConsoleEventType.Error, "Pipeline terminated without OUTPUT")
            emit(
                AgentOrchestratorState.Error(
                    "Pipeline execution terminated unexpectedly without reaching OUTPUT node.",
                ),
            )
        }
    }.onCompletion {
        // Terminal flush: completion, failure and cancellation all land here,
        // so the persisted trace is complete the moment the run ends — even
        // when the process is about to die right after. The cancellation path
        // arrives with the coroutine already cancelled, hence NonCancellable;
        // the flush itself is best-effort and never throws storage failures.
        if (runId != null) {
            withContext(NonCancellable) {
                runTraceRepository.flush()
            }
        }
    }

    /**
     * Builds the run tree of a root run.
     *
     * The ledger's ceilings are resolved from the run's origin and its counters
     * seeded from what the run record already holds. The seed is what makes a
     * ceiling bind across a resume: every answered background approval comes
     * back through the resume path, and a run may park any number of times — a
     * ledger that started at zero on each attempt would hand a nightly loop a
     * fresh ceiling after every answer, which is the one scenario these
     * ceilings exist for.
     *
     * The repetition detector is built fresh rather than seeded from storage:
     * its state is a *sequence* of steps, and the sequence lives in the run
     * trace, which the walk re-reads anyway on a resume. Every replayed record
     * is fed back through `replay(...)` as it passes, so a resumed run rebuilds
     * the window it had rather than starting blind — which matters precisely
     * because a run that parks often is a run in a loop.
     *
     * @param runId Id of the root run record, or `null` for a non-persisted run.
     * @param root The root overload's image and origin arguments.
     * @return The tree at depth `0`.
     */
    private suspend fun rootTree(runId: String?, root: RunEntry.Root): RunTreeContext {
        val ceilings = resolveRunCeilingsUseCase(root.origin)
        val spent = runId?.let { pipelineRunRepository.getSpend(it) } ?: RunSpend()
        val delivery = root.imageInput?.let { RunImageDelivery(it) }
        return RunTreeContext(
            depth = 0,
            budget = RunBudgetLedger(
                ceilings = ceilings,
                rootRunId = runId,
                stepsAlreadySpent = spent.steps,
                tokensAlreadySpent = spent.tokens,
                stepCeilingExtensions = spent.stepCeilingExtensions,
                tokenCeilingExtensions = spent.tokenCeilingExtensions,
            ),
            stuckDetector = GraphStuckDetector(),
            contextNotes = RunContextNotes(),
            imageDelivery = delivery,
            // A fresh run knows from the attachment; a resumed one (which never
            // re-delivers) from the persisted PipelineRun.hadImage.
            imagePresent = delivery != null || root.runHadImage,
            generatingModel = RunGeneratingModel(),
            origin = root.origin,
        )
    }

    /**
     * Counts the number of nodes reachable from [startNode] by following the first outgoing edge
     * of each node, including [startNode] itself. Stops at [NodeType.OUTPUT] (inclusive),
     * dead ends, already-visited nodes, or any node whose ID is in [stopNodeIds].
     *
     * Used to estimate the remaining steps on the active branch after a routing decision
     * or to measure the item-subgraph depth inside a [NodeType.QUEUE_PROCESSOR].
     *
     * @param startNode The node to start counting from, or null (returns 0).
     * @param graph The pipeline graph to traverse.
     * @param stopNodeIds IDs of nodes that act as exclusive stop boundaries (not counted).
     * @return The number of nodes on the path.
     */
    private fun countNodesOnPath(
        startNode: NodeModel?,
        graph: PipelineGraph,
        stopNodeIds: Set<String> = emptySet(),
    ): Int {
        var count = 0
        var node = startNode
        val visited = mutableSetOf<String>()
        while (node != null && node.id !in visited && node.id !in stopNodeIds) {
            visited.add(node.id)
            count++
            if (node.type == NodeType.OUTPUT) break
            val nextId = graph.connections.firstOrNull { it.sourceNodeId == node.id }?.targetNodeId
            node = graph.nodes.find { it.id == nextId }
        }
        return count
    }

    private fun findNextNodeId(
        currentNode: NodeModel,
        graph: PipelineGraph,
        conditionResult: Boolean?,
        routingKey: String? = null,
    ): String? {
        val edges = graph.connections.filter { it.sourceNodeId == currentNode.id }
        if (edges.isEmpty()) {
            Timber.tag("PipelineDebug").d("[ROUTE] from=${currentNode.id} label=null -> to=null")
            return null
        }

        val targetNodeId = if (currentNode.type == NodeType.IF_CONDITION) {
            val expectedLabel = if (conditionResult == true) RouteLabels.TRUE else RouteLabels.FALSE
            val oppositeLabel = if (conditionResult == true) RouteLabels.FALSE else RouteLabels.TRUE
            val exactTarget = edges.find { RouteLabels.matches(it.label, expectedLabel) }?.targetNodeId
            when {
                exactTarget != null -> exactTarget
                // The author wired the opposite branch but left this one
                // unconnected: terminate the branch (-> "terminated without
                // OUTPUT") instead of silently falling through to an arbitrary
                // first edge and running the wrong branch on this verdict.
                edges.any { RouteLabels.matches(it.label, oppositeLabel) } -> null
                // No True/False labels at all — a single default edge. Keep the
                // legacy fall-through so an unlabelled pass-through still routes.
                else -> edges.firstOrNull()?.targetNodeId
            }
        } else if (currentNode.type == NodeType.INTENT_ROUTER) {
            // `routingKey == null` is the router's real failure mode, not an
            // absent one: the structured gate constrains the answer to the
            // labelled edges, so a *successful* verdict always names one of
            // them. What actually goes unrouted is a gate that gave up after its
            // repair attempts — which is why the fallback has to cover the null
            // case, and why it was reachable by nothing when it did not.
            val matchedEdge = routingKey?.let { key ->
                edges.find { RouteLabels.matches(it.label, key) }
                    ?: edges.find { !it.label.isNullOrBlank() && routingKeyContainsLabelAsWord(key, it.label) }
            }
            matchedEdge?.targetNodeId ?: unmatchedRouterTarget(currentNode, edges)
        } else if (currentNode.type == NodeType.EVALUATION && routingKey != null) {
            // EVALUATION emits a Pass / Retry / Fail verdict as the routing key;
            // route to the edge whose label matches the verdict, falling back to
            // the first outgoing edge when the verdict has no dedicated port.
            edges.find { RouteLabels.matches(it.label, routingKey) }?.targetNodeId
                ?: edges.firstOrNull()?.targetNodeId
        } else {
            edges.firstOrNull()?.targetNodeId
        }

        val edgeLabel = edges.find { it.targetNodeId == targetNodeId }?.label ?: "null"
        Timber.tag("PipelineDebug").d("[ROUTE] from=${currentNode.id} label=$edgeLabel -> to=$targetNodeId")
        return targetNodeId
    }

    /**
     * One QUEUE_PROCESSOR iteration boundary: the node the walk moves to next,
     * and the input it carries there.
     *
     * @property node Next node, or `null` when the `Done` edge is unwired.
     * @property inputText Input for [node].
     * @property queueFinished `true` when the queue is exhausted and the walk is
     *   leaving it by the `Done` edge — the caller clears its active-queue
     *   cursor on that.
     */
    private data class QueueStep(val node: NodeModel?, val inputText: String, val queueFinished: Boolean)

    /**
     * Ends one QUEUE_PROCESSOR iteration and begins the next, or leaves the
     * queue by its `Done` edge when nothing is left.
     *
     * Extracted from the walk when a second caller appeared: an item failing
     * inside a queue whose author set `stopOnError = false` has to advance to
     * the next item exactly as a successful one does. Written as a member
     * function taking what it needs rather than a closure over the walk's
     * locals, because capturing the walk's `currentNode` would cost every smart
     * cast in the loop — a large, unrelated edit in the app's most load-bearing
     * function.
     *
     * @param graph The running graph.
     * @param queueProcessorId Id of the QUEUE_PROCESSOR that owns the loop.
     * @param remainingItems Items not yet executed; the next one is **removed**.
     * @param results Results accumulated so far, rendered into the next input.
     * @return Where the walk goes next.
     */
    private fun stepQueue(
        graph: PipelineGraph,
        queueProcessorId: String,
        remainingItems: MutableList<String>,
        results: List<String>,
    ): QueueStep {
        val edges = graph.connections.filter { it.sourceNodeId == queueProcessorId }
        val itemNodeId = edges.find { RouteLabels.matches(it.label, RouteLabels.ITEM) }?.targetNodeId
            ?: edges.firstOrNull()?.targetNodeId
        val doneNodeId = edges.find { RouteLabels.matches(it.label, RouteLabels.DONE) }?.targetNodeId

        if (remainingItems.isEmpty() || itemNodeId == null) {
            val summary = "Queue execution completed.\nResults:\n" +
                results.mapIndexed { i, res -> "${i + 1}. $res" }.joinToString("\n")
            return QueueStep(graph.nodes.find { it.id == doneNodeId }, summary, queueFinished = true)
        }

        val nextItem = remainingItems.removeAt(0)
        val contextStr = results.mapIndexed { i, res -> "Result of Subtask ${i + 1}:\n$res" }.joinToString("\n\n")
        val subtaskInstruction = DefaultPrompts.QueueProcessor.SUBTASK_INSTRUCTION
        val input = if (contextStr.isNotEmpty()) {
            "PREVIOUS RESULTS CONTEXT:\n$contextStr\n\n---\n\n$subtaskInstruction" +
                "\n\nCURRENT SUBTASK TO EXECUTE:\n$nextItem"
        } else {
            "$subtaskInstruction\n\nCURRENT SUBTASK TO EXECUTE:\n$nextItem"
        }
        return QueueStep(graph.nodes.find { it.id == itemNodeId }, input, queueFinished = false)
    }

    /**
     * Where an INTENT_ROUTER sends a run it could not route.
     *
     * Reached when the structured gate gave up after its repair attempts (no
     * verdict at all) or, rarely, when a verdict names no wired edge. The first
     * is the common one: the gate constrains the model to the labelled edges, so
     * a successful answer already names one.
     *
     * With a [NodeModel.fallbackClass] set, the run takes the edge labelled with
     * it — and **terminates** when no such edge is wired, for the same reason
     * IF_CONDITION does above: an author who named a fallback and then failed to
     * connect it is better served by "terminated without OUTPUT" than by the
     * wrong branch running on a verdict nobody chose.
     *
     * With no fallback class — every pipeline saved before this field existed —
     * the historical first-edge-in-storage-order behaviour is kept. Changing it
     * would silently re-route graphs whose authors never made this decision, and
     * the workaround those authors were told to use (put the fallback branch
     * first) depends on exactly that behaviour.
     *
     * @param node The routing node.
     * @param edges Its outgoing connections.
     * @return The next node id, or `null` to terminate the branch.
     */
    private fun unmatchedRouterTarget(node: NodeModel, edges: List<ConnectionModel>): String? {
        val fallback = node.fallbackClass?.takeIf { it.isNotBlank() }
            ?: return edges.firstOrNull()?.targetNodeId
        return edges.find { it.label?.equals(fallback, ignoreCase = true) == true }?.targetNodeId
    }

    /**
     * INTENT_ROUTER fallback match: `true` when [routingKey] contains [label] as
     * a **standalone token** (case-insensitive). Used only after an exact label
     * match fails, to tolerate a model that wraps the chosen label in a sentence
     * ("I choose Cancel") while still rejecting incidental substring hits — an
     * unanchored `contains` would route the key "Cancel" to a port labelled
     * "can".
     *
     * The boundary is expressed as alphanumeric-adjacency lookarounds rather than
     * `\b`: a `\b`-based regex fails to match labels that begin or end with a
     * non-word character (e.g. a port labelled `C#` or `node.js`), because `\b`
     * requires a word↔non-word transition at the label edge. The lookarounds
     * `(?<![A-Za-z0-9])` / `(?![A-Za-z0-9])` instead reject a match only when an
     * alphanumeric character abuts the label, so `C#` matches in "Use C# here"
     * while "can" still does not match inside "Cancel". [label] is regex-escaped
     * so its own characters are literal.
     *
     * @param routingKey The router's chosen routing key (model output).
     * @param label The candidate edge label to test against [routingKey].
     * @return `true` if [label] appears as a standalone token inside [routingKey].
     */
    private fun routingKeyContainsLabelAsWord(routingKey: String, label: String): Boolean = Regex(
        "(?<![A-Za-z0-9])${Regex.escape(label)}(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(routingKey)

    /**
     * Mirrors a human-in-the-loop suspension (and its resolution) into the
     * persistent run record. [AgentOrchestratorState.WaitingForApproval] and
     * [AgentOrchestratorState.AwaitingClarification] move the record to the
     * matching WAITING_* status; the first state forwarded *after* a
     * suspension flips it back to [PipelineRunStatus.RUNNING] (the node-end
     * flip in the main loop covers executors that emit no state after
     * resolution). All other states leave the record untouched — the RUNNING
     * transition itself and every terminal status are owned by the task
     * queue. The repository is best-effort by contract, so no guard is
     * needed here.
     *
     * @param runId Id of the persistent run record.
     * @param state The orchestrator state about to be forwarded downstream.
     * @param wasSuspended Whether the record currently sits in a WAITING_* status.
     * @return The new suspension flag to carry into the next forwarded state.
     */
    private suspend fun persistSuspensionTransition(
        runId: String,
        state: AgentOrchestratorState,
        wasSuspended: Boolean,
    ): Boolean = when {
        state is AgentOrchestratorState.WaitingForApproval -> {
            pipelineRunRepository.updateStatus(runId, PipelineRunStatus.WAITING_APPROVAL)
            // Suspension flush: the run may now wait indefinitely (and the
            // process may die waiting), so the trace must be durable up to
            // this exact point.
            runTraceRepository.flush()
            true
        }
        state is AgentOrchestratorState.AwaitingClarification -> {
            pipelineRunRepository.updateStatus(runId, PipelineRunStatus.WAITING_CLARIFICATION)
            runTraceRepository.flush()
            true
        }
        state is AgentOrchestratorState.WaitingForCeilingRaise -> {
            // Reached twice for one pause, and both are wanted. The engine that
            // raised it calls this directly, before parking. A *parent* engine
            // reaches it when a sub-pipeline forwards the pause upwards, which
            // is what puts the whole stack into WAITING_CEILING rather than
            // leaving the root RUNNING behind a child that is waiting — the
            // shape of a defect found on device, which made every answer to a nested
            // park bounce off the resume guard.
            pipelineRunRepository.updateStatus(runId, PipelineRunStatus.WAITING_CEILING)
            runTraceRepository.flush()
            true
        }
        // Console lines, node-I/O snapshots and run notices are observations
        // *about* the run, not progress of it: they keep arriving while a HITL
        // gate is waiting (a sub-pipeline forwards its child's console traffic
        // upwards). Letting them fall through to the branch below made the first
        // such line read as "the wait ended" and flip the record back to RUNNING
        // while the gate was still open. For a nested pipeline that left the root
        // RUNNING and the child WAITING_APPROVAL, so `ResumePipelineRunUseCase`
        // — which requires a resumable *root* — rejected every attempt to answer
        // the parked notification.
        //
        // A notice is only ever raised just after a node is charged, so today it
        // cannot coincide with an open gate. It is classified here anyway: the
        // guarantee should rest on what the state *means*, not on an ordering
        // argument that a later change could quietly invalidate.
        state is AgentOrchestratorState.ConsoleLog ||
            state is AgentOrchestratorState.NodeIO ||
            state is AgentOrchestratorState.RunNotice -> wasSuspended
        wasSuspended -> {
            pipelineRunRepository.updateStatus(runId, PipelineRunStatus.RUNNING)
            false
        }
        else -> false
    }

    /**
     * Makes a ceiling pause durable and, unless the user is already looking at
     * the session, tells them about it.
     *
     * The record is what the pause *is*: the engine coroutine ends immediately
     * after this returns, so from here on the question exists only in the
     * pending-interaction store. It carries the axis and both numbers because
     * the card that asks it has to state the limit **this run** was stopped at —
     * the setting behind that number may well have been edited by the time the
     * answer comes, hours later and in another process.
     *
     * @param runId Id of the run being parked. For a sub-pipeline this is the
     *   child's id: the park sits where the pause happened, while the grant it
     *   buys lands on the tree's root, which the submission path resolves.
     * @param sessionId Id of the owning chat session.
     * @param ceiling Which ceiling bound, and by how much.
     * @return `true` when the park is durable and the caller may end the walk;
     *   `false` when the store refused it and the run must stop instead — a
     *   question recorded nowhere would leave the run waiting for an answer no
     *   surface could ever offer.
     */
    private suspend fun parkOnCeiling(runId: String, sessionId: String, ceiling: HardCeilingBreach): Boolean {
        val saved = pendingInteractionRepository.save(
            PendingInteraction(
                runId = runId,
                sessionId = sessionId,
                kind = PendingInteractionKind.CEILING,
                ceilingAxis = ceiling.axis,
                ceilingLimit = ceiling.limit,
                ceilingSpent = ceiling.spent,
                requestedAt = System.currentTimeMillis(),
            ),
        )
        if (saved) {
            ceilingNotifier.sendCeilingPauseRequest(runId, sessionId, ceiling)
        }
        return saved
    }

    /**
     * Decides whether [node]'s input string should be assembled by
     * [NodeContextBuilder] (true) or passed through as the raw
     * `currentInputText` (false). Delegates to [NodeModel.usesContextConfig],
     * the single source of truth shared with `PipelineGraph.validate()` so
     * the validator only flags empty configs on nodes that actually consume
     * them.
     */
    private fun shouldComposeContext(node: NodeModel): Boolean = node.usesContextConfig()

    /**
     * Whether [node]'s composed input is a prompt for the on-device model, so
     * tool text in it is cut to the user's single-read budget.
     *
     * A CLOUD node, and any node given a cloud provider, runs on a provider's
     * window the user does not size here — it gets tool text whole, bounded by
     * the response budget only. A TOOL node counts like any other: its input
     * never reaches the tool as it is, it is the prompt the model turns into the
     * tool's arguments, on the local model unless the node names a provider.
     *
     * @param node a node whose context is being composed.
     * @return `true` when the node's executor prompts the local model.
     */
    private fun feedsOnDeviceModel(node: NodeModel): Boolean = when (node.type) {
        NodeType.LITE_RT -> true
        NodeType.CLOUD -> false
        else -> node.cloudProvider.isNullOrBlank()
    }

    /**
     * Which console channel a protective stop belongs on.
     *
     * The walk exits through one seam whatever decided to end it, so the choice
     * of channel has to be made from the reason rather than from the site — and
     * it is worth making, because the two channels answer different questions.
     * A ceiling line says a number the user chose was reached; a detector line
     * says the pipeline misbehaved. Filing the second under the first is how a
     * console stops being searchable.
     *
     * Exhaustive rather than defaulting: the reasons the walk cannot produce
     * are listed too, on the neutral channel. An `else` would file the next
     * engine-raised reason under the ceiling channel — a console line claiming
     * a limit was reached when none was, found only by whoever greps for it.
     *
     * @param reason The typed cause the walk stopped on.
     * @return The console event type its diagnostic should be pushed under.
     */
    private fun consoleTypeFor(reason: RunTerminationReason): ConsoleEventType = when (reason) {
        RunTerminationReason.NoProgress -> ConsoleEventType.StuckDetector
        is RunTerminationReason.StepCeiling,
        is RunTerminationReason.TokenCeiling,
        -> ConsoleEventType.RunCeiling
        // Exhaustive rather than an `else`, and the reasons below are listed
        // even though the walk cannot produce them. An `else` would quietly
        // file the next engine-raised reason under the ceiling channel — a
        // console line that says a limit was reached when none was, discovered
        // only by whoever greps for it later.
        RunTerminationReason.RunStalled,
        RunTerminationReason.HitlWindowExpired,
        RunTerminationReason.GraphChanged,
        RunTerminationReason.ProcessDied,
        RunTerminationReason.DiscardedByUser,
        RunTerminationReason.NotResumable,
        -> ConsoleEventType.SystemMessage
    }

    /**
     * Returns a copy of [node] with its `systemPrompt` rendered through
     * [PromptTemplateEngine], substituting all `$VARIABLE` placeholders using the
     * injected [PromptVariableProvider] set. For nodes whose `systemPrompt` is
     * not consumed by an LLM (e.g. [NodeType.TOOL], [NodeType.IF_CONDITION]) the
     * original [node] instance is returned unchanged to avoid wasted work and
     * accidental substitution inside fields that happen to share the syntax.
     */
    private suspend fun renderNodeSystemPrompt(node: NodeModel): NodeModel {
        val rawPrompt = node.systemPrompt
        if (rawPrompt.isNullOrEmpty() || node.type !in LLM_NODE_TYPES) return node
        val rendered = promptTemplateEngine.render(rawPrompt, promptVariableProviders.toList())
        if (rendered === rawPrompt || rendered == rawPrompt) return node
        return node.copy(systemPrompt = rendered)
    }

    /**
     * Parses a list of items from a node's text output, used to seed a
     * `QUEUE_PROCESSOR` from an upstream `DECOMPOSITION` (or any list-producing
     * node).
     *
     * JSON isolation is delegated to the shared [JsonPayloadExtractor] and the
     * array is deserialized with `kotlinx.serialization`, so this no longer
     * carries its own ```json regex or `org.json` walk — a `DECOMPOSITION` node
     * already validated and re-encoded its list through the structured-output
     * gate, so the common case is a clean array. The Markdown-list fallback (and
     * the single-item fallback) remain for nodes that emit a plain bulleted or
     * numbered list rather than JSON.
     *
     * @param text The upstream node output to parse.
     * @return The parsed items, or a single-element list of [text] when nothing
     *   list-shaped is found.
     */
    private fun parseListFromText(text: String): List<String> {
        val payload = JsonPayloadExtractor.extract(text)
        if (payload.startsWith("[")) {
            try {
                val list = listJson.decodeFromString(ListSerializer(String.serializer()), payload)
                if (list.isNotEmpty()) return list
            } catch (e: IllegalArgumentException) {
                // Not a valid string array — fall through to the Markdown-list parsing.
                // `decodeFromString` is non-suspend, so this cannot mask a CancellationException.
                Timber.tag("PipelineDebug").e(e, "Error parsing JSON list")
            }
        }

        val lines = text.lines().map { it.trim() }.filter { it.matches(Regex("""^(\d+\.|-|\*)\s+.*""")) }
        if (lines.isNotEmpty()) {
            return lines.map { it.replaceFirst(Regex("""^(\d+\.|-|\*)\s+"""), "") }
        }

        return listOf(text)
    }

    /**
     * Writes the tree's accumulated spend onto the root run record.
     *
     * Called once per executed node, from whatever depth is running. Two things
     * make that cadence the right one rather than an extravagance: the walk
     * already writes to `pipeline_runs` on every node entry (`updateCurrentNode`),
     * so this adds no new class of traffic; and the counter has to be exact at
     * every park, because a parked run resumes by reading it back. Nodes are
     * seconds apart — they are LLM calls — so the write is never hot.
     *
     * Best-effort by the repository's contract: losing the write loses accuracy,
     * never the run, and an under-count makes the ceiling bind late rather than
     * early.
     *
     * @param ledger The run tree's spend ledger.
     */
    private suspend fun persistSpend(ledger: RunBudgetLedger) {
        val rootId = ledger.rootRunId ?: return
        pipelineRunRepository.recordSpend(
            rootRunId = rootId,
            stepsSpent = ledger.stepsSpent,
            tokensSpent = ledger.tokensSpent,
        )
    }

    /**
     * Returns this state with a credential quoted in its error message masked, and
     * any other state unchanged.
     *
     * An executor forwards its own `Error` straight to the engine's collector, and from
     * there it becomes the run record's message — the one the chat export and the
     * trigger-journal export share. Redacting here covers the executors that scrub
     * their provider errors and the ones that do not.
     */
    private fun AgentOrchestratorState.withRedactedError(): AgentOrchestratorState =
        if (this is AgentOrchestratorState.Error) copy(message = CloudErrorSanitizer.redactSecrets(message)) else this

    private companion object {
        /**
         * Injected into the run's own context the first time an axis crosses its
         * soft threshold, so the model driving the next node can bring the task
         * to a close on its own terms instead of discovering the hard stop by
         * walking into it.
         *
         * Deliberately says what to do rather than quoting a number: the numbers
         * are in the console line a person reads, and a budget figure inside the
         * prompt invites the model to reason about arithmetic instead of about
         * the task.
         */
        const val SOFT_CEILING_CONTEXT_NOTE: String =
            "SYSTEM NOTICE: this run is close to its resource limit and may be stopped before it " +
                "finishes. Wrap up now: produce the best answer you can from what you already have, " +
                "and do not start new sub-tasks or additional tool calls."

        /**
         * Injected into the run's own context the first time the stuck-detector
         * decides the run is going in circles, so the model gets a chance to
         * break the pattern itself before the run is ended for it. This first
         * stage is meant to be the last one: a model told that it is repeating
         * itself usually stops.
         *
         * Names the observation rather than the machinery. "You have produced
         * this before" is something a model can act on; the signal name and the
         * repetition count are for the console, where an engineer reads them.
         */
        const val STUCK_CONTEXT_NOTE: String =
            "SYSTEM NOTICE: this run appears to be repeating itself — the same work has produced the " +
                "same result more than once, and the run will be stopped if that continues. Change " +
                "approach or finish: give the best answer you can from what you already have, and do " +
                "not repeat a step you have already taken."

        /** Lenient JSON used to parse a `QUEUE_PROCESSOR` seed list (see [parseListFromText]). */
        val listJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Node types whose `systemPrompt` is forwarded to an LLM engine and
         * therefore needs `$VARIABLE` placeholders resolved before execution.
         * Includes [NodeType.LITE_RT], [NodeType.CLOUD], [NodeType.OUTPUT] from
         * the explicit task spec plus the other LLM-driven node types in this
         * codebase (`SUMMARY`, `INTENT_ROUTER`, `DECOMPOSITION`, `EVALUATION`).
         */
        private val LLM_NODE_TYPES: Set<NodeType> = setOf(
            NodeType.LITE_RT,
            NodeType.CLOUD,
            NodeType.OUTPUT,
            NodeType.SUMMARY,
            NodeType.INTENT_ROUTER,
            NodeType.DECOMPOSITION,
            NodeType.EVALUATION,
            NodeType.CLARIFICATION,
        )

        /** Crashlytics custom key for the id of the pipeline currently executing. */
        const val CRASH_KEY_PIPELINE_ID: String = "active_pipeline_id"

        /** Crashlytics custom key for the display name of the active local LLM. */
        const val CRASH_KEY_ACTIVE_MODEL: String = "active_model"

        /** Value reported when no local model is currently selected. */
        const val ACTIVE_MODEL_NONE: String = "none"

        /** Bytes-per-kilobyte divisor for the `Image input` console line. */
        const val BYTES_PER_KB: Long = 1024L
    }
}
