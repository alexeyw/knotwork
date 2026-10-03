package app.knotwork.android.domain.engine

import app.knotwork.android.domain.constants.PipelineExecutionDefaults
import app.knotwork.android.domain.engine.executors.NodeExecutorFactory
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.RunNoticeCause
import app.knotwork.android.domain.models.RunTreeContext
import app.knotwork.android.domain.models.diagnostic
import app.knotwork.android.domain.repositories.MetricsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import timber.log.Timber

/**
 * Runs one node live — its executor, not a checkpoint record — and accounts for
 * it: forwards what the executor reports, records its duration, charges the run
 * tree's ledger, and announces a soft ceiling crossing.
 *
 * What the executor emits is forwarded into the run's flow as it arrives: its
 * states (mirrored into the run record, an error's text redacted), its console
 * lines (stamped by the run's console), and its result. An executor that parks
 * its run in the background ends the walk with no terminal state; one that
 * throws ends it with an error. Either way the walk returns, and this class has
 * already emitted everything the run's observers need.
 *
 * One instance per engine invocation.
 *
 * @param nodeExecutorFactory Resolves the executor for a node type.
 * @param metricsRepository Node durations and structured-output repairs.
 * @param collector The invocation's flow collector.
 * @param console The invocation's console.
 * @param records The invocation's run record.
 * @param inputs What each node sees.
 * @param tree The run tree, whose ledger is charged and whose notes receive advice.
 * @param graph The running graph, for a routing node's choices.
 * @param call Who runs it: the session, the invocation's prompt and run id.
 * @param resuming Whether this invocation resumes an interrupted run.
 * @param beforeOutput Called just before an OUTPUT node's executor runs — the last
 *   moment the engine may still push a console line ahead of `Completed`.
 */
