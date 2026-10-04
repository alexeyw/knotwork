package app.knotwork.android.data.engine

import ai.koog.http.client.KoogHttpClient
import app.knotwork.android.domain.models.CloudProvider
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [OpenAiCompatibleClients]: the addresses, paths, headers and deadlines the
 * three OpenAI-compatible providers are built with. The wire itself is in
 * [OpenAiCompatibleServerEndToEndTest].
 */
class OpenAiCompatibleClientsTest {

    /** What one `create` call was asked for. */
    private data class Created(val baseUrl: String, val headers: Map<String, String>, val timeouts: List<Long>)

    /** A transport that records what it is asked to build and builds nothing real. */
    private class RecordingFactory : KoogHttpClient.Factory {
        val created = mutableListOf<Created>()

        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json,
        ): KoogHttpClient {
            created +=
                Created(baseUrl, headers, listOf(requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis))
            return mockk(relaxed = true)
        }
    }

    private val sharedDeadlines = with(CloudClientTimeouts.CONFIG) {
        listOf(requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis)
    }

    @Test
    fun `given OpenRouter and Groq when configured then they use their fixed address and Koog's default paths`() {
        val openRouter = OpenAiCompatibleClients.settings(CloudProvider.OPENROUTER, null, CloudClientTimeouts.CONFIG)
        val groq = OpenAiCompatibleClients.settings(CloudProvider.GROQ, "ignored", CloudClientTimeouts.CONFIG)

        assertEquals("https://openrouter.ai/api", openRouter.baseUrl)
        assertEquals("v1/chat/completions", openRouter.chatCompletionsPath)
        assertEquals("https://api.groq.com/openai", groq.baseUrl)
        assertEquals("v1/models", groq.modelsPath)
    }

    @Test
    fun `given a server the user runs when configured then its paths are relative to the v1 address`() {
        // Koog's defaults appended to an address ending in /v1 would make /v1/v1/… (measured).
        val own = OpenAiCompatibleClients.settings(
            CloudProvider.OPENAI_COMPATIBLE,
            "http://192.168.1.20:8000/v1",
            CloudClientTimeouts.CONFIG,
        )

        assertEquals("http://192.168.1.20:8000/v1", own.baseUrl)
        assertEquals("chat/completions", own.chatCompletionsPath)
        assertEquals("models", own.modelsPath)
    }

    @Test
    fun `given a provider not reached through the OpenAI API when configured then it is refused`() {
        try {
            OpenAiCompatibleClients.settings(CloudProvider.ANTHROPIC, null, CloudClientTimeouts.CONFIG)
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `given a key when built then the transport carries it as a Bearer token with the shared deadlines`() {
        val http = RecordingFactory()

        assertNotNull(OpenAiCompatibleClients.client(CloudProvider.GROQ, "gsk-test", null, http))

        val created = http.created.single()
        assertEquals("Bearer gsk-test", created.headers["Authorization"])
        assertEquals(sharedDeadlines, created.timeouts)
    }

    @Test
    fun `given no key when built then no Authorization header is sent, and the deadlines still apply`() {
        // Koog's convenience constructor would send "Authorization: Bearer" with nothing after it.
        val http = RecordingFactory()

        OpenAiCompatibleClients.client(CloudProvider.OPENAI_COMPATIBLE, null, "http://192.168.1.20:8000/v1", http)

        val created = http.created.single()
        assertFalse("headers: ${created.headers}", created.headers.keys.any { it.equals("Authorization", true) })
        assertEquals("http://192.168.1.20:8000/v1", created.baseUrl)
        assertEquals(sharedDeadlines, created.timeouts)
    }
}
