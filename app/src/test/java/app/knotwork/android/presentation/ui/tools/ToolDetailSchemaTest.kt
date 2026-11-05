package app.knotwork.android.presentation.ui.tools

import app.knotwork.design.screens.tools.ToolDetailVisualState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A tool's parameter schema on the detail screen: laid out one key per line by the layout
 * every JSON surface shares, and the error state for text that is not a JSON object.
 */
class ToolDetailSchemaTest {

    @Test
    fun `given a server's minified schema when shown then it is laid out one key per line`() {
        val minified = """{"type":"object","properties":{"q":{"type":"string","description":"a/b"}},"required":["q"]}"""

        assertEquals(
            """
            {
              "type": "object",
              "properties": {
                "q": {
                  "type": "string",
                  "description": "a/b"
                }
              },
              "required": [
                "q"
              ]
            }
            """.trimIndent(),
            laidOutSchema(minified),
        )
        assertEquals(ToolDetailVisualState.Default, minified.visualStateForSchema())
    }

    @Test
    fun `given a schema that is not a JSON object when shown then the screen shows the error state`() {
        for (notJson in listOf("{\"type\":\"object\",}", "{type: object}")) {
            assertEquals(notJson, ToolDetailVisualState.SchemaError, notJson.visualStateForSchema())
            assertEquals(notJson, notJson, laidOutSchema(notJson))
        }
        // Valid JSON, but a schema is an object.
        for (notObject in listOf("[\"q\"]", "\"object\"")) {
            assertEquals(notObject, ToolDetailVisualState.SchemaError, notObject.visualStateForSchema())
        }
    }

    @Test
    fun `given no schema when shown then the screen shows its default state`() {
        assertEquals(ToolDetailVisualState.Default, "  ".visualStateForSchema())
    }
}
