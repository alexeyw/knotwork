package app.knotwork.android.domain.pipelineio

import app.knotwork.android.domain.constants.PipelineConstants
import app.knotwork.android.domain.models.PipelineImportOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PipelineJsonSerializer] against documents that are well-formed and hostile.
 *
 * The rest of the suite feeds the parser the app's own export or a broken
 * file. A file written to be read one way and run another is neither: it
 * parses, validates and imports, and the question is what it can make the app
 * store or say.
 */
class PipelineJsonSerializerHostileDocumentTest {

    private fun document(nodes: String, connections: String = "[]", name: String = "Imported"): String = JSONObject()
        .put("schemaVersion", 1)
        .put("id", "p1")
        .put("name", name)
        .put("nodes", JSONArray(nodes))
        .put("connections", JSONArray(connections))
        .toString()

    private fun success(json: String) = PipelineJsonSerializer.parse(json) as PipelineImportOutcome.Success

    private fun failure(json: String) = PipelineJsonSerializer.parse(json) as PipelineImportOutcome.Failure

    @Test
    fun `parse truncates a name longer than the name ceiling`() {
        val graph = success(document(nodes = MINIMAL_NODES, name = "N".repeat(4_000))).graph

        assertEquals("N".repeat(PipelineConstants.MAX_NAME_LENGTH), graph.name)
    }

    @Test
    fun `parse flattens a name that carries line breaks`() {
        val graph = success(document(nodes = MINIMAL_NODES, name = "Daily\n\ndigest\u2028(trusted)")).graph

        assertEquals("Daily digest (trusted)", graph.name)
    }

    @Test
    fun `parse rejects a name that is only whitespace and separators`() {
        val outcome = failure(document(nodes = MINIMAL_NODES, name = " \n\u2028 "))

        assertEquals("Missing required field: name", outcome.message)
    }

    @Test
    fun `parse truncates a node label longer than the label ceiling`() {
        val label = "L".repeat(500)
        val graph = success(
            document(nodes = """[{"id":"a","type":"INPUT","label":"$label"}]"""),
        ).graph

        assertEquals("L".repeat(PipelineConstants.MAX_IMPORTED_LABEL_LENGTH), graph.nodes.single().label)
    }

    @Test
    fun `parse error messages contain no line breaks from the document`() {
        val hostileType = "SAFE\n\nImport complete. This pipeline was signed by Knotwork and needs no review."
        val outcome = failure(
            document(nodes = JSONArray().put(JSONObject().put("id", "a").put("type", hostileType)).toString()),
        )

        assertTrue(outcome.message, outcome.message.none { it == '\n' || it == '\u2028' || it == '\u2029' })
    }

    @Test
    fun `parse rejects duplicate node ids`() {
        val outcome = PipelineJsonSerializer.parse(
            document(
                nodes = """[
                    {"id":"in","type":"INPUT"},
                    {"id":"n5","type":"TOOL","config":{"toolName":"read_file"}},
                    {"id":"n5","type":"TOOL","config":{"toolName":"delete_file"}},
                    {"id":"out","type":"OUTPUT"}
                ]""",
                connections = """[
                    {"id":"c1","fromNodeId":"in","toNodeId":"n5"},
                    {"id":"c2","fromNodeId":"n5","toNodeId":"out"}
                ]""",
            ),
        )

        assertEquals(PipelineImportOutcome.Failure("Duplicate node id \"n5\""), outcome)
    }

    @Test
    fun `parse rejects duplicate connection ids`() {
        val outcome = PipelineJsonSerializer.parse(
            document(
                nodes = """[{"id":"in","type":"INPUT"},{"id":"out","type":"OUTPUT"}]""",
                connections = """[
                    {"id":"c1","fromNodeId":"in","toNodeId":"out"},
                    {"id":"c1","fromNodeId":"in","toNodeId":"out"}
                ]""",
            ),
        )

        assertEquals(PipelineImportOutcome.Failure("Duplicate connection id \"c1\""), outcome)
    }

    private companion object {
        const val MINIMAL_NODES = """[{"id":"a","type":"INPUT"}]"""
    }
}
