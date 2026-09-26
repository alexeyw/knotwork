package app.knotwork.android.domain.models

/**
 * The branch labels a run follows out of a branching node, and the one rule that
 * matches an edge's label against them.
 *
 * IF_CONDITION, QUEUE_PROCESSOR and EVALUATION have fixed branches; an
 * INTENT_ROUTER's branches are the labels of its outgoing edges. The engine
 * matches a label without regard to case, and the importer, the editor canvas and
 * the browser editor use the same rule, so what the canvas draws is what the run
 * takes: when the canvas matched exactly, an IF edge labelled `false` was drawn
 * from the True port while every message without the keyword went down it.
 */
object RouteLabels {

    /** IF_CONDITION's branch when the condition holds. */
    const val TRUE: String = "True"

    /** IF_CONDITION's branch when it does not. */
    const val FALSE: String = "False"

    /** QUEUE_PROCESSOR's branch for each item. */
    const val ITEM: String = "Item"

    /** QUEUE_PROCESSOR's branch once the queue is empty. */
    const val DONE: String = "Done"

    /** EVALUATION's branch for an accepted result. */
    const val PASS: String = "Pass"

    /** EVALUATION's branch for another attempt. */
    const val RETRY: String = "Retry"

    /** EVALUATION's branch for a final failure. */
    const val FAIL: String = "Fail"

    /**
     * The fixed branches of [type], spelled as its ports are, or `null` for a node
     * type whose branches are not fixed (an INTENT_ROUTER's are its edge labels; the
     * rest have one way out).
     *
     * @param type the node an edge leaves.
     * @return the branch labels in port order, or `null`.
     */
    fun fixedBranches(type: NodeType): List<String>? = when (type) {
        NodeType.IF_CONDITION -> listOf(TRUE, FALSE)
        NodeType.QUEUE_PROCESSOR -> listOf(ITEM, DONE)
        NodeType.EVALUATION -> listOf(PASS, RETRY, FAIL)
        else -> null
    }

    /**
     * Whether an edge labelled [edgeLabel] is the branch named [branch]: equal
     * ignoring case. A missing label is no branch.
     *
     * @param edgeLabel the edge's label, as stored.
     * @param branch the branch to test for.
     * @return `true` when the run would take this edge for [branch].
     */
    fun matches(edgeLabel: String?, branch: String): Boolean =
        edgeLabel != null && edgeLabel.equals(branch, ignoreCase = true)

    /**
     * The port spelling of [edgeLabel] among [type]'s fixed branches.
     *
     * @param type the node the edge leaves; must have [fixedBranches].
     * @param edgeLabel the edge's label.
     * @return the matching branch as its port spells it, or `null` when the label
     *   names none of them.
     */
    fun canonical(type: NodeType, edgeLabel: String): String? =
        fixedBranches(type)?.firstOrNull { matches(edgeLabel, it) }
}
