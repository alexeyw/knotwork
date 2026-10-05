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
    fun child(parentRunId: String, nodeId: String, visitIndex: Int): String =
        "$parentRunId$SEPARATOR$nodeId$SEPARATOR$visitIndex"

    /**
     * The `PIPELINE` node and visit that started the child run [childRunId] of
     * [parentRunId] — the inverse of [child].
     *
     * @param childRunId A run id.
     * @param parentRunId The run it may be a child of.
     * @return The node id and visit index, or `null` when [childRunId] is not a
     *   child id of [parentRunId].
     */
    fun parentVisit(childRunId: String, parentRunId: String): Pair<String, Int>? {
        val rest = childRunId.removePrefix("$parentRunId$SEPARATOR")
        if (rest == childRunId) return null
        val nodeId = rest.substringBeforeLast(SEPARATOR, missingDelimiterValue = "")
        val visit = rest.substringAfterLast(SEPARATOR).toIntOrNull()
        return if (nodeId.isEmpty() || visit == null) null else nodeId to visit
    }

    /** Between the parent id, the node id and the visit index. */
    private const val SEPARATOR = "::"
}
