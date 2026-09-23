package app.knotwork.android.data.mcp

import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Session-lifecycle regression tests for [KoogMcpClient], covering a defect
 * found by a directed on-device MCP test: on the device a tool call failed with
 * `-32000 No valid session ID provided`, and the wire capture showed sessions
 * being opened and abandoned faster than anything was using them.
 *
 * These drive the real [KoogMcpClient.connect] / [KoogMcpClient.executeTool]
 * against a minimal Streamable-HTTP stub, so they assert what actually crosses
 * the wire rather than what the implementation claims.
 *
 * The pre-existing [KoogMcpClientTest] injects a mock registry by reflection
 * and never exercises `connect` at all — which is why none of this was caught
 * before the directed run.
 */
class KoogMcpClientSessionTest {

    private val server = MockWebServer()
    private val received = CopyOnWriteArrayList<String>()

    /**
     * JSON-RPC method the stub server refuses to answer, modelling the server
     * that accepts a request and then goes quiet — the shape of the
     * silent-server failures a directed on-device test found. `null` (the default) makes the stub answer everything.
     */
    private var stallMethod: String? = null

    /**
     * Text the stub returns from `tools/call`. `null` (the default) answers with an
     * empty result, which is all the session tests need.
     */
    private var callResultText: String? = null

    /** Released in [tearDown] so a stalled dispatcher thread never outlives the test. */
    private val stallRelease = CountDownLatch(1)

    @After
    fun tearDown() {
        stallRelease.countDown()
        server.close()
    }

