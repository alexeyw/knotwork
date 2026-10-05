package app.knotwork.android.data.mcp

import app.knotwork.android.domain.connection.AddressRefusal
import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTransport
import app.knotwork.android.domain.repositories.NetworkSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [KoogMcpConnectionChecker] against a stub MCP server, through the real [KoogMcpClient]: what a
 * check of a form finds, that it leaves no session behind, and that a refused check opens nothing.
 */
class KoogMcpConnectionCheckerTest {

    private val server = MockWebServer()
    private val received = CopyOnWriteArrayList<String>()
    private val approved = MutableStateFlow(setOf<String>())
    private val networkSettings = mockk<NetworkSettings> { every { approvedCleartextOrigins } returns approved }

    /** Real clients, with a short handshake deadline so a silent server fails fast. */
    private val clients = object : McpClientFactory {
        override fun create(): McpClient = KoogMcpClient().apply { connectTimeoutMs = HANDSHAKE_MS }
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** Starts the stub; [answer] decides each POST, `null` answering as a working MCP server. */
    private fun start(answer: ((method: String?) -> MockResponse?)? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "DELETE") {
                    received += "DELETE"
                    return MockResponse.Builder().code(200).build()
                }
                if (request.method == "GET") {
                    return MockResponse.Builder().code(405).build()
                }
                val body = request.body?.utf8().orEmpty()
                val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                val id = Regex("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)").find(body)?.groupValues?.get(1) ?: "1"
                received += "${method ?: "?"} auth=${request.headers["Authorization"]}"
                answer?.invoke(method)?.let { return it }
                return when (method) {
                    "initialize" -> sse(
                        """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25",""" +
                            """"capabilities":{"tools":{}},"serverInfo":{"name":"stub","version":"1"}}}""",
                        sessionId = "s-1",
                    )
                    "notifications/initialized" -> MockResponse.Builder().code(202).build()
                    "tools/list" -> sse(
                        """{"jsonrpc":"2.0","id":$id,"result":{"tools":[""" +
                            """{"name":"echo","inputSchema":{"type":"object"}},""" +
                            """{"name":"search","inputSchema":{"type":"object"}}]}}""",
                    )
                    else -> sse("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                }
            }
        }
        server.start()
        approved.value = setOf(server.url("/").toString().trimEnd('/'))
    }

    private fun sse(payload: String, sessionId: String? = null): MockResponse {
        val headers = mutableListOf("Content-Type", "text/event-stream")
        sessionId?.let { headers += listOf("mcp-session-id", it) }
        return MockResponse.Builder().code(200).headers(Headers.headersOf(*headers.toTypedArray()))
            .body("event: message\ndata: $payload\n\n").build()
    }

    private fun config(transport: McpTransport = McpTransport.STREAMABLE_HTTP, auth: McpAuth = McpAuth.None) =
        McpServerConfig(url = server.url("/mcp").toString(), transport = transport, auth = auth)

    private suspend fun check(config: McpServerConfig = config()): ConnectionCheckResult =
        withTimeout(20.seconds) { KoogMcpConnectionChecker(clients, networkSettings).check(config) }

    private val host get() = server.url("/").host

    @Test
    fun `given a working server when checked then its tools are listed and the session is ended`() = runBlocking {
        start()

        val result = check(config(auth = McpAuth.Bearer("tok")))

        assertEquals(ConnectionCheckResult.Reachable(listOf("echo", "search")), result)
        assertTrue("the token is sent: $received", received.first().endsWith("auth=Bearer tok"))
        assertTrue("the check leaves no session open: $received", "DELETE" in received)
    }

    @Test
    fun `given a server rejecting the credentials when checked then it says so`() = runBlocking {
        start { MockResponse.Builder().code(401).body("{}").build() }

        val withToken = check(config(auth = McpAuth.Bearer("tok")))
        val without = check()

        assertEquals(ConnectionCheckResult.Failed(host, ConnectionFailure.Unauthorized(401, keySent = true)), withToken)
        assertEquals(ConnectionCheckResult.Failed(host, ConnectionFailure.Unauthorized(401, keySent = false)), without)
    }

    @Test
    fun `given an address that is not an MCP endpoint when checked then it is not MCP, over either transport`() =
        runBlocking {
            start { MockResponse.Builder().code(404).body("Not Found").build() }
            val missing = check()
            val sse = check(config(transport = McpTransport.SSE))

            assertEquals(ConnectionCheckResult.Failed(host, ConnectionFailure.NotMcp), missing)
            assertEquals(ConnectionCheckResult.Failed(host, ConnectionFailure.NotMcp), sse)
        }

    @Test
    fun `given a web page at the address when checked then it is not MCP`() = runBlocking {
        start {
            MockResponse.Builder().code(200).headers(Headers.headersOf("Content-Type", "text/html"))
                .body("<html>hi</html>").build()
        }

        assertEquals(ConnectionCheckResult.Failed(host, ConnectionFailure.NotMcp), check())
    }

    @Test
    fun `given a server rejecting the credentials over SSE when checked then it says so`() = runBlocking {
        // SSE reports the status on the response it carries, not as a code of its own.
        start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(401).body("{}").build()
        }

        val result = check(config(transport = McpTransport.SSE, auth = McpAuth.Bearer("tok")))

        assertEquals(ConnectionCheckResult.Failed(host, ConnectionFailure.Unauthorized(401, keySent = true)), result)
    }

    @Test
    fun `given a server that never finishes the handshake when checked then the handshake timed out`() = runBlocking {
        start { method ->
            if (method == "initialize") {
                MockResponse.Builder().code(200).headers(Headers.headersOf("Content-Type", "text/event-stream"))
                    .body(": keep-alive\n\n").build()
            } else {
                null
            }
        }

        val result = check()

        assertEquals(
            ConnectionCheckResult.Failed(host, ConnectionFailure.HandshakeTimeout(HANDSHAKE_MS.milliseconds)),
            result,
        )
    }

    @Test
    fun `given an address the cleartext rule refuses when checked then no client is created`() = runBlocking {
        val factory = mockk<McpClientFactory>()
        val checker = KoogMcpConnectionChecker(factory, networkSettings)

        val public = checker.check(McpServerConfig(url = "http://mcp.example.com/mcp"))
        val unapproved = checker.check(McpServerConfig(url = "http://192.168.1.20:8080/mcp"))

        assertEquals(ConnectionCheckResult.Refused(AddressRefusal.PublicCleartext("mcp.example.com")), public)
        assertEquals(
            ConnectionCheckResult.Refused(AddressRefusal.CleartextNeedsApproval("http://192.168.1.20:8080")),
            unapproved,
        )
        verify(exactly = 0) { factory.create() }
    }

    @Test
    fun `given a connect that fails when checked then the client is still released`() = runBlocking {
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.connect(any()) } throws IOException("boom")
        val factory = object : McpClientFactory {
            override fun create(): McpClient = client
        }

        val result = KoogMcpConnectionChecker(factory, networkSettings)
            .check(McpServerConfig(url = "https://mcp.example.com/mcp"))

        assertEquals(ConnectionCheckResult.Failed("mcp.example.com", ConnectionFailure.Other("boom")), result)
        coVerify(exactly = 1) { client.disconnect() }
    }

    private companion object {
        const val HANDSHAKE_MS = 1_500L
    }
}
