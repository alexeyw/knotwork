package app.knotwork.android.data.engine

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.get
import ai.koog.http.client.ktor.KtorKoogHttpClient
import app.knotwork.android.data.engine.retry.RetryAfterSlot
import app.knotwork.android.data.engine.retry.causeChain
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
 * Unit tests for [KoogTransportFactory].
 *
 * The factory does not configure a client; it extends the one Koog's factory configures. So
 * besides its two checks — every hop against [ModelHopRule], redirects included, and the
 * `Retry-After` of an error answer — these pin that the extension loses nothing of Koog's
 * setup: the default headers that carry the API key, and the socket deadline that keeps a
 * silent provider from holding a step for fifteen minutes.
 */
class KoogTransportFactoryTest {

    private val server = MockWebServer().apply { start() }
    private val slot = RetryAfterSlot()

    /** The local server's origin, approved for unencrypted traffic as a user's LAN server would be. */
    private fun origin(): String = server.url("/").toString().trimEnd('/')

    /** A second local server: another origin, never approved. */
    private val elsewhere = MockWebServer().apply { start() }

    @After
    fun tearDown() {
        server.close()
        elsewhere.close()
    }

    private fun redirect(to: String): MockResponse = MockResponse.Builder().code(302).addHeader("Location", to).build()

    private fun client(headers: Map<String, String> = emptyMap(), socketTimeoutMillis: Long = 10_000): KoogHttpClient =
        KoogTransportFactory(
            KtorKoogHttpClient.Factory(),
            slot,
            ModelHopRule(setOf(origin()), localOnly = false),
        ).create(
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

    @Test
    fun `given a redirect to an unencrypted address that was not approved when called then it is never sent`() =
        runBlocking {
            // The approved LAN server must not be able to hand the prompt to another one.
            server.enqueue(redirect(elsewhere.url("/v1/chat/completions").toString()))

            val error = withTimeout(15.seconds) { failingGet(client()) }

            assertTrue("$error", causeChain(error).any { it is HopRefusedException })
            assertEquals("the redirect target received the request", 0, elsewhere.requestCount)
        }

    @Test
    fun `given a redirect to an unencrypted public address when called then it is refused before sending`() =
        runBlocking {
            server.enqueue(redirect("http://203.0.113.7/v1/chat/completions"))

            val error = withTimeout(15.seconds) { failingGet(client()) }

            // Coroutines may copy an exception to recover its stack trace, so the refusal can
            // appear more than once in the chain; any copy carries the same sentence.
            val refusal = causeChain(error).filterIsInstance<HopRefusedException>().first()
            assertTrue(refusal.message, refusal.message.orEmpty().contains("203.0.113.7"))
        }

    @Test
    fun `given a redirect within the approved origin when called then it is followed`() = runBlocking {
        server.enqueue(redirect(server.url("/v1/moved").toString()))
        server.enqueue(answer(200))

        client().get<String>("x")

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `given the first request to an address the rule refuses when called then nothing is sent`() = runBlocking {
        val refusing = KoogTransportFactory(
            KtorKoogHttpClient.Factory(),
            slot,
            ModelHopRule(emptySet(), localOnly = false),
        )
            // Short deadlines: if the request were sent, the test fails fast instead of waiting
            // on a server that has nothing queued.
            .create(
                clientName = "test",
                baseUrl = server.url("/").toString(),
                requestTimeoutMillis = 2_000,
                connectTimeoutMillis = 2_000,
                socketTimeoutMillis = 2_000,
            )

        val error = withTimeout(5.seconds) { failingGet(refusing) }

        assertTrue("$error", causeChain(error).any { it is HopRefusedException })
        assertEquals(0, server.requestCount)
    }
}
