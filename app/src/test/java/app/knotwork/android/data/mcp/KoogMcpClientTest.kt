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
import org.junit.Assert.assertTrue
import org.junit.Test

class KoogMcpClientTest {

    private fun makeClient(tools: List<Tool<*, *>>): KoogMcpClient {
        val client = KoogMcpClient()
        val mockRegistry = mockk<ToolRegistry>()
        every { mockRegistry.tools } returns tools
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
}
