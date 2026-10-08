package app.knotwork.android.domain.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [JsonNesting] — the bracket count that runs before an import is parsed. */
class JsonNestingTest {

    private fun nested(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

    @Test
    fun `given nesting at the limit when scanned then it passes, one level more does not`() {
        assertFalse(JsonNesting.exceeds(nested(JsonNesting.MAX_DEPTH)))
        assertTrue(JsonNesting.exceeds(nested(JsonNesting.MAX_DEPTH + 1)))
    }

    @Test
    fun `given objects and arrays mixed when scanned then both count as levels`() {
        assertTrue(JsonNesting.exceeds("""{"a":[{"b":[1]}]}""", maxDepth = 3))
        assertFalse(JsonNesting.exceeds("""{"a":[{"b":[1]}]}""", maxDepth = 4))
    }

    @Test
    fun `given brackets inside strings when scanned then they are not levels`() {
        val text = """{"text":"[[[[[[ {{{{ ]]"}"""

        assertFalse(JsonNesting.exceeds(text, maxDepth = 1))
    }

    @Test
    fun `given an escaped quote inside a string when scanned then the string does not end there`() {
        // The \" keeps the scanner inside the string, so the brackets after it are text.
        // One escaped quote, not a pair: a pair would hide a scanner that ignores escapes.
        val text = """{"text":"5\" [[[[ screen"}"""

        assertFalse(JsonNesting.exceeds(text, maxDepth = 1))
    }

    @Test
    fun `given siblings rather than nesting when scanned then depth does not accumulate`() {
        val text = "[" + "[],".repeat(1_000) + "[]]"

        assertFalse(JsonNesting.exceeds(text, maxDepth = 2))
    }

    @Test
    fun `given malformed text when scanned then it is left to the parser`() {
        assertFalse(JsonNesting.exceeds("]]]] not json {"))
    }
}
