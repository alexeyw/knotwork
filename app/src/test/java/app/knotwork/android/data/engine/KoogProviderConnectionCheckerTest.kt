package app.knotwork.android.data.engine

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import app.knotwork.android.data.engine.retry.CloudRetryWrapper
import app.knotwork.android.domain.connection.AddressRefusal
import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.connection.ConnectionRefusal
import app.knotwork.android.domain.connection.ProviderConnectionDraft
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.NetworkSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * [KoogProviderConnectionChecker] on the wire: the request each provider's check sends, what it
 * makes of the answer, and that a refused check sends nothing and tells the privacy indicator
 * nothing.
 *
 * Hosted providers have fixed addresses; their requests are routed to the local server behind the
 * real transport, so the per-hop rule and the deadlines still apply.
 */
class KoogProviderConnectionCheckerTest {

    private val server = MockWebServer().apply { start() }
    private val elsewhere = MockWebServer().apply { start() }

    @After
    fun tearDown() {
        server.close()
        elsewhere.close()
    }

    private val origin get() = server.url("/").toString().trimEnd('/')
    private val localOnly = MutableStateFlow(false)
    private val approved = MutableStateFlow(setOf<String>())

    private val networkSettings = mockk<NetworkSettings>(relaxed = true) {
        every { blockNetworkFromLocalModel } returns localOnly
        every { approvedCleartextOrigins } returns approved
        every { cloudRetryMaxAttempts } returns flowOf(1)
    }
    private val tracker = mockk<NetworkActivityTracker>(relaxed = true)

    private fun checker(routeHosted: Boolean = false): KoogProviderConnectionChecker {
        val gate = ModelNetworkGate(networkSettings)
        val factory = KoogClientFactory(mockk(), gate, CloudRetryWrapper(networkSettings))
        return KoogProviderConnectionChecker(factory, gate, tracker).apply {
            if (routeHosted) {
                transportFor = { retryAfter, rule ->
                    Rerouting(KoogTransportFactory(KtorKoogHttpClient.Factory(), retryAfter, rule), origin)
                }
            }
        }
    }