    /** Minimal Streamable-HTTP MCP server: answers `initialize` and `tools/list`. */
    private fun start() {
        var sessionCounter = 0
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "DELETE") {
                    received += "DELETE sid=${request.headers["mcp-session-id"]}"
                    return MockResponse.Builder().code(200).build()
                }
                if (request.method == "GET") {
                    received += "GET(sse) sid=${request.headers["mcp-session-id"]}"
                    // Keep the server-initiated stream empty but open-ended.
                    return MockResponse.Builder()
                        .code(200)
                        .headers(Headers.headersOf("Content-Type", "text/event-stream"))
                        .body(": keep-alive\n\n")
                        .build()
                }
                val body = request.body?.utf8().orEmpty()
                val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1) ?: "1"
                received += "${method ?: "?"} sid=${request.headers["mcp-session-id"]}"

                if (method != null && method == stallMethod) {
                    // Hold the response the way an unresponsive server does. The
                    // ceiling only bounds the test if the client has no deadline
                    // of its own — which is exactly the defect under test.
                    stallRelease.await(STALL_CEILING_SECONDS, TimeUnit.SECONDS)
                }

                return when (method) {
                    "initialize" -> {
                        sessionCounter += 1
                        val payload = """
                            {"jsonrpc":"2.0","id":$id,"result":{
                              "protocolVersion":"2025-11-25",
                              "capabilities":{"tools":{"listChanged":true}},
                              "serverInfo":{"name":"stub","version":"1"}}}
                        """.trimIndent()
                        sse(payload, sessionId = "session-$sessionCounter")
                    }
                    "notifications/initialized" -> MockResponse.Builder().code(202).build()
                    "tools/list" -> sse(
                        """
                        {"jsonrpc":"2.0","id":$id,"result":{"tools":[
                          {"name":"echo","description":"Echoes back",
                           "inputSchema":{"type":"object",
                             "properties":{"message":{"type":"string"}},
                             "required":["message"]}}]}}
                        """.trimIndent(),
                    )
                    "tools/call" -> callResultText?.let { text ->
                        sse(
                            """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":""" +
                                JSONObject.quote(text) + "}]}}",
                        )
                    } ?: sse("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                    else -> sse("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                }
            }
        }
        server.start()
    }

    private fun sse(payload: String, sessionId: String? = null): MockResponse {
        val headers = mutableListOf("Content-Type", "text/event-stream")
        if (sessionId != null) {
            headers += listOf("mcp-session-id", sessionId)
        }
        return MockResponse.Builder()
            .code(200)
            .headers(Headers.headersOf(*headers.toTypedArray()))
            .body("event: message\ndata: ${payload.replace("\n", "")}\n\n")
            .build()
    }

    @Test
    fun `given a fresh client when connect then exactly one session is opened`() = runTest {
        start()
        val client = KoogMcpClient()

        client.connect(
            McpServerConfig(
                url = server.url("/mcp").toString(),
                transport = McpTransport.STREAMABLE_HTTP,
            ),
        )
        val tools = client.getTools()

        assertEquals(listOf("echo"), tools.map { it.name })
        assertEquals(1, received.count { it.startsWith("initialize") })

        client.disconnect()
    }

    @Test
    fun `given a live connection when disconnect then the session is terminated server-side`() = runTest {
        start()
        val client = KoogMcpClient()
        client.connect(
            McpServerConfig(
                url = server.url("/mcp").toString(),
                transport = McpTransport.STREAMABLE_HTTP,
            ),
        )
        client.getTools()

        client.disconnect()

        // Closing the socket is not enough: an MCP server keeps a Streamable-HTTP
        // session until it is explicitly terminated, so a client that only closes
        // strands one session per connect.
        assertTrue(
            "expected a session-terminating DELETE, wire was: $received",
            received.any { it.startsWith("DELETE") },
        )
    }

    @Test
    fun `given a tool call in flight when a concurrent reconnect lands then the call still resolves`() = runTest {
        start()
        val client = KoogMcpClient()
        val config = McpServerConfig(
            url = server.url("/mcp").toString(),
            transport = McpTransport.STREAMABLE_HTTP,
        )
        client.connect(config)
        client.getTools()

        // The call runs in a scope of its own so its failure does not tear down
        // the test scope — the whole point is to observe what the caller sees.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val call = scope.async {
            client.executeTool(name = "echo", arguments = """{"message":"hi"}""")
        }
        // A concurrent Refresh (McpServerRepositoryImpl.fetchToolList calls
        // connect() unconditionally) tears the transport down underneath it.
        client.connect(config)

        // Deliberately catching without re-throwing: the type the caller observes
        // IS the assertion here. Nothing suspends afterwards, so cooperative
        // cancellation is not compromised.
        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        val observed: Throwable? = try {
            call.await()
            null
        } catch (e: Throwable) {
            e
        }

        // Before the session snapshot was atomic, the reconnect blanked the
        // registry mid-lookup and the caller was told `Tool echo not found` —
        // i.e. the agent was informed a tool it has does not exist.
        assertFalse(
            "a concurrent reconnect must not surface as a missing tool: $observed",
            observed is IllegalArgumentException,
        )

        scope.cancel()
        client.disconnect()
    }

    /**
     * Deadline regression for finding F12/F13: a tool call must end on a
     * deadline **the app chose**. Before this, no timeout was installed at all,
     * so the limit was whatever Ktor engine happened to be on the classpath
     * (measured at 10 s on the device), and the attempted fix via Ktor's
     * `HttpTimeout` plugin removed even that — the call became unbounded, which
     * froze the whole serial task queue.
     *
     * Two things are asserted, and the second is the load-bearing one: the
     * failure must be an ordinary exception, not a [CancellationException].
     * A cancellation propagates through `ToolRepositoryImpl` and takes the
     * entire run down instead of being reported as one failed tool call.
     */
    @Test
    fun `given a server that never answers when executeTool then it fails on its own deadline`() = runTest {
        stallMethod = "tools/call"
        start()
        val client = KoogMcpClient().apply { toolCallTimeoutMs = TEST_DEADLINE_MS }
        client.connect(
            McpServerConfig(
                url = server.url("/mcp").toString(),
                transport = McpTransport.STREAMABLE_HTTP,
            ),
        )
        client.getTools()

        // Deliberately catching without re-throwing: the type and message the
        // caller observes ARE the assertion. Nothing suspends afterwards.
        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        val observed: Throwable? = try {
            client.executeTool(name = "echo", arguments = """{"message":"hi"}""")
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue("expected a deadline failure, got $observed", observed is IOException)
        assertTrue(
            "the error must name the deadline, got ${observed?.message}",
            observed?.message.orEmpty().contains("did not respond within"),
        )
        assertFalse(
            "a timeout reported as cancellation would take the whole run down: $observed",
            observed is CancellationException,
        )
    }

    /**
     * Same deadline, other end of the connection: a server that accepts the
     * socket and never finishes the handshake used to leave the Tools row
     * spinning on "Connecting…" for the life of the process.
     */
    @Test
    fun `given a server that never completes the handshake when connect then it fails on its own deadline`() = runTest {
        stallMethod = "initialize"
        start()
        val client = KoogMcpClient().apply { connectTimeoutMs = TEST_DEADLINE_MS }

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        val observed: Throwable? = try {
            client.connect(
                McpServerConfig(
                    url = server.url("/mcp").toString(),
                    transport = McpTransport.STREAMABLE_HTTP,
                ),
            )
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue("expected a deadline failure, got $observed", observed is IOException)
        assertTrue(
            "the error must name the deadline, got ${observed?.message}",
            observed?.message.orEmpty().contains("did not complete the handshake"),
        )
        assertFalse(
            "a timeout reported as cancellation would take the caller down with it: $observed",
            observed is CancellationException,
        )
    }

    /**
     * The result of a tool call is untrusted remote text that reaches the chat
     * history, the run state and every later prompt, so it is cut where it enters
     * the app — the same way `http_request` cuts a response body — instead of being
     * handed on whole.
     */
    @Test
    fun `given a tool result longer than the budget when executeTool then the result is cut with a marker`() = runTest {
        callResultText = "x".repeat(OVERSIZED_RESULT_CHARS)
        start()
        val client = KoogMcpClient()
        client.connect(
            McpServerConfig(
                url = server.url("/mcp").toString(),
                transport = McpTransport.STREAMABLE_HTTP,
            ),
        )

        val result = client.executeTool(name = "echo", arguments = """{"message":"hi"}""")

        val budget = SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT
        assertTrue(
            "the result must be cut at the budget, got ${result.length} chars",
            result.toByteArray(Charsets.UTF_8).size < budget + MARKER_ALLOWANCE,
        )
        assertTrue(
            "a cut result must say so, tail was: ${result.takeLast(MARKER_ALLOWANCE)}",
            result.endsWith("[... result truncated at $budget bytes]"),
        )
        client.disconnect()
    }

    @Test
    fun `given the user lowered the budget when executeTool then the result is cut at the user's budget`() = runTest {
        callResultText = "y".repeat(SMALL_BUDGET_BYTES.toInt() * 4)
        start()
        val client = KoogMcpClient(resultByteBudget = { SMALL_BUDGET_BYTES })
        client.connect(
            McpServerConfig(
                url = server.url("/mcp").toString(),
                transport = McpTransport.STREAMABLE_HTTP,
            ),
        )

        val result = client.executeTool(name = "echo", arguments = """{"message":"hi"}""")

        assertTrue(result.endsWith("[... result truncated at $SMALL_BUDGET_BYTES bytes]"))
        assertTrue(result.toByteArray(Charsets.UTF_8).size < SMALL_BUDGET_BYTES + MARKER_ALLOWANCE)
        client.disconnect()
    }

    private companion object {
        /** A budget the user could set (the slider floor is 64 KB), small enough to see. */
        const val SMALL_BUDGET_BYTES = 65_536L

        /** A result comfortably past the default 1 MiB budget. */
        const val OVERSIZED_RESULT_CHARS = 1_200_000

        /** Room for the truncation marker on top of the budget. */
        const val MARKER_ALLOWANCE = 64

        /** Deadline used by the timeout tests — short enough to keep them quick. */
        const val TEST_DEADLINE_MS = 300L

        /** Upper bound on how long the stub holds a stalled response. */
        const val STALL_CEILING_SECONDS = 30L
    }
}
