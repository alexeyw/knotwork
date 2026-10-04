package app.knotwork.android.data.engine

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ModelHopRule]: where one request of a model client may go.
 */
class ModelHopRuleTest {

    private val approved = setOf("http://192.168.1.20:8000")
    private val open = ModelHopRule(approved, localOnly = false)
    private val restricted = ModelHopRule(approved, localOnly = true)

    @Test
    fun `given an https address when judged then it may be sent`() {
        assertNull(open.refusal("https://api.groq.com/openai/v1/chat/completions"))
    }

    @Test
    fun `given an approved private unencrypted address when judged then it may be sent`() {
        assertNull(open.refusal("http://192.168.1.20:8000/v1/chat/completions"))
    }

    @Test
    fun `given an unencrypted public address when judged then it is refused, naming the host`() {
        val refusal = open.refusal("http://203.0.113.7/v1/chat/completions")

        assertTrue(refusal, refusal.orEmpty().contains("203.0.113.7"))
        assertTrue(refusal, refusal.orEmpty().contains("https://"))
    }

    @Test
    fun `given an unencrypted private address that was not approved when judged then it is refused`() {
        // Consent is per origin: approving one LAN server does not let it hand the request
        // to another one over plain HTTP.
        val refusal = open.refusal("http://192.168.1.30:8000/v1/chat/completions")

        assertTrue(refusal, refusal.orEmpty().contains("http://192.168.1.30:8000"))
        assertTrue(refusal, refusal.orEmpty().contains("not been approved"))
    }

    @Test
    fun `given the restriction on and a public https host when judged then it is refused`() {
        val refusal = restricted.refusal("https://evil.example/collect")

        assertTrue(refusal, refusal.orEmpty().contains("evil.example"))
        assertTrue(refusal, refusal.orEmpty().contains("Block network from local model"))
    }

    @Test
    fun `given the restriction on and a local address when judged then it may be sent`() {
        assertNull(restricted.refusal("http://192.168.1.20:8000/v1/chat/completions"))
        assertNull(restricted.refusal("https://192.168.1.21/v1/chat/completions"))
    }

    @Test
    fun `given a refusal when worded then it carries no retry keyword`() {
        // The retry policy judges a failure without an HTTP status by Koog's text rules; a
        // refusal that read as "connection refused" would be tried again, pointlessly.
        listOf(
            open.refusal("http://203.0.113.7/"),
            open.refusal("http://192.168.1.30:8000/"),
            restricted.refusal("https://evil.example/"),
        ).forEach { refusal ->
            val retried = ai.koog.prompt.executor.clients.retry.RetryConfig.DEFAULT_PATTERNS
                .any { it.matches(refusal.orEmpty()) }
            assertTrue("would be retried: $refusal", !retried)
        }
    }
}
