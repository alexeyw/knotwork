package app.knotwork.design.components

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where [unbrokenRunCuts] cuts a text, and what the pieces keep. */
class UnbrokenRunCutsTest {

    @Test
    fun `given prose of any length when cut then it stays whole`() {
        val prose = "the model reads the context and answers ".repeat(5_000)

        assertTrue(unbrokenRunCuts(prose).isEmpty())
        assertEquals(listOf(prose), prose.splitAtUnbrokenRuns())
    }

    @Test
    fun `given a run of 5 000 characters when cut at 2 000 then it is cut twice inside the run`() {
        val text = "before " + "x".repeat(5_000) + " after"

        // The run starts at 7: cuts at 7 + 2 000 and 7 + 4 000; the prose stays with its piece.
        assertEquals(listOf(2_007, 4_007), unbrokenRunCuts(text))
        assertEquals(listOf(2_007, 2_000, 1_006), text.splitAtUnbrokenRuns().map { it.length })
        assertEquals(text, text.splitAtUnbrokenRuns().joinToString(""))
    }

    @Test
    fun `given a run of exactly the maximum when cut then it stays whole`() {
        assertTrue(unbrokenRunCuts("y".repeat(2_000)).isEmpty())
        assertEquals(listOf(2_000), unbrokenRunCuts("y".repeat(2_001)))
    }

    @Test
    fun `given minified JSON with hyphens, slashes and zero-width spaces when cut then none of them breaks the run`() {
        // Measured: none of these keeps layout linear, so none may reset the count.
        val json = "{\"url\":\"https://example.org/a-b/c-d\",​\"id\":1},".repeat(200)

        assertEquals(json.length / 2_000, unbrokenRunCuts(json).size)
    }

    @Test
    fun `given a non-breaking space in a run when cut then it does not break the run`() {
        val text = "a".repeat(1_500) + " " + "b".repeat(1_500)

        assertEquals(listOf(2_000), unbrokenRunCuts(text))
    }

    @Test
    fun `given a long Chinese or Japanese paragraph when cut then it stays whole`() {
        // Measured linear: 200 000 Han characters lay out in 56 ms, kana in 24 ms.
        val han = "模型读取上下文并给出三个步骤的计划。".repeat(500)
        val kana = "モデルはコンテキストを読みました。".repeat(500)

        assertTrue(unbrokenRunCuts(han).isEmpty())
        assertTrue(unbrokenRunCuts(kana).isEmpty())
    }

    @Test
    fun `given a run of characters outside the basic plane when cut then no cut splits a surrogate pair`() {
        val emoji = "😀".repeat(1_500)

        val cuts = unbrokenRunCuts(emoji)

        assertEquals(listOf(2_000), cuts)
        cuts.forEach { assertTrue(!Character.isLowSurrogate(emoji[it])) }
        assertEquals(emoji, emoji.splitAtUnbrokenRuns().joinToString(""))
    }

    @Test
    fun `given a styled text with a link across a cut when split then each piece keeps its part of both`() {
        val styled = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("z".repeat(1_990)) }
            withLink(LinkAnnotation.Url("https://example.org")) { append("q".repeat(20)) }
            append("w".repeat(500))
        }

        val pieces = styled.splitAtUnbrokenRuns()

        assertEquals(listOf(2_000, 510), pieces.map { it.length })
        assertEquals(1, pieces[0].spanStyles.size)
        assertEquals(1, pieces[0].getLinkAnnotations(0, pieces[0].length).size)
        assertEquals(1, pieces[1].getLinkAnnotations(0, pieces[1].length).size)
        assertEquals(styled.text, pieces.joinToString("") { it.text })
    }
}
