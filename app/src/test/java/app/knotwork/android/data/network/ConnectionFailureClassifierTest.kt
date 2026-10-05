package app.knotwork.android.data.network

import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.get
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClientException
import app.knotwork.android.data.engine.HopRefusedException
import app.knotwork.android.data.mcp.McpHandshakeTimeoutException
import app.knotwork.android.domain.connection.ConnectionFailure
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [ConnectionFailureClassifier]. The error shapes are the ones measured on the
 * clients the checks use — a Koog HTTP failure wrapped by its client, Streamable HTTP's status
 * error, Ktor's connect deadline (a `ConnectException`), a refused socket — and the read deadline
 * is produced by a real Koog client against a silent server.
 */
class ConnectionFailureClassifierTest {

    private val url = "http://192.168.1.20:8000/v1/models"

    private fun context(keySent: Boolean = true, mcp: Boolean = false, retryAfter: String? = null) =
        ConnectionFailureClassifier.Context(
            keySent = keySent,
            requestedUrl = url,
            retryAfterHeader = retryAfter,
            connectTimeout = 30.seconds,
            socketTimeout = 60.seconds,
            mcp = mcp,
        )

    /** An HTTP failure as a Koog provider client reports it: wrapped in its own exception. */
    private fun http(status: Int, body: String? = null): Throwable =
        LLMClientException("OpenAILLMClient", "Error fetching models", KoogHttpClientException("test", status, body))

    private fun classify(error: Throwable, context: ConnectionFailureClassifier.Context = context()) =
        ConnectionFailureClassifier.classify(error, context, Instant.parse("2026-10-04T12:00:00Z"))

    @Test
    fun `given 401 or 403 when classified then the credentials were rejected, and whether any were sent is kept`() {
        assertEquals(ConnectionFailure.Unauthorized(401, keySent = true), classify(http(401)))
        assertEquals(ConnectionFailure.Unauthorized(403, keySent = true), classify(http(403)))
        assertEquals(
            ConnectionFailure.Unauthorized(401, keySent = false),
            classify(http(401), context(keySent = false)),
        )
    }

