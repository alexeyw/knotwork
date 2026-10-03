package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit coverage for [GraphRouting].
 *
 * The engine's own tests reach these rules through whole runs; here each one is
 * pinned on a graph built for it, including the cases a run reaches rarely — a
 * router whose gate gave up, an IF whose own branch is unwired, a label that is a
 * substring of the model's answer but not a word in it.
 */
class GraphRoutingTest {

    private fun node(id: String, type: NodeType, fallbackClass: String? = null) =
        NodeModel(id = id, type = type, x = 0f, y = 0f, fallbackClass = fallbackClass)

    private fun edge(from: String, to: String, label: String? = null) =
        ConnectionModel(id = "$from->$to", sourceNodeId = from, targetNodeId = to, label = label)

    private fun graph(nodes: List<NodeModel>, edges: List<ConnectionModel>) =
        PipelineGraph(id = "g", name = "G", nodes = nodes, connections = edges)

    private val input = node("in", NodeType.INPUT)
    private val llm = node("llm", NodeType.LITE_RT)
    private val output = node("out", NodeType.OUTPUT)

    // --- fixedStepCount ---------------------------------------------------------

    @Test
    fun `given a graph without routing or queues then its step count is known up front`() {
        val g = graph(listOf(input, llm, output), listOf(edge("in", "llm"), edge("llm", "out")))

        assertEquals(3, GraphRouting.fixedStepCount(g))
    }

    @Test
    fun `given a router, a condition or a queue then the step count waits for the branch`() {
        for (type in listOf(NodeType.INTENT_ROUTER, NodeType.IF_CONDITION, NodeType.QUEUE_PROCESSOR)) {
            assertNull(
                type.name,
                GraphRouting.fixedStepCount(graph(listOf(input, node("b", type), output), emptyList())),
            )
        }
    }

    // --- routingChoices ---------------------------------------------------------

    @Test
    fun `given a router then its choices are its distinct labelled edges in order`() {
        val router = node("r", NodeType.INTENT_ROUTER)
        val g = graph(
            listOf(router, llm, output),
            listOf(
                edge("r", "llm", "billing"),
                edge("r", "out", "support"),
                edge("r", "out", ""),
                edge("r", "llm", "billing"),
                edge("llm", "out", "elsewhere"),
            ),
        )

        assertEquals(listOf("billing", "support"), GraphRouting.routingChoices(router, g))
    }

    @Test
    fun `given any other node then it has no choices`() {
        val g = graph(listOf(llm, output), listOf(edge("llm", "out", "labelled")))

        assertEquals(emptyList<String>(), GraphRouting.routingChoices(llm, g))
    }

    // --- countNodesOnPath -------------------------------------------------------

    @Test
    fun `given a path then it counts nodes up to OUTPUT inclusive along first edges`() {
        val g = graph(listOf(input, llm, output), listOf(edge("in", "llm"), edge("llm", "out"), edge("in", "out")))

        assertEquals(3, GraphRouting.countNodesOnPath(input, g))
        assertEquals(0, GraphRouting.countNodesOnPath(null, g))
    }

    @Test
    fun `given a stop node or a loop then counting stops before revisiting`() {
        val a = node("a", NodeType.LITE_RT)
        val b = node("b", NodeType.LITE_RT)
        val loop = graph(listOf(a, b), listOf(edge("a", "b"), edge("b", "a")))

        assertEquals(2, GraphRouting.countNodesOnPath(a, loop))
        assertEquals(1, GraphRouting.countNodesOnPath(a, loop, stopNodeIds = setOf("b")))
    }

    // --- nextNodeId: plain nodes ------------------------------------------------

    @Test
    fun `given a node with no edges then the branch ends`() {
        assertNull(GraphRouting.nextNodeId(llm, graph(listOf(llm), emptyList()), null, null))
    }

    @Test
    fun `given a plain node then it leaves by its first edge whatever its verdict`() {
        val g = graph(listOf(llm, output, input), listOf(edge("llm", "out", "x"), edge("llm", "in")))

        assertEquals("out", GraphRouting.nextNodeId(llm, g, conditionResult = false, routingKey = "in"))
    }

