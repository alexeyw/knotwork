package app.knotwork.android.domain.engine

import app.knotwork.android.domain.engine.executors.NodeExecutorFactory
import app.knotwork.android.domain.engine.executors.ToolNodeExecutor
import app.knotwork.android.domain.engine.stuck.GraphStuckDetector
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.EngineImageInput
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.RunBudgetLedger
import app.knotwork.android.domain.models.RunContextNotes
import app.knotwork.android.domain.models.RunGeneratingModel
import app.knotwork.android.domain.models.RunImageDelivery
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTreeContext
import app.knotwork.android.domain.repositories.CrashReportingRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine responsible for executing a given [PipelineGraph], as a root run or as a
 * sub-pipeline inside one.
 *
 * Each invocation validates the graph, announces the run's image, builds the run
 * tree (or inherits its parent's), opens the per-invocation collaborators — the
 * console and trace, the run record, what each node sees, the checkpoint replay,
 * the live node runner — and hands the graph to [RunWalk], which walks it from
 * [NodeType.INPUT] to [NodeType.OUTPUT].
 */
@Singleton
class GraphExecutionEngine
@Inject
constructor(
    private val nodeExecutorFactory: NodeExecutorFactory,
    private val toolNodeExecutor: ToolNodeExecutor,
    private val metricsRepository: MetricsRepository,
    private val crashReportingRepository: CrashReportingRepository,
    private val localModelRepository: LocalModelRepository,
    private val runTraceRepository: RunTraceRepository,
    private val resolveRunCeilingsUseCase: ResolveRunCeilingsUseCase,
    private val runRecords: RunRecordWriter.Factory,
    private val nodeInputs: NodeInputComposer.Factory,
    private val runHeaders: RunHeaders,
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
        // This invocation's run record: current node, spend, WAITING_* status.
        val records = runRecords.open(runId, sessionId)

        // A resumed run's recorded prefix, replayed instead of re-run.
        val replay = CheckpointReplay(resume)

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
        // question it parked on; this is the one point every attempt passes.
        records.consumeParkedCeiling()

        // The state the whole run tree shares. A sub-pipeline inherits its
        // parent's; the root builds one here, after the checks above, because
        // building it reads the run record (see [rootTree]).
        val tree = when (entry) {
            is RunEntry.Nested -> entry.tree
            is RunEntry.Root -> rootTree(runId, entry, records)
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

        // What each node sees: rendered prompt, composed context, the image.
        val inputs = nodeInputs.open(console, graph, sessionId, userPrompt, tree, resume?.memorySnapshot)
        // Runs a node's executor and accounts for it.
        val live = LiveNodeStep(
            nodeExecutorFactory = nodeExecutorFactory,
            metricsRepository = metricsRepository,
            collector = this,
            console = console,
            records = records,
            inputs = inputs,
            tree = tree,
            graph = graph,
            call = LiveNodeStep.NodeCall(sessionId, userPrompt, runId),
            resuming = replay.resuming,
            localModelRepository = localModelRepository,
            beforeOutput = { noteUndeliveredImage() },
        )

        RunWalk(
            collector = this,
            graph = graph,
            tree = tree,
            console = console,
            records = records,
            inputs = inputs,
            replay = replay,
            live = live,
            noteUndeliveredImage = { noteUndeliveredImage() },
        ).walk(inputNode, userPrompt)
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
     * The header is read back the same way: a resumed run keeps the seed and
     * sampler its first attempt chose, so every on-device call of the run — before
     * and after a pause — is reproducible from one header. A run starting for the
     * first time (or one recorded before headers existed) chooses and records one
     * now.
     *
     * @param runId Id of the root run record, or `null` for a non-persisted run.
     * @param root The root overload's image and origin arguments.
     * @param records The run's record, whose spend seeds the ledger.
     * @return The tree at depth `0`.
     */
    private suspend fun rootTree(runId: String?, root: RunEntry.Root, records: RunRecordWriter): RunTreeContext {
        val ceilings = resolveRunCeilingsUseCase(root.origin)
        val spent = records.spendSoFar()
        val delivery = root.imageInput?.let { RunImageDelivery(it) }
        val header = records.recordedHeader() ?: runHeaders.fresh().also { records.recordHeader(it) }
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
            header = header,
        )
    }

    private companion object {
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
