package app.knotwork.design.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.components.chat.ChatMessageReader
import app.knotwork.design.components.chat.ChatMessageReaderState
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chat.LocalChatReaderWork
import app.knotwork.design.theme.KnotworkTheme
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A table cell longer than its column wraps onto more lines instead of ending in an
 * ellipsis — in a chat bubble, which draws tables with [knotworkMarkdownComponents], and in
 * the message reader, whole and cut into chunks.
 *
 * Native graphics, so text has real widths and a long cell has to wrap at all.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class KnotworkMarkdownTableTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun table(rows: Int): String = buildString {
        append("| $LONG_HEADER | What it keeps | Tool |\n|---|---|---|\n")
        append("| web | $LONG_CELL | tar |\n")
        for (row in 2..rows) append("| service $row | data $row | rsync |\n")
    }

    /** The layout of the text drawn for [this], read through its semantics. */
    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /** Matched as a substring: a cell's drawn text keeps the spaces around it in the source row. */
    private fun assertWrapsWhole(text: String) {
        val layout = composeTestRule.onNodeWithText(text, substring = true, useUnmergedTree = true).layout()
        assertTrue("\"$text\" is on ${layout.lineCount} line(s)", layout.lineCount > 1)
        assertFalse("\"$text\" ends in an ellipsis", layout.isLineEllipsized(layout.lineCount - 1))
    }

    /** Waits until the table is drawn: the renderer and the reader both parse it off the main thread. */
    private fun waitForTable() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(LONG_CELL, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun showReader(markdown: String) {
        composeTestRule.setContent {
            KnotworkTheme {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                    LocalChatReaderWork provides Dispatchers.Default,
                ) {
                    ChatMessageReader(
                        state = ChatMessageReaderState(ChatRole.Assistant, "09:14", markdown, markdown = true),
                        onClose = {},
                        onCopy = { true },
                    )
                }
            }
        }
        waitForTable()
    }

    @Test
    fun `given a table in a bubble when a cell is longer than its column then it wraps whole`() {
        composeTestRule.setContent {
            KnotworkTheme {
                Markdown(
                    content = table(rows = 3),
                    typography = knotworkMarkdownTypography(),
                    colors = knotworkMarkdownColor(),
                    components = knotworkMarkdownComponents(),
                )
            }
        }
        waitForTable()

        assertWrapsWhole(LONG_CELL)
        assertWrapsWhole(LONG_HEADER)
    }

    @Test
    fun `given a short table in the reader when a cell is longer than its column then it wraps whole`() {
        showReader(table(rows = 3))

        assertWrapsWhole(LONG_CELL)
    }

    @Test
    fun `given a table the reader cuts into chunks when a cell is longer than its column then it wraps whole`() {
        showReader(table(rows = 30))

        assertWrapsWhole(LONG_CELL)
        assertWrapsWhole(LONG_HEADER)
    }

    private companion object {
        /** A cell several times longer than a column is wide. */
        const val LONG_CELL = "Configuration files and virtual hosts of the web server, with their certificates"

        /** A header cell several times longer than a column is wide. */
        const val LONG_HEADER = "Service whose data and configuration the backup has to keep"
    }
}
