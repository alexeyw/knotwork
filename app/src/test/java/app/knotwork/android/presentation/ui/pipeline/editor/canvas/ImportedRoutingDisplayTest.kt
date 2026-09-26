package app.knotwork.android.presentation.ui.pipeline.editor.canvas

import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.models.RouteLabels
import app.knotwork.android.domain.pipelineio.PipelineJsonSerializer
import app.knotwork.android.presentation.ui.pipeline.editor.config.NodeTypeMapper
import app.knotwork.design.components.pipelineeditor.NodePorts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The canvas draws an imported branching node's edges from the port the run takes
 * them by. The engine matches branch labels without regard to case; the canvas
 * matched them exactly, so an IF edge labelled `false` was drawn from the True port
 * and the False port looked unwired.
 */
class ImportedRoutingDisplayTest {

    private fun parse(json: String) = (PipelineJsonSerializer.parse(json) as PipelineImportOutcome.Success).graph

    @Test
    fun `given an IF edge labelled in lower case when drawn then it leaves the False port`() {
        val graph = parse(
            """
            {"schemaVersion":1,"id":"p","name":"Urgent",
             "nodes":[{"id":"in","type":"INPUT"},
                      {"id":"if","type":"IF_CONDITION","label":"Urgent?","config":{"conditionKeywords":"urgent"}},
                      {"id":"a","type":"OUTPUT"},{"id":"c","type":"OUTPUT"}],
             "connections":[{"id":"e0","fromNodeId":"in","toNodeId":"if"},
                            {"id":"t","fromNodeId":"if","toNodeId":"a","label":"True"},
                            {"id":"f","fromNodeId":"if","toNodeId":"c","label":"false"}]}
            """.trimIndent(),
        )
        val ifNode = graph.nodes.single { it.id == "if" }
        val ports = portsFor(ifNode)
        val falsePortIndex = ports.outbound.indexOfFirst { it.label == "False" }

        val anchor = outboundPortAnchor(ifNode, ports, "false")

        assertEquals(outboundPortAnchors(ifNode)[falsePortIndex].second, anchor)
    }

    @Test
    fun `given a router edge whose label names no class when drawn then it has a port of its own`() {
        val graph = parse(
            """
            {"schemaVersion":1,"id":"p","name":"Route",
             "nodes":[{"id":"in","type":"INPUT"},
                      {"id":"r","type":"INTENT_ROUTER","label":"Route",
                       "config":{"systemPrompt":"small talk (chat) or notes (notes)?"},
                       "nodeConfig":{"v":1,"type":"INTENT_ROUTER","title":"Route",
                                     "classes":[{"name":"chat"},{"name":"notes"}]}},
                      {"id":"reply","type":"OUTPUT"},{"id":"notes","type":"OUTPUT"},{"id":"polish","type":"OUTPUT"}],
             "connections":[{"id":"e0","fromNodeId":"in","toNodeId":"r"},
                            {"id":"e1","fromNodeId":"r","toNodeId":"reply","label":"chat"},
                            {"id":"e2","fromNodeId":"r","toNodeId":"notes","label":"NOTES"},
                            {"id":"e3","fromNodeId":"r","toNodeId":"polish","label":"the"}]}
            """.trimIndent(),
        )
        val router = graph.nodes.single { it.id == "r" }
        val labels = outboundLabelsOf(router.id, graph.connections)

        val ports = portsFor(router, labels)

        // `NOTES` is the declared `notes` branch (the run matches without case);
        // `the` is a branch the run can take that no class names.
        assertEquals(listOf("chat", "notes", "the"), ports.outbound.map { it.label })
        assertEquals(
            outboundPortAnchors(router, labels)[2].second,
            outboundPortAnchor(router, ports, "the"),
        )
    }

    @Test
    fun `given a Retry edge on an evaluation with no retries when drawn then the Retry port is shown`() {
        val graph = parse(
            """
            {"schemaVersion":1,"id":"p","name":"Check",
             "nodes":[{"id":"in","type":"INPUT"},{"id":"ev","type":"EVALUATION",
                       "nodeConfig":{"v":1,"type":"EVALUATION","title":"Check","maxRetries":0}},
                      {"id":"a","type":"OUTPUT"},{"id":"b","type":"OUTPUT"}],
             "connections":[{"id":"e0","fromNodeId":"in","toNodeId":"ev"},
                            {"id":"p","fromNodeId":"ev","toNodeId":"a","label":"Pass"},
                            {"id":"r","fromNodeId":"ev","toNodeId":"b","label":"Retry"}]}
            """.trimIndent(),
        )
        val ev = graph.nodes.single { it.id == "ev" }

        val ports = portsFor(ev, outboundLabelsOf(ev.id, graph.connections))

        assertEquals(listOf("Pass", "Retry", "Fail"), ports.outbound.map { it.label })
    }

    @Test
    fun `given the design catalog then its fixed ports are spelled as the run's branches`() {
        // The canvas draws the catalog's ports; the engine routes by RouteLabels.
        listOf(NodeType.IF_CONDITION, NodeType.QUEUE_PROCESSOR, NodeType.EVALUATION).forEach { type ->
            val ports = NodePorts.forType(NodeTypeMapper.toCatalog(type), maxRetries = 1)
            assertEquals(type.name, RouteLabels.fixedBranches(type), ports.outbound.map { it.label })
        }
    }
}
