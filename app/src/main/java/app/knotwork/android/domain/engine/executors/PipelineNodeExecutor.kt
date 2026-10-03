package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.engine.GraphExecutionEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Provider

/**
 * Executor for [NodeType.PIPELINE][app.knotwork.android.domain.models.NodeType.PIPELINE]
 * nodes — the composition primitive that runs another pipeline graph as a
 * sub-step (a "function call" between pipelines).
 *
 * **Execution model.** The node names its callee through
 * [NodeModel.targetPipelineId]. This executor loads that graph via
 * [PipelineRepository] and runs it by invoking the very same
 * [GraphExecutionEngine] that is driving the parent run, passing the node's
 * input as the sub-pipeline's user prompt. The sub-pipeline's terminal
 * [AgentOrchestratorState.Completed] response becomes this node's
 * [NodeExecutionResult.outputText]; an [AgentOrchestratorState.Error] (or a
 * thrown exception) becomes [NodeExecutionResult.error], which the parent
 * engine surfaces as a run-level error.
 *
 * **Nested run tree.** Unlike the domain core's first cut (which ran the
 * sub-pipeline with a `null` run id and swallowed its intermediate states),
 * a persisted run (`runId != null`) gives the sub-run a **real child run
 * record** linked to the parent via [PipelineRun.parentRunId]. The child run id
 * is *deterministic* — `"<parentRunId>::<nodeId>::<visitIndex>"` — so a resume
 * that re-reaches this node recomputes the exact same id and continues the
 * existing child run instead of starting a fresh one (see *Resume* below). The
 * [ExecutionScope.pipelineVisitIndex] disambiguates a PIPELINE node that runs
 * more than once (inside a `QUEUE_PROCESSOR` loop), and is re-derived
 * deterministically on resume.
 *
 * **Observability across the boundary.** The child's console, trace and
 * per-node I/O states (and its human-in-the-loop states) are forwarded upward
 * as [NodeOutput.State] so the parent engine re-emits them: nested events reach
 * the console (depth-stamped, so they render indented and prefixed with the
 * sub-pipeline name) and an approval/clarification raised *inside* a
 * sub-pipeline surfaces its card in the chat UI. The child's streaming/answer
 * states are intentionally not forwarded — only the sub-pipeline's final
 * response is the parent node's output.
 *
 * **One run tree.** The child engine invocation receives the parent's
 * [RunTreeContext][app.knotwork.android.domain.models.RunTreeContext] whole, one
 * level deeper ([RunTreeContext.nested][app.knotwork.android.domain.models.RunTreeContext.nested]).
 * So the sub-pipeline charges the same step and token ceilings instead of getting
 * a private allowance — exhaustion in depth fails the child, which becomes this
 * node's error and terminates the whole stack. It observes into the same
 * repetition window, can receive the parent's pending advice, delivers the run's
 * single image to the first vision sink *anywhere in the tree* exactly once,
 * attributes its answer to the root OUTPUT, and keeps the parent's origin.
 *
 * **Human-in-the-loop.** When the child parks in its persistent waiting phase
 * (its TOOL/CLARIFICATION node timed out into a durable pending-interaction
 * keyed by the child run id), the child engine ends with
 * [AgentOrchestratorState.SuspendedInBackground] and no terminal state. This
 * executor propagates the park: it forwards the suspension upward and ends
 * without a [NodeOutput.Result], so the whole ancestor stack parks too. The
 * user's later response resumes the *root* run, which replays down to this node
 * and resumes the parked child (see *Resume*).
 *
 * **Resume across the boundary.** On a checkpoint resume the parent replays its
 * recorded prefix; a PIPELINE node whose own `NodeIo` was recorded (its child
 * completed) is replayed and never re-enters this executor. A PIPELINE node the
 * run died *inside* has no recorded `NodeIo`, so it executes live here — and
 * finds its child run still in a resumable status (INTERRUPTED or a WAITING_*
 * park). This executor then rebuilds the child's [ResumeContext] from the
 * child's persisted trace and resumes the child engine instead of restarting
 * it, so neither parent nor child re-executes a completed node. The child's
 * `graphContentHash` is validated against the current graph for every graph in
 * the stack — a sub-pipeline edited since the interruption fails the resume
 * loudly rather than replaying onto a mismatched graph.
 *
 * **Depth safety.** The scope's tree carries the current nesting level; the
 * recursive call runs one level deeper. The authoritative protection against
 * runaway nesting and cycles is *static* — `PipelineCompositionValidator`
 * rejects such compositions before any run starts. The runtime ceiling here
 * ([SettingsRepository.pipelineMaxNestingDepth]) is only the safety net for a
 * graph edited in the window between validation and execution. The object-graph
 * cycle (engine → [NodeExecutorFactory] → this executor → engine) is broken
 * with a [Provider].
 */
