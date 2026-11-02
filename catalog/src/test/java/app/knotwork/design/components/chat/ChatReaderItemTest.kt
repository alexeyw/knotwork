package app.knotwork.design.components.chat

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import app.knotwork.design.components.UNBROKEN_RUN_MAX
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the message reader cuts a message into list items, and which keys the items carry. */
class ChatReaderItemTest {

    private val settings = DefaultAnnotatorSettings(
        linkTextSpanStyle = TextLinkStyles(),
        codeSpanStyle = SpanStyle(),
        annotator = markdownAnnotator(),
    )

    private fun items(markdown: String, head: Boolean = false): List<ChatReaderItem> =
        markdownReaderItems(parseMarkdown(markdown) as State.Success, settings, TextStyle(), head)

    private fun codeFence(lines: Int, language: String = "kotlin"): String =
        "```$language\n" + (1..lines).joinToString("\n") { "line $it" } + "\n```"

    @Test
    fun `given plain text when cut then each line is an item that starts where the line does`() {
        val items = plainReaderItems("first\n\nthird")

        assertEquals(
            listOf(
                ChatReaderItem.PlainText(0, "first"),
                ChatReaderItem.PlainText(6, ""),
                ChatReaderItem.PlainText(7, "third"),
            ),
            items,
        )
    }

    @Test
    fun `given plain text ending in a line break when cut then its last item is an empty line`() {
        assertEquals(listOf("one", ""), plainReaderItems("one\n").map { (it as ChatReaderItem.PlainText).text })
        assertEquals(listOf(""), plainReaderItems("").map { (it as ChatReaderItem.PlainText).text })
    }

    @Test
    fun `given a plain line with a 5 000-character run when cut then its pieces are items of at most 2 000`() {
        val text = "look: " + "y".repeat(5_000) + "\nnext"

        val items = plainReaderItems(text).map { it as ChatReaderItem.PlainText }

        assertEquals(listOf(2_006, 2_000, 1_000, 4), items.map { it.text.length })
        assertEquals(listOf(0, 2_006, 4_006, 5_007), items.map { it.start })
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun `given Markdown blocks when cut then each top-level block is one item keyed by where it starts`() {
        val markdown = "# Plan\n\nFirst step.\n\n- one\n- two\n\n> a quote"

        val blocks = items(markdown).map { it as ChatReaderItem.Block }

        val starts = blocks.map { it.node.startOffset }
        assertEquals(starts.map { "b$it" }, blocks.map { it.key })
        assertTrue(0 in starts && markdown.indexOf("First") in starts && markdown.indexOf("- one") in starts)
    }

    @Test
    fun `given a paragraph with a 5 000-character run when cut then it becomes three pieces of its text`() {
        val run = "x".repeat(5_000)
        val markdown = "Intro.\n\nThe key is $run and that is all."

        val pieces = items(markdown).filterIsInstance<ChatReaderItem.ParagraphPiece>()

        assertEquals(3, pieces.size)
        assertEquals(listOf(0, 1, 2), pieces.map { it.index })
        assertTrue(pieces.all { it.text.length <= UNBROKEN_RUN_MAX + "The key is ".length })
        assertEquals("The key is $run and that is all.", pieces.joinToString("") { it.text.text })
        val start = markdown.indexOf("The key")
        assertEquals(listOf("p$start:0", "p$start:1", "p$start:2"), pieces.map { it.key })
    }

    @Test
    fun `given a paragraph of long prose when cut then it stays one block`() {
        val prose = "the model reads the context and answers with a plan ".repeat(400)

        assertTrue(items(prose).single() is ChatReaderItem.Block)
    }

    @Test
    fun `given a code block of 100 lines when cut then it is three chunks of 40, 40 and 20 lines`() {
        val chunks = items("Before.\n\n" + codeFence(100)).filterIsInstance<ChatReaderItem.CodeChunk>()

        assertEquals(listOf(40, 40, 20), chunks.map { it.code.lines().size })
        assertEquals("line 1", chunks.first().code.lines().first())
        assertEquals("line 100", chunks.last().code.lines().last())
        assertEquals(listOf(true, false, false), chunks.map { it.first })
        assertEquals(listOf(false, false, true), chunks.map { it.last })
        assertTrue(chunks.all { it.language == "kotlin" })
        assertEquals(3, chunks.map { it.key }.toSet().size)
    }

    @Test
    fun `given a code block of exactly 40 lines when cut then the renderer draws it as one block`() {
        assertTrue(items(codeFence(40)).single() is ChatReaderItem.Block)
    }

    @Test
    fun `given a code line of 5 000 characters when cut then each of its pieces counts as a line`() {
        val markdown = "```\n" + "z".repeat(5_000) + "\n" + (1..38).joinToString("\n") { "line $it" } + "\n```"

        val chunks = items(markdown).filterIsInstance<ChatReaderItem.CodeChunk>()

        // Three pieces of the long line and 38 more lines: 41 lines, so two chunks.
        assertEquals(listOf(40, 1), chunks.map { it.code.lines().size })
        assertEquals(listOf(2_000, 2_000, 1_000), chunks.first().code.lines().take(3).map { it.length })
        assertEquals(null, chunks.first().language)
    }

    @Test
    fun `given an indented code block of 50 lines when cut then it is chunked as a fence is`() {
        val markdown = "Before.\n\n" + (1..50).joinToString("\n") { "    line $it" }

        val chunks = items(markdown).filterIsInstance<ChatReaderItem.CodeChunk>()

        assertEquals(listOf(40, 10), chunks.map { it.code.lines().size })
        assertEquals("line 1", chunks.first().code.lines().first())
    }

    @Test
    fun `given a message longer than the head when the head and the whole are cut then the head's keys stay`() {
        val markdown = (1..60).joinToString("\n\n") { "Paragraph $it: " + "words to read ".repeat(20) }
        assertTrue(markdown.length > READER_HEAD_CHARS)

        val head = items(readerHead(markdown), head = true).map { it.key }
        val whole = items(markdown).map { it.key }

        assertTrue(head.isNotEmpty() && head.size < whole.size)
        assertEquals(head, whole.take(head.size))
    }

    @Test
    fun `given a head that cuts through its last paragraph when cut then it ends on the block before`() {
        val markdown = (1..60).joinToString("\n\n") { "Paragraph $it: " + "words to read ".repeat(20) }
        val head = readerHead(markdown)

        val blocks = items(head, head = true).map { (it as ChatReaderItem.Block).node }

        assertTrue(blocks.last().endOffset < head.length)
        val cut = items(head).map { (it as ChatReaderItem.Block).node }.last { it.endOffset >= head.length }
        assertTrue(blocks.none { it.startOffset == cut.startOffset })
    }

    @Test
    fun `given a head that is one long paragraph when cut then the paragraph is drawn as far as the head goes`() {
        val json = "{\"id\":1,\"name\":\"item\"},".repeat(2_000)
        val head = readerHead(json)

        val pieces = items(head, head = true).map { it as ChatReaderItem.ParagraphPiece }

        assertEquals(head, pieces.joinToString("") { it.text.text })
    }

    @Test
    fun `given a message when its head is taken then it is cut at the head's length, never inside a pair`() {
        assertEquals("short", readerHead("short"))
        assertEquals(READER_HEAD_CHARS, readerHead("a".repeat(READER_HEAD_CHARS + 10)).length)

        val emoji = "a".repeat(READER_HEAD_CHARS - 1) + "😀" + "tail"
        assertEquals(READER_HEAD_CHARS - 1, readerHead(emoji).length)
    }
}
