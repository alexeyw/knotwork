package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.RouteLabels
import timber.log.Timber

/**
 * Decides where a run goes next, from the graph's edges and the verdict of the
 * node that has just run.
 *
 * Pure functions of the graph: no state, no I/O beyond a debug log line per
 * routing decision. The engine asks it for the next node, for the labels a routing
 * node may choose between, and for how many steps the path ahead is likely to
 * take, so the progress indicator can show a total.
 */
object GraphRouting {

    /**
     * The step count shown for a graph whose path is known before it runs, or
     * `null` when routing or a queue decides it on the way.
     *
     * @param graph The graph about to run.
     * @return Every node's count for a graph without INTENT_ROUTER, IF_CONDITION
     *   or QUEUE_PROCESSOR nodes; otherwise `null` until a branch is resolved.
     */
    fun fixedStepCount(graph: PipelineGraph): Int? {
        val branches = graph.nodes.any {
            it.type == NodeType.INTENT_ROUTER ||
                it.type == NodeType.IF_CONDITION ||
                it.type == NodeType.QUEUE_PROCESSOR
        }
        return if (branches) null else graph.nodes.size
    }

    /**
     * The labels an INTENT_ROUTER node may choose between: those of its own
     * labelled outgoing edges, which only the graph knows. The node's executor
     * constrains the structured-output gate to them, so it can validate — and
     * repair towards — a key that matches a branch.
     *
     * @param node The node about to run.
     * @param graph The running graph.
     * @return The distinct labels, in edge order; empty for any other node type.
     */
    fun routingChoices(node: NodeModel, graph: PipelineGraph): List<String> = if (node.type == NodeType.INTENT_ROUTER) {
        graph.connections
            .filter { it.sourceNodeId == node.id && !it.label.isNullOrBlank() }
            .map { it.label!! }
            .distinct()
    } else {
        emptyList()
    }

    /**
     * Counts the number of nodes reachable from [startNode] by following the first outgoing edge
     * of each node, including [startNode] itself. Stops at [NodeType.OUTPUT] (inclusive),
     * dead ends, already-visited nodes, or any node whose ID is in [stopNodeIds].
     *
     * Used to estimate the remaining steps on the active branch after a routing decision
     * or to measure the item-subgraph depth inside a [NodeType.QUEUE_PROCESSOR].
     *
     * @param startNode The node to start counting from, or null (returns 0).
     * @param graph The pipeline graph to traverse.
     * @param stopNodeIds IDs of nodes that act as exclusive stop boundaries (not counted).
     * @return The number of nodes on the path.
     */
    fun countNodesOnPath(startNode: NodeModel?, graph: PipelineGraph, stopNodeIds: Set<String> = emptySet()): Int {
        var count = 0
        var node = startNode
        val visited = mutableSetOf<String>()
        while (node != null && node.id !in visited && node.id !in stopNodeIds) {
            visited.add(node.id)
            count++
            if (node.type == NodeType.OUTPUT) break
            val nextId = graph.connections.firstOrNull { it.sourceNodeId == node.id }?.targetNodeId
            node = graph.nodes.find { it.id == nextId }
        }
        return count
    }

