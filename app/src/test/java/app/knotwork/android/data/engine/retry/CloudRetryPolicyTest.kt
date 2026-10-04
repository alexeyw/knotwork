package app.knotwork.android.data.engine.retry

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.streaming.IncompleteStreamException
import app.knotwork.android.data.engine.retry.CloudRetryPolicy.RetryDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [CloudRetryPolicy]: which failures are retried, and after how long.
 */
class CloudRetryPolicyTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC)

    /** Three attempts, 100 ms base, 30 s ceiling, no jitter — so every backoff is exact. */
    private val policy = CloudRetryPolicy(
        maxAttempts = 3,
        initialDelay = 100.milliseconds,
        maxDelay = 30.seconds,
        jitterFactor = 0.0,
        clock = clock,
    )

    /** An HTTP failure the way Koog's OpenAI-shaped clients raise one: wrapped, with the status inside. */
    private fun http(status: Int, body: String): Throwable {
        val transport = KoogHttpClientException(clientName = "OpenAILLMClient", statusCode = status, errorBody = body)
        return LLMClientException(clientName = "OpenAILLMClient", message = transport.message, cause = transport)
    }

    @Test
    fun `given a retryable status when classified then it is retried`() {
        listOf(429, 500, 502, 503, 504, 529).forEach { status ->
            assertTrue("$status", CloudRetryPolicy.isRetryable(http(status, "{}")))
        }
    }

    @Test
    fun `given a 400 whose body mentions 500 when classified then the status decides and it is not retried`() {
        // Koog searched the whole message for "500" and retried this (measured on a local server).
        assertFalse(CloudRetryPolicy.isRetryable(http(400, """{"error":"max_tokens must be <= 500"}""")))
    }

    @Test
    fun `given a body that names a retryable keyword when the status is not retryable then it is not retried`() {
        assertFalse(CloudRetryPolicy.isRetryable(http(401, """{"error":"overloaded? rate limit? invalid key"}""")))
    }

    @Test
    fun `given a failure with no status when classified then Koog's text rules still decide`() {
        assertTrue(CloudRetryPolicy.isRetryable(RuntimeException("429 Too Many Requests")))
        assertTrue(CloudRetryPolicy.isRetryable(RuntimeException("connection reset by peer")))
        assertTrue(CloudRetryPolicy.isRetryable(IncompleteStreamException("cut")))
        assertFalse(CloudRetryPolicy.isRetryable(RuntimeException("401 invalid api key")))
        assertFalse(CloudRetryPolicy.isRetryable(RuntimeException()))
    }

    @Test
    fun `given a socket timeout when classified then it is not retried`() {
        // A retried socket timeout would mean three full 60 s waits instead of one.
        val socketTimeout = "Socket timeout has expired " +
            "[url=https://api.deepseek.com/chat/completions, socket_timeout=60000] ms"

        assertFalse(CloudRetryPolicy.isRetryable(RuntimeException(socketTimeout)))
    }

    @Test
    fun `given a Retry-After header within the ceiling when decided then the retry waits exactly that`() {
        assertEquals(
            RetryDecision.Retry(2.seconds),
            policy.decide(http(429, "{}"), attempt = 0, retryAfterHeader = "2"),
        )
    }

    @Test
    fun `given an HTTP-date header when decided then the wait runs until that date`() {
        val decision = policy.decide(http(503, "{}"), attempt = 0, retryAfterHeader = "Sun, 04 Oct 2026 12:00:07 GMT")

        assertEquals(RetryDecision.Retry(7.seconds), decision)
    }

    @Test
    fun `given both a header and a wait in the body when decided then the header wins`() {
        val error = http(429, """{"error":"Please try again in 9s"}""")

        assertEquals(RetryDecision.Retry(1.seconds), policy.decide(error, attempt = 0, retryAfterHeader = "1"))
    }

    @Test
    fun `given only a wait in the body when decided then it is honoured`() {
        val error = http(429, """{"error":"Please try again in 2.5s"}""")

        assertEquals(
            RetryDecision.Retry(2_500.milliseconds),
            policy.decide(error, attempt = 0, retryAfterHeader = null),
        )
    }

    @Test
    fun `given a requested wait over the ceiling when decided then it is not waited out`() {
        assertEquals(
            RetryDecision.WaitTooLong(45.seconds),
            policy.decide(http(429, """{"error":"retry after 45 seconds"}"""), attempt = 0, retryAfterHeader = null),
        )
        assertEquals(
            RetryDecision.WaitTooLong(62_500.milliseconds),
            policy.decide(http(429, """{"error":"try again in 1m2.5s"}"""), attempt = 0, retryAfterHeader = null),
        )
        assertEquals(
            RetryDecision.WaitTooLong(86_400.seconds),
            policy.decide(http(503, "{}"), attempt = 0, retryAfterHeader = "86400"),
        )
    }

    @Test
    fun `given a wait exactly at the ceiling when decided then it is waited`() {
        assertEquals(
            RetryDecision.Retry(30.seconds),
            policy.decide(http(429, "{}"), attempt = 0, retryAfterHeader = "30"),
        )
    }

    @Test
    fun `given no wait named when decided then the backoff doubles per retry up to the ceiling`() {
        val error = http(503, "{}")
        val long = CloudRetryPolicy(
            maxAttempts = 10,
            initialDelay = 10.seconds,
            maxDelay = 30.seconds,
            jitterFactor = 0.0,
            clock = clock,
        )

        assertEquals(RetryDecision.Retry(100.milliseconds), policy.decide(error, attempt = 0, retryAfterHeader = null))
        assertEquals(RetryDecision.Retry(200.milliseconds), policy.decide(error, attempt = 1, retryAfterHeader = null))
        assertEquals(RetryDecision.Retry(30.seconds), long.decide(error, attempt = 3, retryAfterHeader = null))
    }

    @Test
    fun `given the last attempt failed when decided then it gives up whatever the provider asked`() {
        assertEquals(RetryDecision.GiveUp, policy.decide(http(429, "{}"), attempt = 2, retryAfterHeader = "1"))
        assertEquals(RetryDecision.GiveUp, policy.decide(http(429, "{}"), attempt = 2, retryAfterHeader = "99"))
    }

    @Test
    fun `given a failure that is not retryable when decided then a named wait does not make it so`() {
        assertEquals(RetryDecision.GiveUp, policy.decide(http(401, "{}"), attempt = 0, retryAfterHeader = "1"))
    }

    @Test
    fun `given jitter when decided then the wait never falls below the backoff and stays within its fraction`() {
        val jittery = CloudRetryPolicy(maxAttempts = 3, initialDelay = 1.seconds, maxDelay = 30.seconds, clock = clock)

        repeat(50) {
            val delay = (
                jittery.decide(
                    http(503, "{}"),
                    attempt = 0,
                    retryAfterHeader = null,
                ) as RetryDecision.Retry
                ).delay
            assertTrue("$delay", delay >= 1.seconds && delay <= 1_200.milliseconds)
        }
    }
}
