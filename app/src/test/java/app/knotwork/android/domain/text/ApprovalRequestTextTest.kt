package app.knotwork.android.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [toDisplaySafeExcerpt] — the rule an approval applies to the request it shows
 * next to the call: display-safe like every quoted value, and cut on a word so
 * the excerpt does not end in half of one.
 */
class ApprovalRequestTextTest {

    @Test
    fun `given line breaks and a bidi override when excerpted then the request is one safe line`() {
        val raw = "Buy milk\nand eggs\n\nthen pay invoice\u202Efdp.exe"

        assertEquals("Buy milk and eggs then pay invoice fdp.exe", raw.toDisplaySafeExcerpt(maxLength = 120))
    }

    @Test
    fun `given a request within the limit when excerpted then it is unchanged`() {
        val request = "Remind me to call the dentist tomorrow at 4"

        assertEquals(request, request.toDisplaySafeExcerpt(maxLength = request.length))
    }

    @Test
    fun `given a request past the limit when excerpted then it ends on a whole word inside the limit`() {
        val raw = "Remind me to call the dentist tomorrow at four"

        val excerpt = raw.toDisplaySafeExcerpt(maxLength = 20)

        // "Remind me to call t" is the 19 characters that fit; the cut backs
        // off to the space before the half word.
        assertEquals("Remind me to call…", excerpt)
        assertTrue(excerpt.length <= 20)
    }

    @Test
    fun `given the limit falling right after a word when excerpted then that word is kept`() {
        assertEquals("one two…", "one two three".toDisplaySafeExcerpt(maxLength = 8))
    }

    @Test
    fun `given one long word when excerpted then it is cut at the limit rather than dropped`() {
        // Backing off to a space would leave almost nothing of a text with no
        // space in the part that fits — a long URL, or a script written without
        // spaces — so the cut stays at the limit.
        val excerpt = "see https://example.org/a/very/long/path/to/a/page".toDisplaySafeExcerpt(maxLength = 20)

        assertEquals("see https://example…", excerpt)
        assertEquals(20, excerpt.length)
    }

    @Test
    fun `given an emoji straddling the cut when excerpted then no half character is left`() {
        val raw = "abcdefgh\uD83D\uDE00tail"

        val excerpt = raw.toDisplaySafeExcerpt(maxLength = 10)

        assertEquals("abcdefgh…", excerpt)
        assertTrue(excerpt.none { it.isSurrogate() })
    }

    @Test
    fun `given a blank request when excerpted then the result is empty`() {
        assertEquals("", " \n\t ".toDisplaySafeExcerpt(maxLength = 40))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given a limit with no room beside the ellipsis when excerpted then it is refused`() {
        "anything".toDisplaySafeExcerpt(maxLength = 1)
    }
}
