package app.knotwork.android.data.engine.retry

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.get
import ai.koog.http.client.ktor.KtorKoogHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [RetryAfterCapturingHttpClientFactory].
 *
 * The factory does not configure a client; it extends the one Koog's factory configures. So
 * besides recording the header, these pin that the extension loses nothing of Koog's setup —
 * the default headers that carry the API key, and the socket deadline that keeps a silent
 * provider from holding a step for fifteen minutes.
 */
class RetryAfterCapturingHttpClientFactoryTest {

    private val server = MockWebServer().apply { start() }
    private val slot = RetryAfterSlot()

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(headers: Map<String, String> = emptyMap(), socketTimeoutMillis: Long = 10_000): KoogHttpClient =
        RetryAfterCapturingHttpClientFactory(KtorKoogHttpClient.Factory(), slot).create(
            clientName = "test",
            baseUrl = server.url("/").toString(),
            headers = headers,
            requestTimeoutMillis = 10_000,
            connectTimeoutMillis = 10_000,
            socketTimeoutMillis = socketTimeoutMillis,
        )

    private fun answer(code: Int, retryAfter: String? = null): MockResponse =
        MockResponse.Builder().code(code).body("{}").apply { retryAfter?.let { addHeader("Retry-After", it) } }.build()

    private suspend fun failingGet(http: KoogHttpClient): Throwable {
        try {
            http.get<String>("x")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e
        }
        fail("expected the call to fail")
        error("unreachable")
    }

    @Test
    fun `given an error answer with Retry-After when called then the header is recorded`() = runBlocking {
        server.enqueue(answer(429, retryAfter = "7"))

        failingGet(client())

        assertEquals("7", slot.take())
        assertNull("a header is read once", slot.take())
    }

    @Test
    fun `given a successful answer with Retry-After when called then nothing is recorded`() = runBlocking {
        server.enqueue(answer(200, retryAfter = "7"))

        client().get<String>("x")

        assertNull(slot.take())
    }

    @Test
    fun `given default headers when called then they still reach the server`() = runBlocking {
        // The API key travels as one of these; losing them in the extension would send every
        // request unauthenticated.
        server.enqueue(answer(200))

        client(headers = mapOf("Authorization" to "Bearer sk-test")).get<String>("x")

        assertEquals("Bearer sk-test", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `given a server silent past the socket deadline when called then the call fails within it`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(5, TimeUnit.SECONDS).build())

        val started = System.nanoTime()
        val error = withTimeout(4.seconds) { failingGet(client(socketTimeoutMillis = 300)) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("the socket deadline was lost: waited $elapsedMs ms ($error)", elapsedMs < 3_000)
    }
}
