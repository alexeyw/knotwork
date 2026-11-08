package app.knotwork.android.data.mcp

import app.knotwork.android.data.repositories.McpServerRepositoryImpl
import app.knotwork.android.data.repositories.ToolRepositoryImpl
import app.knotwork.android.data.tools.local.LocalAppFunctionManager
import app.knotwork.android.data.tools.local.SearchTool
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTransport
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.NetworkSettings
import app.knotwork.android.domain.repositories.ToolSettings
import app.knotwork.android.domain.services.CleartextPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An MCP server may close a Streamable-HTTP session at any time, and MCP Kotlin SDK 0.16.0
 * servers do after 30 minutes without requests. The server then answers the old
 * `Mcp-Session-Id` with `404 Not Found`, and the specification (2025-11-25, *Session
 * Management*) requires the client to start a new session. Neither the SDK's client nor
 * Koog does that by itself.
 *
 * Driven through the real agent path ([ToolRepositoryImpl] + [McpConnectionPool] +
 * [KoogMcpClientFactory]) and the real Tools-screen path ([McpServerRepositoryImpl]) over
 * one shared pool, as Hilt wires them, against a stub Streamable-HTTP server — so what is
 * asserted is what reaches the wire. Before the fix the first three tests failed: every call
 * after the close failed with the server's 404, and a forced refresh of the Tools screen sent
 * no request at all and kept reporting `Connected`.
 */
class McpServerClosedSessionTest {

    /** What the stub answers on a closed session: an empty 404, or one with a JSON-RPC error body. */
    private enum class Shape { BARE_404, JSONRPC_404 }

    private val server = MockWebServer()

    /** One line per request: `<method> <json-rpc method or -> sid=<id> -> <status>`. */
    private val wire = CopyOnWriteArrayList<String>()
    private val issued = CopyOnWriteArrayList<String>()
    private val closed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var shape = Shape.BARE_404

    /** When set, every `tools/call` is answered with this status and an empty body, whatever the session. */
    @Volatile
    private var callStatus: Int? = null

    /** When set, every `initialize` is answered with this status: the server refuses new sessions. */
    @Volatile
    private var initializeStatus: Int? = null

    /** `false` models a server without sessions: `initialize` carries no `Mcp-Session-Id`. */
    private var issueSessionIds = true

    @After
    fun tearDown() {
        server.close()
    }

    private fun start() {
        var counter = 0
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val sid = request.headers["mcp-session-id"]
                val body = request.body?.utf8().orEmpty()
                val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                val id = Regex("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)").find(body)?.groupValues?.get(1) ?: "1"
                val tag = "${request.method} ${method ?: "-"} sid=$sid"
                if (sid != null && sid in closed) {
                    wire += "$tag -> 404"
                    return closedSession()
                }
                val fixed = when (method) {
                    "tools/call" -> callStatus
                    "initialize" -> initializeStatus
                    else -> null
                }
                if (fixed != null) {
                    wire += "$tag -> $fixed"
                    return MockResponse.Builder().code(fixed).build()
                }
                wire += "$tag -> 2xx"
                if (request.method == "DELETE") return MockResponse.Builder().code(200).build()
                if (request.method == "GET") {
                    return MockResponse.Builder()
                        .code(200)
                        .headers(Headers.headersOf("Content-Type", "text/event-stream"))
                        .body(": keep-alive\n\n")
                        .build()
                }
                return when (method) {
                    "initialize" -> {
                        counter += 1
                        val newSid = "session-$counter".takeIf { issueSessionIds }
                        newSid?.let { issued += it }
                        sse(
                            """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25",""" +
                                """"capabilities":{"tools":{"listChanged":true}},"serverInfo":{"name":"stub","version":"1"}}}""",
                            sessionId = newSid,
                        )
                    }
                    "notifications/initialized" -> MockResponse.Builder().code(202).build()
                    "tools/list" -> sse(
                        """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"echo","description":"Echoes back",""" +
                            """"inputSchema":{"type":"object","properties":{"message":{"type":"string"}},""" +
                            """"required":["message"]}}]}}""",
                    )
                    "tools/call" -> sse(
                        """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"ok-from-$sid"}]}}""",
                    )
                    else -> sse("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                }
            }
        }
        server.start()
    }

    private fun closedSession(): MockResponse = when (shape) {
        Shape.BARE_404 -> MockResponse.Builder().code(404).build()
        Shape.JSONRPC_404 -> MockResponse.Builder()
            .code(404)
            .headers(Headers.headersOf("Content-Type", "application/json"))
            .body("""{"jsonrpc":"2.0","error":{"code":-32001,"message":"Session not found"},"id":null}""")
            .build()
    }

    private fun sse(payload: String, sessionId: String? = null): MockResponse {
        val headers = mutableListOf("Content-Type", "text/event-stream")
        if (sessionId != null) headers += listOf("mcp-session-id", sessionId)
        return MockResponse.Builder()
            .code(200)
            .headers(Headers.headersOf(*headers.toTypedArray()))
            .body("event: message\ndata: $payload\n\n")
            .build()
    }

