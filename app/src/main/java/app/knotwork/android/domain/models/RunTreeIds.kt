package app.knotwork.android.domain.models

/**
 * How the runs of one run tree are named.
 *
 * A sub-pipeline's run id is derived, not drawn: `"<parentRunId>::<nodeId>::<visitIndex>"`.
 * Determinism is what lets a resume find and continue the same child run rather
 * than start a new one, and what lets a reader of a finished run — the run
 * verification — place each child run under the `PIPELINE` visit that started
 * it. One function, so the two never disagree.
 */
object RunTreeIds {

    /**
     * The id of the sub-pipeline run a `PIPELINE` node starts.
     *
     * @param parentRunId The run the `PIPELINE` node belongs to.
     * @param nodeId The `PIPELINE` node.
     * @param visitIndex The node's zero-based visit index in its invocation.
     * @return The child run's id.
     */
    fun child(parentRunId: String, nodeId: String, visitIndex: Int): String = "$parentRunId::$nodeId::$visitIndex"
}