class PipelineNodeExecutor @Inject constructor(
    private val pipelineRepository: PipelineRepository,
    private val settingsRepository: SettingsRepository,
    private val pipelineRunRepository: PipelineRunRepository,
    private val runTraceRepository: RunTraceRepository,
    private val engineProvider: Provider<GraphExecutionEngine>,
) : NodeExecutor {

    override fun execute(
        node: NodeModel,
        inputText: String,
        sessionId: String,
        originalPrompt: String,
        runId: String?,
        scope: ExecutionScope,
    ): Flow<NodeOutput> = flow {
        val targetId = node.targetPipelineId
        if (targetId.isNullOrBlank()) {
            emit(failure("PIPELINE node '${node.label}' has no target pipeline selected"))
            return@flow
        }

        val maxDepth = settingsRepository.pipelineMaxNestingDepth.first()
        if (scope.run.depth + 1 > maxDepth) {
            emit(failure("Pipeline nesting depth exceeded the limit of $maxDepth"))
            return@flow
        }

        val targetGraph = try {
            pipelineRepository.getPipelineById(targetId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(failure("Failed to load target pipeline '$targetId': ${e.message ?: "unknown error"}"))
            return@flow
        }
        if (targetGraph == null) {
            emit(failure("Target pipeline '$targetId' not found"))
            return@flow
        }

        // Non-persisted runs (editor test runs) keep the original semantics:
        // no child run record, no resume, just a recursive in-memory execution.
        if (runId == null) {
            runNonPersistedChild(targetGraph, sessionId, inputText, scope)
            return@flow
        }

        val childRunId = childRunId(runId, node.id, scope.pipelineVisitIndex)
        when (val prep = prepareChildRun(childRunId, runId, sessionId, inputText, targetGraph)) {
            // prepareChildRun already emitted the failure for the abort case.
            ChildPrep.Aborted -> return@flow
            is ChildPrep.Ready -> runPersistedChild(targetGraph, sessionId, inputText, childRunId, prep.resume, scope)
        }
    }

    /**
     * Runs the sub-pipeline without persistence (the parent run is itself not
     * persisted). States other than the terminal response are forwarded for the
     * editor console, but no child record, resume or park applies.
     */
    private suspend fun FlowCollector<NodeOutput>.runNonPersistedChild(
        targetGraph: PipelineGraph,
        sessionId: String,
        inputText: String,
        scope: ExecutionScope,
    ) {
        var finalResponse: String? = null
        var errorMessage: String? = null
        var terminationReason: RunTerminationReason? = null
        try {
            engineProvider.get().invoke(
                sessionId = sessionId,
                userPrompt = inputText,
                graph = targetGraph,
                runId = null,
                resume = null,
                tree = scope.run.nested(),
            ).collect { state ->
                when (state) {
                    is AgentOrchestratorState.Completed -> finalResponse = state.finalResponse
                    is AgentOrchestratorState.Error -> {
                        errorMessage = state.message
                        // The child's typed cause travels with it. Dropping it
                        // here would settle a nested ceiling breach as an
                        // ordinary failure and, for a trigger run, redden the
                        // health badge for a guard that worked.
                        terminationReason = state.reason
                    }
                    else -> forwardIfObservable(state)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "Sub-pipeline execution failed"
        }
        emitTerminal(errorMessage, terminationReason, finalResponse, targetGraph)
    }

    /**
     * Drives the persisted child run: invokes the engine with the child run id
     * (and optional [childResume]), forwards observable + HITL states, parks the
     * stack when the child parks, and settles the child's terminal record.
     */
    private suspend fun FlowCollector<NodeOutput>.runPersistedChild(
        targetGraph: PipelineGraph,
        sessionId: String,
        inputText: String,
        childRunId: String,
        childResume: ResumeContext?,
        scope: ExecutionScope,
    ) {
        var finalResponse: String? = null
        var errorMessage: String? = null
        var terminationReason: RunTerminationReason? = null
        var childParked = false
        try {
            engineProvider.get().invoke(
                sessionId = sessionId,
                userPrompt = inputText,
                graph = targetGraph,
                runId = childRunId,
                resume = childResume,
                tree = scope.run.nested(),
            ).collect { state ->
                when (state) {
                    is AgentOrchestratorState.Completed -> finalResponse = state.finalResponse
                    is AgentOrchestratorState.Error -> {
                        errorMessage = state.message
                        // The child's typed cause travels with it. Dropping it
                        // here would settle a nested ceiling breach as an
                        // ordinary failure and, for a trigger run, redden the
                        // health badge for a guard that worked.
                        terminationReason = state.reason
                    }
                    is AgentOrchestratorState.SuspendedInBackground -> {
                        // The child parked durably. Forward the suspension so
                        // the parent engine parks the whole stack, and stop
                        // settling this run — its WAITING_* record is owned by
                        // the child engine and the pending-interaction store.
                        childParked = true
                        emit(NodeOutput.State(state))
                    }
                    else -> forwardIfObservable(state)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "Sub-pipeline execution failed"
        }

        if (childParked) {
            // No Result: the parent engine already saw the forwarded
            // SuspendedInBackground and parks. The child stays WAITING_*.
            return
        }

        // The child engine never owns terminal transitions (that split mirrors
        // the task queue for top-level runs), so settle the child record here.
        when {
            errorMessage != null ->
                pipelineRunRepository.finishRun(
                    childRunId,
                    PipelineRunStatus.FAILED,
                    errorMessage,
                    terminationReason,
                )
            finalResponse != null -> pipelineRunRepository.finishRun(childRunId, PipelineRunStatus.COMPLETED)
            else -> pipelineRunRepository.finishRun(
                childRunId,
                PipelineRunStatus.FAILED,
                "Sub-pipeline '${targetGraph.name}' produced no output",
            )
        }
        emitTerminal(errorMessage, terminationReason, finalResponse, targetGraph)
    }

    /**
     * Ensures the child run record exists and is RUNNING, returning a
     * [ChildPrep] that either carries the [ResumeContext] to drive the child
     * engine with ([ChildPrep.Ready], `resume == null` for a fresh run) or
     * signals [ChildPrep.Aborted] after emitting a failure (an unexpected
     * existing-run state or a sub-pipeline graph that changed since
     * interruption).
     */
    private suspend fun FlowCollector<NodeOutput>.prepareChildRun(
        childRunId: String,
        parentRunId: String,
        sessionId: String,
        inputText: String,
        targetGraph: PipelineGraph,
    ): ChildPrep {
        val existing = pipelineRunRepository.getRun(childRunId)
        val childHash = targetGraph.contentHash()

        if (existing == null) {
            // Fresh sub-run: create the QUEUED child record linked to its
            // parent, then transition it to RUNNING with the resolved graph
            // hash — mirroring the task-queue lifecycle for top-level runs.
            val parentOrigin = pipelineRunRepository.getRun(parentRunId)?.origin ?: RunOrigin.CHAT
            pipelineRunRepository.createRun(
                PipelineRun(
                    id = childRunId,
                    sessionId = sessionId,
                    pipelineId = targetGraph.id,
                    origin = parentOrigin,
                    status = PipelineRunStatus.QUEUED,
                    currentNodeId = null,
                    startedAt = System.currentTimeMillis(),
                    finishedAt = null,
                    errorMessage = null,
                    graphContentHash = null,
                    userPrompt = inputText,
                    parentRunId = parentRunId,
                ),
            )
            pipelineRunRepository.markRunning(childRunId, targetGraph.id, childHash)
            return ChildPrep.Ready(resume = null)
        }

        if (existing.status !in RESUMABLE_CHILD_STATUSES) {
            // The PIPELINE node executes live only when its child did not
            // complete (a completed child's NodeIo would be replayed, never
            // re-entering this executor). Any other persisted state is a
            // corruption edge — fail loudly instead of risking a double run.
            emit(failure("Sub-pipeline '${targetGraph.name}' run is in an unexpected state (${existing.status})"))
            return ChildPrep.Aborted
        }

        // Resumable child: validate its graph identity, then continue it from
        // its persisted trace instead of restarting.
        if (existing.graphContentHash != null && existing.graphContentHash != childHash) {
            pipelineRunRepository.finishRun(
                childRunId,
                PipelineRunStatus.FAILED,
                "Sub-pipeline '${targetGraph.name}' was edited since it was interrupted; restart the task instead.",
                RunTerminationReason.GraphChanged,
            )
            emit(
                failure(
                    "Sub-pipeline '${targetGraph.name}' was edited since it was interrupted; restart the task.",
                    RunTerminationReason.GraphChanged,
                ),
            )
            return ChildPrep.Aborted
        }

        val childResume = rebuildResumeContext(childRunId)
        // Flip the resumable child (INTERRUPTED or WAITING_*) back through
        // QUEUED → RUNNING and re-register process ownership.
        pipelineRunRepository.markResumed(childRunId, existing.status)
        pipelineRunRepository.markRunning(childRunId, targetGraph.id, childHash)
        return ChildPrep.Ready(resume = childResume)
    }

    /**
     * Rebuilds the child's [ResumeContext] from its persisted trace — the
     * seq-ordered `NodeIo` prefix to replay, the latest memory snapshot, and
     * the first free seq for new records. Identical in shape to the top-level
     * resume rebuild in the task queue.
     */
    private suspend fun rebuildResumeContext(childRunId: String): ResumeContext {
        val trace = runTraceRepository.getTraceForRun(childRunId)
        return ResumeContext(
            records = trace.filterIsInstance<RunTraceRecord.NodeIo>().sortedBy { it.seq },
            memorySnapshot = trace.filterIsInstance<RunTraceRecord.MemorySnapshot>()
                .maxByOrNull { it.seq }
                ?.entries,
            nextSeq = (trace.maxOfOrNull { it.seq } ?: -1L) + 1,
        )
    }

    /**
     * Forwards a child orchestrator state to the parent engine when it is an
     * observability or HITL state — the console snapshot and per-node I/O
     * snapshot (both depth-stamped, so the console nests them) and the
     * approval/clarification gates (so a sub-pipeline's HITL card surfaces) —
     * plus [AgentOrchestratorState.RunNotice], because the ceilings are shared
     * across the whole run tree: a soft crossing raised inside a sub-pipeline is
     * about the very allowance the user is watching at depth zero, and dropping
     * it here would make the warning arrive only for shallow runs.
     * Streaming/answer/stage states are dropped: only the sub-pipeline's final
     * response is the parent node's output. [AgentOrchestratorState.PipelineTrace]
     * is deliberately *not* forwarded live — it carries no run id, so the UI
     * could not tell a child's cumulative trace from the parent's; the Traces
     * tab gains its sub-pipeline span hierarchy from the persisted run-tree
     * projection on reattach/replay instead.
     */
    private suspend fun FlowCollector<NodeOutput>.forwardIfObservable(state: AgentOrchestratorState) {
        when (state) {
            is AgentOrchestratorState.ConsoleLog,
            is AgentOrchestratorState.NodeIO,
            is AgentOrchestratorState.WaitingForApproval,
            is AgentOrchestratorState.AwaitingClarification,
            is AgentOrchestratorState.WaitingForCeilingRaise,
            is AgentOrchestratorState.RunNotice,
            -> emit(NodeOutput.State(state))
            else -> Unit
        }
    }

    /**
     * Emits this node's terminal [NodeOutput.Result] from the collected child
     * outcome: the error (if any), else the final response, else the defensive
     * "no output" failure.
     */
    private suspend fun FlowCollector<NodeOutput>.emitTerminal(
        errorMessage: String?,
        terminationReason: RunTerminationReason?,
        finalResponse: String?,
        targetGraph: PipelineGraph,
    ) {
        when {
            errorMessage != null -> emit(failure(errorMessage, terminationReason))
            finalResponse != null -> emit(NodeOutput.Result(NodeExecutionResult(outputText = finalResponse)))
            else -> emit(failure("Sub-pipeline '${targetGraph.name}' produced no output"))
        }
    }

    /**
     * Outcome of [prepareChildRun]: the child is [Ready] to run (carrying the
     * resume context, `null` for a fresh run), or the call [Aborted] after
     * emitting a node failure.
     */
    private sealed interface ChildPrep {
        /** The child record is RUNNING; drive the engine with [resume] (`null` = fresh). */
        data class Ready(val resume: ResumeContext?) : ChildPrep

        /** A failure was already emitted; the caller must stop. */
        data object Aborted : ChildPrep
    }

    /**
     * Wraps an error [message] as a terminal [NodeOutput.Result] so the parent
     * engine routes it through its standard node-error channel.
     *
     * @param message The human-readable failure text.
     * @param terminationReason The child's typed cause, when the app itself
     *   ended the sub-pipeline. Carried up so the root run settles with the
     *   same vocabulary the child used.
     */
    private fun failure(message: String, terminationReason: RunTerminationReason? = null): NodeOutput.Result =
        NodeOutput.Result(NodeExecutionResult(error = message, terminationReason = terminationReason))

    private companion object {
        /**
         * Statuses a child sub-run may be continued from when the PIPELINE node
         * re-executes live during a resume: the process-death interruption and
         * the two persistent HITL parks.
         */
        val RESUMABLE_CHILD_STATUSES = setOf(
            PipelineRunStatus.INTERRUPTED,
            PipelineRunStatus.WAITING_APPROVAL,
            PipelineRunStatus.WAITING_CLARIFICATION,
            PipelineRunStatus.WAITING_CEILING,
        )

        /**
         * Builds the deterministic child run id of a sub-pipeline run:
         * `"<parentRunId>::<nodeId>::<visitIndex>"`. Determinism is the
         * mechanism that lets a resume find and continue the same child run
         * rather than starting a new one.
         */
        fun childRunId(parentRunId: String, nodeId: String, visitIndex: Int): String =
            "$parentRunId::$nodeId::$visitIndex"
    }
}
