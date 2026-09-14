package app.knotwork.android.presentation.ui.pipeline.editor.core

import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.math.abs

class AutoLayoutTest {

    private fun node(type: NodeType, id: String = UUID.randomUUID().toString()): NodeModel =
        NodeModel(id = id, type = type, x = 0f, y = 0f, label = id)

    @Test
    fun `given empty graph when compute then empty result`() {
        val result = AutoLayout.compute(PipelineGraph(id = "p", name = "p"))
        assertTrue(result.positions.isEmpty())
        assertTrue(result.depths.isEmpty())
    }

    @Test
    fun `given linear graph when compute then depth increases monotonically`() {
        val a = node(NodeType.INPUT, "a")
        val b = node(NodeType.LITE_RT, "b")
        val c = node(NodeType.OUTPUT, "c")
        val graph = PipelineGraph(
            id = "g",
            name = "g",
            nodes = listOf(a, b, c),
            connections = listOf(
                ConnectionModel(id = "ab", sourceNodeId = "a", targetNodeId = "b"),
                ConnectionModel(id = "bc", sourceNodeId = "b", targetNodeId = "c"),
            ),
        )
        val result = AutoLayout.compute(graph)
        assertEquals(0, result.depths["a"])
        assertEquals(1, result.depths["b"])
        assertEquals(2, result.depths["c"])
    }

    @Test
    fun `given branching graph when compute then siblings share a depth and Y`() {
        val input = node(NodeType.INPUT, "in")
        val left = node(NodeType.LITE_RT, "left")
        val right = node(NodeType.LITE_RT, "right")
        val output = node(NodeType.OUTPUT, "out")
        val graph = PipelineGraph(
            id = "g",
            name = "g",
            nodes = listOf(input, left, right, output),
            connections = listOf(
                ConnectionModel(id = "1", sourceNodeId = "in", targetNodeId = "left"),
                ConnectionModel(id = "2", sourceNodeId = "in", targetNodeId = "right"),
                ConnectionModel(id = "3", sourceNodeId = "left", targetNodeId = "out"),
                ConnectionModel(id = "4", sourceNodeId = "right", targetNodeId = "out"),
            ),
        )
        val result = AutoLayout.compute(graph)
        assertEquals(result.depths["left"], result.depths["right"])
        val leftPos = result.positions.getValue("left")
        val rightPos = result.positions.getValue("right")
        assertEquals(leftPos.second, rightPos.second, 0f)
        assertNotEquals(leftPos.first, rightPos.first)
    }

    @Test
    fun `given any graph when compute then all positions snap to GRID_STEP`() {
        val a = node(NodeType.INPUT, "a")
        val b = node(NodeType.LITE_RT, "b")
        val c = node(NodeType.OUTPUT, "c")
        val graph = PipelineGraph(
            id = "g",
            name = "g",
            nodes = listOf(a, b, c),
            connections = listOf(
                ConnectionModel(id = "ab", sourceNodeId = "a", targetNodeId = "b"),
                ConnectionModel(id = "bc", sourceNodeId = "b", targetNodeId = "c"),
            ),
        )
        val result = AutoLayout.compute(graph)
        result.positions.values.forEach { (x, y) ->
            assertEquals(0f, x % CanvasTransform.GRID_STEP, 1e-3f)
            assertEquals(0f, y % CanvasTransform.GRID_STEP, 1e-3f)
        }
    }

    @Test
    fun `given diamond merge when compute then merge node lands at deepest layer`() {
        val a = node(NodeType.INPUT, "a")
        val b = node(NodeType.LITE_RT, "b")
        val c = node(NodeType.LITE_RT, "c")
        val d = node(NodeType.OUTPUT, "d")
        val graph = PipelineGraph(
            id = "g",
            name = "g",
            nodes = listOf(a, b, c, d),
            connections = listOf(
                ConnectionModel(id = "1", sourceNodeId = "a", targetNodeId = "b"),
                ConnectionModel(id = "2", sourceNodeId = "a", targetNodeId = "c"),
                ConnectionModel(id = "3", sourceNodeId = "b", targetNodeId = "d"),
                ConnectionModel(id = "4", sourceNodeId = "c", targetNodeId = "d"),
            ),
        )
        val result = AutoLayout.compute(graph)
        assertEquals(0, result.depths["a"])
        assertEquals(1, result.depths["b"])
        assertEquals(1, result.depths["c"])
        assertEquals(2, result.depths["d"])
    }

