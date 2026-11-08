package app.knotwork.android.presentation.ui.pipeline.editor.core

import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for [ValidationAutoFix] recipes. Mirrors the behaviour the
 * `Auto-fix` button on the redesigned `ValidationBar` exposes to users.
 */
class ValidationAutoFixTest {

    private fun pipeline(nodes: List<NodeModel> = emptyList(), connections: List<ConnectionModel> = emptyList()) =
        PipelineGraph(id = "pipeline-1", name = "test", nodes = nodes, connections = connections)

    private fun node(id: String, type: NodeType, x: Float = 0f, y: Float = 0f, label: String = type.name) =
        NodeModel(id = id, type = type, x = x, y = y, label = label)

    @Test
    fun `given no errors when apply then graph is unchanged`() {
        val original = pipeline(nodes = listOf(node("n1", NodeType.INPUT)))
        val outcome = fix(original, errors = emptyList())
        assertTrue(outcome.unchanged)
        assertEquals(original, outcome.graph)
    }

    @Test
    fun `given MissingInput when apply then INPUT node is inserted`() {
        val original = pipeline(nodes = listOf(node("n1", NodeType.OUTPUT)))
        val outcome = fix(original, errors = listOf(PipelineValidationError.MissingInput))
        assertEquals(2, outcome.graph.nodes.size)
        assertNotNull(outcome.graph.nodes.firstOrNull { it.type == NodeType.INPUT })
        assertEquals(listOf("add-input"), outcome.appliedRecipes)
    }

    @Test
    fun `given MissingOutput when apply then OUTPUT node is inserted below the lowest existing`() {
        val original = pipeline(nodes = listOf(node("n1", NodeType.INPUT, y = 50f)))
        val outcome = fix(original, errors = listOf(PipelineValidationError.MissingOutput))
        val output = outcome.graph.nodes.firstOrNull { it.type == NodeType.OUTPUT }
        assertNotNull(output)
        assertTrue("OUTPUT y=${output!!.y} should be below INPUT y=50", output.y > 50f)
    }

    @Test
    fun `given MultipleInputs when apply then keeps first and drops rest`() {
        val original = pipeline(
            nodes = listOf(
                node("first", NodeType.INPUT),
                node("dup1", NodeType.INPUT),
                node("dup2", NodeType.INPUT),
                node("output", NodeType.OUTPUT),
            ),
        )
        val outcome = fix(original, errors = listOf(PipelineValidationError.MultipleInputs))
        val inputs = outcome.graph.nodes.filter { it.type == NodeType.INPUT }
        assertEquals(1, inputs.size)
        assertEquals("first", inputs.single().id)
    }

    @Test
    fun `given DisconnectedInput when apply then connects INPUT to topmost peer`() {
        val original = pipeline(
            nodes = listOf(
                node("input", NodeType.INPUT, y = 0f),
                node("low", NodeType.LITE_RT, y = 200f),
                node("high", NodeType.CLOUD, y = 50f),
            ),
        )
        val outcome = fix(original, errors = listOf(PipelineValidationError.DisconnectedInput))
        assertEquals(1, outcome.graph.connections.size)
        val edge = outcome.graph.connections.single()
        assertEquals("input", edge.sourceNodeId)
        // Topmost (smallest y) peer is "high" (y = 50).
        assertEquals("high", edge.targetNodeId)
    }

    @Test
    fun `given DisconnectedOutput when apply then connects bottommost peer to OUTPUT`() {
        val original = pipeline(
            nodes = listOf(
                node("output", NodeType.OUTPUT, y = 300f),
                node("low", NodeType.LITE_RT, y = 200f),
                node("high", NodeType.CLOUD, y = 50f),
            ),
        )
        val outcome = fix(original, errors = listOf(PipelineValidationError.DisconnectedOutput))
        val edge = outcome.graph.connections.singleOrNull()
        assertNotNull(edge)
        // Bottommost (largest y) peer that's not OUTPUT is "low" (y = 200).
        assertEquals("low", edge!!.sourceNodeId)
        assertEquals("output", edge.targetNodeId)
    }

    @Test
    fun `given HasCycles when apply then graph is unchanged (no safe recipe)`() {
        val original = pipeline(
            nodes = listOf(node("input", NodeType.INPUT), node("output", NodeType.OUTPUT)),
        )
        val outcome = fix(original, errors = listOf(PipelineValidationError.HasCycles))
        assertTrue(outcome.unchanged)
    }

    @Test
    fun `given multiple recipes when apply then all that fit are applied in order`() {
        val original = pipeline(
            nodes = listOf(node("output", NodeType.OUTPUT, y = 100f)),
        )
        val errors = listOf(
            PipelineValidationError.MissingInput,
            PipelineValidationError.DisconnectedInput,
        )
        val outcome = fix(original, errors = errors)
        // The MissingInput recipe ran first (added the INPUT), then DisconnectedInput
        // saw the new INPUT and wired it up to the OUTPUT peer.
        assertEquals(listOf("add-input", "connect-input"), outcome.appliedRecipes)
        assertEquals(2, outcome.graph.nodes.size)
        assertEquals(1, outcome.graph.connections.size)
    }

    @Test
    fun `given NodeEmptyContext when focusableNodeId then returns the named node id`() {
        val graph = pipeline(nodes = listOf(node("input", NodeType.INPUT)))
        val focused = PipelineValidationError.NodeEmptyContext(nodeId = "abc").focusableNodeId(graph)
        assertEquals("abc", focused)
    }

    @Test
    fun `given MissingInput when focusableNodeId then returns null (no node to focus)`() {
        val graph = pipeline()
        assertNull(PipelineValidationError.MissingInput.focusableNodeId(graph))
    }

    @Test
    fun `given DisconnectedInput when focusableNodeId then returns the existing INPUT id`() {
        val graph = pipeline(nodes = listOf(node("the-input", NodeType.INPUT)))
        assertEquals("the-input", PipelineValidationError.DisconnectedInput.focusableNodeId(graph))
    }

    /** Runs the auto-fix with the English labels of the nodes it adds. */
    private fun fix(graph: PipelineGraph, errors: List<PipelineValidationError>): AutoFixOutcome =
        ValidationAutoFix.apply(graph, errors, inputLabel = "Input", outputLabel = "Output")
}