    // --- nextNodeId: IF_CONDITION -----------------------------------------------

    private val cond = node("if", NodeType.IF_CONDITION)

    @Test
    fun `given an IF verdict then it takes the matching branch`() {
        val g = graph(listOf(cond, llm, output), listOf(edge("if", "llm", "True"), edge("if", "out", "False")))

        assertEquals("llm", GraphRouting.nextNodeId(cond, g, conditionResult = true, routingKey = null))
        assertEquals("out", GraphRouting.nextNodeId(cond, g, conditionResult = false, routingKey = null))
        assertEquals("out", GraphRouting.nextNodeId(cond, g, conditionResult = null, routingKey = null))
    }

    @Test
    fun `given an IF whose own branch is unwired then the branch ends instead of taking the other`() {
        val g = graph(listOf(cond, output), listOf(edge("if", "out", "False")))

        assertNull(GraphRouting.nextNodeId(cond, g, conditionResult = true, routingKey = null))
    }

    @Test
    fun `given an IF with an unlabelled edge only then it falls through on either verdict`() {
        val g = graph(listOf(cond, output), listOf(edge("if", "out")))

        assertEquals("out", GraphRouting.nextNodeId(cond, g, conditionResult = true, routingKey = null))
    }

    // --- nextNodeId: INTENT_ROUTER ----------------------------------------------

    private fun routerGraph(router: NodeModel, vararg labels: String) = graph(
        listOf(router) + labels.map { node(it, NodeType.LITE_RT) },
        labels.map { edge(router.id, it, it) },
    )

    @Test
    fun `given a routing key naming a label then the router takes that edge`() {
        val router = node("r", NodeType.INTENT_ROUTER)

        assertEquals("cancel", GraphRouting.nextNodeId(router, routerGraph(router, "can", "cancel"), null, "cancel"))
    }

    @Test
    fun `given a key that contains a label as a word then it matches, as a substring it does not`() {
        val router = node("r", NodeType.INTENT_ROUTER)
        val g = routerGraph(router, "can", "C#")

        assertEquals("C#", GraphRouting.nextNodeId(router, g, null, "Use C# here"))
        // "can" sits inside "Cancel" but is not a word there: no match, so the
        // router falls back to its first edge ("x") rather than to a word it never said.
        assertEquals("x", GraphRouting.nextNodeId(router, routerGraph(router, "x", "can"), null, "Cancel it"))
    }

    @Test
    fun `given a router without a verdict and a wired fallback class then it takes the fallback`() {
        val router = node("r", NodeType.INTENT_ROUTER, fallbackClass = "other")

        assertEquals("other", GraphRouting.nextNodeId(router, routerGraph(router, "billing", "other"), null, null))
    }

    @Test
    fun `given a fallback class with no edge for it then the branch ends`() {
        val router = node("r", NodeType.INTENT_ROUTER, fallbackClass = "other")

        assertNull(GraphRouting.nextNodeId(router, routerGraph(router, "billing", "support"), null, "nonsense"))
    }

    @Test
    fun `given no fallback class then an unrouted router takes its first edge, as before the field existed`() {
        val router = node("r", NodeType.INTENT_ROUTER)

        assertEquals("billing", GraphRouting.nextNodeId(router, routerGraph(router, "billing", "support"), null, null))
    }

    // --- nextNodeId: EVALUATION -------------------------------------------------

    @Test
    fun `given an evaluation verdict then it takes the matching port, or the first edge without one`() {
        val eval = node("e", NodeType.EVALUATION)
        val g = graph(
            listOf(eval, llm, output),
            listOf(edge("e", "out", "Pass"), edge("e", "llm", "Retry")),
        )

        assertEquals("llm", GraphRouting.nextNodeId(eval, g, null, "Retry"))
        assertEquals("out", GraphRouting.nextNodeId(eval, g, null, "Fail"))
        assertEquals("out", GraphRouting.nextNodeId(eval, g, null, null))
    }
}