    /** Sends every client to [origin], keeping the path of the address it was built for. */
    private class Rerouting(private val delegate: KoogHttpClient.Factory, private val origin: String) :
        KoogHttpClient.Factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json,
        ): KoogHttpClient = delegate.create(
            clientName,
            origin + URI(baseUrl).rawPath.orEmpty(),
            headers,
            queryParameters,
            requestTimeoutMillis,
            connectTimeoutMillis,
            socketTimeoutMillis,
            json,
        )
    }

    /** A test body with a deadline: a check that waits on a server with nothing queued fails, not hangs. */
    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30.seconds, block) }

    /** The next request the server received; fails instead of waiting for one that never comes. */
    private fun MockWebServer.taken(): RecordedRequest =
        checkNotNull(takeRequest(5, TimeUnit.SECONDS)) { "the server received no further request" }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val openAiList =
        """{"object":"list","data":[{"id":"qwen2.5-7b-instruct","object":"model","created":1,"owned_by":"vllm"},""" +
            """{"id":"llama-3.1-8b","object":"model","created":1,"owned_by":"vllm"}]}"""

    private suspend fun checkOwn(key: String? = null): ConnectionCheckResult {
        approved.value = setOf(origin)
        return withTimeout(15.seconds) {
            checker().check(CloudProvider.OPENAI_COMPATIBLE, ProviderConnectionDraft(key, server.url("/v1").toString()))
        }
    }

    @Test
    fun `given a server the user runs without a key when checked then its list is read at v1 models`() = bounded {
        server.enqueue(json(openAiList))

        val result = checkOwn()

        val request = server.taken()
        assertEquals(ConnectionCheckResult.Reachable(listOf("qwen2.5-7b-instruct", "llama-3.1-8b")), result)
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.target)
        assertNull(request.headers["Authorization"])
        verify(exactly = 1) { tracker.recordOutbound() }
    }

    @Test
    fun `given a server the user runs with a key when checked then the key is sent as a Bearer token`() = bounded {
        server.enqueue(json(openAiList))

        checkOwn(key = " sk-own ")

        assertEquals("Bearer sk-own", server.taken().headers["Authorization"])
    }

    @Test
    fun `given Ollama when checked then its tags are read in one request`() = bounded {
        approved.value = setOf(origin)
        server.enqueue(json("""{"models":[{"name":"llama3.2:3b","size":1},{"name":"qwen3:8b","size":2}]}"""))

        val result = checker().check(CloudProvider.OLLAMA, ProviderConnectionDraft(null, origin))

        assertEquals(ConnectionCheckResult.Reachable(listOf("llama3.2:3b", "qwen3:8b")), result)
        assertEquals("/api/tags", server.taken().target)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `given an answer that is not a model list when checked then it says so`() = bounded {
        approved.value = setOf(origin)
        server.enqueue(json("""{"version":"0.5"}"""))
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html/>").build())

        val ollama = checker().check(CloudProvider.OLLAMA, ProviderConnectionDraft(null, origin))
        val page = checkOwn()

        val host = server.url("/").host
        assertEquals(ConnectionCheckResult.Failed(host, ConnectionFailure.NoModelList), ollama)
        assertEquals(ConnectionCheckResult.Failed(host, ConnectionFailure.NoModelList), page)
    }

    @Test
    fun `given the answers a server can give when checked then each is named`() = bounded {
        server.enqueue(json("""{"detail":"Not Found"}""", code = 404))
        server.enqueue(json("{}", code = 401))
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "7").build())
        server.enqueue(json("{}", code = 503))

        val failures = List(4) { (checkOwn() as ConnectionCheckResult.Failed).failure }

        assertEquals(
            listOf(
                ConnectionFailure.NotFound(server.url("/v1/models").toString()),
                ConnectionFailure.Unauthorized(401, keySent = false),
                ConnectionFailure.RateLimited(7.seconds),
                ConnectionFailure.ServerError(503),
            ),
            failures,
        )
    }

    @Test
    fun `given a redirect to an address that was not approved when checked then it is refused and not followed`() =
        bounded {
            server.enqueue(
                MockResponse.Builder().code(302).addHeader("Location", elsewhere.url("/v1/models").toString()).build(),
            )

            val result = checkOwn() as ConnectionCheckResult.Failed

            assertTrue("${result.failure}", result.failure is ConnectionFailure.RedirectRefused)
            assertEquals(0, elsewhere.requestCount)
        }

    @Test
    fun `given nothing listening on the port when checked then the connection was refused`() = bounded {
        val closed = "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}"
        approved.value = setOf(closed)

        val result = withTimeout(15.seconds) {
            checker().check(CloudProvider.OPENAI_COMPATIBLE, ProviderConnectionDraft(null, "$closed/v1"))
        }

        assertEquals(ConnectionCheckResult.Failed("127.0.0.1", ConnectionFailure.ConnectionRefused), result)
    }

    @Test
    fun `given OpenRouter when checked then its key is checked first, and a rejected one stops the check`() = bounded {
        approved.value = setOf(origin)
        server.enqueue(json("""{"error":{"message":"User not found.","code":401}}""", code = 401))

        val result = checker(
            routeHosted = true,
        ).check(CloudProvider.OPENROUTER, ProviderConnectionDraft("sk-or-x", null))

        val request = server.taken()
        assertEquals("/api/v1/key", request.target)
        assertEquals("Bearer sk-or-x", request.headers["Authorization"])
        assertEquals(
            ConnectionCheckResult.Failed("openrouter.ai", ConnectionFailure.Unauthorized(401, true)),
            result,
        )
        assertEquals("the model list is not asked after a rejected key", 1, server.requestCount)
    }

    @Test
    fun `given OpenRouter with a good key when checked then the list follows the key check`() = bounded {
        approved.value = setOf(origin)
        server.enqueue(json("""{"data":{"label":"sk-or-v1-abc","usage":0}}"""))
        server.enqueue(json(openAiList))

        val result = checker(
            routeHosted = true,
        ).check(CloudProvider.OPENROUTER, ProviderConnectionDraft("sk-or-x", null))

        assertEquals(listOf("/api/v1/key", "/api/v1/models"), List(2) { server.taken().target })
        assertEquals(ConnectionCheckResult.Reachable(listOf("qwen2.5-7b-instruct", "llama-3.1-8b")), result)
    }

    @Test
    fun `given Groq when checked then its list is read at its OpenAI path`() = bounded {
        approved.value = setOf(origin)
        server.enqueue(json(openAiList))

        checker(routeHosted = true).check(CloudProvider.GROQ, ProviderConnectionDraft("gsk-x", null))

        assertEquals("/openai/v1/models", server.taken().target)
    }

    @Test
    fun `given Google rejecting the key with its 400 when checked then the key was rejected`() = bounded {
        approved.value = setOf(origin)
        server.enqueue(
            json(
                """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",""" +
                    """"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""",
                code = 400,
            ),
        )

        val result = checker(routeHosted = true).check(CloudProvider.GOOGLE, ProviderConnectionDraft("AIza-x", null))

        assertEquals(
            ConnectionCheckResult.Failed(
                "generativelanguage.googleapis.com",
                ConnectionFailure.Unauthorized(400, true),
            ),
            result,
        )
    }

    @Test
    fun `given a refusal before sending when checked then nothing is sent and the indicator hears nothing`() = bounded {
        localOnly.value = true
        val blocked = checker().check(CloudProvider.OPENAI, ProviderConnectionDraft("sk", null))
        localOnly.value = false
        val noKey = checker().check(CloudProvider.GROQ, ProviderConnectionDraft(" ", null))
        val public = checker().check(
            CloudProvider.OPENAI_COMPATIBLE,
            ProviderConnectionDraft(null, "http://203.0.113.7/v1"),
        )
        val unapproved = checker().check(CloudProvider.OLLAMA, ProviderConnectionDraft(null, origin))

        assertEquals(ConnectionCheckResult.Refused(ConnectionRefusal.BlockedByLocalOnlyMode), blocked)
        assertEquals(ConnectionCheckResult.Refused(ConnectionRefusal.MissingKey), noKey)
        assertEquals(ConnectionCheckResult.Refused(AddressRefusal.PublicCleartext("203.0.113.7")), public)
        assertEquals(ConnectionCheckResult.Refused(AddressRefusal.CleartextNeedsApproval(origin)), unapproved)
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { tracker.recordOutbound() }
    }

    @Test
    fun `given each provider when its destination is asked then it is the host its check asks`() {
        val checker = checker()

        assertEquals("api.openai.com", checker.destination(CloudProvider.OPENAI, null))
        assertEquals("api.anthropic.com", checker.destination(CloudProvider.ANTHROPIC, null))
        assertEquals("generativelanguage.googleapis.com", checker.destination(CloudProvider.GOOGLE, null))
        assertEquals("api.deepseek.com", checker.destination(CloudProvider.DEEPSEEK, null))
        assertEquals("openrouter.ai", checker.destination(CloudProvider.OPENROUTER, "ignored"))
        assertEquals("api.groq.com", checker.destination(CloudProvider.GROQ, null))
        assertEquals(
            "192.168.1.20",
            checker.destination(CloudProvider.OPENAI_COMPATIBLE, " http://192.168.1.20:8000/v1"),
        )
        assertEquals("192.168.1.20", checker.destination(CloudProvider.OLLAMA, "http://192.168.1.20:11434"))
        assertNull(checker.destination(CloudProvider.OPENAI_COMPATIBLE, " "))
    }
}
