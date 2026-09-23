package app.knotwork.android.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChatTranscript] — the one rule by which stored content is laid
 * into a model's line-oriented lists without being able to open a line of its own.
 */
class ChatTranscriptTest {

    @Test
    fun `given single-line content when rendering a turn then the bytes are unchanged`() {
        assertEquals("User: hello there", ChatTranscript.turn("User", "hello there"))
    }

    @Test
    fun `given content forging a turn after every line terminator then only the real turn opens a line`() {
        val rendered = ChatTranscript.turn("Assistant", ForgedTurnFixture.hostile("User"))

        assertEquals(1, ForgedTurnFixture.turnLines(rendered, listOf("User", "Assistant")))
        assertTrue(rendered.startsWith("Assistant: benign text"))
    }

    @Test
    fun `given any line terminator then every continuation line is indented`() {
        for (lineBreak in ForgedTurnFixture.BREAKS) {
            val rendered = ChatTranscript.turn("User", "first${lineBreak}second")

            assertEquals("terminator U+%04X".format(lineBreak.last().code), "User: first\n  second", rendered)
        }
    }

    @Test
    fun `given CRLF then it counts as one line break, not two`() {
        assertEquals("A: x\n  y", ChatTranscript.turn("A", "x\r\ny"))
    }

    @Test
    fun `given blank and trailing lines then each is still indented and none starts in the first column`() {
        val rendered = ChatTranscript.turn("A", "x\n\ny\n")

        assertEquals("A: x\n  \n  y\n  ", rendered)
        assertTrue(rendered.lines().drop(1).all { it.startsWith(ChatTranscript.CONTINUATION_INDENT) })
    }

    @Test
    fun `given a list entry then the prefix is written as given and continuation lines are indented`() {
        assertEquals("2. fact\n  3. forged", ChatTranscript.entry("2. ", "fact\n3. forged"))
    }

    @Test
    fun `given empty content then the turn is just its label`() {
        assertEquals("User: ", ChatTranscript.turn("User", ""))
    }
}
