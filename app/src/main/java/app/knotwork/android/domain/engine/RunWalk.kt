package app.knotwork.android.domain.engine

import app.knotwork.android.domain.engine.stuck.GraphStuckDetector
import app.knotwork.android.domain.engine.stuck.RunStepObservation
import app.knotwork.android.domain.engine.stuck.StuckVerdict
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.RunNoticeCause
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.RunTreeContext
import app.knotwork.android.domain.models.ToolInvocationResult
import app.knotwork.android.domain.models.asCeilingBreach
import app.knotwork.android.domain.models.diagnostic
import kotlinx.coroutines.flow.FlowCollector
import timber.log.Timber

/**
 * Walks one engine invocation's graph from INPUT until the run ends: at OUTPUT,
 * on a suspension, on an error, or on a protective stop.
 *
 * Every step goes the same way. The ceiling is asked first, so a node that would
 * exceed it is never run. The node is entered — counted, recorded as the run's
 * current node, shown as a stage. It is then replayed from its checkpoint record
 * or run live. A failure ends the run, unless a queue was told to carry on. The
 * step is written to the console and the trace, and shown to the repetition
 * detector. Then the walk moves on: into a queue's loop, down a routing node's
 * branch, or along the first edge.
 *
 * The walk owns its position — the node it is on, the text it carries there and
 * whether a model wrote that text — and the progress it reports. Everything else
 * belongs to the collaborators it is given.
 *
 * One instance per engine invocation.
 *
 * @param collector The invocation's flow collector.
 * @param graph The running graph.
 * @param tree The run tree: its ledger, detector, notes and depth.
 * @param console The invocation's console and trace.
 * @param records The invocation's run record.
 * @param inputs What each node sees; also collects tool results.
 * @param replay The resumed run's recorded prefix.
 * @param live Runs a node's executor.
 * @param noteUndeliveredImage Notes an image no node took, when the walk ends
 *   without an OUTPUT node.
 */
