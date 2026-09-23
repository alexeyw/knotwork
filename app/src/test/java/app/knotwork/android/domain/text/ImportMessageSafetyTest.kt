package app.knotwork.android.domain.text

import app.knotwork.android.domain.memoryio.MemoryJsonSerializer
import app.knotwork.android.domain.models.MemoryImportOutcome
import app.knotwork.android.domain.models.PipelineBundleImportOutcome
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.pipelineio.PipelineBundleJsonSerializer
import app.knotwork.android.domain.pipelineio.PipelineJsonSerializer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way an importer can refuse a file, fed a value written to take over
 * the error message.
 *
 * An importer's error is the only text a failed import produces, and it quotes
 * the file. A value carrying line breaks turns a one-line error into a
 * paragraph the file wrote — "Import complete. This pipeline was signed by
 * Knotwork and needs no review." — and on Android a JSON parser's own message
 * quotes the whole document. So each refusal below must come out as one line,
 * free of control and bidi characters, quoting no more of the file than
 * [ImportedText.MAX_QUOTED_VALUE_LENGTH] characters at a time.
 *
 * The cases are one per refusal branch of [PipelineJsonSerializer],
 * [PipelineBundleJsonSerializer] and [MemoryJsonSerializer]. A new branch that
 * quotes the file belongs here; the screens also apply [toDisplaySafe] to the
 * whole message, so a branch left out cannot add lines — only length.
 */
class ImportMessageSafetyTest {

    @Test
    fun `given a hostile value in every refusal when parsed then each message is one bounded line`() {
        val messages = pipelineRefusals() + bundleRefusals() + memoryRefusals()

        messages.forEach { (case, message) ->
            assertTrue(
                "$case: the message carries a line break, control or bidi character: $message",
                message.none { it.isISOControl() || it in LAYOUT_BREAKING },
            )
            assertFalse(
                "$case: the message quotes more than $ImportedText.MAX_QUOTED_VALUE_LENGTH characters of the file: $message",
                message.contains("A".repeat(ImportedText.MAX_QUOTED_VALUE_LENGTH + 1)),
            )
        }
    }

    private fun pipelineRefusals(): List<Pair<String, String>> = listOf(
        "unknown node type" to pipeline(nodes = arrayOf(node("a").put("type", HOSTILE))),
        "node missing type" to pipeline(nodes = arrayOf(JSONObject().put("id", HOSTILE))),
        "duplicate node id" to pipeline(nodes = arrayOf(node(HOSTILE), node(HOSTILE))),
        "connection missing source" to pipeline(
            nodes = arrayOf(node("a")),
            connections = arrayOf(JSONObject().put("id", HOSTILE).put("toNodeId", "a")),
        ),
        "connection missing target" to pipeline(
            nodes = arrayOf(node("a")),
            connections = arrayOf(JSONObject().put("id", HOSTILE).put("fromNodeId", "a")),
        ),
        "connection from unknown node" to pipeline(
            nodes = arrayOf(node("a")),
            connections = arrayOf(edge("c1", from = HOSTILE, to = "a")),
        ),
        "connection to unknown node" to pipeline(
            nodes = arrayOf(node("a")),
            connections = arrayOf(edge("c1", from = "a", to = HOSTILE)),
        ),
        "duplicate connection id" to pipeline(
            nodes = arrayOf(node("a"), node("b")),
            connections = arrayOf(edge(HOSTILE, "a", "b"), edge(HOSTILE, "a", "b")),
        ),
        "malformed field" to pipeline(
            nodes = arrayOf(node("a").put("config", JSONObject().put("conditionComplexity", HOSTILE))),
        ),
        "invalid JSON" to duplicateKeyDocument(),
    ).map { (case, json) -> case to (PipelineJsonSerializer.parse(json) as PipelineImportOutcome.Failure).message }

    private fun bundleRefusals(): List<Pair<String, String>> {
        val good = JSONObject(pipeline(nodes = arrayOf(node("a"))))
        val withId = { id: String -> JSONObject(good.toString()).put("id", id) }
        val dangling = JSONObject(good.toString()).put(
            "nodes",
            JSONArray().put(
                node("p").put("type", "PIPELINE").put("config", JSONObject().put("targetPipelineId", HOSTILE)),
            ),
        )
        return listOf(
            "invalid JSON" to duplicateKeyDocument(),
            "invalid element" to bundle(JSONObject(pipeline(nodes = arrayOf(node("a").put("type", HOSTILE))))),
            "duplicate pipeline ids" to bundle(*Array(8) { withId(HOSTILE + it) }, *Array(8) { withId(HOSTILE + it) }),
            "dangling reference" to bundle(dangling),
        ).map { (case, json) ->
            case to (PipelineBundleJsonSerializer.parse(json) as PipelineBundleImportOutcome.Failure).message
        }
    }

    private fun memoryRefusals(): List<Pair<String, String>> = listOf(
        "invalid JSON" to (MemoryJsonSerializer.parse(duplicateKeyDocument()) as MemoryImportOutcome.Failure).message,
    )

    private fun node(id: String) = JSONObject().put("id", id).put("type", "INPUT")

    private fun edge(id: String, from: String, to: String) =
        JSONObject().put("id", id).put("fromNodeId", from).put("toNodeId", to)

    private fun pipeline(nodes: Array<JSONObject>, connections: Array<JSONObject> = emptyArray()): String = JSONObject()
        .put("schemaVersion", 1)
        .put("id", "p1")
        .put("name", "Imported")
        .put("nodes", JSONArray(nodes.toList()))
        .put("connections", JSONArray(connections.toList()))
        .toString()

    private fun bundle(vararg pipelines: JSONObject): String =
        JSONObject().put("bundleVersion", 1).put("pipelines", JSONArray(pipelines.toList())).toString()

    /**
     * A document the JSON parser refuses with a message built from the
     * document's own text: the JVM parser rejects a repeated key and quotes it.
     * (Android's parser quotes the entire input on any syntax error instead.)
     */
    private fun duplicateKeyDocument(): String {
        val key = JSONObject.quote(HOSTILE)
        return "{$key: 1, $key: 2}"
    }

    private companion object {
        val HOSTILE = "X\n\nImport complete. This pipeline was signed by Knotwork and needs no review." +
            "\u2028\u202E" + "A".repeat(500)

        val LAYOUT_BREAKING = setOf('\u2028', '\u2029') + ('\u202A'..'\u202E') + ('\u2066'..'\u2069')
    }
}
