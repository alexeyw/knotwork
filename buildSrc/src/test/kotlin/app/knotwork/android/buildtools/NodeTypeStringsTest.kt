package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for [NodeTypeStrings].
 *
 * The reader feeds two documents a verbatim copy of what the app renders, so
 * every case where the copy would read differently from the app — markup, an
 * argument, an escape Android interprets — has to stop generation instead of
 * publishing an approximation. Each refusal is pinned here, plus the real file,
 * which has to parse.
 */
class NodeTypeStringsTest {

    private fun file(vararg entries: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- a comment with <string name=\"x\">y</string> -->\n")
        append("<resources>\n")
        entries.forEach { (name, body) ->
            append("    <string name=\"").append(name).append("\">").append(body).append("</string>\n")
        }
        append("</resources>\n")
    }

    private fun pair(id: String, name: String, description: String): Array<Pair<String, String>> = arrayOf(
        NodeTypeStrings.resourceName(id, "name") to name,
        NodeTypeStrings.resourceName(id, "description") to description,
    )

    private fun refusal(xml: String): String =
        assertThrows(NodeTypeStrings.ParseException::class.java) { NodeTypeStrings.parse(xml) }.message.orEmpty()

    @Test
    fun `given two types when parsed then both texts come back keyed by id in file order`() {
        val parsed = NodeTypeStrings.parse(
            file(*pair("LITE_RT", "LiteRT", "One step."), *pair("INPUT", "Input", "Where it starts.")),
        )

        assertEquals(listOf("LITE_RT", "INPUT"), parsed.keys.toList())
        assertEquals(NodeTypeStrings.Text("LiteRT", "One step."), parsed.getValue("LITE_RT"))
    }

    @Test
    fun `given escaped quotes, entities and wrapped lines when parsed then the text reads as Android renders it`() {
        val parsed = NodeTypeStrings.parse(
            file(*pair("TOOL", "Tool", "The node\\'s \\\"result\\\" &amp; more,\n        on two lines.")),
        )

        assertEquals("The node's \"result\" & more, on two lines.", parsed.getValue("TOOL").description)
    }

    @Test
    fun `given the comment holding a string element when parsed then the comment is ignored`() {
        // `file()` puts a <string> inside the header comment; it must not count.
        val parsed = NodeTypeStrings.parse(file(*pair("INPUT", "Input", "Starts.")))

        assertEquals(setOf("INPUT"), parsed.keys)
    }

    @Test
    fun `given markup when parsed then it is refused`() {
        val message = refusal(file(*pair("INPUT", "Input", "<b>Starts</b>.")))
        assertTrue(message, message.contains("knotwork_node_type_input_description") && message.contains("markup"))
    }

    @Test
    fun `given an angle bracket written as an entity when parsed then it is refused`() {
        // The browser editor puts the name into innerHTML; `&lt;b&gt;` would become markup there.
        val message = refusal(file(*pair("INPUT", "&lt;b&gt;Input", "Starts.")))
        assertTrue(message, message.contains("knotwork_node_type_input_name") && message.contains("plain text"))
    }

    @Test
    fun `given a format argument when parsed then it is refused`() {
        assertTrue(refusal(file(*pair("INPUT", "Input", "Starts %1\$s."))).contains("`%`"))
    }

    @Test
    fun `given an escape other than a quote when parsed then it is refused`() {
        assertTrue(refusal(file(*pair("INPUT", "Input", "Starts\\nhere."))).contains("\\n"))
    }

    @Test
    fun `given an unescaped double quote when parsed then it is refused`() {
        assertTrue(refusal(file(*pair("INPUT", "Input", "Starts \"here\"."))).contains("double quote"))
    }

    @Test
    fun `given an unescaped apostrophe when parsed then it is refused`() {
        assertTrue(refusal(file(*pair("INPUT", "Input", "The run's start."))).contains("apostrophe"))
    }

    @Test
    fun `given a blank text when parsed then it is refused`() {
        assertTrue(refusal(file(*pair("INPUT", "Input", "   "))).contains("empty"))
    }

    @Test
    fun `given a name without its description when parsed then the type is named`() {
        val message = refusal(file(NodeTypeStrings.resourceName("SUMMARY", "name") to "Summary"))
        assertTrue(message, message.contains("SUMMARY has a name but no description"))
    }

    @Test
    fun `given a description without its name when parsed then the type is named`() {
        val message = refusal(file(NodeTypeStrings.resourceName("SUMMARY", "description") to "Condenses."))
        assertTrue(message, message.contains("SUMMARY has a description but no name"))
    }

    @Test
    fun `given a text declared twice when parsed then it is refused`() {
        val message = refusal(file(*pair("INPUT", "Input", "Starts."), *pair("INPUT", "Input", "Starts again.")))
        assertTrue(message, message.contains("declared twice"))
    }

    @Test
    fun `given a string that is not a node-type text when parsed then it is refused`() {
        val message = refusal(file(*pair("INPUT", "Input", "Starts."), "knotwork_other_title" to "Other"))
        assertTrue(message, message.contains("knotwork_other_title"))
    }

    @Test
    fun `given a plurals element when parsed then it is refused rather than skipped`() {
        val xml = file(*pair("INPUT", "Input", "Starts.")).replace(
            "</resources>",
            "    <plurals name=\"knotwork_node_type_input_count\"><item quantity=\"one\">One</item></plurals>\n" +
                "</resources>",
        )
        assertTrue(refusal(xml).contains("resource element"))
    }

    @Test
    fun `given the real resource file when parsed then every type of the domain enum has both texts`() {
        val texts = NodeTypeStrings.parse(read("catalog/src/main/res/values/strings_node_types.xml"))
        val domain = CookbookDocsGenerator.NODE_DOC_META.map { it.id }.toSet()

        assertEquals(domain, texts.keys)
    }

    private fun read(relativePath: String): String = File("../$relativePath").readText()
}