class RunWalk(
    private val collector: FlowCollector<AgentOrchestratorState>,
    private val graph: PipelineGraph,
    private val tree: RunTreeContext,
    private val console: RunConsole,
    private val records: RunRecordWriter,
    private val inputs: NodeInputComposer,
    private val replay: CheckpointReplay,
    private val live: LiveNodeStep,
    private val noteUndeliveredImage: suspend () -> Unit,
) {

    /** The QUEUE_PROCESSOR loop the walk is in, if any. */
    private val queue = QueueCursor(graph)

    /** Every non-INPUT/OUTPUT step so far, re-emitted whole for the console's Traces tab. */
    private val traceSteps = mutableListOf<AgentOrchestratorState.TraceStep>()

    /**
     * Per-node visit counter. A node inside a loop (QUEUE_PROCESSOR) executes once
     * per item; the index tells the visits apart — a PIPELINE node's child run id,
     * every on-device call's seed, the trace record of each visit — and is
     * re-derived deterministically on resume (it increments on replayed visits
     * too), so the in-flight visit lands on the same index as on the interrupted
     * run.
     */
    private val visitCounts = mutableMapOf<String, Int>()

    /**
     * Steps the run is expected to take: known up front for a graph without
     * routing or queues; for any other it stays `null` until a branch is resolved.
     */
    private var estimatedTotalSteps: Int? = GraphRouting.fixedStepCount(graph)

    /** Steps taken so far. */
    private var stepCount = 0

    /** The node the walk is on, or `null` once a branch has ended. */
    private var current: NodeModel? = null

    /** The text the walk carries into [current]. */
    private var carried = ""

    /**
     * Whether a model wrote [carried] (ModelAuthorship): the run's prompt is the
     * user's, a tool's result is not a model's, and a pass-through node keeps
     * whatever it received. The root OUTPUT records it on the chat row so memory
     * extraction skips relayed text.
     */
    private var carriedByModel = false

    /**
     * Runs the graph from [input], carrying [prompt] into it.
     *
     * @param input The graph's INPUT node.
     * @param prompt The invocation's prompt.
     */
    suspend fun walk(input: NodeModel, prompt: String) {
        current = input
        carried = prompt
        // Set the moment a ceiling or the detector refuses to let the walk
        // continue, so the end can say which one bound instead of re-deriving it.
        var stop: RunTerminationReason? = null
        while (true) {
            val node = current ?: break
            when (val ceiling = checkCeiling()) {
                CeilingCheck.Clear -> Unit
                CeilingCheck.Paused -> return
                is CeilingCheck.Stopped -> {
                    stop = ceiling.reason
                    break
                }
            }
            val visit = enter(node)
            val step = execute(node, visit) ?: return
            val result = step.result
            val error = result?.error
            if (result != null && error != null) {
                if (survivesFailure(node, result, CloudErrorSanitizer.redactSecrets(error))) continue else return
            }
            record(node, step, visit)
            if (observe(node, step)) {
                stop = RunTerminationReason.NoProgress
                break
            }
            if (node.type == NodeType.OUTPUT) return
            advance(node, result)
        }
        finish(stop)
    }

    /**
     * Asks the ledger before the next node is charged, so a node that is refused
     * is never counted: a run stopped at the ceiling has spent exactly the
     * ceiling, not one more than it.
     *
     * A ceiling is a number the user chose, and reaching it says nothing went
     * wrong — so the run asks whether it may carry on instead of ending on the
     * spot. The park is durable from the first moment, with no live in-process
     * phase before it. The two HITL gates have one because something is in
     * flight — a resolved tool call, a generated question — that a park would
     * have to persist and a resume re-consume. Here the walk is *between* nodes:
     * the checkpoint already holds everything, so a live wait would hold the
     * foreground service open buying nothing.
     */
    private suspend fun checkCeiling(): CeilingCheck {
        val breach = tree.budget.hardBreach() ?: return CeilingCheck.Clear
        val ceiling = breach.asCeilingBreach()
        if (ceiling == null || !records.parkOnCeiling(ceiling)) {
            // Non-persisted runs (editor test runs) and storage failures stop.
            // A pause the user cannot be asked about, or one recorded nowhere and
            // so unanswerable after this coroutine ends, is worse than a stop that
            // says why it stopped.
            return CeilingCheck.Stopped(breach)
        }
        // The diagnostic plus one stable word, not a sentence. The suffix is
        // load-bearing: without it a pause and a stop produce byte-identical
        // console lines, and whoever greps this later cannot tell a run that
        // ended from one that is still waiting.
        console.push(ConsoleEventType.RunCeiling, "${breach.diagnostic()} — paused")
        val pause = AgentOrchestratorState.WaitingForCeilingRaise(
            axis = ceiling.axis,
            limit = ceiling.limit,
            spent = ceiling.spent,
        )
        records.mirror(pause)
        collector.emit(pause)
        // Ends the walk without a terminal state, exactly as an approval park
        // does — and, from a sub-pipeline, tells the parent PIPELINE node to park
        // the whole stack rather than settle this run.
        collector.emit(AgentOrchestratorState.SuspendedInBackground(PendingInteractionKind.CEILING))
        console.flush()
        return CeilingCheck.Paused
    }

    /**
     * Enters [node]: counts the step, records the node so an interrupted run can
     * report where it stopped, and shows the stage with the current estimate.
     *
     * @return The node's zero-based visit index — incremented on every entry,
     *   replayed or live, so the counter stays aligned through a resume replay and
     *   the live visit gets the right index.
     */
    private suspend fun enter(node: NodeModel): Int {
        stepCount++
        val visitIndex = visitCounts.getOrDefault(node.id, 0)
        visitCounts[node.id] = visitIndex + 1
        records.enterNode(node.id)
        collector.emit(
            AgentOrchestratorState.PipelineStage(
                AgentOrchestratorState.PipelineStepInfo(
                    stepIndex = stepCount,
                    totalSteps = estimatedTotalSteps,
                    nodeName = node.type.name,
                ),
            ),
        )
        return visitIndex
    }

    /**
     * Runs [node] — from its checkpoint record when the interrupted run completed
     * it, live otherwise.
     *
     * A replayed node's recorded output and routing verdicts drive the same
     * control flow a live result would. No executor call, no metrics, no new
     * NodeIo record; the compact console line is the only addition to the trace.
     *
     * @return The step, or `null` when the run has ended: the checkpoint no longer
     *   fits the graph, or the live node parked the run or failed.
     */
    private suspend fun execute(node: NodeModel, visitIndex: Int): Step? = when (val taken = replay.take(node)) {
        ReplayStep.Diverged -> {
            console.push(ConsoleEventType.Error, "Checkpoint trace diverged at ${node.type.name}; resume aborted")
            collector.emit(
                AgentOrchestratorState.Error(
                    "Recorded checkpoint no longer matches the pipeline graph. Restart the task instead.",
                    reason = RunTerminationReason.GraphChanged,
                ),
            )
            null
        }
        is ReplayStep.Replayed -> {
            console.push(ConsoleEventType.NodeExecution, "↻ ${node.type.name} replayed from checkpoint")
            Step(taken.result, taken.input, taken.durationMs, replayed = true)
        }
        ReplayStep.Live -> when (val outcome = live.run(node, carried, carriedByModel, visitIndex)) {
            is LiveOutcome.Ran -> Step(outcome.result, outcome.input, outcome.durationMs, replayed = false)
            LiveOutcome.Parked, LiveOutcome.Failed -> null
        }
    }

    /**
     * Handles a node that failed.
     *
     * Every node's failure passes this point on its way to the run record, the
     * console and the surface, so this is where a credential quoted in a provider
     * error is stopped regardless of which executor produced it — or forgot to
     * scrub it.
     *
     * @param error The node's error, already redacted.
     * @return `true` when the walk carries on — a queue whose author turned
     *   `stopOnError` off moves to its next item; `false` when the run has ended
     *   on the error.
     */
    private suspend fun survivesFailure(node: NodeModel, result: NodeExecutionResult, error: String): Boolean {
        Timber.tag("PipelineDebug").e("[NODE_ERR] type=%s id=%s error=%s", node.type.name, node.id, error)
        console.push(ConsoleEventType.Error, "${node.type.name}: $error")
        // A typed cause is never survivable, whatever the switch says. A
        // `PIPELINE` node forwards a sub-pipeline's ceiling breach or
        // stuck-detector verdict through this same field, and those are not "this
        // subtask failed" — they are the run being out of budget or going in
        // circles. Carrying on would spend the very budget the breach reported as
        // gone.
        if (result.terminationReason == null && queue.continuesAfterFailure()) {
            moveTo(queue.advancePastFailure(error))
            return true
        }
        // A `PIPELINE` node forwards its sub-pipeline's typed cause here.
        // Re-emitting it is what keeps a ceiling breach one nesting level down
        // from settling the root run as an ordinary failure.
        collector.emit(AgentOrchestratorState.Error(error, reason = result.terminationReason))
        return false
    }

    /** Writes [step] — the [visit]-th of [node] — to the console and the trace, and keeps a TOOL node's result. */
    private suspend fun record(node: NodeModel, step: Step, visit: Int) {
        // Skip the "✓" event for OUTPUT — its own emitted Completed state already
        // marks the end of the pipeline, and pushing a ConsoleLog after Completed
        // would shift the terminal state away from the tail of the flow. Replayed
        // nodes already pushed their compact "↻ replayed" event instead.
        if (node.type != NodeType.OUTPUT && !step.replayed) {
            console.push(ConsoleEventType.NodeExecution, "✓ ${node.type.name} in ${step.durationMs}ms")
        }
        if (node.type == NodeType.TOOL) {
            val toolOutput = step.result?.outputText ?: ""
            // Prefer the executor-resolved tool name so "auto"-configured TOOL
            // nodes attribute the observation to the tool that actually ran, not
            // the literal "auto" placeholder. Fall back to the node's configured
            // toolName, then the node label as a last resort.
            val toolName = step.result?.resolvedToolName
                ?: node.toolName?.takeUnless { it.equals("auto", ignoreCase = true) }
                ?: node.label
            inputs.recordToolResult(ToolInvocationResult(toolName = toolName, output = toolOutput))
            console.push(ConsoleEventType.ToolCall, toolName)
        }
        if (node.type == NodeType.INPUT || node.type == NodeType.OUTPUT) return
        val outputText = step.result?.outputText ?: carried
        traceSteps.add(
            AgentOrchestratorState.TraceStep(
                nodeName = node.type.name,
                outputText = outputText,
                durationMs = step.durationMs,
                tokenCount = step.result?.tokenCount,
                depth = tree.depth,
            ),
        )
        // Write-through into the persistent run trace, which a checkpoint resume
        // replays instead of re-running the node. A replayed node appends nothing
        // — its record is already in the trace.
        if (!step.replayed) {
            console.recordNodeIo(node, visit, step.input, outputText, step.durationMs, step.result)
        }
        collector.emit(AgentOrchestratorState.PipelineTrace(traceSteps.toList()))
        // Surface the per-node I/O pair for the Vars tab of the chat-home console
        // pane. INPUT is skipped (its input is the raw user prompt already
        // surfaced as the latest chat row) and OUTPUT is skipped (already
        // terminal; emitting after the `Completed` of OUTPUT would shift the
        // terminal state away from the tail of the flow — same rule applied to
        // the `✓` console event above).
        collector.emit(
            AgentOrchestratorState.NodeIO(
                nodeId = node.id,
                nodeType = node.type.name,
                input = step.input,
                output = outputText,
                depth = tree.depth,
            ),
        )
    }

    /**
     * Shows [step] to the repetition detector.
     *
     * Called **after** the trace append, not before it. The stop's whole
     * user-facing promise is *Open console*, where the repetition is visible — and
     * a stop taken before the append would drop the one step the verdict was
     * actually reached on, so the console would be missing precisely the evidence
     * the reader was sent to find. Judging the same input/output pair the trace
     * has just recorded is the point: for every node the trace carries, what the
     * detector saw and what a person can read back are the same thing. (INPUT is
     * the one exception, and it is the trace's rule, not this one: INPUT and
     * OUTPUT are never recorded. INPUT is still observed, because the pass-through
     * accounting needs it, but it can only ever contribute a pass-through — never
     * the repetition a verdict is reached on.)
     *
     * OUTPUT is excluded on the same grounds as the ✓ event and the soft-ceiling
     * notice: its executor has already emitted `Completed`, and a console line
     * pushed after that would shift the terminal state away from the tail of the
     * flow. Nothing is lost — a run that has just delivered its answer is not
     * going in circles.
     *
     * @return `true` when the detector stops the run.
     */
    private suspend fun observe(node: NodeModel, step: Step): Boolean {
        if (node.type == NodeType.OUTPUT) return false
        val observation = RunStepObservation(
            nodeId = node.id,
            inputFingerprint = GraphStuckDetector.fingerprint(step.input),
            // The engine's own fallback for a node that emitted no result is to
            // forward its input, so the fingerprints match and the step reads as
            // the pass-through it is.
            outputFingerprint = GraphStuckDetector.fingerprint(step.result?.outputText ?: step.input),
        )
        if (step.replayed) {
            // Rebuild the window from history without re-deciding it: the attempt
            // that actually ran this prefix did not stop on it, and reaching a
            // different verdict now would be the app rewriting what already
            // happened.
            //
            // The escalation it earned does carry forward, though, and that
            // obliges us to carry the advice with it: notes are live-only, so the
            // attempt that raised one and then parked destroyed it. Re-queue it for
            // the first live node that composes a prompt — otherwise the resumed
            // run inherits only the clock and is stopped for ignoring something it
            // was never told. The console line and the on-screen notice are not
            // repeated: they were emitted when the verdict was actually reached,
            // and a warning re-shown on every resume is one the reader learns to
            // skip.
            if (tree.stuckDetector.replay(observation)) tree.contextNotes.add(STUCK_CONTEXT_NOTE)
            return false
        }
        return when (val verdict = tree.stuckDetector.observe(observation)) {
            StuckVerdict.Healthy -> false
            is StuckVerdict.Nudge -> {
                val cause = RunNoticeCause.LooksStuck(verdict.signal)
                console.push(ConsoleEventType.StuckDetector, cause.diagnostic())
                collector.emit(AgentOrchestratorState.RunNotice(cause))
                tree.contextNotes.add(STUCK_CONTEXT_NOTE)
                false
            }
            is StuckVerdict.Stop -> {
                // Same seam the ceilings use: the end of the walk owns the console
                // line and the typed terminal state, so a protective stop is worded
                // in exactly one place however it was decided.
                console.push(ConsoleEventType.StuckDetector, verdict.signal.diagnostic)
                true
            }
        }
    }

    /** Moves the walk past [node], which has just run with [result]. */
    private fun advance(node: NodeModel, result: NodeExecutionResult?) {
        if (node.type == NodeType.QUEUE_PROCESSOR) {
            when (val entry = queue.enter(node, result?.outputText ?: carried)) {
                is QueueEntry.FirstItem -> {
                    estimatedTotalSteps = stepCount + entry.remainingSteps
                    // Assembled from earlier results and a planned subtask: the
                    // app's text around other nodes' output, so not a model's.
                    moveTo(QueueStep(entry.node, entry.inputText))
                }
                is QueueEntry.Skipped -> current = entry.node
            }
            return
        }
        // INTENT_ROUTER's outputText is the routing key — a control signal, not a
        // content payload. Keep the carried text so downstream nodes receive the
        // original data, not the routing label.
        carriedByModel = ModelAuthorship.after(node.type, result, carried, carriedByModel)
        carried = if (node.type == NodeType.INTENT_ROUTER) carried else result?.outputText ?: carried
        val nextNodeId = GraphRouting.nextNodeId(node, graph, result?.conditionResult, result?.routingKey)
        val nextNode = graph.nodes.find { it.id == nextNodeId }
        // After a branching node resolves its path, compute the estimated total for that branch.
        if (node.type == NodeType.INTENT_ROUTER || node.type == NodeType.IF_CONDITION) {
            estimatedTotalSteps = stepCount + GraphRouting.countNodesOnPath(nextNode, graph)
        }
        if (queue.isActive && (nextNode == null || nextNode.type == NodeType.QUEUE_PROCESSOR)) {
            moveTo(queue.advance(carried))
            return
        }
        current = nextNode
    }

    /** Moves to a queue boundary: the text there is the app's, not a model's. */
    private fun moveTo(step: QueueStep) {
        carriedByModel = false
        carried = step.inputText
        current = step.node
    }

    /**
     * Ends a walk that did not end at OUTPUT: a protective stop, or a branch that
     * ran out of edges.
     *
     * @param stop What refused to let the walk continue, or `null` when the walk
     *   simply had nowhere left to go.
     */
    private suspend fun finish(stop: RunTerminationReason?) {
        // Covers walks that don't go through an OUTPUT node; the OUTPUT path notes
        // an unused image before OUTPUT runs.
        noteUndeliveredImage()
        if (stop != null) {
            // When this run is a sub-pipeline the Error becomes the parent PIPELINE
            // node's error and terminates the whole stack; the typed reason travels
            // with it, so a stop at any depth stays classified all the way up.
            //
            // Both carriers get the *diagnostic* form, not prose. This string is
            // persisted to `pipeline_runs.errorMessage` and written to the run
            // trace, where it is read by engineers; the sentence a person reads is
            // resolved from the reason in the presentation layer. Keeping a
            // hand-written English sentence here is what previously let one event
            // acquire four different wordings, two of which described behaviour the
            // engine never had.
            console.push(consoleTypeFor(stop), stop.diagnostic())
            collector.emit(AgentOrchestratorState.Error(stop.diagnostic(), reason = stop))
        } else {
            console.push(ConsoleEventType.Error, "Pipeline terminated without OUTPUT")
            collector.emit(
                AgentOrchestratorState.Error(
                    "Pipeline execution terminated unexpectedly without reaching OUTPUT node.",
                ),
            )
        }
    }

    /**
     * Which console channel a protective stop belongs on.
     *
     * The walk exits through one seam whatever decided to end it, so the choice of
     * channel has to be made from the reason rather than from the site — and it is
     * worth making, because the two channels answer different questions. A
     * ceiling line says a number the user chose was reached; a detector line says
     * the pipeline misbehaved. Filing the second under the first is how a console
     * stops being searchable.
     *
     * @param reason The typed cause the walk stopped on.
     * @return The console event type its diagnostic should be pushed under.
     */
    private fun consoleTypeFor(reason: RunTerminationReason): ConsoleEventType = when (reason) {
        RunTerminationReason.NoProgress -> ConsoleEventType.StuckDetector
        is RunTerminationReason.StepCeiling,
        is RunTerminationReason.TokenCeiling,
        -> ConsoleEventType.RunCeiling
        // Exhaustive rather than an `else`, and the reasons below are listed even
        // though the walk cannot produce them. An `else` would quietly file the
        // next engine-raised reason under the ceiling channel — a console line
        // that says a limit was reached when none was, discovered only by whoever
        // greps for it later.
        RunTerminationReason.RunStalled,
        RunTerminationReason.HitlWindowExpired,
        RunTerminationReason.GraphChanged,
        RunTerminationReason.ProcessDied,
        RunTerminationReason.DiscardedByUser,
        RunTerminationReason.NotResumable,
        -> ConsoleEventType.SystemMessage
    }

    /**
     * One node's run, replayed or live.
     *
     * @property result Its result, or `null` when its executor emitted none.
     * @property input What it executed on.
     * @property durationMs How long it took.
     * @property replayed Whether it came from the checkpoint rather than its executor.
     */
    private class Step(
        val result: NodeExecutionResult?,
        val input: String,
        val durationMs: Long,
        val replayed: Boolean,
    )

    /** What the ledger says before the next node. */
    private sealed interface CeilingCheck {
        /** Under every ceiling: run the node. */
        data object Clear : CeilingCheck

        /** A ceiling was reached and the run parked to ask; the walk ends without a terminal state. */
        data object Paused : CeilingCheck

        /**
         * A ceiling was reached and the run cannot ask; the walk stops.
         *
         * @property reason The ceiling that bound.
         */
        data class Stopped(val reason: RunTerminationReason) : CeilingCheck
    }

    private companion object {
        /**
         * Injected into the run's own context the first time the stuck-detector
         * decides the run is going in circles, so the model gets a chance to break
         * the pattern itself before the run is ended for it. This first stage is
         * meant to be the last one: a model told that it is repeating itself
         * usually stops.
         *
         * Names the observation rather than the machinery. "You have produced this
         * before" is something a model can act on; the signal name and the
         * repetition count are for the console, where an engineer reads them.
         */
        const val STUCK_CONTEXT_NOTE: String =
            "SYSTEM NOTICE: this run appears to be repeating itself — the same work has produced the " +
                "same result more than once, and the run will be stopped if that continues. Change " +
                "approach or finish: give the best answer you can from what you already have, and do " +
                "not repeat a step you have already taken."
    }
}
