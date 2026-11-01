package app.knotwork.design.components.console

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.SpanStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Vars row shows a preview of its value, and its menu still copies the whole value.
 *
 * Laying out one string costs time in its whole length, quadratic when the string has no
 * spaces, however few lines are drawn: a 200 000-character value held the main thread for
 * seconds when the row handed all of it to one `Text`. The bound on what the row hands over
 * is pinned on [consoleVarPreview], which builds that string; the rows here pin what a
 * reader and TalkBack get. Native graphics, so a preview's lines are the real font's.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ConsoleVarPreviewTest {

    @get:Rule
    val rule = createComposeRule()

    private val mark = SpanStyle(color = Color.Red)

    // A tool answer the size of the HTTP tool's budget would be larger still; what matters
    // is a value with no spaces.
    private val longValue = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIz".repeat(5_000)

    @Test
    fun `given a value without spaces of 200 000 characters when previewed then the preview keeps its first 400`() {
        val preview = consoleVarPreview(longValue, mark)

        assertEquals(longValue.take(VAR_PREVIEW_MAX_CHARS), preview.text)
    }

    @Test
    fun `given line breaks of every kind and a tab when previewed then each break is one mark and the tab one space`() {
        val preview = consoleVarPreview("a\nb\r\nc\rd\te", mark)

        assertEquals("a ↵ b ↵ c ↵ d e", preview.text)
        assertEquals(3, preview.spanStyles.count { it.item == mark })
    }

    @Test
    fun `given a value that is nothing but line breaks when previewed then the preview stays bounded`() {
        val preview = consoleVarPreview("\n".repeat(100_000), mark)

        assertEquals(" ↵ ".repeat(VAR_PREVIEW_MAX_CHARS), preview.text)
    }

    @Test
    fun `given a CRLF split by the cut when previewed then it still makes one mark`() {
        val value = "x".repeat(VAR_PREVIEW_MAX_CHARS - 1) + "\r\n" + "tail"

        assertEquals("x".repeat(VAR_PREVIEW_MAX_CHARS - 1) + " ↵ ", consoleVarPreview(value, mark).text)
    }

    @Test
    fun `given a surrogate pair across the cut when previewed then the preview does not end on half of it`() {
        val value = "x".repeat(VAR_PREVIEW_MAX_CHARS - 1) + "😀" + "tail"

        assertEquals("x".repeat(VAR_PREVIEW_MAX_CHARS - 1), consoleVarPreview(value, mark).text)
    }

    @Test
    fun `given a long value when the row renders then TalkBack hears the key and the size, not the preview`() {
        render(ConsoleVarRow(node = "TOOL#9c41d2", key = "output", value = longValue))

        rule.onNodeWithContentDescription("output, 200,000 characters").assertExists()
        rule.onAllNodesWithContentDescription(longValue.take(40), substring = true).assertCountEquals(0)
    }

    @Test
    fun `given a short value that overflows three lines when the row renders then it counts as cut`() {
        // 300 characters without spaces wrap to about eight lines at 360 dp.
        render(ConsoleVarRow(node = "TOOL#9c41d2", key = "output", value = "Q".repeat(300)))

        rule.onNodeWithContentDescription("output, 300 characters").assertExists()
    }

    @Test
    fun `given a short value when the row renders then it reads as before, with no size`() {
        render(ConsoleVarRow(node = "LITE_RT#501fab", key = "output", value = "Design review at 11:00"))

        rule.onNodeWithText("Design review at 11:00").assertExists()
        rule.onAllNodesWithContentDescription("characters", substring = true).assertCountEquals(0)
    }

    @Test
    fun `given an empty value when the row renders then it shows a pair of quotes and TalkBack says empty`() {
        render(ConsoleVarRow(node = "TOOL#9c41d2", key = "reason", value = ""))

        rule.onNodeWithContentDescription("reason, empty").assertExists()
    }

    @Test
    fun `given a previewed value when Copy value is picked then the whole row is reported`() {
        val row = ConsoleVarRow(node = "TOOL#9c41d2", key = "output", value = longValue)
        var copied: ConsoleVarRow? = null
        render(row, onCopyVar = { copied = it })

        rule.onNodeWithContentDescription("output, 200,000 characters").performTouchInput { longClick() }
        rule.onNodeWithText("Copy value").performClick()

        assertEquals(row, copied)
    }

    @Test
    fun `given a cut row and a host with a viewer when it is tapped then the viewer opens on it`() {
        val row = ConsoleVarRow(node = "TOOL#9c41d2", key = "output", value = longValue)
        var opened: ConsoleVarRow? = null
        render(row, onOpenVar = { opened = it })

        rule.onNodeWithContentDescription("output, 200,000 characters").performClick()

        assertEquals(row, opened)
    }

    @Test
    fun `given a cut row when it is long-pressed then Open value comes first in its menu and opens it`() {
        val row = ConsoleVarRow(node = "TOOL#9c41d2", key = "output", value = longValue)
        var opened: ConsoleVarRow? = null
        render(row, onOpenVar = { opened = it })

        rule.onNodeWithContentDescription("output, 200,000 characters").performTouchInput { longClick() }
        val open = rule.onNodeWithText("Open value").fetchSemanticsNode().boundsInRoot
        val copy = rule.onNodeWithText("Copy value").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("Open value").performClick()

        assertEquals(true, open.top < copy.top)
        assertEquals(row, opened)
    }

    @Test
    fun `given a short row when it is tapped then nothing opens and its menu offers no Open value`() {
        var opened: ConsoleVarRow? = null
        render(ConsoleVarRow(node = "LITE_RT#501fab", key = "output", value = "Design review at 11:00"), onOpenVar = {
            opened =
                it
        })

        rule.onNodeWithText("Design review at 11:00").performClick()
        rule.onNodeWithText("Design review at 11:00").performTouchInput { longClick() }

        assertEquals(null, opened)
        rule.onAllNodesWithText("Open value").assertCountEquals(0)
    }

    private fun render(
        row: ConsoleVarRow,
        onCopyVar: (ConsoleVarRow) -> Unit = {},
        onOpenVar: ((ConsoleVarRow) -> Unit)? = null,
    ) {
        rule.setContent {
            KnotworkTheme {
                ConsolePane(
                    tab = ConsoleTab.Vars,
                    onTabChange = {},
                    logs = emptyList(),
                    vars = listOf(row),
                    traces = emptyList(),
                    filter = ConsoleFilter.allOn,
                    onFilterChange = {},
                    onSearch = {},
                    onCopyAll = {},
                    onClear = {},
                    onCopyVar = onCopyVar,
                    onOpenVar = onOpenVar,
                )
            }
        }
    }
}
