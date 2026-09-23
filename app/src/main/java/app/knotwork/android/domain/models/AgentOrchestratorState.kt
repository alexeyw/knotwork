package app.knotwork.android.domain.models

/**
 * Represents the current state of the agent orchestrator while the user-authored
 * pipeline graph is being executed node by node (graph-driven orchestration —
 * not an autonomous ReAct loop).
 */
sealed interface AgentOrchestratorState {
    /**
     * The agent is doing nothing.
     */
    data object Idle : AgentOrchestratorState

    /**
     * Initializing the orchestrator, preparing context and tools.
     */
    data object Loading : AgentOrchestratorState

    /**
     * The task is accepted but waiting behind another run: the queue executes one
     * run at a time, and another session's run — a trigger, a share, a scheduled or
     * automated run, another chat — holds the worker.
     *
     * Emitted on the session's own flow only, never as the global state, which keeps
     * describing the run that is actually executing. It ends when the worker picks the
     * task up and emits [Loading]. The wait has no bound of its own: it lasts as long as
     * the run ahead, so a surface must not present it as generation.
     */
    data object Queued : AgentOrchestratorState

    /**
     * The agent is generating a thought or answering the user.
     *
     * @property partialText The generated text so far.
     */
    data class Thinking(val partialText: String) : AgentOrchestratorState

    /**
     * The agent decided to execute a tool.
     *
     * @property toolName The name of the tool being executed.
     * @property arguments The arguments passed to the tool.
     */
    data class ExecutingTool(val toolName: String, val arguments: String) : AgentOrchestratorState

    /**
     * The agent wants to execute a tool, but user confirmation is required.
     *
     * @property toolName The name of the tool.
     * @property arguments The arguments for the tool.
     * @property risk Risk classification of the tool, surfaced to the UI so the
     *   approval prompt can show a risk chip (and, in the notification fallback,
     *   pick a channel / icon that matches the action's reversibility).
     * @property requestId Identity of this one request, minted by the gate when it
     *   is raised and kept by its parked record. Every surface that shows the
     *   request answers with it, and an answer settles only the request it names —
     *   never "whatever the session is waiting on now", which after a second run
     *   starts in the same session is a different request.
     */
    data class WaitingForApproval(
        val toolName: String,
        val arguments: String,
        val risk: ToolRisk,
        val requestId: String,
    ) : AgentOrchestratorState

    /**
     * The agent has paused pipeline execution and is waiting for the user to answer a
     * clarifying question. Distinct from [WaitingForApproval] which is a binary
     * approve/deny gate around a specific tool invocation: a clarification asks for
     * arbitrary input (option choice or free-form text) used to refine the next step.
     *
     * @property request Details of the pending clarification (question, options, timeout).
     */
    data class AwaitingClarification(val request: ClarificationRequest) : AgentOrchestratorState

    /**
     * The run tree has spent one of its ceilings and is waiting for the user to
     * say whether it may carry on.
     *
     * Not a failure and not a question about the work: the pipeline has not gone
     * wrong, it has run out of the allowance the user set for it. Answering yes
     * buys one more portion of [axis] and the run continues from its checkpoint;
     * answering no ends it with the ceiling as its recorded cause — which is
     * exactly what the run used to do without asking.
     *
     * The numbers travel with the state rather than being re-read at render
     * time. The card has to state the limit *this run* was stopped at, and by
     * the time it is answered — hours later, in another process — the setting
     * behind that limit may have changed.
     *
     * @property axis Which ceiling bound.
     * @property limit The limit in force when it bound, in the axis's own unit.
     *   Already includes every portion granted to this run before now.
     * @property spent What the run tree had charged against [axis].
     */
    data class WaitingForCeilingRaise(val axis: RunCeilingAxis, val limit: Int, val spent: Int) :
        AgentOrchestratorState

