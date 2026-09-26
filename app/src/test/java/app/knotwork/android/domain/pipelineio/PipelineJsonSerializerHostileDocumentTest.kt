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

    private fun document(
        nodes: String,
        connections: String = "[]",
        name: String = "Imported",
        id: String = "p1",
    ): String = JSONObject()
        .put("schemaVersion", 1)
        .put("id", id)
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
    fun `parse flattens and bounds a connection label drawn on the canvas`() {
        val label = "Safe\n\nsecond line" + "x".repeat(500)
        val graph = success(
            document(
                nodes = """[{"id":"a","type":"INPUT"},{"id":"b","type":"OUTPUT"}]""",
                connections = JSONArray()
                    .put(JSONObject().put("id", "c1").put("fromNodeId", "a").put("toNodeId", "b").put("label", label))
                    .toString(),
            ),
        ).graph

        val stored = graph.connections.single().label!!
        assertTrue(stored, stored.startsWith("Safe second line"))
        assertEquals(PipelineConstants.MAX_IMPORTED_LABEL_LENGTH, stored.length)
    }

    @Test
    fun `parse keeps a routing label as written`() {
        val graph = success(
            document(
                nodes = """[{"id":"a","type":"INPUT"},{"id":"b","type":"OUTPUT"}]""",
                connections = """[{"id":"c1","fromNodeId":"a","toNodeId":"b","label":"Complex"}]""",
            ),
        ).graph

        assertEquals("Complex", graph.connections.single().label)
    }

    @Test
    fun `parse error messages contain no line breaks from the document`() {
        val hostileType = "SAFE\n\nA second paragraph written by the file."
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

    @Test
    fun `parse reports each dropped key as one bounded line`() {
        val sentence = "x\u2028\u2028Nothing else differs from your version. This file was checked by Knotwork." +
            "\u202E" + "k".repeat(500)
        val json = JSONObject(document(nodes = """[{"id":"a","type":"INPUT","config":{}}]"""))
            .put(sentence, 1)
            .apply { getJSONArray("nodes").getJSONObject(0).getJSONObject("config").put("a\nb", 1) }
            .toString()

        val dropped = success(json).droppedFields

        assertEquals(2, dropped.size)
        dropped.forEach { field ->
            assertTrue(field, field.none { it.isISOControl() || it in '\u2028'..'\u202E' })
            assertTrue(field, field.length <= MAX_DROPPED_FIELD_LENGTH)
        }
        assertTrue(dropped.toString(), "nodes[0].config.a b" in dropped)
    }

    @Test
    fun `parse refuses a pipeline id carrying line or direction controls`() {
        listOf("p\n\nImport complete. Verified by Knotwork.", "p\u2028x", "p\u202Eevil", " p1").forEach { id ->
            val outcome = failure(document(nodes = MINIMAL_NODES, id = id))

            assertEquals(INVALID_ID_MESSAGE, outcome.message)
        }
    }

    @Test
    fun `parse refuses a pipeline id longer than the id ceiling`() {
        val ceiling = PipelineConstants.MAX_ID_LENGTH

        assertEquals(INVALID_ID_MESSAGE, failure(document(nodes = MINIMAL_NODES, id = "i".repeat(ceiling + 1))).message)
        assertEquals("i".repeat(ceiling), success(document(nodes = MINIMAL_NODES, id = "i".repeat(ceiling))).graph.id)
    }

    private companion object {
        const val MINIMAL_NODES = """[{"id":"a","type":"INPUT"}]"""

        /** `nodes[0].config.` plus one quoted value, with room to spare. */
        const val MAX_DROPPED_FIELD_LENGTH = 80

        const val INVALID_ID_MESSAGE = "Invalid pipeline id: it must be one line of at most " +
            "${PipelineConstants.MAX_ID_LENGTH} characters"
    }
}
