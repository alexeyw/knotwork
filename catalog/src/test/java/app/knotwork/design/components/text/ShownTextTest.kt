package app.knotwork.design.components.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import app.knotwork.design.components.UNBROKEN_RUN_MAX
import app.knotwork.design.components.unbrokenRunCuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What [shownText] hands a text to lay out, for each of its three rules. */
class ShownTextTest {

    private fun blob(n: Int): String = buildString(n) { repeat(n) { append(('a' + it % 26)) } }

    private fun prose(n: Int): String = "word ".repeat(n / 5 + 1).substring(0, n)

    @Test
    fun `given ordinary text when shown then it is one piece, unchanged, with nothing left out`() {
        val text = "The model answered in two short sentences."

        listOf(Int.MAX_VALUE, 1, 3).forEach { maxLines ->
            val shown = shownText(text, maxLines, cap = TEXT_MAX_CHARS)
            assertEquals(listOf(text), shown.pieces)
            assertEquals(0, shown.omitted)
        }
    }

    @Test
    fun `given a line limit when the text is longer than its lines can hold then it is cut to them`() {
        val shown = shownText(blob(200_000), maxLines = 2, cap = TEXT_MAX_CHARS)

        assertEquals(1, shown.pieces.size)
        assertEquals(2 * LINE_CHARS_MAX, shown.pieces.single().length)
        // An ellipsis already says the text goes on; no count line is added.
        assertEquals(0, shown.omitted)
    }

    @Test
    fun `given a line limit when the text fits its lines then it is not cut`() {
        val text = blob(2 * LINE_CHARS_MAX)

        assertEquals(text, shownText(text, maxLines = 2, cap = TEXT_MAX_CHARS).pieces.single())
    }

    @Test
    fun `given no line limit when a run passes the maximum then it continues in the next piece`() {
        val text = blob(5_000)

        val shown = shownText(text, Int.MAX_VALUE, cap = TEXT_MAX_CHARS)

        assertEquals(text, shown.pieces.joinToString(""))
        assertTrue(shown.pieces.size > 1)
        shown.pieces.forEach { assertTrue(unbrokenRunCuts(it).isEmpty()) }
        assertEquals(0, shown.omitted)
    }

    @Test
    fun `given no line limit when the text passes the cap then the rest is counted, not drawn`() {
        val text = prose(50_000)

        val shown = shownText(text, Int.MAX_VALUE, cap = TEXT_MAX_CHARS)

        assertEquals(text.substring(0, TEXT_MAX_CHARS), shown.pieces.joinToString(""))
        assertEquals(50_000 - TEXT_MAX_CHARS, shown.omitted)
    }

    @Test
    fun `given no cap when the text is long then every character is drawn, in pieces`() {
        val text = blob(50_000)

        val shown = shownText(text, Int.MAX_VALUE, cap = null)

        assertEquals(text, shown.pieces.joinToString(""))
        assertEquals(0, shown.omitted)
        shown.pieces.forEach { assertTrue(it.length <= UNBROKEN_RUN_MAX) }
    }

    @Test
    fun `given a surrogate pair at the cut when cut then the pair is kept whole`() {
        val emoji = "😀"
        val atCap = blob(TEXT_MAX_CHARS - 1) + emoji + "tail"
        val atLines = blob(LINE_CHARS_MAX - 1) + emoji + blob(LINE_CHARS_MAX)

        val capped = shownText(atCap, Int.MAX_VALUE, cap = TEXT_MAX_CHARS)
        val lined = shownText(atLines, maxLines = 1, cap = TEXT_MAX_CHARS)

        assertEquals(TEXT_MAX_CHARS - 1, capped.pieces.joinToString("").length)
        assertEquals(atCap.length - (TEXT_MAX_CHARS - 1), capped.omitted)
        assertEquals(LINE_CHARS_MAX - 1, lined.pieces.single().length)
    }

    @Test
    fun `given a styled text when cut then each piece keeps its part of every style`() {
        val bold = SpanStyle(fontWeight = FontWeight.Bold)
        val text: AnnotatedString = buildAnnotatedString {
            append(blob(1_500))
            withStyle(bold) { append(blob(1_500)) }
        }

        val shown = shownText(text, Int.MAX_VALUE, cap = TEXT_MAX_CHARS)

        assertEquals(2, shown.pieces.size)
        assertEquals(text.text, shown.pieces.joinToString("") { it.text })
        // The cut falls 500 characters into the bold span: both pieces carry part of it.
        shown.pieces.forEach { piece -> assertTrue(piece.spanStyles.any { it.item == bold }) }
    }

    @Test
    fun `given a styled text with a line limit when long then it is cut to its lines`() {
        val text = AnnotatedString(blob(50_000))

        val shown = shownText(text, maxLines = 3, cap = TEXT_MAX_CHARS)

        assertEquals(3 * LINE_CHARS_MAX, shown.pieces.single().length)
    }
}
