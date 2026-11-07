package app.knotwork.design.components.text

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Checks that a laid-out text is whole: its lines end only between words, and none of it
 * is cut by its line limit or its box.
 *
 * A word broken across two lines, or a text ending in an ellipsis, is what a larger font
 * does to a layout that hands a text less width than its longest word — a sibling measured
 * first, or a column whose width does not depend on what it holds.
 */
internal object TextBreakAssertions {

    /**
     * The words [layout] breaks across lines: a line that ends between two letters or digits
     * splits a word, and the word is reported whole, as the source text spells it.
     *
     * @param layout The laid-out text.
     * @return Each broken word, in line order; empty when every line ends between words.
     */
    fun wordsBrokenAcrossLines(layout: TextLayoutResult): List<String> {
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount - 1).mapNotNull { line ->
            val end = layout.getLineEnd(line)
            val splitsAWord = end in 1 until text.length &&
                text[end - 1].isLetterOrDigit() &&
                text[end].isLetterOrDigit()
            if (splitsAWord) wordAround(text, end) else null
        }
    }

    /**
     * Whether [layout] draws less than its text: a line limit cut it, with or without an
     * ellipsis, an ellipsis ended a line, or its lines ran below its box.
     *
     * The width overflow a layout reports is not read: for a text that wraps, the layout
     * read through semantics is measured at the width offered and sized to its longest
     * line, so it reports a width overflow for a text that is drawn whole.
     *
     * @param layout The laid-out text.
     * @return `true` when part of the text is not shown.
     */
    fun isCut(layout: TextLayoutResult): Boolean =
        layout.didOverflowHeight || (0 until layout.lineCount).any(layout::isLineEllipsized)

    /**
     * Asserts that every text under [provider]'s root is whole.
     *
     * @param provider The compose test rule holding the content.
     */
    fun assertEveryTextWhole(provider: SemanticsNodeInteractionsProvider) {
        val texts = provider.onAllNodes(HAS_TEXT_LAYOUT, useUnmergedTree = true)
        val count = texts.fetchSemanticsNodes().size
        assertTrue("no text was drawn", count > 0)
        assertNoFindings((0 until count).map { texts[it].textLayout() })
    }

    /**
     * Asserts that each of [texts] is drawn under [provider]'s root and is whole wherever
     * it is drawn.
     *
     * @param provider The compose test rule holding the content.
     * @param texts The exact texts to check.
     */
    fun assertWhole(provider: SemanticsNodeInteractionsProvider, vararg texts: String) {
        val layouts = texts.flatMap { text ->
            val nodes = provider.onAllNodes(HAS_TEXT_LAYOUT and hasText(text), useUnmergedTree = true)
            val count = nodes.fetchSemanticsNodes().size
            assertTrue("\"$text\" is not drawn", count > 0)
            (0 until count).map { nodes[it].textLayout() }
        }
        assertNoFindings(layouts)
    }

    /** The layout of the text drawn for this node, read through its semantics. */
    fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertNoFindings(layouts: List<TextLayoutResult>) {
        val findings = layouts.flatMap { layout ->
            val text = layout.layoutInput.text.text
            wordsBrokenAcrossLines(layout).map { "\"$text\" breaks \"$it\" across lines" } +
                listOfNotNull(if (isCut(layout)) "\"$text\" is cut (${layout.lineCount} line(s) shown)" else null)
        }
        if (findings.isNotEmpty()) fail(findings.joinToString(separator = "\n"))
    }

    private fun wordAround(text: String, index: Int): String {
        val start = (index - 1 downTo 0).firstOrNull { !text[it].isLetterOrDigit() }?.plus(1) ?: 0
        val end = (index until text.length).firstOrNull { !text[it].isLetterOrDigit() } ?: text.length
        return text.substring(start, end)
    }

    private val HAS_TEXT_LAYOUT = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
}