    /** From now on the stub answers every session issued so far with 404, as a server that closed them. */
    private fun closeSessions() {
        closed += issued
    }

    /** Requests seen from [from] on, with the server-initiated GET stream left out (its timing is the SDK's). */
    private fun wireSince(from: Int): List<String> = wire.subList(from, wire.size).filterNot { it.startsWith("GET ") }

    private fun List<String>.initializes(): Int = count { it.startsWith("POST initialize ") }

    private fun List<String>.toolCalls(): List<String> = filter { it.startsWith("POST tools/call ") }

    private fun config() =
        McpServerConfig(url = server.url("/mcp").toString(), transport = McpTransport.STREAMABLE_HTTP)

    private fun toolSettings(config: McpServerConfig): ToolSettings {
        val settings: ToolSettings = mockk()
        every { settings.mcpServers } returns flowOf(listOf(config))
        every { settings.disabledAppFunctions } returns flowOf(emptySet())
        every { settings.disabledMcpTools } returns flowOf(emptySet())
        every { settings.toolRiskOverrides } returns flowOf(emptyMap())
        every { settings.allowedHttpDomains } returns flowOf(emptyList())
        every { settings.httpToolMaxResponseBytes } returns flowOf(64_000L)
        return settings
    }

    private fun networkSettings(config: McpServerConfig): NetworkSettings {
        val settings: NetworkSettings = mockk()
        every { settings.approvedCleartextOrigins } returns flowOf(setOfNotNull(CleartextPolicy.originOf(config.url)))
        every { settings.blockNetworkFromLocalModel } returns flowOf(false)
        return settings
    }

    /** The agent's repository and the Tools screen's, over one shared pool — as Hilt wires them. */
    private class Paths(
        val pool: McpConnectionPool,
        val agent: ToolRepositoryImpl,
        val toolsScreen: McpServerRepositoryImpl,
    )

    private fun paths(config: McpServerConfig): Paths {
        val toolSettings = toolSettings(config)
        val networkSettings = networkSettings(config)
        val pool = McpConnectionPool(KoogMcpClientFactory(mockk(relaxed = true), toolSettings), networkSettings)
        val appFunctions: LocalAppFunctionManager = mockk()
        coEvery { appFunctions.getAvailableFunctions() } returns emptyList()
        coEvery { appFunctions.isDiscovered(any()) } returns false
        val apiKeys: ApiKeyRepository = mockk()
        every { apiKeys.getApiKey(any()) } returns flowOf(null)
        every { apiKeys.getBaseUrl(any()) } returns flowOf(null)
        every { apiKeys.getModel(any()) } returns flowOf(null)
        val searchTool: SearchTool = mockk(relaxed = true)
        every { searchTool.asAgentTool() } returns AgentTool("search_tool", "desc", "{}")
        return Paths(
            pool = pool,
            agent = ToolRepositoryImpl(
                toolSettings,
                networkSettings,
                pool,
                appFunctions,
                apiKeys,
                searchTool,
                emptyMap(),
            ),
            toolsScreen = McpServerRepositoryImpl(pool),
        )
    }

    /** What the agent's call threw, or `null` when it returned. The type and message are the assertion. */
    private suspend fun Paths.failureOfCall(): Throwable? {
        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        return try {
            agent.executeTool("echo", ARGS, CTX)
            null
        } catch (e: Throwable) {
            e
        }
    }

    private fun agentCallRunsOnANewSession(shape: Shape) = runTest {
        // Given
        this@McpServerClosedSessionTest.shape = shape
        start()
        val paths = paths(config())
        assertTrue(paths.agent.executeTool("echo", ARGS, CTX).contains("ok-from-session-1"))
        closeSessions()
        val mark = wire.size

        // When
        val result = paths.agent.executeTool("echo", ARGS, CTX)

        // Then
        assertTrue("result: $result", result.contains("ok-from-session-2"))
        val after = wireSince(mark)
        assertEquals("wire: $after", 1, after.initializes())
        assertEquals(
            "wire: $after",
            listOf("POST tools/call sid=session-1 -> 404", "POST tools/call sid=session-2 -> 2xx"),
            after.toolCalls(),
        )
    }

    @Test
    fun `given an empty 404 for the session when the agent calls a tool then the call runs on a new session`() =
        agentCallRunsOnANewSession(Shape.BARE_404)

    @Test
    fun `given a JSON-RPC 404 for the session when the agent calls a tool then the call runs on a new session`() =
        agentCallRunsOnANewSession(Shape.JSONRPC_404)