    /**
     * The run has been parked in its persistent waiting phase: the live
     * in-process HITL gate ([WaitingForApproval] or [AwaitingClarification])
     * timed out — or a ceiling bound, which parks straight away — the pending
     * request is durable in the pending-interaction store, and the engine
     * coroutine is about to end without a terminal state. The run record keeps its WAITING_* status; the user's response
     * later resumes the run from its checkpoint — possibly in a different
     * process. Consumers treat this as "run no longer live" without mapping
     * it to failure or cancellation.
     *
     * @property kind Which HITL gate the run is parked on.
     */
    data class SuspendedInBackground(val kind: PendingInteractionKind) : AgentOrchestratorState

    /**
     * The tool execution finished.
     *
     * @property toolName The name of the tool.
     * @property result The result observation from the tool.
     */
    data class ObservationResult(val toolName: String, val result: String) : AgentOrchestratorState

    /**
     * The agent is answering the user directly.
     *
     * @property partialText The text response generated so far.
     */
    data class Answering(val partialText: String) : AgentOrchestratorState

    /**
     * The orchestration cycle is fully completed.
     *
     * @property finalResponse The complete answer from the agent.
     */
    data class Completed(val finalResponse: String) : AgentOrchestratorState

    /**
     * An error occurred during the orchestration.
     *
     * @property message What went wrong, in text. Its audience depends on
     *   [reason]: with `reason == null` this is an ordinary failure and the
     *   string is the user-facing description, shown verbatim; with a [reason]
     *   present the app decided to end the run itself, and this is the terse
     *   **diagnostic** form (`RunTerminationReason.diagnostic()`) that lands in
     *   the console and in `pipeline_runs.errorMessage`. The sentence a person
     *   reads for a typed stop is resolved from [reason] in the presentation
     *   layer instead, so one event is worded once rather than once per
     *   surface.
     * @property reason The typed cause when the app itself decided to end the
     *   run — a ceiling, the stuck-detector, the silence watchdog, an expired approval window.
     *   `null` for an ordinary node or engine failure, which has no entry in
     *   that vocabulary. Defaulted so the two dozen sites that emit a plain
     *   failure stay unchanged, and carried so the ones that settle a run can
     *   tell a protective stop from a defect without matching [message] by
     *   string.
     */
    data class Error(val message: String, val reason: RunTerminationReason? = null) : AgentOrchestratorState

    /**
     * A non-terminal advisory about the run in flight: it is still going, but
     * something about it is worth saying while there is time to act.
     *
     * Emitted at most once per cause per run tree. **Live-only** — never
     * persisted, never replayed, and it carries its own numbers precisely
     * because it can only exist while the run is in memory (see
     * [RunNoticeCause]).
     *
     * Every existing `when` over this sealed type closes with an `else`, so
     * this variant is inert everywhere that has no opinion about it. The one
     * place it must *not* be inert is the sub-pipeline boundary: the ceilings
     * are shared across the whole run tree, so a notice raised at depth is
     * about the same allowance the user is watching at depth zero, and
     * `PipelineNodeExecutor` forwards it upward for that reason.
     *
     * @property cause What the notice is about.
     */
    data class RunNotice(val cause: RunNoticeCause) : AgentOrchestratorState

    /**
     * Holds the progress metadata of a single pipeline execution step.
     *
     * @property stepIndex The 1-based index of the current step.
     * @property totalSteps The estimated total steps for the current branch, or null when unknown
     *   (e.g. before a routing decision is made or a queue is populated).
     * @property nodeName The type name of the node currently being executed.
     */
    data class PipelineStepInfo(val stepIndex: Int, val totalSteps: Int?, val nodeName: String)

    /**
     * Indicates the current pipeline stage (node) the agent is executing.
     *
     * @property stepInfo Progress metadata for the current step.
     */
    data class PipelineStage(val stepInfo: PipelineStepInfo) : AgentOrchestratorState

