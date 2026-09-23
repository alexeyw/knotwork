package app.knotwork.android.data.mcp

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import io.ktor.client.HttpClient
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoogMcpClientTest {

    private fun makeClient(tools: List<Tool<*, *>>): KoogMcpClient {
        val mockRegistry = mockk<ToolRegistry>()
        every { mockRegistry.tools } returns tools
        return makeClient(mockRegistry)
    }

    private fun makeClient(mockRegistry: ToolRegistry): KoogMcpClient {
        val client = KoogMcpClient()
        // `connect` publishes an atomic Session snapshot rather than separate
        // registry / httpClient fields, so the stub has to be installed the same way.
        // The Session type is reached through the field itself — naming it inline
        // would be an internal FQN reference, which `checkNoInternalFqn` rejects.
        val constructor = sessionField(client).type.declaredConstructors.first()
        constructor.isAccessible = true
        val session = constructor.newInstance(
            mockk<HttpClient>(relaxed = true),
            mockk<Transport>(relaxed = true),
            mockRegistry,
        )
        sessionField(client).set(client, session)
        return client
    }

    /** Reflective handle on the private `session` field published by `connect`. */
    private fun sessionField(client: KoogMcpClient) =
        client.javaClass.getDeclaredField("session").apply { isAccessible = true }

    @Test
    fun `getTools maps ToolRegistry to AgentTool with valid JSON Schema`() = runTest {
        val mockDescriptor = mockk<ToolDescriptor>()
        every { mockDescriptor.description } returns "test description"
        every { mockDescriptor.requiredParameters } returns emptyList()
        every { mockDescriptor.optionalParameters } returns emptyList()

        val mockTool = mockk<Tool<Any, Any>>()
        every { mockTool.name } returns "testTool"
        every { mockTool.descriptor } returns mockDescriptor

        val tools = makeClient(listOf(mockTool)).getTools()

        assertEquals(1, tools.size)
        assertEquals("testTool", tools[0].name)
        assertEquals("test description", tools[0].description)
        val schema = JSONObject(tools[0].parameters)
        assertEquals("object", schema.getString("type"))
    }

    @Test
    fun `given a tool description longer than the cap when getTools then the description is clamped`() = runTest {
        // A description is untrusted remote text that lands in the system prompt of
        // every run through `$TOOLS`, whether or not the server is ever called.
        val longDescription = "d".repeat(OVERSIZED_DESCRIPTION_CHARS)
        val tools = makeClient(listOf(tool(name = "longTool", description = longDescription))).getTools()

        val description = tools.single().description
        assertTrue(
            "the description must be clamped, got ${description.length} chars",
            description.length < OVERSIZED_DESCRIPTION_CHARS / 2,
        )
        assertTrue(description.startsWith("d".repeat(DESCRIPTION_PREFIX_CHARS)))
    }

    @Test
    fun `given a name outside the MCP naming rule when getTools then the tool is not published`() = runTest {
        val tools = makeClient(
            listOf(
                tool(name = "ok_name.v2-x", description = "fine"),
                tool(name = "two\nlines", description = "forges a catalogue line"),
                tool(name = "", description = "empty"),
                tool(name = "n".repeat(MAX_NAME_CHARS + 1), description = "too long"),
                tool(name = "has space", description = "space"),
            ),
        ).getTools()

        assertEquals(listOf("ok_name.v2-x"), tools.map { it.name })
    }

    @Test
    fun `given a tool the catalogue limits left out when executeTool then it is refused as not found`() = runTest {
        val hidden = tool(name = "has space", description = "not published")
        val registry = mockk<ToolRegistry>()
        every { registry.tools } returns listOf(hidden)
        every { registry.getToolOrNull("has space") } returns hidden
        val client = makeClient(registry)

        val exception = runCatching { client.executeTool("has space", "{}") }.exceptionOrNull()

        assertTrue("expected not-found, got $exception", exception is IllegalArgumentException)
        assertTrue(exception!!.message!!.contains("not found"))
    }

    @Test
    fun `given more tools than the count limit when getTools then the tail is not published`() = runTest {
        val many = (0 until KoogMcpClient.MAX_PUBLISHED_TOOLS + 10).map { tool(name = "t$it", description = "d") }

        val tools = makeClient(many).getTools()

        assertEquals(KoogMcpClient.MAX_PUBLISHED_TOOLS, tools.size)
        assertEquals("t0", tools.first().name)
    }

    @Test
    fun `given a catalogue past the size budget when getTools then publishing stops at the first tool over it`() =
        runTest {
            // Each tool renders just under a quarter of the budget, so the fifth one
            // is the first that does not fit — and nothing after it is published,
            // even a small one: the published set is a prefix of the server's order.
            val quarter = KoogMcpClient.CATALOGUE_BUDGET_CHARS / 4 - QUARTER_SLACK
            val bulky = (0 until 5).map { bulkyTool(name = "big$it", size = quarter) }
            val tools = makeClient(bulky + tool(name = "small", description = "s")).getTools()

            assertEquals(listOf("big0", "big1", "big2", "big3"), tools.map { it.name })
        }

    @Test
    fun `given a parameter description longer than the cap when getTools then it is clamped`() = runTest {
        val longParam = ToolParameterDescriptor(
            name = "query",
            description = "q".repeat(OVERSIZED_DESCRIPTION_CHARS),
            type = ToolParameterType.String,
        )
        val descriptor = mockk<ToolDescriptor>()
        every { descriptor.description } returns "desc"
        every { descriptor.requiredParameters } returns listOf(longParam)
        every { descriptor.optionalParameters } returns emptyList()
        val tool = mockk<Tool<Any, Any>>()
        every { tool.name } returns "search"
        every { tool.descriptor } returns descriptor

        val schema = JSONObject(makeClient(listOf(tool)).getTools().single().parameters)

        val description = schema.getJSONObject("properties").getJSONObject("query").getString("description")
        assertTrue(description.endsWith(KoogMcpClient.DESCRIPTION_TRUNCATED_MARKER))
        assertEquals(
            KoogMcpClient.MAX_DESCRIPTION_CHARS + KoogMcpClient.DESCRIPTION_TRUNCATED_MARKER.length,
            description.length,
        )
    }

    @Test
    fun `given a clamp that would split a surrogate pair when clampDescription then the pair is kept whole`() {
        val emoji = "\uD83D\uDE00"
        val text = "a".repeat(KoogMcpClient.MAX_DESCRIPTION_CHARS - 1) + emoji + "tail"

        val clamped = KoogMcpClient.clampDescription(text)

        val body = clamped.removeSuffix(KoogMcpClient.DESCRIPTION_TRUNCATED_MARKER)
        assertEquals(KoogMcpClient.MAX_DESCRIPTION_CHARS - 1, body.length)
        assertFalse(Character.isHighSurrogate(body.last()))
    }

    @Test
    fun `given a result that fits when capResult then it is returned unchanged`() {
        assertEquals("short", KoogMcpClient.capResult("short", maxBytes = 5))
    }

    @Test
    fun `given a cut that lands inside a multibyte character when capResult then it cuts before that character`() {
        // "é" is two bytes in UTF-8, so "aéé" is five: a 4-byte budget would end
        // halfway through the second "é", and the cut steps back to before it.
        val capped = KoogMcpClient.capResult("aéé", maxBytes = 4)

        assertEquals("aé\n[... result truncated at 4 bytes]", capped)
    }

    @Test
    fun `getTools preserves declared parameter types instead of calling everything a string`() = runTest {
        val duration = mockk<ToolParameterDescriptor>()
        every { duration.name } returns "duration"
        every { duration.description } returns "How long to run, in seconds"
        every { duration.type } returns ToolParameterType.Integer

        val ratio = mockk<ToolParameterDescriptor>()
        every { ratio.name } returns "ratio"
        every { ratio.description } returns ""
        every { ratio.type } returns ToolParameterType.Float

        val verbose = mockk<ToolParameterDescriptor>()
        every { verbose.name } returns "verbose"
        every { verbose.description } returns ""
        every { verbose.type } returns ToolParameterType.Boolean

        val mockDescriptor = mockk<ToolDescriptor>()
        every { mockDescriptor.description } returns "long running op"
        every { mockDescriptor.requiredParameters } returns listOf(duration)
        every { mockDescriptor.optionalParameters } returns listOf(ratio, verbose)

        val mockTool = mockk<Tool<Any, Any>>()
        every { mockTool.name } returns "trigger-long-running-operation"
        every { mockTool.descriptor } returns mockDescriptor

        val schema = JSONObject(makeClient(listOf(mockTool)).getTools()[0].parameters)
        val props = schema.getJSONObject("properties")

        // Advertising every parameter as a string made the model send "300" for a
        // numeric field, and the server rejected the call on schema validation —
        // which made any MCP tool with a non-string parameter unusable
        // (found by a directed on-device test).
        assertEquals("integer", props.getJSONObject("duration").getString("type"))
        assertEquals("number", props.getJSONObject("ratio").getString("type"))
        assertEquals("boolean", props.getJSONObject("verbose").getString("type"))
        // The description is what tells the model what the argument means.
        assertEquals(
            "How long to run, in seconds",
            props.getJSONObject("duration").getString("description"),
        )
    }

    @Test
    fun `getTools renders enum and array parameters as their JSON Schema shapes`() = runTest {
        val mode = mockk<ToolParameterDescriptor>()
        every { mode.name } returns "mode"
        every { mode.description } returns ""
        every { mode.type } returns ToolParameterType.Enum(arrayOf("fast", "slow"))

        val tags = mockk<ToolParameterDescriptor>()
        every { tags.name } returns "tags"
        every { tags.description } returns ""
        every { tags.type } returns ToolParameterType.List(ToolParameterType.String)

        val mockDescriptor = mockk<ToolDescriptor>()
        every { mockDescriptor.description } returns "d"
        every { mockDescriptor.requiredParameters } returns listOf(mode, tags)
        every { mockDescriptor.optionalParameters } returns emptyList()

        val mockTool = mockk<Tool<Any, Any>>()
        every { mockTool.name } returns "shaped"
        every { mockTool.descriptor } returns mockDescriptor

        val props = JSONObject(makeClient(listOf(mockTool)).getTools()[0].parameters)
            .getJSONObject("properties")

        val modeSchema = props.getJSONObject("mode")
        assertEquals("string", modeSchema.getString("type"))
        assertEquals("fast", modeSchema.getJSONArray("enum").getString(0))
        val tagsSchema = props.getJSONObject("tags")
        assertEquals("array", tagsSchema.getString("type"))
        assertEquals("string", tagsSchema.getJSONObject("items").getString("type"))
    }

    @Test
    fun `getTools builds required array for required parameters`() = runTest {
        val requiredParam = mockk<ToolParameterDescriptor>()
        every { requiredParam.name } returns "query"
        every { requiredParam.description } returns ""
        every { requiredParam.type } returns ToolParameterType.String

        val mockDescriptor = mockk<ToolDescriptor>()
        every { mockDescriptor.description } returns "search tool"
        every { mockDescriptor.requiredParameters } returns listOf(requiredParam)
        every { mockDescriptor.optionalParameters } returns emptyList()

        val mockTool = mockk<Tool<Any, Any>>()
        every { mockTool.name } returns "search"
        every { mockTool.descriptor } returns mockDescriptor

        val tools = makeClient(listOf(mockTool)).getTools()

        val schema = JSONObject(tools[0].parameters)
        assertTrue(schema.has("required"))
        assertEquals("query", schema.getJSONArray("required").getString(0))
        assertTrue(schema.getJSONObject("properties").has("query"))
    }

    @Test
    fun `connect that fails during transport attachment leaves session field null`() = runTest {
        // Leak-on-failure regression guard: when SSE transport attachment to a
        // non-routable address surfaces an exception, the freshly-created HttpClient
        // must be closed locally. The field stays at its previous value (null on a
        // first attempt), so a retry does not accumulate leaked Ktor engines.
        val client = KoogMcpClient()

        val outcome = runCatching { client.connect(McpServerConfig(url = "http://127.0.0.1:1")) }

        assertTrue("Expected connect against unreachable host to fail", outcome.isFailure)
        assertEquals(
            "Failed connect must clean up the new HttpClient — leaked engine = bug",
            null,
            sessionField(client).get(client),
        )
    }

    @Test
    fun `repeated failed connects do not accumulate leaked HttpClient instances`() = runTest {
        // Same invariant under repeated failure: the field must remain clean so callers
        // can keep retrying without building up open Ktor engines.
        val client = KoogMcpClient()

        repeat(3) {
            runCatching { client.connect(McpServerConfig(url = "http://127.0.0.1:1")) }
            assertEquals(
                "After failed connect #${it + 1} the session field must be null",
                null,
                sessionField(client).get(client),
            )
        }
    }

    @Test
    fun `disconnect after a failed connect keeps session field null`() = runTest {
        // Defect 5 regression guard: a disconnect after a failed connect is harmless
        // (no double-close) and leaves the field in the documented "no client" state.
        val client = KoogMcpClient()

        runCatching { client.connect(McpServerConfig(url = "http://127.0.0.1:1")) }
        client.disconnect()

        assertEquals(null, sessionField(client).get(client))
    }

    @Test
    fun `getTools optional parameters appear in properties but not in required`() = runTest {
        val optionalParam = mockk<ToolParameterDescriptor>()
        every { optionalParam.name } returns "lang"
        every { optionalParam.description } returns ""
        every { optionalParam.type } returns ToolParameterType.String

        val mockDescriptor = mockk<ToolDescriptor>()
        every { mockDescriptor.description } returns "search tool"
        every { mockDescriptor.requiredParameters } returns emptyList()
        every { mockDescriptor.optionalParameters } returns listOf(optionalParam)

        val mockTool = mockk<Tool<Any, Any>>()
        every { mockTool.name } returns "search"
        every { mockTool.descriptor } returns mockDescriptor

        val tools = makeClient(listOf(mockTool)).getTools()

        val schema = JSONObject(tools[0].parameters)
        assertTrue(schema.getJSONObject("properties").has("lang"))
        assertTrue(!schema.has("required"))
    }

    @Test
    fun `composeHeaders renders Bearer auth as Authorization header`() {
        val headers = KoogMcpClient.composeHeaders(
            config = McpServerConfig(url = "https://x/", auth = McpAuth.Bearer(token = "abc")),
        )
        assertEquals("Bearer abc", headers["Authorization"])
    }

    @Test
    fun `composeHeaders renders Basic auth as base64-encoded Authorization header`() {
        val headers = KoogMcpClient.composeHeaders(
            config = McpServerConfig(url = "https://x/", auth = McpAuth.Basic(username = "user", password = "pw")),
        )
        // Base64("user:pw") = dXNlcjpwdw==
        assertEquals("Basic dXNlcjpwdw==", headers["Authorization"])
    }

    @Test
    fun `composeHeaders puts ApiKey auth under the requested header name`() {
        val headers = KoogMcpClient.composeHeaders(
            config = McpServerConfig(
                url = "https://x/",
                auth = McpAuth.ApiKey(headerName = "X-API-Key", value = "secret"),
            ),
        )
        assertEquals("secret", headers["X-API-Key"])
    }

    @Test
    fun `composeHeaders lets custom headers override the typed auth`() {
        // Power-user contract: if you take the trouble to set an explicit
        // Authorization row in the headers section, it wins over the typed
        // Bearer above. This allows oddball Authorization schemes the typed
        // selector doesn't cover (DPoP, MAC, etc.) without a code change.
        val headers = KoogMcpClient.composeHeaders(
            config = McpServerConfig(
                url = "https://x/",
                auth = McpAuth.Bearer(token = "typed"),
                headers = mapOf("Authorization" to "Custom override"),
            ),
        )
        assertEquals("Custom override", headers["Authorization"])
    }

    @Test
    fun `composeHeaders skips empty Bearer and ApiKey entries`() {
        val emptyBearer = KoogMcpClient.composeHeaders(
            config = McpServerConfig(url = "https://x/", auth = McpAuth.Bearer(token = "")),
        )
        val emptyApiKey = KoogMcpClient.composeHeaders(
            config = McpServerConfig(url = "https://x/", auth = McpAuth.ApiKey(headerName = "X-K", value = "")),
        )
        assertTrue(emptyBearer.isEmpty())
        assertTrue(emptyApiKey.isEmpty())
    }

    /** A registry tool with no parameters, named [name] and described by [description]. */
    private fun tool(name: String, description: String): Tool<Any, Any> {
        val descriptor = mockk<ToolDescriptor>()
        every { descriptor.description } returns description
        every { descriptor.requiredParameters } returns emptyList()
        every { descriptor.optionalParameters } returns emptyList()
        val tool = mockk<Tool<Any, Any>>()
        every { tool.name } returns name
        every { tool.descriptor } returns descriptor
        return tool
    }

    /**
     * A tool whose optional parameters carry about [size] characters of parameter
     * descriptions — each at the description cap — so its rendered schema weighs
     * roughly that much.
     */
    private fun bulkyTool(name: String, size: Int): Tool<Any, Any> {
        val perParam = KoogMcpClient.MAX_DESCRIPTION_CHARS
        val params = (0 until size / perParam).map { i ->
            ToolParameterDescriptor(name = "p$i", description = "x".repeat(perParam), type = ToolParameterType.String)
        }
        val descriptor = mockk<ToolDescriptor>()
        every { descriptor.description } returns "d"
        every { descriptor.requiredParameters } returns emptyList()
        every { descriptor.optionalParameters } returns params
        val tool = mockk<Tool<Any, Any>>()
        every { tool.name } returns name
        every { tool.descriptor } returns descriptor
        return tool
    }

    private companion object {
        /** The MCP specification's longest tool name. */
        const val MAX_NAME_CHARS = 128

        /** Keeps four bulky tools just inside the budget once names and JSON punctuation count. */
        const val QUARTER_SLACK = 2_048

        /** A description far past any cap a real catalogue needs. */
        const val OVERSIZED_DESCRIPTION_CHARS = 50_000

        /** How much of the original text a clamped description must still start with. */
        const val DESCRIPTION_PREFIX_CHARS = 1_000
    }
}
