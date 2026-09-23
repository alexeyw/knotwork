package app.knotwork.android.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cycle detection in [PipelineGraph.isValidDAG] / [PipelineGraph.validate].
 *
 * The graph reaching this check can come from a file the user picked, so its
 * size is the file's choice, not the editor's: the check must finish on a
 * graph far longer than anyone would draw.
 */
class PipelineGraphValidationTest {

    private fun node(id: String, type: NodeType = NodeType.LITE_RT) = NodeModel(id = id, type = type, x = 0f, y = 0f)

    private fun edge(from: String, to: String) =
        ConnectionModel(id = "$from->$to", sourceNodeId = from, targetNodeId = to)

    private fun graph(nodes: List<NodeModel>, connections: List<ConnectionModel>) =
        PipelineGraph(id = "p", name = "p", nodes = nodes, connections = connections, updatedAt = 0L)

    @Test
    fun `given a chain far deeper than the call stack when validated then the check completes`() {
        val length = 30_000
        val ids = (0 until length).map { "n$it" }
        val nodes = ids.mapIndexed { i, id ->
            when (i) {
                0 -> node(id, NodeType.INPUT)
                length - 1 -> node(id, NodeType.OUTPUT)
                else -> node(id)
            }
        }
        val chain = graph(nodes, ids.zipWithNext { from, to -> edge(from, to) })

        assertTrue(chain.isValidDAG())
        assertEquals(emptyList<PipelineValidationError>(), chain.validate())
    }

    @Test
    fun `given a cycle when validated then it is reported`() {
        val cyclic = graph(
            listOf(node("a"), node("b"), node("c")),
            listOf(edge("a", "b"), edge("b", "c"), edge("c", "a")),
        )

        assertFalse(cyclic.isValidDAG())
    }

    @Test
    fun `given a self loop when validated then it is reported`() {
        assertFalse(graph(listOf(node("a")), listOf(edge("a", "a"))).isValidDAG())
    }

    @Test
    fun `given two paths into one node when validated then it is not mistaken for a cycle`() {
        val diamond = graph(
            listOf(node("a"), node("b"), node("c"), node("d")),
            listOf(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")),
        )

        assertTrue(diamond.isValidDAG())
    }

    @Test
    fun `given a back edge into a queue processor when validated then it is not a cycle`() {
        val loop = graph(
            listOf(node("q", NodeType.QUEUE_PROCESSOR), node("work")),
            listOf(edge("q", "work"), edge("work", "q")),
        )

        assertTrue(loop.isValidDAG())
    }

    @Test
    fun `given a cycle reached only after a long acyclic prefix when validated then it is reported`() {
        val prefix = (0 until 5_000).map { "p$it" }
        val nodes = (prefix + listOf("x", "y")).map { node(it) }
        val edges = prefix.zipWithNext { from, to -> edge(from, to) } +
            listOf(edge(prefix.last(), "x"), edge("x", "y"), edge("y", "x"))

        assertFalse(graph(nodes, edges).isValidDAG())
    }
}