    /**
     * The id of the node a run moves to after [currentNode].
     *
     * Most nodes leave by their first outgoing edge. Three types choose:
     * - IF_CONDITION takes the `True` or `False` edge for [conditionResult];
     * - INTENT_ROUTER takes the edge whose label matches [routingKey], exactly or
     *   as a standalone word, and otherwise falls back (see [unmatchedRouterTarget]);
     * - EVALUATION takes the edge labelled with its verdict, or the first edge when
     *   the verdict has no dedicated port.
     *
     * @param currentNode The node that has just run.
     * @param graph The running graph.
     * @param conditionResult An IF_CONDITION node's verdict; ignored for other types.
     * @param routingKey A routing node's key — INTENT_ROUTER's chosen label,
     *   EVALUATION's verdict; ignored for other types.
     * @return The next node's id, or `null` to end the branch here.
     */
    fun nextNodeId(
        currentNode: NodeModel,
        graph: PipelineGraph,
        conditionResult: Boolean?,
        routingKey: String?,
    ): String? {
        val edges = graph.connections.filter { it.sourceNodeId == currentNode.id }
        if (edges.isEmpty()) {
            Timber.tag("PipelineDebug").d("[ROUTE] from=${currentNode.id} label=null -> to=null")
            return null
        }

        val targetNodeId = if (currentNode.type == NodeType.IF_CONDITION) {
            val expectedLabel = if (conditionResult == true) RouteLabels.TRUE else RouteLabels.FALSE
            val oppositeLabel = if (conditionResult == true) RouteLabels.FALSE else RouteLabels.TRUE
            val exactTarget = edges.find { RouteLabels.matches(it.label, expectedLabel) }?.targetNodeId
            when {
                exactTarget != null -> exactTarget
                // The author wired the opposite branch but left this one
                // unconnected: terminate the branch (-> "terminated without
                // OUTPUT") instead of silently falling through to an arbitrary
                // first edge and running the wrong branch on this verdict.
                edges.any { RouteLabels.matches(it.label, oppositeLabel) } -> null
                // No True/False labels at all — a single default edge. Keep the
                // legacy fall-through so an unlabelled pass-through still routes.
                else -> edges.firstOrNull()?.targetNodeId
            }
        } else if (currentNode.type == NodeType.INTENT_ROUTER) {
            // `routingKey == null` is the router's real failure mode, not an
            // absent one: the structured gate constrains the answer to the
            // labelled edges, so a *successful* verdict always names one of
            // them. What actually goes unrouted is a gate that gave up after its
            // repair attempts — which is why the fallback has to cover the null
            // case, and why it was reachable by nothing when it did not.
            val matchedEdge = routingKey?.let { key ->
                edges.find { RouteLabels.matches(it.label, key) }
                    ?: edges.find { !it.label.isNullOrBlank() && routingKeyContainsLabelAsWord(key, it.label) }
            }
            matchedEdge?.targetNodeId ?: unmatchedRouterTarget(currentNode, edges)
        } else if (currentNode.type == NodeType.EVALUATION && routingKey != null) {
            // EVALUATION emits a Pass / Retry / Fail verdict as the routing key;
            // route to the edge whose label matches the verdict, falling back to
            // the first outgoing edge when the verdict has no dedicated port.
            edges.find { RouteLabels.matches(it.label, routingKey) }?.targetNodeId
                ?: edges.firstOrNull()?.targetNodeId
        } else {
            edges.firstOrNull()?.targetNodeId
        }

        val edgeLabel = edges.find { it.targetNodeId == targetNodeId }?.label ?: "null"
        Timber.tag("PipelineDebug").d("[ROUTE] from=${currentNode.id} label=$edgeLabel -> to=$targetNodeId")
        return targetNodeId
    }

    /**
     * Where an INTENT_ROUTER sends a run it could not route.
     *
     * Reached when the structured gate gave up after its repair attempts (no
     * verdict at all) or, rarely, when a verdict names no wired edge. The first
     * is the common one: the gate constrains the model to the labelled edges, so
     * a successful answer already names one.
     *
     * With a [NodeModel.fallbackClass] set, the run takes the edge labelled with
     * it — and **terminates** when no such edge is wired, for the same reason
     * IF_CONDITION does above: an author who named a fallback and then failed to
     * connect it is better served by "terminated without OUTPUT" than by the
     * wrong branch running on a verdict nobody chose.
     *
     * With no fallback class — every pipeline saved before this field existed —
     * the historical first-edge-in-storage-order behaviour is kept. Changing it
     * would silently re-route graphs whose authors never made this decision, and
     * the workaround those authors were told to use (put the fallback branch
     * first) depends on exactly that behaviour.
     *
     * @param node The routing node.
     * @param edges Its outgoing connections.
     * @return The next node id, or `null` to terminate the branch.
     */
    private fun unmatchedRouterTarget(node: NodeModel, edges: List<ConnectionModel>): String? {
        val fallback = node.fallbackClass?.takeIf { it.isNotBlank() }
            ?: return edges.firstOrNull()?.targetNodeId
        return edges.find { it.label?.equals(fallback, ignoreCase = true) == true }?.targetNodeId
    }

    /**
     * INTENT_ROUTER fallback match: `true` when [routingKey] contains [label] as
     * a **standalone token** (case-insensitive). Used only after an exact label
     * match fails, to tolerate a model that wraps the chosen label in a sentence
     * ("I choose Cancel") while still rejecting incidental substring hits — an
     * unanchored `contains` would route the key "Cancel" to a port labelled
     * "can".
     *
     * The boundary is expressed as alphanumeric-adjacency lookarounds rather than
     * `\b`: a `\b`-based regex fails to match labels that begin or end with a
     * non-word character (e.g. a port labelled `C#` or `node.js`), because `\b`
     * requires a word↔non-word transition at the label edge. The lookarounds
     * `(?<![A-Za-z0-9])` / `(?![A-Za-z0-9])` instead reject a match only when an
     * alphanumeric character abuts the label, so `C#` matches in "Use C# here"
     * while "can" still does not match inside "Cancel". [label] is regex-escaped
     * so its own characters are literal.
     *
     * @param routingKey The router's chosen routing key (model output).
     * @param label The candidate edge label to test against [routingKey].
     * @return `true` if [label] appears as a standalone token inside [routingKey].
     */
    private fun routingKeyContainsLabelAsWord(routingKey: String, label: String): Boolean = Regex(
        "(?<![A-Za-z0-9])${Regex.escape(label)}(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(routingKey)
}
