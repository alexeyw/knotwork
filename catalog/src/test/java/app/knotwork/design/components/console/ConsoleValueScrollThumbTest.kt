package app.knotwork.design.components.console

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** When the value viewer's scroll thumb shows, where it sits, and what dragging it does. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ConsoleValueScrollThumbTest {

    @get:Rule
    val rule = createComposeRule()

    /** 5 000 characters at 42 a row: three pieces, 120 rows. */
    private val longLine = ConsoleValuePieces(listOf(ConsoleValueLine(indent = 0, text = "x".repeat(5_000)))) { 42 }

    @Test
    fun `given exactly five screens of rows when asked then no thumb, and one row more shows it`() {
        assertFalse(consoleValueThumbShown(rowCount = 100, rowPx = 10f, viewportPx = 200))
        assertTrue(consoleValueThumbShown(rowCount = 101, rowPx = 10f, viewportPx = 200))
    }

    @Test
    fun `given a scroll position when mapped then the thumb's share is the rows above the top over the range`() {
        // 120 rows, 10 of them visible: the top row runs from 0 to 110.
        val map = ConsoleValueScrollMap(longLine, rowPx = 10f, contentViewportPx = 100)

        assertEquals(0f, map.fraction(firstIndex = 0, firstOffsetPx = 0), 0f)
        assertEquals(47f / 110f, map.fraction(firstIndex = 1, firstOffsetPx = 0), 1e-6f)
        assertEquals(1f, map.fraction(firstIndex = 2, firstOffsetPx = 250), 0f)
    }

    @Test
    fun `given a thumb share when mapped then the list scrolls to the row at that share`() {
        val map = ConsoleValueScrollMap(longLine, rowPx = 10f, contentViewportPx = 100)

        assertEquals(ConsoleValueScrollTarget(index = 0, offsetPx = 0, lineIndex = 0), map.target(0f))
        // Row 55 is the ninth row of the second piece; row 110 the seventeenth of the third.
        assertEquals(ConsoleValueScrollTarget(index = 1, offsetPx = 80, lineIndex = 0), map.target(0.5f))
        assertEquals(ConsoleValueScrollTarget(index = 2, offsetPx = 160, lineIndex = 0), map.target(1f))
        assertEquals(map.target(1f), map.target(3f))
    }

    @Test
    fun `given several lines when a share is mapped then the target names the line it falls in`() {
        val lines = listOf(
            ConsoleValueLine(indent = 0, text = "short"),
            ConsoleValueLine(indent = 0, text = ""),
            ConsoleValueLine(indent = 0, text = "y".repeat(4_000)),
            ConsoleValueLine(indent = 0, text = "end"),
        )
        // 103 rows at 40 a row, 3 visible: the top row runs from 0 to 100.
        val map = ConsoleValueScrollMap(ConsoleValuePieces(lines) { 40 }, rowPx = 10f, contentViewportPx = 30)

        assertEquals(1, map.target(0.01f).lineIndex)
        assertEquals(ConsoleValueScrollTarget(index = 3, offsetPx = 0, lineIndex = 2), map.target(0.52f))
    }

    @Test
    fun `given a value shorter than its viewport when mapped then the thumb stays at the top`() {
        val short = ConsoleValuePieces(listOf(ConsoleValueLine(indent = 0, text = "ten chars."))) { 42 }
        val map = ConsoleValueScrollMap(short, rowPx = 10f, contentViewportPx = 100)

        assertEquals(0f, map.fraction(firstIndex = 0, firstOffsetPx = 0), 0f)
        assertEquals(ConsoleValueScrollTarget(index = 0, offsetPx = 0, lineIndex = 0), map.target(1f))
    }

    @Test
    fun `given a value of a few lines when shown then there is no thumb`() {
        show(lines = 40)

        rule.onNodeWithTag(CONSOLE_VALUE_THUMB_TAG).assertDoesNotExist()
    }

    @Test
    fun `given a long value when the thumb is dragged to the foot and back then the list follows it`() {
        show(lines = 2_000)
        flingOnce()

        rule.onNodeWithTag(CONSOLE_VALUE_THUMB_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 10_000f))
        }
        rule.waitForIdle()
        assertTrue("dragged to the foot, the list is at its end", atEnd())

        rule.onNodeWithTag(CONSOLE_VALUE_THUMB_TAG).performTouchInput {
            moveBy(Offset(0f, -10_000f))
            up()
        }
        rule.waitForIdle()
        assertEquals(0f, scrollRange().value(), 0f)
    }

    @Test
    fun `given a dragged thumb when TalkBack reads the viewer then neither the thumb nor its bubble is there`() {
        show(lines = 2_000)
        flingOnce()

        rule.onNodeWithTag(CONSOLE_VALUE_THUMB_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 300f))
        }
        rule.waitForIdle()

        rule.onNodeWithTag(CONSOLE_VALUE_THUMB_TAG)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        // The merged tree, as TalkBack walks it: the accessibility delegate reads `replacedChildren`, which
        // leaves out what `clearAndSetSemantics` clears (ui 1.12.1 SemanticsNode.kt lines 331-332, 351).
        rule.onAllNodes(hasText(" of 2,000", substring = true)).assertCountEquals(0)
        rule.onNodeWithTag(CONSOLE_VALUE_THUMB_TAG).performTouchInput { up() }
    }

    @Test
    fun `given the thumb gone after scrolling stopped when its place is swiped then the list scrolls instead`() {
        show(lines = 2_000)
        flingOnce()
        rule.onNodeWithTag(CONSOLE_VALUE_THUMB_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 10_000f))
            up()
        }
        rule.waitForIdle()
        assertTrue(atEnd())

        rule.mainClock.advanceTimeBy(THUMB_HIDE_DELAY_MS + 500)
        // A shown thumb dragged down stays at the foot; a hidden one lets the list scroll back.
        rule.onNodeWithTag(CONSOLE_VALUE_THUMB_TAG).performTouchInput { swipeDown() }
        rule.waitForIdle()

        assertFalse("the swipe reached the list", atEnd())
    }

    @Test
    fun `given a shown thumb when the system gestures are read then its place is excluded from them`() {
        show(lines = 2_000)
        assertTrue("no exclusion before the thumb shows", exclusionRects().isEmpty())

        flingOnce()

        assertEquals(1, exclusionRects().size)
    }

    private fun show(lines: Int) {
        val text = (1..lines).map { "line %05d".format(it) }
        rule.setContent {
            KnotworkTheme {
                ConsoleValueViewer(
                    state = ConsoleValueViewerState(
                        row = ConsoleVarRow(node = "TOOL#9d3e01", key = "body", value = text.joinToString("\n")),
                        body = ConsoleValueBody.Ready(ConsoleValueFormat.Text, text.map { ConsoleValueLine(0, it) }),
                    ),
                    onClose = {},
                    onCopy = { true },
                )
            }
        }
        rule.waitForIdle()
    }

    /** Scrolls the list a little, the way a finger does, so the thumb shows. */
    private fun flingOnce() {
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp(startY = centerY, endY = centerY - 200f) }
        rule.waitForIdle()
    }

    private fun scrollRange(): ScrollAxisRange =
        rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    /** A lazy list reports a range one past its value while it can scroll forward. */
    private fun atEnd(): Boolean = scrollRange().let { it.value() == it.maxValue() }

    /** The system gesture exclusions the viewer's window holds. */
    private fun exclusionRects(): List<Rect> {
        fun View.all(): List<View> = listOf(this) + (
            (this as? ViewGroup)?.let { group ->
                (0 until group.childCount).flatMap { group.getChildAt(it).all() }
            } ?: emptyList()
            )
        return ShadowDialog.getLatestDialog().window!!.decorView.all().flatMap { it.systemGestureExclusionRects }
    }
}