    @Test
    fun `given graph with a back-edge when compute then it terminates and lays out every node`() {
        // QUEUE_PROCESSOR re-iteration loop: in -> queue -> worker -> queue (back-edge) -> out.
        // Before the cycle guard this overflowed the resolution stack.
        val input = node(NodeType.INPUT, "in")
        val queue = node(NodeType.QUEUE_PROCESSOR, "queue")
        val worker = node(NodeType.LITE_RT, "worker")
        val output = node(NodeType.OUTPUT, "out")
        val graph = PipelineGraph(
            id = "g",
            name = "g",
            nodes = listOf(input, queue, worker, output),
            connections = listOf(
                ConnectionModel(id = "1", sourceNodeId = "in", targetNodeId = "queue"),
                ConnectionModel(id = "2", sourceNodeId = "queue", targetNodeId = "worker"),
                ConnectionModel(id = "3", sourceNodeId = "worker", targetNodeId = "queue"),
                ConnectionModel(id = "4", sourceNodeId = "queue", targetNodeId = "out"),
            ),
        )
        val result = AutoLayout.compute(graph)
        assertEquals(4, result.positions.size)
        assertEquals(4, result.depths.size)
        // The acyclic prefix still layers monotonically; the back-edge is ignored.
        assertEquals(0, result.depths["in"])
        assertTrue(result.depths.getValue("queue") > result.depths.getValue("in"))
    }

    @Test
    fun `given a self-looping node when compute then it terminates and the node stays at depth 0`() {
        val a = node(NodeType.QUEUE_PROCESSOR, "a")
        val graph = PipelineGraph(
            id = "g",
            name = "g",
            nodes = listOf(a),
            connections = listOf(
                ConnectionModel(id = "self", sourceNodeId = "a", targetNodeId = "a"),
            ),
        )
        val result = AutoLayout.compute(graph)
        assertEquals(1, result.positions.size)
        assertEquals(0, result.depths["a"])
    }

    @Test
    fun `given a larger sibling gap when compute then siblings spread further apart`() {
        val input = node(NodeType.INPUT, "in")
        val left = node(NodeType.LITE_RT, "l")
        val right = node(NodeType.LITE_RT, "r")
        val graph = PipelineGraph(
            id = "g",
            name = "g",
            nodes = listOf(input, left, right),
            connections = listOf(
                ConnectionModel(id = "1", sourceNodeId = "in", targetNodeId = "l"),
                ConnectionModel(id = "2", sourceNodeId = "in", targetNodeId = "r"),
            ),
        )
        val narrow = AutoLayout.compute(graph)
        val wide = AutoLayout.compute(graph, siblingGap = 600f, layerGap = AutoLayout.LAYER_GAP_Y)
        val narrowSpan = abs(narrow.positions.getValue("l").first - narrow.positions.getValue("r").first)
        val wideSpan = abs(wide.positions.getValue("l").first - wide.positions.getValue("r").first)
        assertTrue(wideSpan > narrowSpan)
    }

    @Test
    fun `given a larger layer gap when compute then layers spread further apart`() {
        val a = node(NodeType.INPUT, "a")
        val b = node(NodeType.LITE_RT, "b")
        val graph = PipelineGraph(
            id = "g",
            name = "g",
            nodes = listOf(a, b),
            connections = listOf(ConnectionModel(id = "ab", sourceNodeId = "a", targetNodeId = "b")),
        )
        val narrow = AutoLayout.compute(graph)
        val wide = AutoLayout.compute(graph, siblingGap = AutoLayout.SIBLING_GAP_X, layerGap = 600f)
        val narrowDy = abs(narrow.positions.getValue("a").second - narrow.positions.getValue("b").second)
        val wideDy = abs(wide.positions.getValue("a").second - wide.positions.getValue("b").second)
        assertTrue(wideDy > narrowDy)
    }

    @Test
    fun `given a two-node cycle when compute then it terminates and lays out both nodes`() {
        val a = node(NodeType.QUEUE_PROCESSOR, "a")
        val b = node(NodeType.LITE_RT, "b")
        val graph = PipelineGraph(
            id = "g",
            name = "g",
            nodes = listOf(a, b),
            connections = listOf(
                ConnectionModel(id = "ab", sourceNodeId = "a", targetNodeId = "b"),
                ConnectionModel(id = "ba", sourceNodeId = "b", targetNodeId = "a"),
            ),
        )
        val result = AutoLayout.compute(graph)
        assertEquals(2, result.positions.size)
        assertEquals(2, result.depths.size)
    }
}