    @Test
    fun `given a closed session when the Tools screen refreshes then it asks and the agent uses the new one`() =
        runTest {
            // Given
            start()
            val config = config()
            val paths = paths(config)
            assertTrue(paths.toolsScreen.fetchToolList(config).isSuccess)
            closeSessions()
            val mark = wire.size

            // When
            val refreshed = paths.toolsScreen.fetchToolList(config, forceRefresh = true)

            // Then
            assertEquals(listOf("echo"), refreshed.getOrThrow().map { it.name })
            assertEquals(McpConnectionStatus.Connected, paths.toolsScreen.observeConnectionStatus(config.url).first())
            val afterRefresh = wireSince(mark)
            assertTrue("wire: $afterRefresh", "POST tools/list sid=session-1 -> 404" in afterRefresh)
            assertEquals("wire: $afterRefresh", 1, afterRefresh.initializes())
            // The new connection listed its tools while connecting; it is not asked twice.
            assertEquals(
                "wire: $afterRefresh",
                listOf("POST tools/list sid=session-2 -> 2xx"),
                afterRefresh.filter { it.startsWith("POST tools/list sid=session-2") },
            )

            val callMark = wire.size
            val result = paths.agent.executeTool("echo", ARGS, CTX)
            assertTrue("result: $result", result.contains("ok-from-session-2"))
            assertEquals(0, wireSince(callMark).initializes())
        }

    @Test
    fun `given a live session when the Tools screen refreshes then the server is asked over the same session`() =
        runTest {
            // Given
            start()
            val config = config()
            val paths = paths(config)
            assertTrue(paths.toolsScreen.fetchToolList(config).isSuccess)
            val mark = wire.size

            // When
            val refreshed = paths.toolsScreen.fetchToolList(config, forceRefresh = true)

            // Then — a real request, and no new session for it.
            assertTrue(refreshed.isSuccess)
            assertEquals(listOf("POST tools/list sid=session-1 -> 2xx"), wireSince(mark))
        }

    @Test
    fun `given the new session is closed too when the agent calls a tool then the call fails after one new session`() =
        runTest {
            // Given — every call is answered 404, so the repeat on the new session is refused as well.
            start()
            val paths = paths(config())
            assertTrue(paths.agent.executeTool("echo", ARGS, CTX).contains("ok-from-session-1"))
            callStatus = 404
            val mark = wire.size

            // When
            val failure = paths.failureOfCall()

            // Then
            assertTrue("failure: $failure", failure is McpSessionExpiredException)
            val after = wireSince(mark)
            assertEquals("wire: $after", 1, after.initializes())
            assertEquals(
                "wire: $after",
                listOf("POST tools/call sid=session-1 -> 404", "POST tools/call sid=session-2 -> 404"),
                after.toolCalls(),
            )
        }

    @Test
    fun `given the server fails a call for another reason when the agent calls a tool then it is not repeated`() =
        runTest {
            // Given — a 500 says nothing about whether the call ran.
            start()
            val paths = paths(config())
            assertTrue(paths.agent.executeTool("echo", ARGS, CTX).contains("ok-from-session-1"))
            callStatus = 500
            val mark = wire.size

            // When
            val failure = paths.failureOfCall()

            // Then
            assertTrue("failure: $failure", failure != null && failure !is McpSessionExpiredException)
            val after = wireSince(mark)
            assertEquals("wire: $after", listOf("POST tools/call sid=session-1 -> 500"), after.toolCalls())
            assertEquals("wire: $after", 0, after.initializes())
        }

    @Test
    fun `given a server without sessions answers 404 when the agent calls a tool then it is not repeated`() = runTest {
        // Given — no session id was ever issued, so a 404 means a wrong address, not a closed session.
        issueSessionIds = false
        start()
        val paths = paths(config())
        assertTrue(paths.agent.executeTool("echo", ARGS, CTX).contains("ok-from-null"))
        callStatus = 404
        val mark = wire.size

        // When
        val failure = paths.failureOfCall()

        // Then
        assertTrue("failure: $failure", failure != null && failure !is McpSessionExpiredException)
        val after = wireSince(mark)
        assertEquals("wire: $after", listOf("POST tools/call sid=null -> 404"), after.toolCalls())
        assertEquals("wire: $after", 0, after.initializes())
    }

    @Test
    fun `given a new session is refused when the agent recovers then the Tools screen is not Connected`() = runTest {
        // Given — the Tools screen has a fresh list from session-1 in its cache.
        start()
        val config = config()
        val paths = paths(config)
        assertTrue(paths.toolsScreen.fetchToolList(config).isSuccess)
        closeSessions()
        initializeStatus = 503

        // When — the agent's call finds the session closed and cannot open a new one.
        val failure = paths.failureOfCall()

        // Then — the agent's call fails, the pool holds no connection, and the screen's
        // next fetch asks the server instead of serving the cached list as Connected.
        assertTrue("failure: $failure", failure != null)
        assertNull(paths.pool.peek(config.url))
        val fetched = paths.toolsScreen.fetchToolList(config)
        assertTrue(fetched.isFailure)
        assertTrue(paths.toolsScreen.observeConnectionStatus(config.url).first() is McpConnectionStatus.Error)
    }

    private companion object {
        const val ARGS = """{"message":"hi"}"""
        val CTX = ToolExecutionContext(sessionId = "test")
    }
}
