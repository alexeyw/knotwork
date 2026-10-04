package app.knotwork.android.data.engine.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [RetryAfterHint]: every form a provider uses to say how long to wait.
 */
class RetryAfterHintTest {

    private val now = Instant.parse("2026-10-04T12:00:00Z")

    @Test
    fun `given a number of seconds in the header when read then it is the wait`() {
        assertEquals(2.seconds, RetryAfterHint.fromHeader("2", now))
        assertEquals(2.seconds, RetryAfterHint.fromHeader(" 2 ", now))
    }

    @Test
    fun `given an HTTP date in the header when read then the wait runs until it`() {
        assertEquals(5.seconds, RetryAfterHint.fromHeader("Sun, 04 Oct 2026 12:00:05 GMT", now))
    }

    @Test
    fun `given an HTTP date already past when read then the wait is zero`() {
        assertEquals(Duration.ZERO, RetryAfterHint.fromHeader("Sun, 04 Oct 2026 11:59:00 GMT", now))
    }

    @Test
    fun `given no header, a blank one, garbage or a negative number when read then there is no wait`() {
        assertNull(RetryAfterHint.fromHeader(null, now))
        assertNull(RetryAfterHint.fromHeader("  ", now))
        assertNull(RetryAfterHint.fromHeader("soon", now))
        assertNull(RetryAfterHint.fromHeader("-3", now))
    }

    @Test
    fun `given the phrases Koog read when read from text then each gives its seconds`() {
        assertEquals(3.seconds, RetryAfterHint.fromText("Please retry after 3 seconds"))
        assertEquals(4.seconds, RetryAfterHint.fromText("retry-after: 4"))
        assertEquals(5.seconds, RetryAfterHint.fromText("Wait 5 seconds and try"))
        assertEquals(2.seconds, RetryAfterHint.fromText("Rate limit reached. Please try again in 2s."))
    }

    @Test
    fun `given Go durations after try again in when read from text then each part counts`() {
        // Groq writes its waits this way; Koog did not recognise the minute form at all.
        assertEquals(62_500.milliseconds, RetryAfterHint.fromText("Please try again in 1m2.5s."))
        assertEquals(7_660.milliseconds, RetryAfterHint.fromText("Please try again in 7.66s."))
        assertEquals(750.milliseconds, RetryAfterHint.fromText("try again in 750ms"))
        assertEquals(3_600.seconds, RetryAfterHint.fromText("try again in 1h0m0s"))
    }

    @Test
    fun `given text naming no wait, or a malformed duration when read then there is no wait`() {
        assertNull(RetryAfterHint.fromText("max_tokens must be <= 500 for this model"))
        assertNull(RetryAfterHint.fromText("try again in 5x3s"))
    }

    @Test
    fun `given a wait named only in a wrapped cause when read from the chain then it is found`() {
        val error = RuntimeException("outer", IllegalStateException("Rate limit reached. Please try again in 2s."))

        assertEquals(2.seconds, RetryAfterHint.fromMessages(error))
    }

    @Test
    fun `given a cause chain that loops when walked then it ends`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)

        assertEquals(listOf(first, second), causeChain(first))
    }
}
