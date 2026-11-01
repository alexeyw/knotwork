package app.knotwork.design.components.console

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the console's value viewer shows and does. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ConsoleValueViewerTest {

    @get:Rule
    val rule = createComposeRule()

    private val row = ConsoleVarRow(node = "TOOL#9c41d2", key = "body", value = """{"a":1}""")

    private val json = ConsoleValueBody.Ready(
        format = ConsoleValueFormat.Json,
        lines = listOf(
            ConsoleValueLine(indent = 0, text = "{"),
            ConsoleValueLine(indent = 1, text = "\"a\": 1"),
            ConsoleValueLine(indent = 0, text = "}"),
        ),
    )

    private fun show(
        body: ConsoleValueBody,
        viewed: ConsoleVarRow = row,
        onClose: () -> Unit = {},
        onCopy: (ConsoleVarRow) -> Boolean = { true },
    ) {
        rule.setContent {
            KnotworkTheme {
                ConsoleValueViewer(state = ConsoleValueViewerState(viewed, body), onClose = onClose, onCopy = onCopy)
            }
        }
    }

    @Test
    fun `given a formatted JSON value when shown then the key, node, meta line and lines are there`() {
        show(json)

        rule.onNodeWithText("body").assertExists()
        rule.onNodeWithText("TOOL#9c41d2").assertExists()
        rule.onNodeWithText("JSON, formatted · 7 characters").assertExists()
        rule.onNodeWithText("\"a\": 1").assertExists()
    }

    @Test
    fun `given a text value when shown then the meta line counts its lines`() {
        val text = ConsoleVarRow(node = "LITE_RT#501fab", key = "input", value = "one\ntwo")
        show(
            body = ConsoleValueBody.Ready(
                ConsoleValueFormat.Text,
                listOf(ConsoleValueLine(0, "one"), ConsoleValueLine(0, "two")),
            ),
            viewed = text,
        )

        rule.onNodeWithText("Text · 7 characters · 2 lines").assertExists()
    }

    @Test
    fun `given formatting when shown then the viewer waits 300 ms before it says it is formatting`() {
        rule.mainClock.autoAdvance = false
        show(ConsoleValueBody.Formatting)

        rule.mainClock.advanceTimeBy(250)
        rule.onAllNodesWithText("Formatting…").assertCountEquals(0)
        rule.onNodeWithText("7 characters").assertExists()

        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Formatting…").assertExists()
    }

    @Test
    fun `given a value that fits the clipboard when Copy is tapped then the row is copied and nothing is said`() {
        var copied: ConsoleVarRow? = null
        show(
            json,
            onCopy = {
                copied = it
                true
            },
        )

        rule.onNodeWithContentDescription("Copy value").performClick()

        assertEquals(row, copied)
        rule.onAllNodesWithText("Too long for the clipboard. Nothing was copied.").assertCountEquals(0)
    }

    @Test
    fun `given a value too large for the clipboard when Copy is tapped then the viewer says nothing was copied`() {
        show(json, onCopy = { false })

        rule.onNodeWithContentDescription("Copy value").performClick()

        rule.onNodeWithText("Too long for the clipboard. Nothing was copied.").assertExists()
    }

    @Test
    fun `given the viewer when Close is tapped then the host hears it once the closing motion ends`() {
        var closed = 0
        show(json, onClose = { closed++ })

        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()

        assertEquals(1, closed)
    }

    @Test
    fun `given a line of 200 000 characters without spaces when shown then no item holds more than 2 000 of them`() {
        val long = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIz".repeat(5_000)
        show(
            body = ConsoleValueBody.Ready(ConsoleValueFormat.Text, listOf(ConsoleValueLine(0, long))),
            viewed = row.copy(value = long),
        )

        val longest = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text] }
            .maxOf { it.length }
        assertTrue("the longest text in the viewer has $longest characters", longest <= VALUE_PIECE_MAX_CHARS)
    }
}
