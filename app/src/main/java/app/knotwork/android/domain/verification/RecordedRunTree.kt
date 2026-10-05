package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.models.RunTreeIds

/**
 * One run tree as it was recorded: the root run, every sub-pipeline run under it,
 * and each run's trace in the order the engine wrote it.
 *
 * Everything that reads a finished run as a whole — the check's plan, the run
 * digest, the trace export — walks this one structure, so they agree on which
 * sub-pipeline run belongs under which `PIPELINE` visit, including the visit of a
 * `PIPELINE` node that failed and so wrote no record of its own.
 *
 * @property root The root run.
 * @property runs Every run of the tree, the root included, by id.
 * @property traces Each run's trace by run id, in `seq` order.
 */
data class RecordedRunTree(
    val root: PipelineRun,
    val runs: Map<String, PipelineRun>,
    val traces: Map<String, List<RunTraceRecord>>,
) {

    /** Every record of the tree, run by run. */
    val records: List<RunTraceRecord> get() = traces.values.flatten()

    /**
     * The trace of one run.
     *
     * @param runId A run of the tree.
     * @return Its records in `seq` order; empty for a run that recorded none.
     */
    fun traceOf(runId: String): List<RunTraceRecord> = traces[runId].orEmpty()

    /**
     * The sub-pipeline run a `PIPELINE` node started.
     *
     * @param runId The run the `PIPELINE` node belongs to.
     * @param nodeId The `PIPELINE` node.
     * @param visit The node's visit index.
     * @return The child run, or `null` when none was recorded.
     */
    fun childAt(runId: String, nodeId: String, visit: Int): PipelineRun? = runs[RunTreeIds.child(runId, nodeId, visit)]

    /**
     * The `PIPELINE` visits of [runId] that started a child run but left no record
     * in [runId]'s own trace — a `PIPELINE` node that failed writes none, yet its
     * child run holds what it did.
     *
     * @param runId A run of the tree.
     * @return The node id and visit index of each such visit, by node id, then visit.
     */
    fun unrecordedPipelineVisits(runId: String): List<Pair<String, Int>> {
        val recorded = traceOf(runId).mapNotNull { it.visitKey() }.toSet()
        return runs.values.filter { it.parentRunId == runId }
            .mapNotNull { RunTreeIds.parentVisit(it.id, runId) }
            .filterNot { it in recorded }
            .sortedWith(compareBy({ it.first }, { it.second }))
    }

    /**
     * Every run of the tree in the order a reader follows it: a run, then each
     * sub-pipeline run where its `PIPELINE` visit stands in the trace, then those
     * of the visits that left no record. A run reachable by neither — an id that
     * names no visit — comes last, by start time, so nothing recorded is dropped.
     *
     * @return The runs, the root first.
     */
    fun inTreeOrder(): List<PipelineRun> {
        val ordered = LinkedHashMap<String, PipelineRun>()
        fun visit(run: PipelineRun) {
            if (ordered.putIfAbsent(run.id, run) != null) return
            for (child in childrenOf(run.id)) visit(child)
        }
        visit(root)
        runs.values.filterNot { it.id in ordered }.sortedBy { it.startedAt }.forEach { ordered[it.id] = it }
        return ordered.values.toList()
    }

    /** The child runs of [runId] in the order their `PIPELINE` visits ran, unrecorded visits last. */
    private fun childrenOf(runId: String): List<PipelineRun> {
        val recorded = traceOf(runId).filterIsInstance<RunTraceRecord.NodeIo>()
            .filter { it.nodeType == NodeType.PIPELINE.name }
            .mapNotNull { io -> io.visit?.let { childAt(runId, io.nodeId, it) } }
        val unrecorded = unrecordedPipelineVisits(runId).mapNotNull { (nodeId, visit) -> childAt(runId, nodeId, visit) }
        return recorded + unrecorded
    }
}

/**
 * The node visit a record belongs to.
 *
 * @return The node id and visit index, or `null` for a record that belongs to no
 *   visit (a console line, a memory snapshot) or one written before visits were kept.
 */
internal fun RunTraceRecord.visitKey(): Pair<String, Int>? = when (this) {
    is RunTraceRecord.NodeIo -> visit?.let { nodeId to it }
    is RunTraceRecord.LocalModelCall -> nodeId to visit
    is RunTraceRecord.CloudModelCall -> nodeId to visit
    is RunTraceRecord.ConsoleEntry, is RunTraceRecord.MemorySnapshot -> null
}
