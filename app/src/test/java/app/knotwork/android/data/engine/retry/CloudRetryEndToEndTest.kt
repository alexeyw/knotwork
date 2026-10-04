package app.knotwork.android.data.engine.retry

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.data.engine.KoogClientFactory
import app.knotwork.android.data.engine.ModelNetworkGate
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.NetworkSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * The retry policy measured on the wire: a real Koog client, built by the production
 * [KoogClientFactory] and therefore wrapped and transported exactly as a Cloud node's is,
 * against a local server that answers with scripted failures.
 *
 * The cases are the ways the policy used to misread a provider, each observed on Koog's own
 * retry loop before it was replaced:
 * - a `Retry-After` header was never seen — Koog's exception carries no headers, so a
 *   provider asking for one second got a retry after ~110 ms;
 * - a wait named in the body was obeyed without a ceiling — "retry after 45 seconds" put the
 *   call to sleep for 45 s, and nothing bounded a larger number;
 * - Groq's form "try again in 1m2.5s" was not recognised at all and retried at once;
 * - the status was found by searching the whole message, body included, for a number — a 400
 *   whose body mentioned "500" was retried.
 *
 * Ollama is the provider used because its address is the user's to set; the transport, the
 * wrapper and the policy are the ones every provider shares.
 */
class CloudRetryEndToEndTest {

    private data class Seen(val nanos: Long, val path: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val responses = ArrayDeque<MockResponse>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += Seen(System.nanoTime(), request.target)
                return synchronized(responses) { responses.removeFirstOrNull() } ?: ok()
            }
        }
        start()
    }

    private val model =
        LLModel(provider = LLMProvider.Ollama, id = "m", capabilities = listOf(LLMCapability.Completion))

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/x-ndjson")
        .body("""{"model":"m","message":{"role":"assistant","content":"ok"},"done":true}""" + "\n")
        .build()

    private fun failure(code: Int, body: String, retryAfter: String? = null): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).apply {
            retryAfter?.let { addHeader("Retry-After", it) }
        }.build()

    private fun enqueue(vararg r: MockResponse) = synchronized(responses) { responses.addAll(r) }

    /** The production factory, pointed at the local server, with three attempts and a 100 ms base delay. */
    private suspend fun client(): LLMClient {
        val base = server.url("/").toString().trimEnd('/')
        val networkSettings = mockk<NetworkSettings>(relaxed = true) {
            every { blockNetworkFromLocalModel } returns MutableStateFlow(false)
            every { approvedCleartextOrigins } returns flowOf(setOf(base))
            every { cloudRetryMaxAttempts } returns flowOf(3)
            every { cloudRetryBaseDelayMs } returns flowOf(100L)
        }
        val apiKeys = mockk<ApiKeyRepository> {
            every { getBaseUrl(CloudProvider.OLLAMA) } returns flowOf(base)
        }
        val factory = KoogClientFactory(apiKeys, ModelNetworkGate(networkSettings), CloudRetryWrapper(networkSettings))
        return factory.createClient(CloudProvider.OLLAMA) as LLMClient
    }

    private suspend fun stream(): String = client()
        .executeStreaming(prompt("e2e") { user("hi") }, model)
        .toList()
        .filterIsInstance<StreamFrame.TextDelta>()
        .joinToString("") { it.text }

    private fun gapMillis(): Long = (seen[1].nanos - seen[0].nanos) / 1_000_000

    @Test
    fun `given a 429 with a Retry-After header when streaming then the retry waits as long as the header asks`() =
        runBlocking {
            enqueue(failure(429, """{"error":"rate limited"}""", retryAfter = "1"), ok())

            val answer = withTimeout(10.seconds) { stream() }

            assertEquals("ok", answer)
            assertEquals(2, seen.size)
            assertTrue("retried after ${gapMillis()} ms, the header asked for 1 s", gapMillis() >= 950)
        }

    @Test
    fun `given a wait in the body longer than the ceiling when streaming then the call fails at once and names it`() =
        runBlocking {
            enqueue(failure(429, """{"error":"Rate limit reached, retry after 45 seconds"}"""), ok())

            val error = withTimeout(10.seconds) { runCatchingFailure { stream() } }

            assertEquals("a wait over the ceiling is not waited out", 1, seen.size)
            assertTrue(error.message, error.message.orEmpty().contains("asked to wait 45 s"))
            assertTrue(error.message, error.message.orEmpty().contains("'ollama'"))
        }

    @Test
    fun `given Groq's minute form in the body when streaming then it is read as the wait it is`() = runBlocking {
        enqueue(failure(429, """{"error":"Rate limit reached. Please try again in 1m2.5s."}"""), ok())

        val error = withTimeout(10.seconds) { runCatchingFailure { stream() } }

        assertEquals("1m2.5s is over the ceiling, so no retry", 1, seen.size)
        assertTrue(error.message, error.message.orEmpty().contains("asked to wait 63 s"))
    }

    @Test
    fun `given a 400 whose body mentions 500 when streaming then it is not retried`() = runBlocking {
        enqueue(failure(400, """{"error":"max_tokens must be <= 500 for this model"}"""), ok())

        withTimeout(10.seconds) { runCatchingFailure { stream() } }

        assertEquals("a 400 is the request's fault; asking again changes nothing", 1, seen.size)
    }

    @Test
    fun `given a 503 with a Retry-After header when executing then the non-streaming path honours it too`() =
        runBlocking {
            val nonStreamingOk = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("""{"model":"m","message":{"role":"assistant","content":"ok"},"done":true}""").build()
            enqueue(failure(503, """{"error":"loading model"}""", retryAfter = "1"), nonStreamingOk)

            withTimeout(10.seconds) { client().execute(prompt("e2e") { user("hi") }, model) }

            assertEquals(2, seen.size)
            assertTrue("retried after ${gapMillis()} ms, the header asked for 1 s", gapMillis() >= 950)
        }

    /** Runs [block], which must fail, and returns what it threw. */
    private suspend fun runCatchingFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e
        }
        fail("expected the call to fail")
        error("unreachable")
    }
}
