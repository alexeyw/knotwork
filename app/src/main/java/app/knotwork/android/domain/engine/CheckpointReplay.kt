package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.ResumeContext

/**
 * Walks a resumed run's recorded prefix: for each node the interrupted run
 * completed, hands back what it recorded instead of running it again.
 *
 * The records are the interrupted run's `NodeIo` trace, in `seq` order. While the
 * cursor has records left, each visited node is matched against the next one: the
 * recorded output and routing verdicts then drive the very same control flow a
 * live result would, so branches, queue iterations and inter-node inputs are
 * re-derived without re-running any executor. INPUT and OUTPUT are never recorded
 * — the trace skips them by design — so they always run live, even mid-replay:
 * INPUT is a pure passthrough, and a recorded OUTPUT cannot exist for an
 * interrupted run. The first node without a record runs live, and so does every
 * node after it.
 *
 * One instance per engine invocation.
 *
 * @param resume The interrupted run's checkpoint, or `null` for a fresh run, which
 *   replays nothing.
 */
class CheckpointReplay(private val resume: ResumeContext?) {

    /** Position of the next record to replay. */
    private var cursor = 0

    /**
     * Whether this invocation resumes an interrupted run. INPUT and OUTPUT run
     * again on every resumed attempt, and were already charged by the first one.
     */
    val resuming: Boolean get() = resume != null

    /**
     * Decides how [node] runs: from its record, live, or not at all because the
     * record names another node. A replayed record is consumed.
     *
     * @param node The node the walk has reached.
     * @return What to do with it.
     */
    fun take(node: NodeModel): ReplayStep {
        val records = resume?.records ?: return ReplayStep.Live
        if (cursor >= records.size || node.type == NodeType.INPUT || node.type == NodeType.OUTPUT) {
            return ReplayStep.Live
        }
        val record = records[cursor]
        // The persisted prefix diverged from the graph walk: the trace cannot serve
        // as a checkpoint (corruption, or an edit that slipped past hash
        // validation). Failing loudly beats silently executing a half-replayed run
        // on inconsistent inputs.
        if (record.nodeId != node.id) return ReplayStep.Diverged
        cursor++
        return ReplayStep.Replayed(
            result = NodeExecutionResult(
                outputText = record.outputText,
                conditionResult = record.conditionResult,
                routingKey = record.routingKey,
                tokenCount = record.tokenCount,
                resolvedToolName = record.resolvedToolName,
            ),
            input = record.inputText,
            durationMs = record.durationMs,
        )
    }
}

/** How the walk runs the node it has reached, as [CheckpointReplay.take] decides. */
sealed interface ReplayStep {

    /** No record applies: run the node's executor. */
    data object Live : ReplayStep

    /**
     * The node completed before the interruption: use what it recorded.
     *
     * @property result Its recorded output, routing verdicts, token count and tool name.
     * @property input What it executed on.
     * @property durationMs How long it took then.
     */
    data class Replayed(val result: NodeExecutionResult, val input: String, val durationMs: Long) : ReplayStep

    /** The next record names a different node: the checkpoint no longer fits the graph. */
    data object Diverged : ReplayStep
}
