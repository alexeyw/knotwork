package app.knotwork.android.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [toDisplaySafe] — the one rule every importer uses to quote a file back into
 * the app's own sentence: every kind of line break, control and bidi character
 * out, whitespace collapsed, length clamped without splitting a character.
 */
class ImportedTextTest {

    @Test
    fun `given every kind of line break when made display safe then the value is one line`() {
        val raw = "a\n\nb\rc\u2028d\u2029e\u0085f\u000Bg\u000Ch"

        assertEquals("a b c d e f g h", raw.toDisplaySafe())
    }

    @Test
    fun `given a bidi override when made display safe then it cannot reorder what follows`() {
        val raw = "invoice\u202Efdp.exe and \u2066isolated\u2069 text"

        val safe = raw.toDisplaySafe()

        assertTrue(safe.none { it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' })
        assertEquals("invoice fdp.exe and isolated text", safe)
    }

    @Test
    fun `given whitespace runs and padding when made display safe then they collapse and trim`() {
        assertEquals("one two", "  one \t\t  two  ".toDisplaySafe())
    }

    @Test
    fun `given a value within the limit when made display safe then it is unchanged`() {
        assertEquals("Daily digest", "Daily digest".toDisplaySafe())
        assertEquals("x".repeat(60), "x".repeat(60).toDisplaySafe())
    }

    @Test
    fun `given a value past the limit when made display safe then it is cut with an ellipsis inside the limit`() {
        val safe = "x".repeat(4_000).toDisplaySafe(maxLength = 10)

        assertEquals("xxxxxxxxx…", safe)
        assertEquals(10, safe.length)
    }

    @Test
    fun `given an emoji straddling the cut when made display safe then no half character is left`() {
        // "abcdefgh" + U+1F600 (a surrogate pair) + more: the cut at 9 lands between the halves.
        val raw = "abcdefgh\uD83D\uDE00tail"

        val safe = raw.toDisplaySafe(maxLength = 10)

        assertEquals("abcdefgh…", safe)
        assertTrue(safe.none { it.isSurrogate() })
    }

    @Test
    fun `given a blank value when made display safe then it is empty`() {
        assertEquals("", " \n\t ".toDisplaySafe())
    }

    @Test
    fun `given no ellipsis when a stored name is cut then it is cut at the limit and nothing is added`() {
        assertEquals("abcde", "abcde fghij".toDisplaySafe(maxLength = 5, ellipsis = ""))
        assertEquals("abcd", "abcd efghij".toDisplaySafe(maxLength = 5, ellipsis = ""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given a limit with no room for the ellipsis when made display safe then it is refused`() {
        "abc".toDisplaySafe(maxLength = 1)
    }
}