class LiveNodeStep(
    private val nodeExecutorFactory: NodeExecutorFactory,
    private val metricsRepository: MetricsRepository,
    private val collector: FlowCollector<AgentOrchestratorState>,
    private val console: RunConsole,
    private val records: RunRecordWriter,
    private val inputs: NodeInputComposer,
    private val tree: RunTreeContext,
    private val graph: PipelineGraph,
    private val call: NodeCall,
    private val resuming: Boolean,
    private val beforeOutput: suspend () -> Unit,
) {

    /**
     * Who an executor runs for.
     *
     * @property sessionId The chat session.
     * @property userPrompt The invocation's prompt — the original task.
     * @property runId The invocation's run record, or `null` when not persisted.
     */
    data class NodeCall(val sessionId: String, val userPrompt: String, val runId: String?)

    /**
     * Runs [node] on [carriedInput].
     *
     * @param node The node the walk has reached.
     * @param carriedInput The text the run carried to it.
     * @param carriedByModel Whether a model wrote [carriedInput].
     * @param pipelineVisitIndex This visit's index when [node] is a PIPELINE node.
     * @return The node's result, or that the walk has ended.
     */
    suspend fun run(
        node: NodeModel,
        carriedInput: String,
        carriedByModel: Boolean,
        pipelineVisitIndex: Int,
    ): LiveOutcome {
        console.push(ConsoleEventType.NodeExecution, "▶ ${node.type.name}")

        // Give UI time to render the stage before CPU-heavy inference starts
        kotlinx.coroutines.delay(PipelineExecutionDefaults.LITE_RT_PREWARM_DELAY_MS)

        val executor = nodeExecutorFactory.getExecutor(node.type)
        Timber.tag(
            "PipelineDebug",
        ).d(
            "[NODE_IN] type=${node.type.name} id=${node.id} " +
                "input=${carriedInput.take(PipelineExecutionDefaults.NODE_IO_LOG_CHAR_LIMIT)}",
        )
        // The node with its system prompt rendered, and the input composed from
        // the context blocks it opted into.
        val prepared = inputs.prepare(node, carriedInput)
        val startMs = System.currentTimeMillis()
        val scope = ExecutionScope(
            run = tree,
            pipelineVisitIndex = pipelineVisitIndex,
            // A routing node validates its key against the labels of its own
            // outgoing edges — surfaced so the executor can constrain (and repair
            // towards) a key that matches a branch.
            routingChoices = GraphRouting.routingChoices(node, graph),
            // The run's image goes to the first vision-eligible node of the tree only.
            imagePath = inputs.takeImage(node),
            inputWrittenByModel = carriedByModel,
        )
        // Before the terminal OUTPUT node runs: its executor emits `Completed`,
        // after which no further console line may be pushed (it would shift the
        // last orchestrator state away from `Completed`).
        if (node.type == NodeType.OUTPUT) beforeOutput()

        val result = try {
            val collected = collect(
                executor.execute(prepared.node, prepared.input, call.sessionId, call.userPrompt, call.runId, scope),
                prepared.node,
            )
            // A parked run ends the walk without a terminal state: the executor
            // made the pending request durable and the run record keeps its
            // WAITING_* status — the user's response resumes the run from its
            // checkpoint later, possibly in another process. Flush the buffered
            // trace first so the checkpoint is complete up to this exact node.
            if (collected.parked) {
                console.push(ConsoleEventType.NodeExecution, "⏸ ${node.type.name} parked awaiting user response")
                console.flush()
                return LiveOutcome.Parked
            }
            // The executor flow completing means any HITL suspension of this node
            // is definitively resolved.
            records.endSuspension()
            Timber.tag(
                "PipelineDebug",
            ).d(
                "[NODE_OUT] type=${node.type.name} id=${node.id} " +
                    "output=${collected.result?.outputText?.take(PipelineExecutionDefaults.NODE_IO_LOG_CHAR_LIMIT)}",
            )
            collected.result
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Node executors suspend; collapsing a cancelled run into an `Error`
            // emission would both surface a false error and keep the flow alive
            // past its collector's cancellation.
            throw e
        } catch (e: Exception) {
            // The exception may be a provider's, quoting its key. The throwable
            // still goes to Timber for its stack trace — the crash-reporting tree
            // redacts every record it forwards — but the text surfaced and
            // persisted from here is redacted at this line.
            val safeMessage = CloudErrorSanitizer.redactSecrets(e.message ?: "Unknown error")
            Timber.tag(
                "PipelineDebug",
            ).e(e, "[NODE_ERR] type=%s id=%s error=%s", node.type.name, node.id, safeMessage)
            console.push(ConsoleEventType.Error, "${node.type.name}: $safeMessage")
            collector.emit(AgentOrchestratorState.Error(safeMessage))
            return LiveOutcome.Failed
        }
        val durationMs = System.currentTimeMillis() - startMs
        metricsRepository.recordNodeExecution(node.type, durationMs, result?.tokenCount)
        charge(node, result)
        return LiveOutcome.Ran(result, prepared.input, durationMs)
    }

    /** Forwards what the executor emits and keeps its result and whether it parked. */
    private suspend fun collect(outputs: Flow<NodeOutput>, executedNode: NodeModel): Collected {
        val collected = Collected()
        outputs.collect { output ->
            when (output) {
                is NodeOutput.State -> {
                    if (output.state is AgentOrchestratorState.SuspendedInBackground) {
                        // Not mirrored: while the record is WAITING_*, mirroring
                        // any other state flips it back to RUNNING, and a parked
                        // run must keep its WAITING_* status.
                        collected.parked = true
                    } else {
                        records.mirror(output.state)
                    }
                    collector.emit(output.state.withRedactedError())
                }
                is NodeOutput.Result -> collected.result = output.result
                is NodeOutput.Console -> {
                    // The node has no console sink of its own; the engine owns
                    // `seq`/`depth` stamping. A repair attempt additionally bumps
                    // the per-node repair counter so the statistics surface
                    // reflects how often this node's structured output stumbled.
                    console.push(output.type, output.message)
                    if (output.type == ConsoleEventType.StructuredOutputRepair) {
                        metricsRepository.recordStructuredOutputRepair(executedNode.label)
                    }
                }
            }
        }
        return collected
    }

    /**
     * Charges the run tree for [node], which has just run, and announces a soft
     * ceiling crossing.
     *
     * Only work actually done is charged — this runs on the live path alone. A
     * replayed node was charged when it really ran; charging it again would make a
     * run that parks often die earlier than one that never parks — the inverse of
     * what the persisted ledger is for.
     *
     * Two further exclusions keep the *step* counter stable across attempts, which
     * is the property a persisted counter has to have and the one a per-attempt
     * budget never needed:
     *
     *  - INPUT and OUTPUT are never written to the trace, so they are never
     *    replayed and run their executors again on every resumed attempt. On a
     *    fresh attempt they are ordinary steps and are charged; on a resume they
     *    were already charged the first time, and charging them again would let a
     *    run that parks fifteen times exhaust a fifteen-step ceiling on
     *    pass-through nodes alone — which is the very scenario the persisted
     *    counter exists to bound.
     *  - A node whose result carries a termination reason is a `PIPELINE` node
     *    whose child already charged this same tree and stopped it; charging the
     *    parent node on top would persist one more than the ceiling it just
     *    reported.
     *
     * Tokens are charged unconditionally: INPUT produces none, and OUTPUT is
     * terminal, so neither can be double-counted later.
     */
    private suspend fun charge(node: NodeModel, result: NodeExecutionResult?) {
        val replayedPassThrough = resuming && (node.type == NodeType.INPUT || node.type == NodeType.OUTPUT)
        if (!replayedPassThrough && result?.terminationReason == null) {
            tree.budget.chargeStep()
        }
        tree.budget.chargeTokens(result?.tokenCount, approximate = result?.tokensEstimated != false)
        records.recordSpend(tree.budget)
        // Announce a soft crossing where it happens, not at the top of the next
        // iteration: a run whose last node crosses the threshold would otherwise
        // never say so, because there is no next iteration to say it in.
        //
        // OUTPUT is excluded for the reason the `✓` event and the `NodeIO`
        // emission are: its executor has already emitted `Completed`, and a console
        // line pushed after that would shift the terminal state away from the tail
        // of the flow. Nothing is lost — a warning that the run is approaching its
        // limit has no reader once the answer has been delivered.
        if (node.type == NodeType.OUTPUT) return
        val soft = tree.budget.claimSoftBreach() ?: return
        // The cause is typed at the source. The console gets the terse diagnostic;
        // the sentence the user reads is resolved from this same value in the
        // presentation layer, so the crossing is worded once rather than once per
        // surface.
        val cause = RunNoticeCause.ApproachingCeiling(axis = soft.axis, spent = soft.spent, hardLimit = soft.hardLimit)
        console.push(ConsoleEventType.RunCeiling, cause.diagnostic())
        collector.emit(AgentOrchestratorState.RunNotice(cause))
        tree.contextNotes.add(SOFT_CEILING_CONTEXT_NOTE)
    }

    /**
     * Returns this state with a credential quoted in its error message masked, and
     * any other state unchanged.
     *
     * An executor forwards its own `Error` straight to the engine's collector, and
     * from there it becomes the run record's message — the one the chat export and
     * the trigger-journal export share. Redacting here covers the executors that
     * scrub their provider errors and the ones that do not.
     */
    private fun AgentOrchestratorState.withRedactedError(): AgentOrchestratorState =
        if (this is AgentOrchestratorState.Error) copy(message = CloudErrorSanitizer.redactSecrets(message)) else this

    /** What one executor run left behind. */
    private class Collected {
        var result: NodeExecutionResult? = null
        var parked: Boolean = false
    }

    /** The advice injected when the run crosses a soft ceiling. */
    companion object {
        /**
         * Injected into the run's own context the first time an axis crosses its
         * soft threshold, so the model driving the next node can bring the task to
         * a close on its own terms instead of discovering the hard stop by walking
         * into it.
         *
         * Deliberately says what to do rather than quoting a number: the numbers
         * are in the console line a person reads, and a budget figure inside the
         * prompt invites the model to reason about arithmetic instead of about the
         * task.
         */
        const val SOFT_CEILING_CONTEXT_NOTE: String =
            "SYSTEM NOTICE: this run is close to its resource limit and may be stopped before it " +
                "finishes. Wrap up now: produce the best answer you can from what you already have, " +
                "and do not start new sub-tasks or additional tool calls."
    }
}

/** How a live node run ended, for the walk. */
sealed interface LiveOutcome {

    /**
     * The node ran to completion.
     *
     * @property result Its result, or `null` when its executor emitted none.
     * @property input What it executed on — its composed input.
     * @property durationMs How long it took.
     */
    data class Ran(val result: NodeExecutionResult?, val input: String, val durationMs: Long) : LiveOutcome

    /** The node parked the run in the background; the walk ends without a terminal state. */
    data object Parked : LiveOutcome

    /** The node's executor threw; the error is already emitted and the walk ends. */
    data object Failed : LiveOutcome
}
