package app.knotwork.android.presentation.ui.orchestrator.presets

import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GraphFlowPreviewTest {

    @Test
    fun `given empty graph when render then returns placeholder`() {
        val graph = PipelineGraph(id = "p", name = "n")
        assertNull(GraphFlowPreview.render(graph))
    }

    @Test
    fun `given simple input output graph when render then returns two-token arrow`() {
        val nIn = node("in", NodeType.INPUT)
        val nOut = node("out", NodeType.OUTPUT)
        val graph = PipelineGraph(
            id = "p",
            name = "n",
            nodes = listOf(nIn, nOut),
            connections = listOf(connection("c1", "in", "out")),
        )

        assertEquals("INPUT → OUTPUT", GraphFlowPreview.render(graph))
    }

    @Test
    fun `given three-node chain when render then walks via outgoing connections`() {
        val nIn = node("in", NodeType.INPUT)
        val nLite = node("lite", NodeType.LITE_RT)
        val nOut = node("out", NodeType.OUTPUT)
        val graph = PipelineGraph(
            id = "p",
            name = "n",
            nodes = listOf(nIn, nLite, nOut),
            connections = listOf(
                connection("c1", "in", "lite"),
                connection("c2", "lite", "out"),
            ),
        )

        assertEquals("INPUT → LITE_RT → OUTPUT", GraphFlowPreview.render(graph))
    }

    @Test
    fun `given graph with no INPUT when render then walk starts at first node`() {
        val nA = node("a", NodeType.LITE_RT)
        val nB = node("b", NodeType.OUTPUT)
        val graph = PipelineGraph(
            id = "p",
            name = "n",
            nodes = listOf(nA, nB),
            connections = listOf(connection("c1", "a", "b")),
        )

        assertEquals("LITE_RT → OUTPUT", GraphFlowPreview.render(graph))
    }

    @Test
    fun `given branching graph when render then picks first outgoing edge by declaration order`() {
        val nIn = node("in", NodeType.INPUT)
        val nLite = node("lite", NodeType.LITE_RT)
        val nCloud = node("cloud", NodeType.CLOUD)
        val nOut = node("out", NodeType.OUTPUT)
        // The INPUT has two outgoing edges; the helper must follow the
        // first one in declaration order so the preview stays deterministic.
        val graph = PipelineGraph(
            id = "p",
            name = "n",
            nodes = listOf(nIn, nLite, nCloud, nOut),
            connections = listOf(
                connection("c1", "in", "lite"),
                connection("c2", "in", "cloud"),
                connection("c3", "lite", "out"),
            ),
        )

        assertEquals("INPUT → LITE_RT → OUTPUT", GraphFlowPreview.render(graph))
    }

    @Test
    fun `given cyclic graph when render then walk terminates on revisit`() {
        // A → B → A — the walker must stop instead of looping forever.
        val nA = node("a", NodeType.LITE_RT)
        val nB = node("b", NodeType.LITE_RT)
        val graph = PipelineGraph(
            id = "p",
            name = "n",
            nodes = listOf(nA, nB),
            connections = listOf(
                connection("c1", "a", "b"),
                connection("c2", "b", "a"),
            ),
        )

        assertEquals("LITE_RT → LITE_RT", GraphFlowPreview.render(graph))
    }

    @Test
    fun `given graph longer than max nodes when render then caps token count`() {
        val nodes = (0 until 10).map { idx -> node(idx.toString(), NodeType.LITE_RT) }
        val conns = (0 until 9).map { idx ->
            connection("c$idx", idx.toString(), (idx + 1).toString())
        }
        val graph = PipelineGraph(id = "p", name = "n", nodes = nodes, connections = conns)

        val rendered = GraphFlowPreview.render(graph)
        // 6 tokens, 5 arrows
        val tokenCount = checkNotNull(rendered).split(" → ").size
        assertEquals(6, tokenCount)
    }

    private fun node(id: String, type: NodeType): NodeModel = NodeModel(
        id = id,
        type = type,
        x = 0f,
        y = 0f,
        contextConfig = NodeContextConfig(),
    )

    private fun connection(id: String, source: String, target: String): ConnectionModel = ConnectionModel(
        id = id,
        sourceNodeId = source,
        targetNodeId = target,
    )
}
