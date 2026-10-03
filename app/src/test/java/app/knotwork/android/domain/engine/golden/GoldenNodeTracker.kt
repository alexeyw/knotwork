package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.PipelineGraph

/**
 * Knows which node of which pipeline the engine is executing right now, so the scripted model
 * can answer "per node and per visit" although the model seam receives nothing but a prompt.
 *
 * The signal is the engine's own: before every node — live or replayed, at every nesting depth
 * — it calls `PipelineRunRepository.updateCurrentNode(runId, nodeId)`, and the harness's run
 * repository forwards that call here. Execution under `runTest` is sequential, so the last node
 * entered is the one whose executor is calling the model. A nested run's id is the
 * deterministic `"<parentRunId>::<nodeId>::<visitIndex>"` the `PIPELINE` executor mints, which
 * is how the tracker finds the sub-pipeline's graph.
 *
 * Visits are counted per run and node, and reset at the start of every engine invocation. A
 * resumed attempt replays its prefix and re-enters a parked sub-pipeline under the same child
 * run id, so every node it re-executes gets the visit number the interrupted attempt gave it
 * and the model answers it identically — which a re-raised approval needs, since a recorded
 * decision applies only to a byte-identical call. (Counting per pipeline instead would number
 * a resumed child from one even when the same sub-pipeline had run before it.)
 *
 * @param rootRunId The run id the harness gives the top-level run.
 * @param root The scenario's root pipeline.
 * @param library Every pipeline a `PIPELINE` node may name, keyed by id.
 * @param log Receives violations (a node the graph does not have).
 */
internal class GoldenNodeTracker(
    private val rootRunId: String,
    private val root: PipelineGraph,
    private val library: Map<String, PipelineGraph>,
    private val log: GoldenEventLog,
) {

    /**
     * Where the engine is.
     *
     * @property runId The (root or nested) run executing the node.
     * @property pipeline The graph the node belongs to.
     * @property node The node.
     * @property visit One-based visit number of the node within [runId] in this engine invocation.
     */
    data class Position(val runId: String, val pipeline: PipelineGraph, val node: NodeModel, val visit: Int)

    private val visits = mutableMapOf<String, Int>()

    /** The node most recently entered, or `null` before the first node of an attempt. */
    var current: Position? = null
        private set

    /** Forgets the previous attempt's visits; called before every engine invocation. */
    fun startAttempt() {
        visits.clear()
        current = null
    }

    /**
     * Records that the engine is about to execute [nodeId] in [runId].
     *
     * @param runId The run the node executes in.
     * @param nodeId The node.
     * @return The new position.
     */
    fun enter(runId: String, nodeId: String): Position {
        val pipeline = pipelineOf(runId)
        val node = pipeline.nodes.singleOrNull { it.id == nodeId }
            ?: log.violation("Run $runId entered node $nodeId, which pipeline ${pipeline.id} does not have")
        val key = "$runId/$nodeId"
        val visit = (visits[key] ?: 0) + 1
        visits[key] = visit
        return Position(runId, pipeline, node, visit).also { current = it }
    }

    /**
     * The pipeline [runId] executes: the root for the root run, otherwise the target of the
     * `PIPELINE` node the run id names.
     *
     * @param runId A run id minted by the harness or by the `PIPELINE` executor.
     * @return The graph.
     */
    fun pipelineOf(runId: String): PipelineGraph {
        if (runId == rootRunId) return root
        val visitSeparator = runId.lastIndexOf(SEPARATOR)
        val nodeSeparator = runId.lastIndexOf(SEPARATOR, startIndex = visitSeparator - 1)
        if (visitSeparator <= 0 || nodeSeparator <= 0) {
            log.violation("Run id $runId is neither the root nor a child id the PIPELINE executor mints")
        }
        val parentRunId = runId.substring(0, nodeSeparator)
        val pipelineNodeId = runId.substring(nodeSeparator + SEPARATOR.length, visitSeparator)
        val pipelineNode = pipelineOf(parentRunId).nodes.firstOrNull { it.id == pipelineNodeId }
            ?: log.violation("Run id $runId names node $pipelineNodeId, which its parent pipeline does not have")
        val targetId = pipelineNode.targetPipelineId
            ?: log.violation("Node $pipelineNodeId names no pipeline")
        return library[targetId] ?: log.violation("Pipeline $targetId is not in the golden library")
    }

    private companion object {
        /** Separator of the child run id the `PIPELINE` executor mints. */
        const val SEPARATOR = "::"
    }
}
