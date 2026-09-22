package app.knotwork.android.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException

class CloudErrorSanitizerTest {

    @Test
    fun `given a Google transport error when sanitized then the api key is gone`() {
        // Verbatim shape measured against a stalled stub: Google authenticates by query
        // parameter, so a plain socket timeout already carries the key.
        val raw = "Error from client: GoogleLLMClient\nMessage: Socket timeout has expired " +
            "[url=https://generativelanguage.googleapis.com/v1beta/models/" +
            "gemini-3-flash-preview:streamGenerateContent?alt=sse&key=AIzaSyREALSECRET123, " +
            "socket_timeout=60000] ms"

        val sanitized = CloudErrorSanitizer.sanitize(raw)

        assertFalse("the key must not survive", sanitized.contains("AIzaSyREALSECRET123"))
        assertTrue("the redaction must be visible", sanitized.contains("key=***"))
        assertTrue("the diagnosis must survive", sanitized.contains("Socket timeout has expired"))
        assertTrue("unrelated params must survive", sanitized.contains("alt=sse"))
    }

    @Test
    fun `given a bearer token when sanitized then only the token is removed`() {
        val sanitized = CloudErrorSanitizer.sanitize("401 Unauthorized (Authorization: Bearer sk-abc123XYZ)")

        assertFalse(sanitized.contains("sk-abc123XYZ"))
        assertTrue(sanitized.contains("Bearer ***"))
        assertTrue(sanitized.contains("401 Unauthorized"))
    }

    @Test
    fun `given assorted secret parameter names when sanitized then each value is masked`() {
        val sanitized = CloudErrorSanitizer.sanitize(
            "failed url=https://x/y?api_key=one&access_token=two&token=three&password=four&page=7",
        )

        listOf("one", "two", "three", "four").forEach {
            assertFalse("leaked $it", sanitized.contains(it))
        }
        assertTrue("non-secret params must survive", sanitized.contains("page=7"))
    }

    @Test
    fun `given a message with no secret when sanitized then it is unchanged`() {
        val raw = "Error from client: DeepSeekLLMClient\nMessage: Socket timeout has expired " +
            "[url=https://api.deepseek.com/chat/completions, socket_timeout=60000] ms"

        assertEquals(raw, CloudErrorSanitizer.sanitize(raw))
    }

    @Test
    fun `given Koog's doubled client prefix when sanitized then the repeat is dropped`() {
        // Verbatim from the reference device (airplane mode, Google): Koog's wrapper
        // prefixes "Error from client: <name>" to a message that already starts with it,
        // so the user's error card opened with the same line twice.
        val raw = "Error from client: GoogleLLMClient\n" +
            "Error from client: GoogleLLMClient\n" +
            "Message: Unable to resolve host \"generativelanguage.googleapis.com\": " +
            "No address associated with hostname"

        val sanitized = CloudErrorSanitizer.sanitize(raw)

        assertEquals(
            "Error from client: GoogleLLMClient\n" +
                "Message: Unable to resolve host \"generativelanguage.googleapis.com\": " +
                "No address associated with hostname",
            sanitized,
        )
    }

    @Test
    fun `given identical lines that are not adjacent when sanitized then both survive`() {
        // Only the adjacent repeat is noise; a line that recurs later in a longer
        // provider message may be carrying real structure.
        val raw = "attempt failed\nreason: timeout\nattempt failed"

        assertEquals(raw, CloudErrorSanitizer.sanitize(raw))
    }

    @Test
    fun `given a message trailing off into null when sanitized then the cause type replaces it`() {
        // Verbatim from the reference device: the provider dropped the connection
        // mid-answer, the underlying exception had no message, and the whole error card
        // read "Exception during streaming: null".
        val raw = "Error from client: OllamaClient\nMessage: Exception during streaming: null"

        val sanitized = CloudErrorSanitizer.sanitize(raw, causeName = "EOFException")

        assertFalse("the reader must not be shown the word null", sanitized.endsWith("null"))
        assertTrue(sanitized.endsWith("Exception during streaming: EOFException"))
    }

    @Test
    fun `given a JSON body containing null values when sanitized then they survive`() {
        // The counter-case: only a *trailing* null is noise. A provider's error body may
        // legitimately carry null fields, and rewriting those would corrupt the payload.
        val raw = """{"error":{"code":400,"finish_reason":null,"message":"bad request"}}"""

        assertEquals(raw, CloudErrorSanitizer.sanitize(raw, causeName = "EOFException"))
    }

    @Test
    fun `given a blank or null message when sanitized then a readable fallback is returned`() {
        assertEquals("Unknown error", CloudErrorSanitizer.sanitize(null))
        assertEquals("Unknown error", CloudErrorSanitizer.sanitize("   "))
    }

    @Test
    fun `given a wrapped provider failure when sanitize throwable then the key is masked and the cause named`() {
        val wrapper = RuntimeException(null, EOFException())
        val leaking = IllegalStateException("Socket timeout [url=https://x.googleapis.com/v1?alt=sse&key=AIzaSyA]")

        assertEquals(
            "Socket timeout [url=https://x.googleapis.com/v1?alt=sse&key=***]",
            CloudErrorSanitizer.sanitize(leaking),
        )
        assertEquals("EOFException", CloudErrorSanitizer.sanitize(wrapper))
    }

    @Test
    fun `given text with credentials when redactSecrets then only the secrets change`() {
        val raw = "GET https://h/x?api_key=abc&q=1 failed: Authorization: Bearer sk-1\nError: null"

        assertEquals(
            "GET https://h/x?api_key=***&q=1 failed: Authorization: Bearer ***\nError: null",
            CloudErrorSanitizer.redactSecrets(raw),
        )
    }

    @Test
    fun `given text without credentials when redactSecrets then it is returned unchanged`() {
        // Unlike sanitize, redaction must not rewrite repeated lines or a trailing
        // `null` — it runs over console lines and log records of every kind.
        val raw = "routing verdict: null\nrouting verdict: null"

        assertEquals(raw, CloudErrorSanitizer.redactSecrets(raw))
    }
}