    /**
     * Represents a single step in the pipeline execution trace.
     *
     * @property nodeName The name of the node that was executed.
     * @property outputText The output text generated by the node.
     * @property durationMs Wall-clock time the node took to execute, in milliseconds.
     * @property tokenCount Approximate number of tokens produced by the node, or `null`
     *   for non-LLM nodes (routers, IF-conditions, tool nodes, queue processors).
     * @property depth Pipeline-nesting level of the run this step belongs to
     *   (`0` top-level, `1` direct sub-pipeline, …). The Traces tab nests a
     *   sub-pipeline's spans under the `PIPELINE` node that spawned them.
     */
    data class TraceStep(
        val nodeName: String,
        val outputText: String,
        val durationMs: Long = 0,
        val tokenCount: Int? = null,
        val depth: Int = 0,
    )

    /**
     * Current execution trace of the pipeline.
     *
     * @property steps The list of steps executed so far.
     */
    data class PipelineTrace(val steps: List<TraceStep>) : AgentOrchestratorState

    /**
     * Snapshot of the agent console log accumulated since the start of the
     * current pipeline run. Emitted by
     * [app.knotwork.android.domain.engine.GraphExecutionEngine] every time a new
     * [ConsoleEvent] is appended; the UI mirrors [events] into
     * `ChatUiState.consoleLines` and renders the latest entries in the
     * collapsed mini-console and the full bottom sheet.
     *
     * @property events Append-only ordered list of console events for the
     *   current run. The engine emits a fresh immutable copy on every change.
     * @property runId Id of the persistent pipeline run the events belong to,
     *   or `null` when the run is not persisted (e.g. editor test runs). The
     *   UI uses it at the console replay/live seam: a live snapshot of the
     *   same run merges with the replayed baseline by [ConsoleEvent.seq].
     */
    data class ConsoleLog(val events: List<ConsoleEvent>, val runId: String? = null) : AgentOrchestratorState

    /**
     * Per-node input/output snapshot emitted by
     * [app.knotwork.android.domain.engine.GraphExecutionEngine] after every
     * non-`INPUT` / non-`OUTPUT` node completes. Powers the Vars tab of the
     * chat-home console pane: the UI accumulates each emission into a
     * `Map<nodeId, NodeIO>` and renders two rows per node (`input` and
     * `output`), grouped by [nodeId].
     *
     * @property nodeId stable identifier of the producing node (matches
     *   `NodeModel.id` so the UI can correlate vars rows with the same
     *   node across re-renders).
     * @property nodeType type name of the producing node (e.g. `LITE_RT`).
     * @property input executor input observed at the start of the step
     *   (already-composed context if the node opted into
     *   `NodeContextConfig`, otherwise the upstream node's raw output).
     * @property output executor result observed at the end of the step.
     * @property depth Pipeline-nesting level of the run this node belongs to
     *   (`0` top-level, `1` direct sub-pipeline, …). The Vars tab renders a
     *   sub-pipeline's rows indented under the spawning `PIPELINE` node.
     */
    data class NodeIO(
        val nodeId: String,
        val nodeType: String,
        val input: String,
        val output: String,
        val depth: Int = 0,
    ) : AgentOrchestratorState
}

/**
 * Whether the orchestrator is mid-run on the shared inference engine — i.e. in
 * any **non-terminal** state. Idle, [AgentOrchestratorState.Completed] and
 * [AgentOrchestratorState.Error] are terminal (engine free); every other state
 * (loading, streaming, awaiting approval, …) means a foreground generation could
 * be holding the engine's single conversation.
 *
 * Single source of truth for the "don't touch the engine right now" predicate
 * shared by the background coordinators and the voice-transcription pre-flight.
 */
val AgentOrchestratorState.isBusy: Boolean
    get() = when (this) {
        is AgentOrchestratorState.Idle,
        is AgentOrchestratorState.Completed,
        is AgentOrchestratorState.Error,
        -> false

        else -> true
    }