    @Test
    fun `given Google's answer to an unknown key when classified then it is a rejected key, not a 400`() {
        // Measured: Google answers an invalid key with 400 INVALID_ARGUMENT, reason API_KEY_INVALID.
        val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",""" +
            """"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""

        assertEquals(ConnectionFailure.Unauthorized(400, keySent = true), classify(http(400, body)))
    }

    @Test
    fun `given another 400 when classified then the server's own message is quoted, credentials removed`() {
        val body = """{"error":{"message":"bad header Authorization: Bearer sk-secret-123 rejected"}}"""

        val failure = classify(http(400, body))

        assertEquals(ConnectionFailure.UnexpectedAnswer(400, "bad header Authorization: Bearer *** rejected"), failure)
    }

    @Test
    fun `given a long or empty body when quoted then it is cut, or left out`() {
        val failure = classify(http(418, "x".repeat(500))) as ConnectionFailure.UnexpectedAnswer

        assertEquals(161, failure.excerpt?.length)
        assertTrue(failure.excerpt.orEmpty().endsWith("…"))
        assertEquals(ConnectionFailure.UnexpectedAnswer(418, null), classify(http(418, "  ")))
    }

    @Test
    fun `given 404 when classified then the address asked is named, or for MCP the endpoint is wrong`() {
        assertEquals(ConnectionFailure.NotFound(url), classify(http(404, "Not Found")))
        assertEquals(ConnectionFailure.NotMcp, classify(StreamableHttpError(404, "Not Found"), context(mcp = true)))
    }

    @Test
    fun `given 429 when classified then the wait comes from the header, else from the answer`() {
        assertEquals(ConnectionFailure.RateLimited(7.seconds), classify(http(429), context(retryAfter = "7")))
        assertEquals(
            ConnectionFailure.RateLimited(62_500.milliseconds),
            classify(http(429, """{"error":{"message":"Rate limit reached. Please try again in 1m2.5s."}}""")),
        )
        assertEquals(ConnectionFailure.RateLimited(null), classify(http(429)))
    }

    @Test
    fun `given 5xx when classified then it is the server's error`() {
        assertEquals(ConnectionFailure.ServerError(503), classify(http(503)))
        assertEquals(
            ConnectionFailure.ServerError(500),
            classify(StreamableHttpError(500, "oops"), context(mcp = true)),
        )
    }

    @Test
    fun `given an MCP endpoint answering with a status or a body of another kind then it is not MCP`() {
        assertEquals(
            ConnectionFailure.NotMcp,
            classify(StreamableHttpError(-1, "Unexpected content type: text/html"), context(mcp = true)),
        )
        assertEquals(
            ConnectionFailure.NotMcp,
            classify(StreamableHttpError(405, "Method Not Allowed"), context(mcp = true)),
        )
        assertEquals(
            ConnectionFailure.Unauthorized(401, keySent = false),
            classify(StreamableHttpError(401, "{}"), context(keySent = false, mcp = true)),
        )
    }

    @Test
    fun `given a success that is not the list when classified then it is no list, or not MCP`() {
        val decode = IOException("decode", SerializationException("Unexpected JSON token"))

        assertEquals(ConnectionFailure.NoModelList, classify(decode))
        assertEquals(ConnectionFailure.NoModelList, classify(NotTheListException("an Ollama model list")))
        assertEquals(ConnectionFailure.NotMcp, classify(decode, context(mcp = true)))
    }

    @Test
    fun `given a connect deadline when classified then it is a connect timeout, not a refused connection`() {
        // Ktor's ConnectTimeoutException IS a ConnectException; the order of the rules decides.
        val ktor = ConnectTimeoutException("Connect timeout has expired [url=http://10.0.0.9]", null)

        assertEquals(ConnectionFailure.ConnectTimeout(30.seconds), classify(ktor))
    }

    @Test
    fun `given a read deadline whose message quotes a URL with connect in it when classified then it is silence`() {
        // Ktor words a read deadline with the URL in it; reading the message would take this
        // server's silence for a connect timeout.
        val read = SocketTimeoutException("Socket timeout has expired [url=http://connect.lan:8000/v1/models]")

        assertEquals(ConnectionFailure.Silence(60.seconds), classify(read))
    }

    @Test
    fun `given a refused socket when classified then it is a refused connection`() {
        val refused = ConnectException("Failed to connect to /127.0.0.1:9").apply {
            initCause(ConnectException("Connection refused"))
        }

        assertEquals(ConnectionFailure.ConnectionRefused, classify(refused))
        assertTrue(classify(ConnectException("Network is unreachable")) is ConnectionFailure.Other)
    }

    @Test
    fun `given a host that does not resolve when classified then it is an unknown host`() {
        assertEquals(ConnectionFailure.UnknownHost, classify(IOException("x", UnknownHostException("nowhere.invalid"))))
    }

    @Test
    fun `given a refused redirect or a stalled handshake when classified then they are named`() {
        val hop = LLMClientException("x", "y", HopRefusedException("Stopped an unencrypted request to 203.0.113.7"))

        assertEquals(ConnectionFailure.RedirectRefused("Stopped an unencrypted request to 203.0.113.7"), classify(hop))
        assertEquals(
            ConnectionFailure.HandshakeTimeout(30.seconds),
            classify(McpHandshakeTimeoutException("http://h/mcp", 30.seconds), context(mcp = true)),
        )
    }

    @Test
    fun `given anything else when classified then it is told as it came, without a credential`() {
        val failure = classify(IllegalStateException("Trust anchor not found for https://x?key=AIza-secret"))

        assertEquals(ConnectionFailure.Other("Trust anchor not found for https://x?key=***"), failure)
    }

    @Test
    fun `given a server silent past the read deadline when a real client fails then it is silence`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(5, TimeUnit.SECONDS).build())
            val client = KtorKoogHttpClient.Factory().create(
                clientName = "test",
                baseUrl = server.url("/").toString(),
                requestTimeoutMillis = 10_000,
                connectTimeoutMillis = 10_000,
                socketTimeoutMillis = 300,
            )

            val error = try {
                withTimeout(4.seconds) { client.get<String>("x") }
                fail("expected the call to time out")
                error("unreachable")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }

            assertEquals(ConnectionFailure.Silence(60.seconds), classify(error))
            assertFalse(
                "the read deadline is not a connect deadline",
                classify(error) is ConnectionFailure.ConnectTimeout,
            )
        }
    }

    /** What a real Ktor SSE client throws for [response], with [inSession] run once the session opens. */
    private suspend fun sseFailure(response: MockResponse, inSession: () -> Unit = {}): Throwable =
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response)
            HttpClient { install(SSE) }.use { client ->
                try {
                    withTimeout(10.seconds) { client.sse(server.url("/sse").toString()) { inSession() } }
                    fail("expected the SSE session to fail")
                    error("unreachable")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
            }
        }

    @Test
    fun `given an SSE address answering with a web page when classified then it is not MCP`() = runBlocking {
        val page = MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html/>").build()

        assertEquals(ConnectionFailure.NotMcp, classify(sseFailure(page), context(mcp = true)))
    }

    @Test
    fun `given an SSE stream that broke after it opened when classified then it is not taken for a wrong address`() =
        runBlocking {
            // Ktor wraps a failure inside an open session with the stream's own 200 response.
            val stream = MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
                .chunkedBody(": keep-alive\n\n", maxChunkSize = 64).build()

            val failure = classify(sseFailure(stream) { throw IOException("stream dropped") }, context(mcp = true))

            assertEquals(ConnectionFailure.Other("stream dropped"), failure)
        }
}
