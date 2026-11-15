package app.knotwork.design.components.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How [HitlConfirmationCard] wraps a line of its arguments that is wider than the card.
 *
 * The host lays the arguments out one key or item per line, indented by nesting. A line
 * too long for the card wraps, and the wrapped part used to start at the left edge of the
 * block — under the braces, where it read as a new key one level up (seen at font scale
 * 200 %). The continuation now starts where the line's own text starts, as the console's
 * value window draws it ([withHangingIndent]); every line still gets exactly one row
 * before it wraps, and the characters are the host's.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class HitlConfirmationCardArgumentsWrapTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `given an indented line that wraps when expanded then the continuation starts under the line's text`() {
        setExpandedCard(NESTED, scale = 2f)
        val layout = argumentsLayout()
        val drawn = layout.layoutInput.text.text
        val lineText = drawn.indexOf("\"start\"")
        val value = drawn.indexOf("\"2026")

        // Precondition: the line does wrap, between the key and its value.
        assertNotEquals(layout.getLineForOffset(lineText), layout.getLineForOffset(value))
        assertEquals(
            layout.getBoundingBox(lineText).left,
            layout.getBoundingBox(value).left,
            PIXEL_TOLERANCE,
        )
    }

    @Test
    fun `given a line without indent that wraps when expanded then the continuation starts at the left edge`() {
        setExpandedCard(PLAIN, scale = 2f)
        val layout = argumentsLayout()
        val lastRow = layout.lineCount - 1

        assertTrue(lastRow > 0)
        assertEquals(
            layout.getBoundingBox(0).left,
            layout.getBoundingBox(layout.getLineStart(lastRow)).left,
            PIXEL_TOLERANCE,
        )
    }

    @Test
    fun `given indented lines that fit when expanded then each line takes one row at its own indent`() {
        setExpandedCard(NESTED, scale = 1f)
        val layout = argumentsLayout()
        val drawn = layout.layoutInput.text.text
        val outer = drawn.indexOf("\"when\"")
        val inner = drawn.indexOf("\"minutes\"")
        val charWidth = layout.getBoundingBox(outer + 1).left - layout.getBoundingBox(outer).left

        // One row per line: a run of equally indented lines is a paragraph of its own, and
        // that must neither add a blank row nor join two lines into one.
        assertEquals(NESTED.lines().size, layout.lineCount)
        // Rows as far apart across a paragraph boundary as inside one: each paragraph trims
        // its line height at its own edges, which must stay only at the text's edges.
        val gaps = (1 until layout.lineCount).map { layout.getLineBaseline(it) - layout.getLineBaseline(it - 1) }
        gaps.forEach { assertEquals(gaps.first(), it, PIXEL_TOLERANCE) }
        // Two more spaces of indent are two characters further right — the hanging indent
        // does not move the first row of a line.
        assertEquals(
            layout.getBoundingBox(outer).left + 2 * charWidth,
            layout.getBoundingBox(inner).left,
            PIXEL_TOLERANCE,
        )
    }

    @Test
    fun `given arguments longer than two rows when collapsed then the second row ends in an ellipsis`() {
        setCard(NESTED, scale = 1f, expand = false)
        val layout = argumentsLayout()

        // The ellipsis is what says there is more below; a line limit that falls on a
        // paragraph boundary cuts the text without one.
        assertTrue(layout.isLineEllipsized(layout.lineCount - 1))
    }

    @Test
    fun `given a blank line among indented ones when expanded then it stays a blank row`() {
        setExpandedCard(WITH_BLANK_LINE, scale = 1f)

        assertEquals(WITH_BLANK_LINE.lines().size, argumentsLayout().lineCount)
    }

    @Test
    fun `given laid-out text when indented then every line comes back in order, each run under its indent`() {
        val charWidth = 0.6.em
        listOf(NESTED, WITH_BLANK_LINE, MIXED, "  only one indented line", "{\n  \"a\": 1\n}\n").forEach { text ->
            val shown = withHangingIndent(text, charWidth, LineHeightStyle.Default)
            val paragraphs = shown.paragraphStyles.sortedBy { it.start }

            assertEquals(text, 0, paragraphs.first().start)
            assertEquals(text, shown.text.length, paragraphs.last().end)
            paragraphs.zipWithNext().forEach { (a, b) -> assertEquals(text, a.end, b.start) }
            // The boundary between two paragraphs is the line break the text no longer holds.
            assertEquals(text, paragraphs.joinToString("\n") { shown.text.substring(it.start, it.end) })
            paragraphs.forEach { paragraph ->
                val lines = shown.text.substring(paragraph.start, paragraph.end).split('\n')
                val indent = lines.first().takeWhile { it == ' ' }.length.takeIf { it < lines.first().length } ?: 0
                assertEquals(text, charWidth * indent, paragraph.item.textIndent?.restLine)
            }
            // The default trims the text's first top and last bottom: only the outer paragraphs do.
            val trims = paragraphs.map { checkNotNull(it.item.lineHeightStyle).trim }
            val expected = List(paragraphs.size) { index ->
                when {
                    paragraphs.size == 1 -> LineHeightStyle.Trim.Both
                    index == 0 -> LineHeightStyle.Trim.FirstLineTop
                    index == paragraphs.lastIndex -> LineHeightStyle.Trim.LastLineBottom
                    else -> LineHeightStyle.Trim.None
                }
            }
            assertEquals(text, expected, trims)
        }
    }

    @Test
    fun `given a style that trims nothing when indented then no paragraph trims`() {
        val none = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        val trims = withHangingIndent(NESTED, 0.6.em, none).paragraphStyles.map { it.item.lineHeightStyle }

        assertTrue(trims.all { it == none })
    }

    @Test
    fun `given text without an indented line when indented then it comes back unstyled`() {
        listOf(PLAIN, "line one\nline two", "", "\n").forEach { text ->
            val shown = withHangingIndent(text, 0.6.em, LineHeightStyle.Default)

            assertEquals(text, shown.text)
            assertTrue(text, shown.paragraphStyles.isEmpty())
        }
    }

    private fun setExpandedCard(arguments: String, scale: Float) = setCard(arguments, scale, expand = true)

    private fun setCard(arguments: String, scale: Float, expand: Boolean) {
        composeTestRule.setContent {
            val base = LocalDensity.current
            KnotworkTheme {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = scale),
                    LocalDensity provides Density(density = base.density, fontScale = scale),
                ) {
                    HitlConfirmationCard(
                        model = HitlConfirmationModel(
                            risk = Risk.Sensitive,
                            toolName = TOOL_NAME,
                            summary = "",
                            arguments = arguments,
                            timestamp = "14:02",
                        ),
                        pendingTypedConfirm = "",
                        onTypedConfirmChange = {},
                        allowOnceEnabled = true,
                        onAllowOnce = {},
                        onAllowAlways = null,
                        onReject = {},
                    )
                }
            }
        }
        if (expand) {
            composeTestRule.onNode(hasClickAction() and hasContentDescription("Expand arguments")).performClick()
        }
    }

    /** The layout of the arguments text: the one text of the card in the monospace block that is not the tool id. */
    private fun argumentsLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule.onNode(
            hasText(ARGUMENTS_MARKER, substring = true) or hasText(PLAIN),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private companion object {
        const val PIXEL_TOLERANCE = 1f
        const val TOOL_NAME = "calendar.create_event"

        /** A key every indented sample carries, to find the arguments text among the card's. */
        const val ARGUMENTS_MARKER = "\"when\""

        /** `{"when":{"start":"2026-11-06T10:00","minutes":30}}` as the host lays it out. */
        val NESTED = """
            {
              "when": {
                "start": "2026-11-06T10:00",
                "minutes": 30
              }
            }
        """.trimIndent()

        /** Text that is not JSON, shown as it came, with a blank line between indented ones. */
        const val WITH_BLANK_LINE = "{\n  \"when\": 1,\n\n  \"then\": 2\n}"

        /** Text that is not JSON: indents that come and go, a line of spaces, unindented lines in a row. */
        const val MIXED = "first\nsecond\n    deep\n  half\n   \n  half again\nlast\n"

        /** Text that is not JSON, shown as it came: one long line with no indent. */
        const val PLAIN = "not json: a call whose arguments the model wrote as a sentence"
    }
}
